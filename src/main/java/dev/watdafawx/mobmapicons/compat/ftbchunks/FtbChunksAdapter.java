package dev.watdafawx.mobmapicons.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.client.event.MapIconEvent;
import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.mapicon.EntityMapIcon;
import dev.ftb.mods.ftblibrary.util.client.ClientUtils;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.util.List;

/**
 * FTB Chunks adapter: swaps FTB's own mob heads / red dots for our rendered mob icons.
 *
 * <p><b>How it replaces instead of adds.</b> {@link MapIconEvent} is append-only, so a second icon for
 * the same mob would draw on top of FTB's. Rather than fight FTB's per-type {@code ENTITY_ICON} config
 * (a persisted file FTB re-saves whenever it meets a new mob type) or mix into its classes, this runs
 * <i>after</i> FTB's own listener and edits the event's list in place: every {@link EntityMapIcon} FTB
 * built for a non-player entity is swapped for an {@link OverrideIcon} wrapping it. That inherits every
 * FTB rule for free (minimap.entities master switch, the per-type toggle, invisible entities,
 * only-surface, empty icons, edge culling) and shows FTB's own icon for any mob whose icon is not ready
 * yet, with zero config or class patching. {@link IconPass} does that walk and also folds crowds into
 * one {@link ClusterIcon} (farm mode) and applies the maxIcons cap.
 *
 * <p>Two private FTB fields are read reflectively (the event's list and the icon's entity). If a future
 * FTB renames them the adapter logs once and does nothing, so FTB keeps its own icons.
 *
 * <p>Listeners are registered on first world join, not at init: that is guaranteed to be after FTB's own
 * client init registered its listener, so ours always runs second.
 */
public final class FtbChunksAdapter {

    private static final Field EVENT_ICONS = field(MapIconEvent.class, "icons");
    private static final Field ICON_ENTITY = field(EntityMapIcon.class, "entity");

    private static boolean registered;
    private static boolean warned;

    private FtbChunksAdapter() {}

    /** Called reflectively from {@code MobMapIcons}; only when FTB Chunks is loaded. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(FtbChunksAdapter::onLogin);
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (registered) {
            return;
        }
        registered = true;
        MapIconEvent.MINIMAP.register(FtbChunksAdapter::replaceIcons);
        MapIconEvent.LARGE_MAP.register(FtbChunksAdapter::replaceIcons);
        NeoForge.EVENT_BUS.addListener(FtbChunksAdapter::onClientTick);
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, frame -> FtbPerf.frame());
    }

    @SuppressWarnings("unchecked")
    private static void replaceIcons(MapIconEvent event) {
        if (!ClientConfig.ftbChunksEnabled() || EVENT_ICONS == null || ICON_ENTITY == null) {
            return;
        }
        try {
            IconPass.process(event.getMapType(), (List<MapIcon>) EVENT_ICONS.get(event));
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
        }
    }

    /** The entity behind one of FTB's own entity icons, or null if FTB's internals changed. */
    static Entity entityOf(EntityMapIcon icon) {
        try {
            return (Entity) ICON_ENTITY.get(icon);
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
            return null;
        }
    }

    // Farm mode groups by screen cell, so a zoom (or a config edit) must rebuild FTB's icon lists. The
    // minimap rebuilds itself every 500 ms; the large map only on request, so ask for it here.
    private static int sinceLargeRefresh;

    private static void onClientTick(ClientTickEvent.Post event) {
        FtbPerf.tick();
        OwnFace.tick();
        if (!ClientConfig.ftbChunksEnabled() || EVENT_ICONS == null || ICON_ENTITY == null) {
            return;
        }
        try {
            if (IconPass.stale(MapType.MINIMAP)) {
                FTBChunksAPI.clientApi().requestMinimapIconRefresh();
            }
            if (++sinceLargeRefresh >= 5 && ClientUtils.getCurrentGuiAs(LargeMapScreen.class) instanceof LargeMapScreen map
                    && IconPass.stale(MapType.LARGE_MAP)) {
                sinceLargeRefresh = 0;
                map.refreshIcons();
            }
        } catch (LinkageError | RuntimeException e) {
            warnOnce(e instanceof Exception ex ? ex : new RuntimeException(e));
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
            return null;
        }
    }

    private static void warnOnce(Exception e) {
        if (!warned) {
            warned = true;
            MobMapIcons.LOG.warn("FTB Chunks internals differ from the supported 2101.1.x line; leaving FTB's own mob icons alone", e);
        }
    }
}
