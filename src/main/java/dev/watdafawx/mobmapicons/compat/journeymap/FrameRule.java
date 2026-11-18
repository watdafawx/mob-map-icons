package dev.watdafawx.mobmapicons.compat.journeymap;

import dev.watdafawx.mobmapicons.ClientConfig.JourneyMapFrame;
import dev.watdafawx.mobmapicons.api.IconStyle;

/**
 * Which of JourneyMap's frame and our icon style survive when both would draw an edge.
 *
 * <p>JourneyMap draws every mob icon inside its own marker: a teardrop filled in the hostility colour
 * (red hostile, green villager, white passive...) and, on top of the icon, a black border (red when
 * the mob is aggro) plus the heading tail. Several of our styles add a second edge of their own
 * (outline, ring, shadow, pin), which reads as a double frame. This picks one:
 * <ul>
 *   <li><b>Self-contained tokens</b> (BADGE, HEAD_PIN, SILHOUETTE, RETRO, CHIBI, PAPER_INK) are a
 *       finished marker by themselves, so under AUTO the JourneyMap marker is hidden and the style is
 *       drawn unchanged.</li>
 *   <li><b>Edge-only styles</b> (OUTLINE, SHADOW) exist to separate the mob from the map, which the
 *       marker already does, so under AUTO the marker stays and the mob is drawn as PLAIN inside it.</li>
 *   <li>PLAIN and TOP_DOWN have nothing to drop and keep the marker.</li>
 * </ul>
 * With the marker kept (ON, or AUTO for the styles above), BADGE, HEAD_PIN and SILHOUETTE are also drawn
 * PLAIN, since the marker already carries the hostility colour. With it hidden (OFF, or AUTO) the chosen
 * style is drawn as is.
 *
 * <p>AUTO keeps the marker whatever the style when {@code rotateWithHeading} is off: the tail on the
 * marker is then the only heading cue left. Pure logic, no JourneyMap types.
 */
record FrameRule(IconStyle body, boolean hideFrame) {

    static FrameRule of(IconStyle style, JourneyMapFrame mode, boolean rotateWithHeading, boolean canHide) {
        boolean hide = canHide && switch (mode) {
            case ON -> false;
            case OFF -> true;
            case AUTO -> rotateWithHeading && selfContained(style);
        };
        return new FrameRule(hide ? style : insideMarker(style), hide);
    }

    private static boolean selfContained(IconStyle style) {
        return switch (style) {
            case BADGE, HEAD_PIN, SILHOUETTE, RETRO, CHIBI, PAPER_INK -> true;
            default -> false;
        };
    }

    /** The style to draw when JourneyMap's marker stays: the one that adds nothing the marker already draws. */
    private static IconStyle insideMarker(IconStyle style) {
        return switch (style) {
            case OUTLINE, SHADOW, BADGE, HEAD_PIN, SILHOUETTE -> IconStyle.PLAIN;
            default -> style;
        };
    }
}
