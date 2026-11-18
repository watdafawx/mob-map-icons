Mob Map Icons - in-game screenshots
====================================

Taken 2026-11-18 with the `runScene` dev run (build.gradle): a creative superflat world at noon,
mob spawning off, 15 NoAI mobs placed around the player (zombie, skeleton, creeper, enderman, pig,
cow, baby cow, sheep, chicken, wolf, villager, iron golem, bat, slime, cat). Default mod config
(FULL_BODY, iconSize 16, tint off). Game window 1920x1080, GUI scale 2. Every image is a crop of
the real frame, enlarged 2x with nearest-neighbour so map pixels stay sharp. One map mod per run.

Files
-----
ftbchunks-minimap.png    FTB Chunks minimap (rotating, zoom 4, scale 2, minimap.large_entities=true).
ftbchunks-large-map.png  FTB Chunks large map (M), zoomed in 5 steps.
before-after.png         FTB Chunks large map, same view: left mod disabled (FTB's own heads),
                         right with Mob Map Icons.
xaero-minimap.png        Xaero's Minimap, radar on, entity icons set to "Always", minimap_zoom 4.
xaero-worldmap.png       Xaero's World Map (M), minimap radar shown on the world map, 2 zoom steps.
journeymap-minimap.png   JourneyMap minimap, zoomed in 2 steps, default mob icon display.
journeymap-fullmap.png   JourneyMap fullscreen map (J).

Versions tested
---------------
Minecraft 1.21.1, NeoForge 21.1.250
FTB Chunks 2101.1.24, FTB Library 2101.1.40, FTB Teams 2101.1.12, Architectury 13.0.12
Xaero's Minimap 26.6.0, Xaero's World Map 1.47.0
JourneyMap 6.0.9

Notes
-----
- FTB: needs minimap.large_entities=true for the minimap to look like this; at FTB's default
  (0.667 scale) full-body icons are small.
- Xaero: icons only replace dots when the radar category's "Display icons" is "Always" (Xaero's
  default is "Player List"). Xaero's own pig/cat/wolf icons are overridden.
- JourneyMap draws mob icons at its own fixed size inside its heading markers, so iconSize has no
  visible effect there.

Xaero re-shoot, config screen and style gallery (2026-11-18)
------------------------------------------------------------
Same scene as above plus 2 more sheep (red, blue) and a second (black) cat, fire resistance so nothing
burns, default config (FULL_BODY, OUTLINE, iconSize 16, xaeroScale 0.75). Crops of the real frame,
enlarged 2x with nearest-neighbour. Taken on rig with `runScene`, driven through run/scene-cmd.txt
(scene steps `click`, `key`, `type`, `cmd`, `shot`).

xaero-minimap.png    Xaero's Minimap, radar "Display icons" = Always, minimap_zoom 4, xaeroScale 0.75.
xaero-worldmap.png   Xaero's World Map (M), 5 scroll steps in, same radar setting.
config-screen.png    Mods > Mob Map Icons > Config (NeoForge ConfigurationScreen). Every option fits on
                     one screen: Icon style, Icon size, Farm mode, Xaero scale and the rest.
styles-gallery.png   The same view on the FTB Chunks large map (M, 5 zoom steps) in each of the ten icon
                     styles, switched live with `/mobmapicons style <name>`. One crop per style, same
                     region, tiled and labelled with Pillow. Real in-game pixels, no mock-up.

Notes
- Xaero picks up a style or xaeroScale change on its own within ~1-3 s (the adapter polls once a
  second, then Xaero reloads its radar sprites). No F3+T needed.
- Xaero draws one icon per entity type, so differently coloured sheep all show the white sheep. FTB
  Chunks draws the per-variant icon (red and blue sheep in the gallery).
- Xaero's own radar icons are small heads. Full-body icons at xaeroScale 0.75 are about 2x their size;
  0.5 is close to Xaero's own size, 1.0 fills the whole radar cell and overlaps in a crowd.
