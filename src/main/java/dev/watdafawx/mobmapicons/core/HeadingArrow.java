package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Adds the heading arrow to a finished icon, for map mods that fit the icon into a marker of their own
 * (JourneyMap's round pin) and so cannot use the arrow baked in with the style: the baked arrow lives in
 * the margin the style leaves, and fitting the whole icon into the marker shrinks that arrow away. Here
 * the icon is fitted first and the arrow is drawn over its rim afterwards, bigger, in a saturated colour
 * with a dark edge so it reads on the marker's light fill. The icon keeps its size (JourneyMap draws a
 * texture at its pixel size).
 */
public final class HeadingArrow {
    private static final int FILL = 0xff1ec8ff;  // ABGR: amber
    private static final double LEN = 0.26, HALF = 0.175; // of the icon edge
    private static final double TIP = 0.335;            // tip distance from the centre: inside the marker's border, which JourneyMap draws over the icon
    private static final double EDGE = 0.05;             // dark edge, of the icon edge (at least 1 px)

    private HeadingArrow() {}

    /**
     * @param png    a square RGBA PNG (already fitted)
     * @param bucket heading bucket 0..7, 0 points down, clockwise (see {@code MobIconApi.yawBucket})
     * @return the PNG with the arrow drawn on its rim; {@code null} if undecodable
     */
    public static byte @Nullable [] overlay(byte[] png, int bucket) {
        try (NativeImage out = NativeImage.read(new ByteArrayInputStream(png))) {
            int n = out.getWidth();
            double edge = Math.max(1.0, n * EDGE);
            IconStyles.drawArrow(out, n / 2.0, n / 2.0, n * TIP, n * LEN, n * HALF, Math.floorMod(bucket, 8),
                    FILL, IconStyles.ARROW_EDGE, edge, edge + 0.3, 0.85);
            return out.asByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
