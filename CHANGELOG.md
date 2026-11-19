# Changelog

## 0.4.4 (unreleased)

- Quick options panel, faster: one compact view with no scrolling (style, mode, size, rotate, arrow, farm, players, own
  icon). Style, mode and the on/off/global rows step on a click and on the mouse wheel over the row; right click or
  shift+click steps back. Stepping off "global" always lands on a value that changes the look. Changing style and size
  for one map mod is three clicks (its tab, Style, Size). A **Same as global** toggle on each map mod tab replaces
  "All back to global"; rarely used rows (the map mod's own options, max icons, cluster radius) are on a second page, **More >**.
- Separate icon size for the minimap and the full-screen map, all map mods: `iconSize` is replaced by `iconSizeMinimap`
  and `iconSizeFullscreen` (global, plus a per-map-mod override, `0` = inherit). FTB Chunks picks by its map type
  (minimap vs large map), JourneyMap by its UI (minimap vs full-screen/web map). Xaero's World Map
  shares the minimap radar's sprites, so Xaero gets two sprite sets and the World Map's is swapped in while its screen
  is open (see below). An old `iconSize` seeds both new keys. The panel's size row
  edits the view you are on (a Mini / Full button switches); the config screen lists both.
- JourneyMap: new **JM mobs** and **JM players** rows in the panel (JourneyMap's Mob Display / Player Display, written
  live and saved) and `[journeymap] autoIconDisplay` (default on): JourneyMap draws its own dot or pin round our icons
  unless both are `Icons`, so while the adapter is enabled they are set to `Icons` for the minimap and the full-screen
  map. Your previous values are remembered and restored when the adapter is disabled or the option is turned off.
- JourneyMap's own rows (mob heading, show self, mob scale, self scale, display) now write to the view the panel is on
  (minimap or full-screen map), and the minimap's separate properties object is kept in step.
- Xaero: own icon size for the World Map (`[xaero] iconSizeFullscreen`) and the minimap. The mod serves the set of the
  view on screen and swaps it, with one icon reload, when a World Map screen opens or closes (only if the two sets differ;
  nothing per frame). `xaeroScale` now multiplies with size / 16 (16 keeps the old look); the sprite is capped at 60 of
  Xaero's 64 px box. The panel's Xaero tab has the Mini / Full button too and stays stable over the World Map.
- JourneyMap: a **JM rows edit: minimap / full-screen map** button in the panel's JM tab (and on its More page), so the
  JM rows edit either view from either place.
- JourneyMap: your Mob Display / Player Display values are put back in JourneyMap's files when the game quits, so they
  never keep `Icons` while the mod is gone; the next start sets `Icons` again while `autoIconDisplay` is on.
- JourneyMap: with Mob Display on `Icons` the icon is drawn at full size in the chosen style (OUTLINE keeps its outline)
  instead of fitted small and plain into a marker JourneyMap no longer draws.
- Config screen: one-level sections, size options next to each other, global options in the order the panel shows them.
- API: `MapView` and view overloads (`getIcon(entity, MapMod, MapView)`, `iconSize(MapMod, MapView)`, `profile(MapMod, MapView)`);
  the old overloads mean the minimap.
- Dev harness: `rclick`, `clicks` / `resetclicks`, `jmdisplay`, `cfgsave` and the `sizemin` / `sizefull` / `autodisplay` cfg keys.

## 0.4.3

- Heading arrow: a small arrow on each icon shows the way the mob faces (FTB Chunks, JourneyMap; `headingArrow`, on by default). `rotateWithHeading` is now off by default.
- JourneyMap: fixed the map mod's own mob scale and self scale rows (they now change the minimap live), and `colorSelf` is restored so the own icon never leaves it white in JourneyMap's config.
- Per-map-mod config: new `[ftb]`, `[xaero]` and `[journeymap]` sections. Each overrides style, mode, size, rotate
  with heading, heading arrow, farm mode and other players for that map mod only (default: inherit the global value), so
  FTB Chunks can show OUTLINE while JourneyMap shows BADGE at the same time. `xaeroScale` moved to `[xaero]`,
  `journeymapFrame` to `[journeymap]`, and the old flat on/off switches became `enabled` in each section. Old config
  files are read once and their values become the new defaults.
- A section exists only for installed map mods, with explicit translation keys; with none installed there are no map-mod sections.
- `ownPlayer` (off by default, per map mod): draw your own marker with our icon. FTB Chunks large map and minimap (face),
  JourneyMap minimap and fullscreen map (arrow textures, top-down view). Not available for Xaero.
- Quick options panel is context-aware: it opens on the section of the map mod whose screen is open, has a tab per
  installed map mod, an "all back to global" button, and a few of the map mod's own icon options (FTB Chunks large icons,
  entity heads, player heads; JourneyMap mob heading, show self, mob and self scale).
- API: `MapMod`, `IconProfile` and per-map-mod `MobIconApi` overloads (`getIcon(entity, MapMod)`, ...). The icon cache is keyed
  by profile, so different map mods can show different styles at once.
- Dev harness: `cfg <ftb|xaero|journeymap>.<style|mode|size|own|enabled|...> <value>` and `opts <ftb|xaero|journeymap|global>` scene steps.

## 0.4.2

