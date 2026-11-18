package dev.watdafawx.mobmapicons.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.client.event.MapIconEvent;
import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.client.mapicon.EntityMapIcon;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.util.List;
import java.util.ListIterator;

/**
 * FTB Chunks adapter: swaps FTB's own mob heads / red dots for our rendered mob icons.
 *
 * <p><b>How it replaces instead of adds.</b> {@link MapIconEvent} is append-only, so a second icon for
 * the same mob would draw on top of FTB's. Rather than fight FTB's per-type {@code ENTITY_ICON} config
 * (a persisted file FTB re-saves whenever it meets a new mob type) or mix into its classes, this runs
 * <i>after</i> FTB's own listener and edits the event's list in place: every {@link EntityMapIcon} FTB
 * built for a non-player entity whose type already has a ready icon is swapped for an {@link OverrideIcon}
 * wrapping it. That inherits every FTB rule for free (minimap.entities master switch, the per-type
 * toggle, invisible entities, only-surface, empty icons, edge culling) and shows FTB's own icon for any
 * type that is not ready yet, with zero config or class patching.
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
    }

    @SuppressWarnings("unchecked")
    private static void replaceIcons(MapIconEvent event) {
        if (!ClientConfig.ftbChunksEnabled() || EVENT_ICONS == null || ICON_ENTITY == null) {
            return;
        }
        try {
            List<MapIcon> icons = (List<MapIcon>) EVENT_ICONS.get(event);
            for (ListIterator<MapIcon> it = icons.listIterator(); it.hasNext(); ) {
                if (it.next() instanceof EntityMapIcon ftbIcon) {
                    Entity entity = (Entity) ICON_ENTITY.get(ftbIcon);
                    ResourceLocation texture = textureFor(entity);
                    if (texture != null) {
                        it.set(new OverrideIcon(ftbIcon, entity));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce(e);
        }
    }

    /** Our icon texture for this entity, or null to leave FTB's own icon in place. */
    private static ResourceLocation textureFor(Entity entity) {
        if (entity == null || entity instanceof Player) {
            return null; // FTB draws player faces itself
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (ClientConfig.isBlacklisted(id)) {
            return null;
        }
        return MobIconApi.getIcon(entity.getType());
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
