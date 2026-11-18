package dev.watdafawx.mobmapicons.compat.xaero;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Turns a mob icon PNG (any square-ish size, {@code iconSize} 8..64) into the 64x64 RGBA sprite that
 * Xaero's {@code RadarIconSpriteFormPrerenderer} expects.
 *
 * <p>Xaero binds the sprite as a texture and draws it as a fixed 64x64 quad
 * ({@code RadarIconCreator.ICON_WIDTH = 64}), so the image has to be exactly 64x64 or the UVs are wrong.
 * {@code outlined_sprite} makes Xaero add the black outline itself ({@code drawOutlineLayer}), which needs
 * transparent room around the picture. The icon is copied 1:1 (centred) so it stays pixel-sharp and
 * {@code iconSize} sets its size on the radar; only icons wider than {@link #MAX_CONTENT} are scaled down.
 */
final class XaeroSprite {
    static final int SIZE = 64;
    /**
     * Largest picture that still leaves room for Xaero's outline. Smaller icons are not scaled up:
     * stretching the default 32px texture to 56px drew our icons about three times the width of
     * Xaero's own head icons, blurred them, and made {@code iconSize} do nothing on Xaero.
     */
    private static final int MAX_CONTENT = 60;

    private XaeroSprite() {}

    /** @return 64x64 PNG bytes, or {@code null} if the source could not be decoded */
    static byte[] convert(byte[] sourcePng) {
        try (NativeImage src = NativeImage.read(new ByteArrayInputStream(sourcePng))) {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            int side = Math.max(w, h);
            if (side <= MAX_CONTENT) return copyCentred(src);

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

            float scale = (float) MAX_CONTENT / side;
            float outW = w * scale, outH = h * scale;
            float x0 = (SIZE - outW) / 2f, y0 = (SIZE - outH) / 2f;
            int sub = scale < 1f ? 3 : 1; // downscale: average 3x3 bilinear samples per output pixel

            try (NativeImage out = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true)) {
                for (int oy = 0; oy < SIZE; oy++) {
                    for (int ox = 0; ox < SIZE; ox++) {
                        float r = 0, g = 0, b = 0, a = 0;
                        for (int sy = 0; sy < sub; sy++) {
                            for (int sx = 0; sx < sub; sx++) {
                                float fx = (ox + (sx + 0.5f) / sub - x0) / scale - 0.5f;
                                float fy = (oy + (sy + 0.5f) / sub - y0) / scale - 0.5f;
                                int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
                                float tx = fx - ix, ty = fy - iy;
                                for (int k = 0; k < 4; k++) {
                                    int px = ix + (k & 1), py = iy + (k >> 1);
                                    if (px < 0 || py < 0 || px >= w || py >= h) continue; // outside = transparent
                                    float wt = ((k & 1) == 0 ? 1 - tx : tx) * ((k >> 1) == 0 ? 1 - ty : ty);
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

    private static int clamp(float unitValue) {
        return Math.max(0, Math.min(255, Math.round(unitValue * 255f)));
    }
}
