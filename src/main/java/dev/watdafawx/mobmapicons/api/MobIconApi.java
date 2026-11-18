package dev.watdafawx.mobmapicons.api;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.core.MobIconCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * The one read API adapters use: which map icon, if any, an entity type resolves to.
 *
 * <p>Adapters must go through this and never through {@code core/} directly. The contract stays
 * stable while the implementation under it changes: {@code null} means "draw nothing special", which
 * is what a blacklisted type, a type that cannot be drawn and a not-yet-built icon all return.
 *
 * <p>Icons are rendered lazily from the mob's own model and texture: the first lookup of a type
 * queues it and returns {@code null}, and a later lookup (usually a frame or two later) returns the
 * texture. Every method is safe to call from any thread and cheap enough to call every frame.
 *
 * <p>Icons are {@code 2 * iconSize()} pixels square, registered with the client texture manager
 * under {@code mob_map_icons:icons/<namespace>/<path>}.
 */
public final class MobIconApi {

    private MobIconApi() {}

    /**
     * The icon for this entity type, or {@code null} when it should keep the map mod's own marker.
     *
     * @param type the entity type being drawn; never null
     * @return texture id of the icon, or {@code null} for "no icon (yet)"
     */
    @Nullable
    public static ResourceLocation getIcon(EntityType<?> type) {
        return MobIconCache.getIcon(type);
    }

    /** {@link #getIcon(EntityType)} for an entity's type. Icons are per type, not per instance. */
    @Nullable
    public static ResourceLocation getIcon(Entity entity) {
        return MobIconCache.getIcon(entity.getType());
    }

    /** {@code true} when {@link #getIcon(EntityType)} would return a texture right now. Does not queue. */
    public static boolean isReady(EntityType<?> type) {
        return MobIconCache.isReady(type);
    }

    /**
     * The icon as PNG bytes (for map mods that load icons from a resource pack), or {@code null} when
     * it is not ready or the type is blacklisted. Does not queue a render; call
     * {@link #getIcon(EntityType)} for that.
     */
    public static byte @Nullable [] getPng(EntityType<?> type) {
        return MobIconCache.getPng(type);
    }

    /**
     * Throws every icon away; they are rendered again on demand. Runs on resource reload and on an
     * iconMode/iconSize change by itself; adapters that cache icons should re-query afterwards.
     */
    public static void invalidateAll() {
        MobIconCache.invalidateAll();
    }

    /**
     * ResourceManager-readable icon for JourneyMap and Xaero, unlike getIcon's GPU texture id.
     * TODO(core): preserve this path contract when replacing the API stubs.
     */
    @Nullable
    public static ResourceLocation getResourceIcon(EntityType<?> type) {
        if (ClientConfig.isBlacklisted(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type))
                || getIcon(type) == null || getPng(type) == null) return null;
        var id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return ResourceLocation.fromNamespaceAndPath("mob_map_icons",
                "textures/mob_map_icons/" + id.getNamespace() + "/" + id.getPath() + ".png");
    }

    /** Edge length in pixels of every icon this mod hands out, after config. Textures are twice this. */
    public static int iconSize() {
        return ClientConfig.iconSize();
    }
}
