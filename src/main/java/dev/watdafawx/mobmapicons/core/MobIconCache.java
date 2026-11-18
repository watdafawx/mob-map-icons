package dev.watdafawx.mobmapicons.core;

/**
 * Keeps one icon per entity type, built lazily and reused for the life of the resource reload.
 *
 * <p>TODO(core): key the cache by {@code EntityType} identity - there is a fixed number of them, so a
 * plain map is fine - plus the icon mode and size, and drop the entries on
 * {@code RegisterClientReloadListenersEvent}/resource reload so a texture pack change is picked up.
 * Blacklisted types are answered here, not in the adapters, so every map mod agrees on the list.
 *
 * <p>Icons are baked at most once per type per reload; map mods ask for the same handful of types
 * hundreds of times a second while a map screen is open.
 */
public final class MobIconCache {

    private MobIconCache() {}
}
