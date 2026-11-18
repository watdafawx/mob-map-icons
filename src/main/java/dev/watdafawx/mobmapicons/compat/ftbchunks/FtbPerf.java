package dev.watdafawx.mobmapicons.compat.ftbchunks;

import dev.watdafawx.mobmapicons.MobMapIcons;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;

/**
 * Dev-only timing log, on with {@code -Dmobmapicons.ftbperf=true} or a file {@code ftbperf.flag} in the game folder: every 10 seconds one line with how
 * much time our icon list pass and our icon draws took, so a crowded scene can be compared with the mod
 * off without a profiler. Costs two nanoTime reads per icon draw even when off, nothing else.
 */
final class FtbPerf {
    private static final boolean ON = Boolean.getBoolean("mobmapicons.ftbperf")
            || Files.exists(FMLPaths.GAMEDIR.get().resolve("ftbperf.flag"));
    private static int ticks;
    private static int frames;
    private static long since = System.nanoTime();

    /** Counts rendered frames so the log line can report fps. */
    static void frame() {
        frames++;
    }

    private FtbPerf() {}

    static void tick() {
        if (!ON || ++ticks < 200) {
            return;
        }
        ticks = 0;
        long now = System.nanoTime();
        double secs = (now - since) / 1e9;
        double fps = frames / secs;
        since = now;
        int fr = frames;
        frames = 0;
        int passes = IconPass.passCount, draws = IconDraw.drawCount;
        MobMapIcons.LOG.info("ftbperf: {} frames = {} fps ({} ms/frame); list pass {} x avg {} us (last {} -> {} icons); draws {} in 10 s, avg {} us, total {} ms/s",
                fr, Math.round(fps * 10) / 10.0, Math.round(1000 / fps * 100) / 100.0,
                passes, passes == 0 ? 0 : IconPass.passNanos / passes / 1000, IconPass.lastIn, IconPass.lastOut,
                draws, draws == 0 ? 0 : IconDraw.drawNanos / draws / 1000, IconDraw.drawNanos / 10_000_000L);
        IconPass.passNanos = 0;
        IconPass.passCount = 0;
        IconDraw.drawNanos = 0;
        IconDraw.drawCount = 0;
    }
}
