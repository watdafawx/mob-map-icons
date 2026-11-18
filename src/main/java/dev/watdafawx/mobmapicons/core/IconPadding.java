package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Generic "fit this icon into a smaller centred box" helper for map mods that draw an icon inside a
 * fixed marker (JourneyMap's round one) and let anything wider than the marker spill over its edge.
 *
 * <p>The icon keeps its size and its own transparent margin; it is only ever shrunk, around the
 * centre of the canvas, so every opaque pixel lands inside a circle (or a square) of
 * {@code (1 - 2 * inset)} times the canvas edge. Mobs that already fit are left alone, so relative
 * sizes between mobs survive (a chicken stays smaller than a cow); only the wide ones are scaled
 * down. Pure pixel work on a copy, no game state, safe on any thread.
 */
public final class IconPadding {
    /** Alpha at or above this counts as part of the mob. */
    private static final int OPAQUE = 24;

    private IconPadding() {}

    /**
     * @param png    a square RGBA PNG
     * @param inset  margin as a fraction of the edge, per side (0.12 = 12%); clamped to 0..0.45
     * @param circle {@code true}: fit inside the inscribed circle, {@code false}: inside the centred square
     * @return the padded PNG, or {@code null} when {@code png} cannot be decoded
     */
    public static byte @Nullable [] fit(byte[] png, double inset, boolean circle) {
        try (NativeImage src = NativeImage.read(new ByteArrayInputStream(png))) {
            int w = src.getWidth(), h = src.getHeight();
            double cx = w / 2.0, cy = h / 2.0;
            double limit = (1.0 - 2.0 * Math.max(0.0, Math.min(0.45, inset))) * Math.min(w, h) / 2.0;
            // How far out is the farthest opaque pixel (edge of the pixel, so +0.5)?
            double far = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (alpha(src.getPixelRGBA(x, y)) < OPAQUE) continue;
                    double dx = Math.abs(x + 0.5 - cx) + 0.5, dy = Math.abs(y + 0.5 - cy) + 0.5;
                    far = Math.max(far, circle ? Math.sqrt(dx * dx + dy * dy) : Math.max(dx, dy));
                }
            }
            double scale = far <= limit || far <= 0 ? 1.0 : limit / far;
            if (scale >= 0.999) return png;
            try (NativeImage out = new NativeImage(NativeImage.Format.RGBA, w, h, true)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        out.setPixelRGBA(x, y, sample(src, cx + (x + 0.5 - cx) / scale, cy + (y + 0.5 - cy) / scale));
                    }
                }
                return out.asByteArray();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int alpha(int abgr) {
        return abgr >>> 24;
    }

    /** Bilinear sample at a continuous pixel-space point; premultiplied so edges do not fringe. Colour is ABGR. */
    private static int sample(NativeImage img, double px, double py) {
        double fx = px - 0.5, fy = py - 0.5;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        double tx = fx - x0, ty = fy - y0;
        double a = 0, r = 0, g = 0, b = 0;
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) {
                int x = x0 + i, y = y0 + j;
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) continue;
                double wgt = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - ty : ty);
                int c = img.getPixelRGBA(x, y);
                double ca = (c >>> 24) / 255.0;
                a += wgt * ca;
                r += wgt * ca * (c & 0xFF);
                g += wgt * ca * ((c >> 8) & 0xFF);
                b += wgt * ca * ((c >> 16) & 0xFF);
            }
        }
        if (a <= 0.001) return 0;
        int ia = (int) Math.round(a * 255), ir = (int) Math.round(r / a), ig = (int) Math.round(g / a), ib = (int) Math.round(b / a);
        return (ia << 24) | (Math.min(255, ib) << 16) | (Math.min(255, ig) << 8) | Math.min(255, ir);
    }
}
