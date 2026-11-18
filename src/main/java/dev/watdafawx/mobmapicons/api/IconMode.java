package dev.watdafawx.mobmapicons.api;

/**
 * How much of a mob an icon shows.
 *
 * <p>FULL_BODY is the whole model as seen from the front; HEAD is only the head, for dense maps
 * where a full body turns into an unreadable blob at map scale.
 */
public enum IconMode {
    FULL_BODY,
    HEAD
}
