/**
 * FTB Chunks integration.
 *
 * <p>{@link dev.watdafawx.mobmapicons.compat.ftbchunks.FtbChunksAdapter} swaps FTB Chunks' entity map icons
 * for ours (heading, farm-mode clusters, icon cap). It is gated on {@code ModList.isLoaded("ftbchunks")}
 * in {@link dev.watdafawx.mobmapicons.MobMapIcons} and loaded by class name.
 *
 * <p>Nothing outside this package may touch an adapter - not even a field - because FML loads the
 * rest of the mod on clients that do not have FTB Chunks installed.
 */
package dev.watdafawx.mobmapicons.compat.ftbchunks;
