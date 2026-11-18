package dev.watdafawx.mobmapicons.core;

/**
 * Turns one {@link net.minecraft.world.entity.EntityType} into a texture.
 *
 * <p>TODO(core): draw the entity's own model - either the whole body or its head, per
 * {@code ClientConfig.iconMode()} - into a {@code NativeImage}, register it as a dynamic texture and
 * hand the texture id back through {@code MobIconApi}. Rendering has to happen on the render thread,
 * so the cache asks for icons on the client thread and re-renders when {@code iconMode} or
 * {@code iconSize} changes.
 *
 * <p>Nothing may reference this class from an eagerly loaded class of a map adapter; adapters only
 * ever see {@code api/}.
 */
public final class MobIconRenderer {

    private MobIconRenderer() {}
}
