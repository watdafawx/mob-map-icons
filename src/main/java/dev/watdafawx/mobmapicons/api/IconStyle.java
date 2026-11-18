package dev.watdafawx.mobmapicons.api;

/** Appearance, independent of the body/head selection. */
public enum IconStyle {
    PLAIN, OUTLINE, SILHOUETTE, BADGE, RETRO, TOP_DOWN, CHIBI, PAPER_INK, SHADOW, HEAD_PIN;
    public boolean extraRender() { return this == CHIBI || this == HEAD_PIN; }
}