- Quick options panel: a key binding (unbound by default) or `/mobmapicons options` opens a small panel over the
  minimap or a fullscreen map (FTB Chunks, Xaero's World Map, JourneyMap). Style, mode, size, heading, farm mode,
  players, hostile tint, Xaero scale and max icons change live, with the map visible behind it.
- Head mode now shows head and shoulders, bigger in the frame; the option reads "Head and shoulders".
- CHIBI: the shrunk body is bottom-aligned with the head overlapping it, as in the original mock.
- Dev harness skips Minecraft's first-run Narrator prompt; the self-test now expects the player icon to render.

## 0.4.1

- JourneyMap: new config `journeymapFrame` (AUTO default, ON, OFF) so JourneyMap's marker no longer fights
  our icon styles. AUTO hides the marker for self-contained styles (BADGE, HEAD_PIN, SILHOUETTE, RETRO,
  CHIBI, PAPER_INK) and, for OUTLINE and SHADOW, keeps the marker and draws the mob plain inside it, so
  there is one frame instead of two. ON always keeps the marker, OFF always hides it.
- JourneyMap: the square box around player icons is gone.
- API: `MobIconApi.getPng` / `getResourceIcon` overloads that take an `IconStyle`.

## 0.4.0

- Players: other players are drawn from their own skin and model, full body and turning with their
  heading, on FTB Chunks and JourneyMap. New config `players` (default on). The local player is never
  touched; players never merge in farm mode; Xaero keeps its own heads.
- JourneyMap draws players from a private face cache, so the adapter swaps icons in there by reflection
  and restores the faces when `players` is off or the field is missing.
- Dev scene: `players` / `cfgplayers` steps spawn fake remote players.

## 0.3.0

- Ten live icon styles, default OUTLINE, applied to cached textures and PNGs.
- In-game config screen and `/mobmapicons style <name>` / `reload` commands.
- Bounded previous-generation textures during rebuilds; style comparison contact sheets.
- Xaero: new `xaeroScale` config (default 0.75) sizes icons against Xaero's own 56 px icon box, nearest-neighbour up and area down, republished on change without a resource reload.
- JourneyMap: icons now follow the mob's heading (8 rotated icons per variant, picked per radar
  update; JourneyMap never rotates a mob icon itself), and every icon is shrunk to fit inside the
  round marker, so wide mobs (cows, sheep, wolves) no longer spill past it. Padded copies are served
  from their own `mob_map_icons_jm` texture path; other map adapters keep the unpadded icons.
- JourneyMap farm mode: same-kind, same-variant mobs close together on the map collapse into one
  icon labelled `37 x Cow`. Named, leashed, tamed and boss mobs are never merged; `maxIcons` keeps
  the nearest icons. JourneyMap cannot draw a count badge, so the count is the icon's name label.
- FTB Chunks: icons turn to face the mob's heading (8 directions) on the large map and on a rotating
  minimap, using `MobIconApi.getIcon(entity, yawBucket)`. Off when `rotateWithHeading` is false.
- FTB Chunks: farm mode. Mobs of the same type and icon that sit within `clusterRadius` screen pixels
  of each other merge into one icon with an `x37` badge (`999+` cap) and a `37 x Cow` tooltip, once a
  group reaches `clusterMinCount`. Named, leashed, tamed and boss mobs and the local player never
  merge. Zooming in splits clusters; FTB's icon list is rebuilt when the zoom or the config changes.
- FTB Chunks: farm mode joins touching grid cells of one kind into a single cluster, so a whole pen is one `x150` icon instead of a dozen overlapping `x12` ones; kinds sharing one spot (mixed pens) fan out, and a label that would overprint a bigger neighbour's is dropped (the tooltip stays).
- FTB Chunks: `maxIcons` caps the icons per map (nearest to the player first; a cluster counts as one).
- Icon lists are processed in one pass with pooled objects, so 300 mobs on the map cost no more
  than FTB's own heads.

## 0.2.0

- Icons per variant: babies, sheep colours, villager type and profession, cat/wolf/horse/fish
  variants, collars, charged creepers and sizes (slimes, scaled mobs) each get their own icon. The
  per-type icon stays as the fallback.
- Headings: `MobIconApi.getIcon(Entity, yawBucket)` gives 8 rotated icons per variant, rendered on
  demand; `MobIconApi.yawBucket(float)` maps a yaw to a bucket. New config `rotateWithHeading`
  (default on) tells map adapters whether to use them.
- Head mode crops around the model's head part instead of the top of the model. Flying and wide mobs
  fall back to the full body.
- The icon is drawn from a throwaway copy posed where the real mob (or the player) is, at full
  brightness; the real entity is never touched. Name tags no longer leak into icons.
- Cache is an LRU of 512 icons, still at most 2 renders per frame.
- `/mobmapicons headings` and a contact sheet in `/mobmapicons dump`. The self-test covers variants,
  headings and head crops.

## 0.1.0

- First version. Renders each mob type's real model and texture to an icon (full body or head) and
  shows it on FTB Chunks (minimap and large map), Xaero's Minimap and World Map, and JourneyMap.
- Config: icon mode, icon size, per-map-mod toggles, hostility tint (FTB Chunks), entity blacklist.
- Client commands `/mobmapicons render <entity_type>` and `/mobmapicons dump`.
