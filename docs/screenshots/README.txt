Mob Map Icons - in-game screenshots
====================================

Retaken 2026-11-18 on the current build (icon style OUTLINE, per-variant icons, heading, farm mode)
with the `runScene` dev run (build.gradle): a creative superflat world at noon, mob spawning off,
19 NoAI mobs spread around the player (zombie, skeleton, creeper, enderman, pig, cow, baby cow,
white/red/blue sheep, chicken, wolf, tabby + black cat, villager, iron golem, bat, slime, axolotl),
several turned to different yaws. Default mod config (FULL_BODY, OUTLINE, iconSize 16, tint off,
rotateWithHeading, farmMode on, maxIcons 150) unless a file below says otherwise. Game window
1920x1080, GUI scale 2. Every image is a crop of the real frame, enlarged 2x with nearest-neighbour
so map pixels stay sharp; the side-by-side images add a label strip. One map mod per run.

Files
-----
ftbchunks-minimap.png    FTB Chunks minimap (north-locked, zoom 4, scale 2, minimap.large_entities=true).
ftbchunks-large-map.png  FTB Chunks large map (M), zoomed in 5 steps.
before-after.png         FTB Chunks large map, same view: left mod disabled (FTB's own heads),
                         right with Mob Map Icons.
heading.png              FTB Chunks large map: three rows (cow, pig, zombie), each at yaw 0, 45 ... 315.
                         The icon turns to the mob's heading (8 buckets, rotateWithHeading=true).
ftbchunks-farm-off-vs-on.png
                         FTB Chunks large map, same scene: 144 cows in a 12x12 pen plus four crowds of
                         16 (zombie, creeper, skeleton, spider). Left farmMode=false (maxIcons 500 so
                         nothing is cut), right farmMode=true: one icon + count badge per crowd (x144, x16).
journeymap-minimap.png   JourneyMap minimap, zoomed in 2 steps (=), default mob icon display.
journeymap-fullmap.png   JourneyMap fullscreen map (J).
journeymap-farm-off-vs-on.png
                         JourneyMap fullscreen map, same scene as the FTB farm pair, one zoom step out
                         from journeymap-fullmap. Left farmMode=false (maxIcons 500), right farmMode=true
                         (default), crowds become "144 x Cow", "16 x Zombie" ...
xaero-minimap.png        Xaero's Minimap, radar on, entity icons set to "Always", minimap_zoom 4.
xaero-worldmap.png       Xaero's World Map (M), minimap radar shown on the world map, 2 zoom steps.
(the two Xaero files are older and are retaken separately)

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
- With farmMode=false, JourneyMap stopped drawing mobs beyond ~35 blocks in these runs, so the farm crowds sit inside that.
- Farm shots with farmMode=false use maxIcons=500: at the default 150 the nearest 150 icons win and
  crowd members further out are simply not drawn.
