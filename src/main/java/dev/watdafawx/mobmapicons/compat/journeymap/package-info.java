/**
 * JourneyMap integration.
 *
 * <p>An adapter hands {@link dev.watdafawx.mobmapicons.api.MobIconApi} icons to JourneyMap's entity
 * rendering. TODO(compat:journeymap): add {@code JourneyMapAdapter} with a static {@code init()} here,
 * gated on {@code ModList.isLoaded("journeymap")} and loaded by class name, and add JourneyMap as a
 * {@code compileOnly} dependency in this file's own {@code // === compat:journeymap ===} block.
 *
 * <p>Nothing outside this package may touch an adapter - the rest of the mod loads on clients that
 * do not have JourneyMap.
 */
package dev.watdafawx.mobmapicons.compat.journeymap;
