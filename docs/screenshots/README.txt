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


Players (2026-11-18)
--------------------
Two fake RemotePlayers (Steve_Test, Alex_Test: the two default skins, different headings) stand next to the
mobs in the `runScene` scene. Left players=false (the map mod's own face), right players=true. The local
player is never touched.

players-ftbchunks.png   FTB Chunks large map (M, 5 zoom steps): face vs full-body skin icon, heading respected.
players-journeymap.png  JourneyMap minimap, zoomed 2 steps: face vs full-body skin icon. JourneyMap draws players
                        from its own skin-face cache (not the radar icon API), so the adapter puts our icon there.


JourneyMap frame (2026-11-18)
-----------------------------
journeymap-frames.png   JourneyMap minimap, zoomed 2 steps, same scene and two fake players. Columns: OUTLINE,
                        BADGE, SILHOUETTE, RETRO, HEAD_PIN. Top row BEFORE (JourneyMap pin + our style = double
                        frame, black box round the players), bottom row AFTER (journeymapFrame=AUTO).
                        Real in-game pixels, 2x nearest-neighbour crops, tiled and labelled with Pillow.

Config `journeymapFrame` (default AUTO):
- AUTO: BADGE, HEAD_PIN, SILHOUETTE, RETRO, CHIBI, PAPER_INK are finished tokens, so JourneyMap's pin is hidden
  and the style is drawn as is. OUTLINE and SHADOW keep the pin and draw the mob PLAIN inside it. PLAIN and
  TOP_DOWN keep the pin. With rotateWithHeading=false the pin always stays (its tail is the only heading cue).
- ON: always keep JourneyMap's pin (OUTLINE, SHADOW, BADGE, HEAD_PIN, SILHOUETTE drawn PLAIN inside it).
- OFF: always hide the pin, draw the chosen style as is.
The pin border goes through the API (WrappedEntity.setDrawOutline). The coloured fill is hidden by registering a
transparent texture over JourneyMap's marker-icon-bg-160 ids in the vanilla TextureManager. The player box is
DrawEntityStep.playerOutlineTexture, swapped by guarded reflection (one warning and fallback if it moves).
Players: the square box is gone in every style.
Not shown: JourneyMap fullscreen map (before/after zoom did not match, so left out).
