/**
 * FTB Chunks integration.
 *
 * <p>An adapter registers {@link dev.watdafawx.mobmapicons.api.MobIconApi} icons for mob dots on FTB
 * Chunks' maps. TODO(compat:ftbchunks): add {@code FtbChunksAdapter} with a static {@code init()}
 * here, gated on {@code ModList.isLoaded("ftbchunks")} in
 * {@link dev.watdafawx.mobmapicons.MobMapIcons} and loaded by class name, and add FTB Chunks as a
 * {@code compileOnly} dependency in this file's own {@code // === compat:ftbchunks ===} block.
 *
 * <p>Nothing outside this package may touch an adapter - not even a field - because FML loads the
 * rest of the mod on clients that do not have FTB Chunks installed.
 */
package dev.watdafawx.mobmapicons.compat.ftbchunks;
