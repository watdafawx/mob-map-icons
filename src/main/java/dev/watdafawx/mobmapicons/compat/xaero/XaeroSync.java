package dev.watdafawx.mobmapicons.compat.xaero;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MapView;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Keeps the {@link XaeroIconPack} in step with the icons the core has finished, and tells Xaero to re-read
 * its icon definitions when that set changes.
 *
 * <h2>Why this does not loop or spam</h2>
 * <ul>
 *   <li>It never starts a client resource reload. Xaero reads definitions straight from the resource manager
 *       (which asks our live pack), so the cheap path is to call Xaero's own "reload entity icon definitions"
 *       and "clear the icon cache" hooks. The core's reload listener (which invalidates icons) therefore
 *       never fires because of us, and a core invalidation can only cause one more poll-driven update.</li>
 *   <li>Polling is once a second; a change must be quiet for {@link #QUIET_MS} (batching a burst of finished
 *       icons into one reload) but never waits longer than {@link #MAX_WAIT_MS}.</li>
 *   <li>Nothing happens while a reload or loading overlay is up.</li>
 *   <li>An icon that goes "not ready" (the core is re-rendering it) keeps its previous sprite instead of
 *       flickering back to Xaero's model; only a blacklist hit or the config toggle removes it.</li>
 *   <li>{@code xaeroScale} and the view's icon size are part of the snapshot key, so changing either
 *       republishes every sprite on the next poll (the sprite is a fixed 64x64 quad, so a different icon size
 *       means different pixels).</li>
 * </ul>
 *
 * <h2>Two sizes (minimap and World Map)</h2>
 * Xaero draws its minimap radar and the World Map radar with the same icon manager, so both read the same
 * sprites. To still give each its own size there are two snapshots, one per {@link MapView}, and the pack serves
 * the one of the view that is on screen: while one of Xaero's {@code GuiMap} screens is open it is the full-screen
 * one, otherwise the minimap one. The swap (one republish plus Xaero's two reload hooks) happens once, in the tick
 * the screen opens or closes, and only when the two snapshots differ (at equal sizes nothing is done). Nothing is
 * done per frame; the screen check is one class-name comparison per tick, cached per screen instance.
 *
 * <p>Xaero is touched only reflectively ({@code HudMod.INSTANCE -> ... -> MinimapFBORenderer}), so there is no
 * compile-time dependency. If its classes changed shape, this logs once and stays off.
 */
final class XaeroSync {
    private static final int POLL_TICKS = 20;
    private static final long QUIET_MS = 2_000;
    private static final long MAX_WAIT_MS = 10_000;
    private static final ResourceLocation PLAYER = ResourceLocation.withDefaultNamespace("player");

    // Client main thread only.
    private static int ticks;
    /** What each view wants served, indexed by {@link MapView#ordinal()}. */
    private static final Snapshot[] desired = {Snapshot.EMPTY, Snapshot.EMPTY};
    /** The view whose snapshot the pack serves: FULLSCREEN while Xaero's World Map screen is open. */
    private static MapView active = MapView.MINIMAP;
    private static long lastChangeMs;
    private static long dirtySinceMs = -1;
    private static boolean needsXaeroReload;
    private static boolean disabled;
    private static Handles handles;
    private static Screen lastScreen;
    private static boolean lastScreenIsMap;

    private XaeroSync() {}

    static void onTick(ClientTickEvent.Post event) {
        if (disabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) return; // resource reload / loading screen in progress

        MapView view = viewOf(mc.screen);
        if (view != active) {
            active = view;
            // Opening or closing the World Map: serve that view's sprites right now (no quiet period), but only if
            // they differ from what is served. Both sets were prepared by the polls before.
            Snapshot want = desired[active.ordinal()];
            Snapshot served = XaeroIconPack.served();
            if (!want.sameContent(served)) {
                apply(mc, served, want);
                dirtySinceMs = -1;
            }
        }

        if (++ticks % POLL_TICKS == 0) poll(mc);
        if (needsXaeroReload && reloadXaero()) needsXaeroReload = false;
    }

    private static void poll(Minecraft mc) {
        requestVisibleTypes(mc);
        long now = Util.getMillis();
        boolean changed = false;
        for (MapView view : MapView.values()) {
            Snapshot prev = desired[view.ordinal()];
            Snapshot next = compute(view, prev);
            if (!next.sameContent(prev)) {
                desired[view.ordinal()] = next;
                changed |= view == active; // a change of the view not on screen waits for its screen
            }
        }
        if (changed) lastChangeMs = now;

        Snapshot served = XaeroIconPack.served();
        Snapshot want = desired[active.ordinal()];
        if (want.sameContent(served)) {
            dirtySinceMs = -1;
        } else {
            if (dirtySinceMs < 0) dirtySinceMs = now;
            if (now - lastChangeMs >= QUIET_MS || now - dirtySinceMs >= MAX_WAIT_MS) {
                apply(mc, served, want);
                dirtySinceMs = -1;
            }
        }
    }

    /** FULLSCREEN while one of Xaero's World Map screens ({@code xaero.map.gui.GuiMap} or a subclass) is open. */
    private static MapView viewOf(Screen screen) {
        if (screen != lastScreen) {
            lastScreen = screen;
            lastScreenIsMap = false;
            for (Class<?> c = screen == null ? null : screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getName().equals("xaero.map.gui.GuiMap")) {
                    lastScreenIsMap = true;
                    break;
                }
            }
        }
        return lastScreenIsMap ? MapView.FULLSCREEN : MapView.MINIMAP;
    }

    /** Dev harness: which view's sprites are wanted right now. */
    static MapView activeView() {
        return active;
    }

    /**
     * Asks the core for an icon for every mob type in the loaded world, in both views. Icons render lazily on
     * first request and nothing on Xaero's side ever asks the core (Xaero reads sprites from our pack), so
     * without this no icon would ever be rendered and the pack would stay empty.
     */
    private static void requestVisibleTypes(Minecraft mc) {
        if (mc.level == null || !ClientConfig.xaeroEnabled()) return;
        var seen = new java.util.HashSet<net.minecraft.world.entity.EntityType<?>>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.LivingEntity
                    && !(entity instanceof net.minecraft.world.entity.player.Player)
                    && seen.add(entity.getType())) {
                for (MapView view : MapView.values()) MobIconApi.getIcon(entity.getType(), MapMod.XAERO, view);
            }
        }
    }

    private static void apply(Minecraft mc, Snapshot old, Snapshot next) {
        XaeroIconPack.serve(next);
        // A sprite Xaero already bound as a texture would keep showing the old picture.
        var textures = mc.getTextureManager();
        for (var id : old.ids()) {
            var now = next.get(id);
            // Also true when only the scale changed (or the view): the old sprite was drawn at the other size.
            if (now == null || !old.get(id).matches(now.source(), now.scale())) {
                textures.release(XaeroIconPack.spriteLocation(id));
            }
        }
        needsXaeroReload = true;
        MobMapIcons.LOG.info("Xaero: serving {} mob icons for the {} (was {})", next.entries().size(),
                active == MapView.FULLSCREEN ? "World Map" : "minimap", old.entries().size());
    }

    /**
     * Fresh snapshot of one view: keeps old entries whose source and scale are unchanged, converts only the rest.
     * A type whose icon at this view's size is not rendered yet uses the minimap's picture (scaled to this view's
     * size) until it is.
     */
    private static Snapshot compute(MapView view, Snapshot previous) {
        if (!ClientConfig.xaeroEnabled()) return Snapshot.EMPTY;
        // Read once per poll: a change is picked up by the next poll and republishes every sprite.
        double scale = ClientConfig.xaeroSpriteScale(view);
        var entries = new HashMap<ResourceLocation, Snapshot.Entry>();
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            // Players keep Xaero's own head icons.
            if (id.equals(PLAYER) || ClientConfig.isBlacklisted(id)) continue;
            var old = previous.get(id);
            byte[] png = null;
            if (MobIconApi.isReady(type, MapMod.XAERO, view)) {
                png = MobIconApi.getPng(type, MapMod.XAERO, view);
            } else if (view != MapView.MINIMAP && MobIconApi.isReady(type, MapMod.XAERO, MapView.MINIMAP)) {
                png = MobIconApi.getPng(type, MapMod.XAERO, MapView.MINIMAP);
            }
            if (png == null) {
                if (old != null) entries.put(id, old);
            } else if (old != null && old.matches(png, scale)) {
                entries.put(id, old);
            } else {
                byte[] sprite = XaeroSprite.convert(png, scale);
                if (sprite != null) entries.put(id, new Snapshot.Entry(png.clone(), sprite, scale));
                else if (old != null) entries.put(id, old);
            }
        }
        return entries.isEmpty() ? Snapshot.EMPTY : new Snapshot(java.util.Map.copyOf(entries));
    }

    /** @return false to retry next poll (Xaero not initialised yet), true when done or permanently off */
    private static boolean reloadXaero() {
        try {
            if (handles == null) handles = Handles.resolve();
            Object fbo = handles.fboRenderer();
            if (fbo == null) return false;
            handles.resetResources.invoke(fbo); // re-read entity/icon/definition/**.json
            handles.resetIcons.invoke(fbo);     // drop icons built from the old definitions
            return true;
        } catch (InvocationTargetException e) {
            return fail(e.getCause());
        } catch (ReflectiveOperationException | LinkageError e) {
            return fail(e);
        }
    }

    private static boolean fail(Throwable cause) {
        disabled = true;
        MobMapIcons.LOG.error("Xaero minimap internals differ from what Mob Map Icons expects (tested with "
                + "Xaero's Minimap 26.6.0); icons stay on Xaero's defaults", cause);
        return true;
    }

    /** Reflective path from the Xaero mod singleton to the object that owns the radar icon manager. */
    private record Handles(Method getInterfaces, Method getMinimapInterface, Method getFboRenderer,
                           Method resetResources, Method resetIcons, java.lang.reflect.Field instance) {
        static Handles resolve() throws ReflectiveOperationException {
            Class<?> hud = Class.forName("xaero.common.HudMod");
            Class<?> interfaces = Class.forName("xaero.common.interfaces.InterfaceManager");
            Class<?> minimapInterface = Class.forName("xaero.common.minimap.MinimapInterface");
            Class<?> fbo = Class.forName("xaero.common.minimap.render.MinimapFBORenderer");
            return new Handles(
                    hud.getMethod("getInterfaces"),
                    interfaces.getMethod("getMinimapInterface"),
                    minimapInterface.getMethod("getMinimapFBORenderer"),
                    fbo.getMethod("resetEntityIconsResources"),
                    fbo.getMethod("resetEntityIcons"),
                    hud.getField("INSTANCE"));
        }

        /** @return the renderer, or null while Xaero is still starting up */
        Object fboRenderer() throws ReflectiveOperationException {
            Object hud = instance.get(null);
            Object ifaces = hud == null ? null : getInterfaces.invoke(hud);
            Object mi = ifaces == null ? null : getMinimapInterface.invoke(ifaces);
            return mi == null ? null : getFboRenderer.invoke(mi);
        }
    }
}
