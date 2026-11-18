package dev.watdafawx.mobmapicons.api;

/**
 * Everything about an icon's look that a map mod's settings decide: the effective values after the
 * per-map-mod overrides are applied. Two profiles that render the same pixels are {@code equals}.
 *
 * @param rotate whether the model turns with the mob's heading bucket
 * @param arrow  whether a heading arrow is drawn into the icon
 */
public record IconProfile(IconStyle style, IconMode mode, int size, boolean rotate, boolean arrow) {

    public IconProfile withStyle(IconStyle other) {
        return other == style ? this : new IconProfile(other, mode, size, rotate, arrow);
    }
}
