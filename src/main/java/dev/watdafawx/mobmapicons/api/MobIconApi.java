package dev.watdafawx.mobmapicons.api;

import dev.watdafawx.mobmapicons.ClientConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * The one read API adapters use: which map icon, if any, an entity type resolves to.
 *
 * <p>Adapters must go through this and never through {@code core/} directly. The contract stays
 * stable while the implementation under it changes: {@code null} means "draw nothing special", which
 * is what a blacklisted type, an unknown type and a not-yet-built icon all return.
 */
public final class MobIconApi {

    private MobIconApi() {}

    /**
     * The icon for this entity type, or {@code null} when it should keep the map mod's own marker.
     *
     * <p>TODO(core): delegate to {@code MobIconCache} and the config blacklist once those exist. Until
     * then this returns {@code null} for everything, so every adapter is a no-op by construction.
     *
     * @param type the entity type being drawn; never null
     * @return texture id of the icon, or {@code null} for "no icon"
     */
    @Nullable
    public static ResourceLocation getIcon(EntityType<?> type) {
        return null;
    }

    /** Edge length in pixels of every icon this mod hands out, after config. */
    public static int iconSize() {
        return ClientConfig.iconSize();
    }
}
