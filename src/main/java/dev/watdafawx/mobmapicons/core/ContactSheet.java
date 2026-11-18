package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.mobmapicons.MobMapIcons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Grid of icons in one PNG, for eyeballing many icons at once (dumps, self-test). */
final class ContactSheet {

    /** Opaque dark grey, so both dark and light mobs and the transparent margin show. ABGR. */
    private static final int BACKGROUND = 0xFF3A3A3A;
    /** Slightly lighter cell behind each icon, so the icon's square is visible. ABGR. */
    private static final int CELL = 0xFF505050;
    private static final int GUTTER = 2;

    private ContactSheet() {}

    /** Decodes PNGs; ones that fail to decode are skipped (and logged). */
    static List<NativeImage> decode(List<byte[]> pngs) {
        List<NativeImage> images = new ArrayList<>(pngs.size());
        for (byte[] png : pngs) {
            try {
                images.add(NativeImage.read(png));
            } catch (IOException e) {
                MobMapIcons.LOG.warn("Contact sheet: could not decode an icon", e);
            }
        }
        return images;
    }

    /**
     * Writes {@code images} row-major, {@code columns} per row, each enlarged {@code scale}x with
     * nearest-neighbour, into one PNG. Cells are as big as the biggest image. Closes the images.
     */
    static void write(Path file, List<NativeImage> images, int columns, int scale) throws IOException {
        try {
            if (images.isEmpty()) return;
            int cell = 0;
            for (NativeImage image : images) cell = Math.max(cell, Math.max(image.getWidth(), image.getHeight()));
            cell *= scale;
            int cols = Math.min(columns, images.size());
            int rows = (images.size() + columns - 1) / columns;
            int w = GUTTER + cols * (cell + GUTTER);
            int h = GUTTER + rows * (cell + GUTTER);
            try (NativeImage sheet = new NativeImage(w, h, true)) {
                sheet.fillRect(0, 0, w, h, BACKGROUND);
                for (int i = 0; i < images.size(); i++) {
                    NativeImage image = images.get(i);
                    int ox = GUTTER + (i % columns) * (cell + GUTTER);
                    int oy = GUTTER + (i / columns) * (cell + GUTTER);
                    sheet.fillRect(ox, oy, cell, cell, CELL);
                    for (int y = 0; y < image.getHeight() * scale; y++) {
                        for (int x = 0; x < image.getWidth() * scale; x++) {
                            int src = image.getPixelRGBA(x / scale, y / scale);
                            int a = src >>> 24;
                            if (a == 0) continue;
                            sheet.setPixelRGBA(ox + x, oy + y, a == 255 ? src : blend(src, CELL));
                        }
                    }
                }
                Files.createDirectories(file.toAbsolutePath().getParent());
                sheet.writeToFile(file);
            }
        } finally {
            images.forEach(NativeImage::close);
        }
    }

    /** Straight-alpha {@code src} over opaque {@code dst}, both ABGR. */
    private static int blend(int src, int dst) {
        int a = src >>> 24;
        int out = 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            int s = (src >> shift) & 0xFF;
            int d = (dst >> shift) & 0xFF;
            out |= ((s * a + d * (255 - a)) / 255) << shift;
        }
        return out;
    }
}
