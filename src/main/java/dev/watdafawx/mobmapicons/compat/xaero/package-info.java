/**
 * Xaero's Minimap and Xaero's World Map integration.
 *
 * <p>One adapter covers both: World Map wraps the minimap's own {@code RadarRenderer}, so they share one
 * per-entity icon set. The adapter is data-only - it serves {@code xaerominimap} entity icon definitions and
 * sprites from a top-priority in-memory resource pack ({@link dev.watdafawx.mobmapicons.compat.xaero.XaeroIconPack})
 * and reaches Xaero's reload hooks reflectively, so there is no compile-time dependency on either Xaero jar.
 *
 * <p>Nothing outside this package may touch an adapter - the rest of the mod loads on clients that
 * have neither Xaero mod.
 */
package dev.watdafawx.mobmapicons.compat.xaero;
