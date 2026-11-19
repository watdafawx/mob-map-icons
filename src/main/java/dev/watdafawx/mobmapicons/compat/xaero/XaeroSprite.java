package dev.watdafawx.mobmapicons.compat.xaero;

import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.mobmapicons.ClientConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Turns a mob icon PNG (any size, icon size 8..64) into the 64x64 RGBA sprite that Xaero's
 * {@code RadarIconSpriteFormPrerenderer} expects.
 *
 * <p>Xaero binds the sprite as a texture and draws it as a fixed 64x64 quad
 * ({@code RadarIconCreator.ICON_WIDTH = 64}), so the image has to be exactly 64x64 or the UVs are wrong.
 * {@code outlined_sprite} makes Xaero add the black outline itself ({@code drawOutlineLayer}), which needs
 * transparent room around the picture.
 *
 * <p>Our own PNGs are drawn tight to their edges, whereas Xaero's model icons fill roughly
 * {@link #BASE_CONTENT} px of the 64px cell. {@link ClientConfig#xaeroSpriteScale} (xaeroScale times the view's icon size over 16) says how much of that box
 * the icon should fill (1.0 = 56 px, the default 0.75 = 42 px, at most 60 px), so the picture is scaled to that size and
 * centred, leaving the rest transparent for the outline. Upscaling uses NEAREST (pixel art stays crisp);
 * downscaling averages a 3x3 grid of bilinear samples per output pixel.
 */
final class XaeroSprite {
    static final int SIZE = 64;
    /** Box {@link ClientConfig#xaeroScale()} is relative to: about the size of Xaero's own model icons. */
    private static final int BASE_CONTENT = 56;

    private XaeroSprite() {}

    /** @return 64x64 PNG bytes at the configured scale, or {@code null} if the source could not be decoded */
    static byte[] convert(byte[] sourcePng) {
        return convert(sourcePng, ClientConfig.xaeroSpriteScale(dev.watdafawx.mobmapicons.api.MapView.MINIMAP));
    }

    /**
     * @param contentScale how much of {@link #BASE_CONTENT} the picture fills, matching the config range
     * @return 64x64 PNG bytes, or {@code null} if the source could not be decoded
     */
    static byte[] convert(byte[] sourcePng, double contentScale) {
        try (NativeImage src = NativeImage.read(new ByteArrayInputStream(sourcePng))) {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            int side = Math.max(w, h);
            // ClientConfig.xaeroSpriteScale already caps this; clamping here keeps a hand-edited value from producing a blank or overflowing sprite.
            double scale = Double.isFinite(contentScale) ? Math.max(0.05, Math.min(ClientConfig.XAERO_MAX_SCALE, contentScale)) : 0.75;
            int content = Math.max(1, Math.min(SIZE, (int) Math.round(BASE_CONTENT * scale)));
            if (content == side) return copyCentred(src); // already the right size: no resampling, no blur

            float k = (float) content / side; // source pixel -> sprite pixel
            float outW = w * k, outH = h * k;
            float x0 = (SIZE - outW) / 2f, y0 = (SIZE - outH) / 2f;
            return k > 1f ? upscaleNearest(src, k, x0, y0) : downscale(src, k, x0, y0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Pixel-for-pixel copy, centred in the sprite: no resampling, so no blur. */
    private static byte[] copyCentred(NativeImage src) throws IOException {
        int w = src.getWidth(), h = src.getHeight();
        int x0 = (SIZE - w) / 2, y0 = (SIZE - h) / 2;
        try (NativeImage out = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true)) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out.setPixelRGBA(x0 + x, y0 + y, src.getPixelRGBA(x, y));
                }
            }
            return out.asByteArray();
        }
    }

    /** Nearest-neighbour: every source pixel becomes a solid block, so the icon stays sharp. */
    private static byte[] upscaleNearest(NativeImage src, float k, float x0, float y0) throws IOException {
        int w = src.getWidth(), h = src.getHeight();
        try (NativeImage out = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true)) {
            for (int oy = 0; oy < SIZE; oy++) {
                int sy = (int) Math.floor((oy + 0.5f - y0) / k);
                if (sy < 0 || sy >= h) continue;
                for (int ox = 0; ox < SIZE; ox++) {
                    int sx = (int) Math.floor((ox + 0.5f - x0) / k);
                    if (sx < 0 || sx >= w) continue;
                    out.setPixelRGBA(ox, oy, src.getPixelRGBA(sx, sy));
                }
            }
            return out.asByteArray();
        }
    }

    private static byte[] downscale(NativeImage src, float k, float x0, float y0) throws IOException {
        int w = src.getWidth(), h = src.getHeight();
        // Premultiplied planes so filtering does not bleed black into soft edges.
        float[] pr = new float[w * h], pg = new float[w * h], pb = new float[w * h], pa = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = src.getPixelRGBA(x, y);
                float a = (abgr >>> 24) / 255f;
                int i = y * w + x;
                pa[i] = a;
                pr[i] = (abgr & 255) / 255f * a;
                pg[i] = ((abgr >> 8) & 255) / 255f * a;
                pb[i] = ((abgr >> 16) & 255) / 255f * a;
            }
        }

        final int sub = 3; // average 3x3 bilinear samples per output pixel
        try (NativeImage out = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true)) {
            for (int oy = 0; oy < SIZE; oy++) {
                for (int ox = 0; ox < SIZE; ox++) {
                    float r = 0, g = 0, b = 0, a = 0;
                    for (int sy = 0; sy < sub; sy++) {
                        for (int sx = 0; sx < sub; sx++) {
                            float fx = (ox + (sx + 0.5f) / sub - x0) / k - 0.5f;
                            float fy = (oy + (sy + 0.5f) / sub - y0) / k - 0.5f;
                            int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
                            float tx = fx - ix, ty = fy - iy;
                            for (int c = 0; c < 4; c++) {
                                int px = ix + (c & 1), py = iy + (c >> 1);
                                if (px < 0 || py < 0 || px >= w || py >= h) continue; // outside = transparent
                                float wt = ((c & 1) == 0 ? 1 - tx : tx) * ((c >> 1) == 0 ? 1 - ty : ty);
                                int i = py * w + px;
                                r += pr[i] * wt;
                                g += pg[i] * wt;
                                b += pb[i] * wt;
                                a += pa[i] * wt;
                            }
                        }
                    }
                    float n = sub * sub;
                    a /= n;
                    if (a <= 0.002f) continue;
                    int ai = Math.min(255, Math.round(a * 255f));
                    int ri = clamp(r / n / a), gi = clamp(g / n / a), bi = clamp(b / n / a);
                    out.setPixelRGBA(ox, oy, (ai << 24) | (bi << 16) | (gi << 8) | ri);
                }
            }
            return out.asByteArray();
        }
    }

    private static int clamp(float unitValue) {
        return Math.max(0, Math.min(255, Math.round(unitValue * 255f)));
    }
}
