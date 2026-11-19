package dev.watdafawx.mobmapicons.api;

/**
 * Which view of a map mod is drawing: its small in-world HUD map or its full-screen map. Icon size is
 * configured per view ({@code iconSizeMinimap}, {@code iconSizeFullscreen}); every other look option is shared.
 */
public enum MapView {
    MINIMAP,
    FULLSCREEN
}
