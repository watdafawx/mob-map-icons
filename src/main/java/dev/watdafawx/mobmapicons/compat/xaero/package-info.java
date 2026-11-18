/**
 * Xaero's Minimap and Xaero's World Map integration.
 *
 * <p>One adapter covers both, since the world map and the minimap draw from the same per-entity icon
 * set. TODO(compat:xaero): add {@code XaeroAdapter} with a static {@code init()} here, gated on
 * {@code xaerominimap} or {@code xaeroworldmap} being loaded and loaded by class name, and add the
 * Xaero jars as {@code compileOnly} dependencies in this file's own
 * {@code // === compat:xaero ===} block.
 *
 * <p>Nothing outside this package may touch an adapter - the rest of the mod loads on clients that
 * have neither Xaero mod.
 */
package dev.watdafawx.mobmapicons.compat.xaero;
