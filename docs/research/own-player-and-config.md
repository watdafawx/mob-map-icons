# Own-player marker, context-aware quick options, per-mod config sections (recon from jars)

Analysed (read-only, `javap -p -c`; mojmap names): `ftb-chunks-neoforge-2101.1.24`, `ftb-library-neoforge-2101.1.40`,
`journeymap-neoforge-1.21.1-6.0.9` (+ API `journeymap-api-neoforge-2.0.0-1.21.1`), `xaerominimap-neoforge-1.21.1-26.6.0`,
`xaeroworldmap-neoforge-1.21.1-1.47.0`, `xaerolib 1.7.3` (jar-in-jar of the minimap), NeoForge 21.1.205 sources.
Everything below is from bytecode, nothing was run in-game. "Unverified" marks the gaps.

Today our code never touches the local player: `IconPass` skips `e == player`; `JourneyMapAdapter` only handles
`RemotePlayer`; `XaeroSync` skips `minecraft:player`. `MobIconApi.getIcon(entity, bucket)` already renders a player
because `IconVariant` draws a `RemotePlayer` wearing the profile (so own invisibility / spectator / first-person
hidden-model do not matter for our texture; only the map mod decides whether the marker shows).

---------------------------------------------------------------------------------------------------

## Part 1 - own marker, per map mod

### FTB Chunks (2101.1.24)

**How it draws.** `FTBChunksClient#mapIcons(MapIconEvent)` loops `level.entitiesForRendering()` but calls
`EntityIconUtils.shouldEntityRender(e, player)`: `canTypeRender` is true for `EntityType.PLAYER`, and then
`EntityIconLoader.getSettings(PLAYER).isEmpty()` (ftb-library ships only `faces/normal.png`, no
`faces/minecraft/player.*`) makes it `continue`. So **the local player is not an entity icon in the loop**, and
other players come from the separate `MINIMAP_PLAYER_HEADS` loop (`level.players()`, skips `== mc.player`).
The self marker is added separately, depending on the map type:

| surface | what draws it | in the icon list? |
|---|---|---|
| Large map (`MapType.LARGE_MAP`) | tail of `mapIcons`: `POINTER_ICON_MODE.showFace()` -> `new EntityMapIcon(mc.player, FaceIcon.getFace(profile))` (priority 150 via `instanceof LocalPlayer`); `showPointer()` -> `new gui.PointerIcon()` (`getPos` = player position, `draw` = `Icon` rotated by `player.getYRot()`) | **yes**, both are in `MapIconEvent.icons`, i.e. visible to our `IconPass` |
| Minimap | inline in `FTBChunksClient#renderMinimap` after the icon loop (offset ~2475-2865): gated by `SHOW_PLAYER_WHEN_UNLOCKED` / locked-north, `POINTER_ICON_MODE_MINIMAP.showPointer()` draws a textured quad (`PLAYER` texture) rotated by `player.getYRot()+180` when north is locked; `.showFace()` builds `new EntityMapIcon(player, FaceIcon.getFace(profile)).draw(MINIMAP, g, 0,0,1,1,false,255)` (face scaled 0.75 and moved when both) | **no**, drawn directly, not through the event |

Rotation: pointer rotates with the player yaw (minimap: only when the minimap is locked north; otherwise the map rotates
and the pointer stays up). The face is upright, no label (`EntityMapIcon.addTooltip` shows the entity name on hover only).

**Config.** `POINTER_ICON_MODE` (large map) and `POINTER_ICON_MODE_MINIMAP` are `EnumValue<PointerIconMode>` with
`FACE / POINTER / BOTH` - **there is no "none"**, so the self marker cannot be switched off by config.

**(a) Hide / (b) draw ours.**
- Large map: in `IconPass.process` the list holds `EntityMapIcon` (entity `== player`, reflective field `entity` we already read)
  and `PointerIcon`; `icons.set(i, ours)` or `icons.remove` works exactly like for mobs. S.
- Minimap: nothing to swap in the list. Best route is **mutate the face icon**: `FaceIcon.getFace(profile)` returns a cached
  `FaceIcon` (private static `CACHE: HashMap<UUID,FaceIcon>`) whose `public Icon skin/head/hat` fields are plain public
  mutable fields drawn by `FaceIcon.draw`. Set `face.head = Icon.getIcon(ourTexture)`, `face.hat = Icon.empty()` (both the
  minimap inline draw and the large-map `EntityMapIcon` then show our model with no reflection at all). The FaceIcon's skin
  callback (`lambda$new$0`) rewrites the fields once when the skin resolves, so re-apply on the client tick
  (also switches the heading bucket). Keep FTB's pointer arrow (it is the heading indicator; `mode=BOTH` gives face+arrow).
  Hiding the pointer needs `POINTER_ICON_MODE_MINIMAP=FACE` (user config write + restore) - not recommended.
- Heading: FTB's pointer already turns; our face needs no rotation, bucket optional (`IconDraw.bucket` logic works unchanged).
- Not possible without a Mixin: removing the minimap face/pointer entirely, or drawing a non-square custom shape under the pointer.

### JourneyMap 6.0.9

**How it draws.** Not an API entity, not in `generateDrawSteps` (`MapState.generateDrawSteps` builds mobs/animals/
villagers/players-except-self/waypoints/overlays only). The self marker is drawn imperatively at the end of the map draw:
- Minimap: `ui.minimap.MiniMap.drawMap(GuiGraphics,boolean)` (~offset 1435-1566): if `miniMapProperties.showSelf.get()` and
  `playerArrowFg != null` and `centerPoint != null`: `DrawUtil.drawColoredEntity(g, x, y, playerArrowBg, 0xFFFFFF, alpha, selfDisplayScale, heading)` then the same with
  `playerArrowFg` tinted `playerArrowColor` (= `CoreProperties.colorSelf`, read in `MiniMap.reset()`). `heading = mc.gameRenderer.getMainCamera().getYRot()`.
  `playerArrowBg/Fg` are **private** `DynamicTexture` fields set in `reset()` from `TextureCache.getTexture(TextureCache.PlayerArrowBG|PlayerArrow)` = `journeymap:ui/img/marker-player-bg-160.png` / `marker-player-160.png`.
- Fullscreen: `ui.fullscreen.Fullscreen.drawMap(...)` (~326-500): `fullMapProperties.showSelf`, position `mapRenderer.getPixel(player x,z)`, translate z=1000, the same two textures fetched
  every frame with `TextureCache.getTexture(...)` and `drawColoredEntity(..., getMainCamera().getYRot() ...)`; scale `selfDisplayScale`.
- Rotates continuously (not 8 buckets), tinted by `colorSelf`, no name label (the self marker has none; `showPlayerNames` is for others).
- Other players go the `IgnSkin.faceImageCache` route we already use; the local player does not.

**(a) Hide / (b) draw ours - routes**
1. **Hide with config:** `props.showSelf.set(false)` + `.save()` (`ConfigField.save()` exists). `props` = `JourneymapClient.getInstance().getActiveMiniMapProperties()` /
   `.getFullMapProperties()` (same reflection `JourneyMapAdapter` already uses for `showMobHeading`). It is the user's own option, so write/restore carefully
   (remember the old value, restore when our feature is off or on disconnect). Then draw ours via the supported API:
   `MarkerOverlay` (or `ImageOverlay`) with `MapImage.setRotation(int degrees)`, `setDisplayWidth/Height`, `setAnchorX/Y`, shown via `IClientAPI.show`.
   Downsides: `MarkerOverlay` takes a `BlockPos` (block-quantised, visible stutter on a smooth-scrolling map), changing rotation/icon is the slow `applyOverlay` path,
   overlay steps are drawn with the mob steps, i.e. under other mobs and under the old z=1000 arrow slot. Effort M, behaviour mediocre.
2. **Texture swap (recommended):** keep `showSelf` on and replace what the arrow draws. `TextureCache.getTexture(PlayerArrow|PlayerArrowBG)` is hit every frame by Fullscreen and cached into the two private fields by MiniMap. Create a
   `DynamicTexture` (same size as the 160px marker texture, via `IconPadding`/`NativeImage`) holding our player icon and (i) put it where `TextureCache` resolves those two locations
   (like `FrameHider`, which already transparentises JM textures - reuse its mechanism; `PlayerArrowBG` -> fully transparent), (ii) set the MiniMap fields by reflection (`playerArrowFg/Bg`, private, re-assigned in `MiniMap.reset()`,
   so re-apply after `reset`, e.g. on `ClientTickEvent` compare identity). It keeps JM's position, scale (`selfDisplayScale`), z-order, the size slider and both UIs for free.
   Costs: the quad rotates continuously with the camera yaw (a top-down / "arrow baked in" style looks right, a upright skin model spins and is upside-down facing south), and `colorSelf` multiplies our colours
   (default colour unverified; set our texture's tint inverse or write `colorSelf` to white = user config write). So: **ship a heading-friendly self style** (`TOP_DOWN` or an own "pointer" render) for JM.
   Effort M.
3. Mixin route (out of scope): `DrawUtil#drawColoredEntity` or `MiniMap#drawMap`.

Heading source: JM uses `Camera.getYRot()` (camera yaw), which in front-facing third person (F5 twice) points the arrow backwards. Our overlay approach (1) can use `player.getYRot()`; approach (2) inherits JM's behaviour.
Multiplayer: self marker is not a `Feature` (no server gate), nothing differs; server-side feature flags (`FeatureManager`) only gate radars.

### Xaero's Minimap 26.6.0 + World Map 1.47.0

**How it draws.**
- Minimap: `xaero.common.minimap.render.MinimapRenderer.drawArrow(PoseStack,float,double,double,float,float,float,float,ClientConfigManager)` (call sites at ~3044/3073/3100): translate to centre, `OptimizedMath.rotatePose(pose, yaw, ZP)`, scale `ARROW_SCALE`, colour `MinimapConfigConstants.ARROW_COLORS[ARROW_COLOR]`, alpha `ARROW_OPACITY`, then
  `GuiGraphicsUtils.blit` of a **sprite on the shared GUI atlas** (`GUI_TEXTURES`, UV 49,..). It is not a radar entity and has no icon-definition hook. Option "Main Entity As" (`RADAR_MAIN_ENTITY`, Dot/Arrow) at `MinimapRenderer` ~2200 can draw the main entity as a radar **dot**
  (`RADAR_MAIN_DOT_SIZE`), never as an icon sprite (`minecraft:player` is excluded from `XaeroSync`; player heads are only for *tracked* players, `PlayerTrackerIconRenderer`).
- World Map: `xaero.map.gui.GuiMap.render` -> `drawArrowOnMap` / `drawFarArrowOnMap(PoseStack, VertexConsumer, ...)`, arrow sprite on `xaeroworldmap:gui/gui.png`, colour `SupportXaeroMinimap.getArrowColor()` (follows the minimap's `ARROW_COLOR`). Toggle: `WorldMapProfiledConfigOptions.ARROW` (BooleanConfigOption).
  Radar entities on the World Map = the minimap's `RadarRenderer` wrapper; self not part of it.
- Rotates with yaw (minimap: arrow shown when the minimap does not rotate / "other cases"), no label.

**(a) Hide.** Config only: `WorldMapProfiledConfigOptions.ARROW=false` (world map); minimap arrow: `ARROW_OPACITY` (RangeConfigOption; minimum unverified - may not reach 0) or `ARROW_SCALE`. Writes go through `ClientConfigManager.getCurrentProfile().set(option, value)` (`ConfigProfile.set` fires Xaero's change listener, Xaero saves itself).
Manager: world map `WorldMap.INSTANCE.getConfigs().getClientConfigManager()` (documented in `xaero.md`); minimap analogous via `HudMod.INSTANCE` (getter name unverified; `HudMod.INSTANCE` is already used reflectively in `XaeroSync.Handles`).
**(b) Draw ours.** No list/event. Options:
1. Atlas override via our top-pack (`XaeroIconPack`, `assets/xaeroworldmap/gui/gui.png`, `assets/xaerominimap/.../gui.png`): replace the whole atlas file, only the arrow tile changed; breaks on every Xaero atlas change, would also need to ship Xaero's other sprites. Not recommended.
2. Own draw over Xaero: **World Map** - `ScreenEvent.Render.Post` on `xaero.map.gui.GuiMap`, player screen position from `GuiMap` camera fields (`cameraX/cameraZ/scale`, private, reflection) -> M, no hide needed if we draw on top and set `ARROW=false`. **Minimap** - `RenderGuiLayerEvent.Post` and the minimap rect from `MinimapInterface`/`MinimapProcessor` (the player is always the centre of the minimap) -> L, depends on `xaero.common.*` internals that moved every release.
3. Xaero's own element system (`xaero.map.element.MapElementRenderHandler.add(ElementRenderer)` on the WM; `MinimapElementRenderHandler` on the HUD) is Xaero-internal, not a published API -> L and needs compile-time Xaero classes (our Xaero adapter currently has zero).
Not possible without a Mixin: removing the minimap arrow cleanly while keeping the minimap otherwise unchanged (opacity trick only), drawing under Xaero's other overlays.

### Cross-cutting risks (all three)

- **Heading source:** FTB uses `player.getYRot()` (body/camera yaw in first person), JM `Camera.getYRot()`; in front-facing third person (F5 x2) JM's own arrow points backwards. Use `player.getYRot()` for ours; only relevant to overlay routes.
- **First vs third person:** the player model for our icon is rendered off-screen via a `RemotePlayer` clone (`IconVariant`), so first-person hidden arms / own-model culling do not matter.
- **Invisible / spectator:** FTB draws the face/pointer unconditionally (the invisibility filter only applies to the entity loop); JM and Xaero draw the arrow unconditionally. Our `RemotePlayer` clone is not invisible, so we would show a solid model where the map mod shows its arrow: acceptable, but decide (an invisible potion user may not want the model; optionally gate on `player.isInvisible()` -> keep the map mod's own marker).
- **Their "show self" options:** JM `showSelf` (both maps) already hides the marker; if our route needs the marker on (texture swap) we must respect `showSelf=false`, i.e. do not draw anything in that case. Xaero `ARROW`/`ARROW_OPACITY`, FTB `POINTER_ICON_MODE*` and `SHOW_PLAYER_WHEN_UNLOCKED` (minimap) must be honoured the same way: **never write them as the default path**.
- Multiplayer: no server gating of the self marker in any of the three; JM offscreen player arrow `showOffScreenPlayers` is for others.
- Do not rewrite user config silently: every route that writes a map-mod option must remember the old value and restore it (like the FTB `ENTITY_ICON` caveat in `ftbchunks.md`).

### Recommendation (effort)

| map mod | self marker | route | effort | not possible |
|---|---|---|---|---|
| FTB large map | yes | replace `EntityMapIcon(player)` / `PointerIcon` in `IconPass` list, or mutate `FaceIcon` | S | remove pointer if mode is `POINTER` only |
| FTB minimap | yes (face) | mutate `FaceIcon.head/hat` (public fields) each tick, keep FTB pointer arrow | S-M | hiding FTB face/pointer entirely |
| JourneyMap (mini + full) | yes, rotating | texture swap of `PlayerArrow`/`PlayerArrowBG` + reflective `MiniMap.playerArrowFg/Bg`; self style must suit continuous rotation; or `showSelf=false` + `MarkerOverlay` (block-quantised) | M | 8-bucket skin model; ignoring `colorSelf` tint without a config write |
| Xaero World Map | partial | `ARROW=false` + our quad on `ScreenEvent.Render.Post` over `GuiMap` (reflect camera fields) | M | clean atlas-free replacement via any API |
| Xaero Minimap | partial | `ARROW_OPACITY` low + HUD overlay at the minimap centre | L | exact hide, a stable API |

---------------------------------------------------------------------------------------------------

## Part 2 - context-aware QuickOptions (`core/QuickOptions.java`)

Current panel: painted from `ScreenEvent.Render.Post` on any screen, `HostScreen` when no screen is open (`open()` ~l.94). Rows are `Cycle/Toggle/IntSlider/DoubleSlider` over `ClientConfig.*` values; `owner` = the screen it opened on.
Plan: on open, `Context ctx = detect(mc.screen)`; build rows = our section for `ctx` + the map mod's own icon options. Everything via the map mod's classes must sit behind `ModList.isLoaded` and reflection or an isolated adapter class (like `loadAdapter`) so a missing map mod never classloads.

### 1. Detecting the map mod from the open screen

| map mod | screen detection (no Mixin) |
|---|---|
| FTB Chunks | `mc.screen` is FTB Library's wrapper; adapter already uses `ClientUtils.getCurrentGuiAs(LargeMapScreen.class) != null` (`dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen`, extends `BaseScreen`). Other FTB screens (`ChunkScreen`, `EntityIconSettingsScreen`, `MinimapInfoSortScreen`, waypoint screens) -> also FTB context via `getCurrentGuiAs(BaseScreen)` + package `dev.ftb.mods.ftbchunks.client.gui` |
| JourneyMap | `mc.screen.getClass().getName().startsWith("journeymap.client.ui.")`; fullscreen = `journeymap.client.ui.fullscreen.Fullscreen` (extends `JmUILegacy`, `Screen`) |
| Xaero | World map = `xaero.map.gui.GuiMap` (any `xaero.map.gui.*` is World Map); Xaero minimap option screens are `xaero.common.gui.*` / `xaero.hud.*` |

Use `Class.getName().startsWith(..)` on `mc.screen` (also unwrap FTB's wrapper) - no compile-time reference needed for JM/Xaero. `HostScreen` (no screen open, i.e. the in-world minimaps): show one **tab per loaded map mod** (`ModList.get().isLoaded`), default tab = the first loaded in the order FTB, JourneyMap, Xaero (or the last used, remembered in-memory); with a single map mod no tab bar. Unknown screen (inventory, pause menu...) = same as no screen.

### 2. The map mod's own icon options: read + safe live write

**FTB Chunks** - `FTBChunksClientConfig` public static fields, type `dev.ftb.mods.ftblibrary.snbt.config.*Value`:
`MINIMAP_ENTITIES`, `MINIMAP_ENTITY_HEADS`, `MINIMAP_LARGE_ENTITIES` (icon scale 1.0 vs 0.667), `MINIMAP_PLAYER_HEADS`, `ONLY_SURFACE_ENTITIES`, `POINTER_ICON_MODE` / `POINTER_ICON_MODE_MINIMAP`, `MINIMAP_SCALE`, `MINIMAP_ZOOM`, `MINIMAP_LOCKED_NORTH`, `SQUARE_MINIMAP`, `ENTITY_ICON` (per-type map, edit via FTB's own `EntityIconSettingsScreen` instead).
Read `value.get()`, write `value.set(v)` (bytecode of `BaseValue.set` is a bare `putfield value`, no side effect) then `FTBChunksClientConfig.saveConfig()` (`ConfigManager.getInstance().save("ftbchunks-client")`).
Applies: `MINIMAP_LARGE_ENTITIES` is read every draw (`EntityMapIcon.getIconScale`) -> instant. List-shaping options (`MINIMAP_ENTITIES`, `_ENTITY_HEADS`, `_PLAYER_HEADS`, `ONLY_SURFACE_ENTITIES`) are read when the icon list is rebuilt: minimap within 500 ms or `FTBChunksAPI.clientApi().requestMinimapIconRefresh()`, large map `LargeMapScreen.refreshIcons()` (both already called in `FtbChunksAdapter`). No safe-write caveats beyond the file being rewritten by FTB's own ConfigManager. Effort S.

**JourneyMap** - map properties via reflection (same pattern as `fieldValue(props, name)` in `JourneyMapAdapter`): `JourneymapClient.getInstance().getActiveMiniMapProperties()` and `.getFullMapProperties()` (`InGameMapProperties`, public final fields of `ConfigField` subtypes, `.get()` / `.set(v)` / `.save()`):
`mobDisplay` (`EnumField<EntityDisplay>`: Dots, Icons, OutlinedIcons, DotsAndIcons, DotsAndOutlinedIcons), `mobDisplayScale` (`FloatField`), `showMobHeading`, `playerDisplay`, `playerDisplayScale`, `showPlayerHeading`, `showPlayers`, `showSelf`, `selfDisplayScale`;
`MiniMapProperties` only: `shape` (`Square, Rectangle_Horizontal, Rectangle_Vertical, Circle`), `orientation` (`North, OldNorth, PlayerHeading`), `showReticle`; `CoreProperties.legacyIcons`, `.colorSelf` (`getCoreProperties()`).
Note the minimap has presets (`getActiveMiniMapProperties` = the active preset), the fullscreen map one set; write the one for the detected UI (fullscreen screen -> `getFullMapProperties`, in-world/Minimap -> active minimap props).
Applies: all are read inside `MapState.generateDrawSteps` / `RadarDrawStepFactory.prepareSteps` / the draw methods, so they show on the next map refresh (expected < 1 s; unverified); `DataCache.INSTANCE.resetRadarCaches()` (public) forces a rebuild if needed. Persisted with `ConfigField.save()`; no JM reload call needed. Effort M (reflection + enum/float widgets; our existing `FarmClusters`/`FrameRule` read these too, so a change must also re-evaluate `journeyMapShowsMobHeading`).

**Xaero** - options are `xaero.lib.common.config.option.ConfigOption`s on `MinimapProfiledConfigOptions` (and `WorldMapProfiledConfigOptions`): `DISPLAY_RADAR`, `RADAR_MAIN_ENTITY`, `RADAR_MAIN_DOT_SIZE`, `RADAR_DOTS_STYLE`, `RADAR_NAME_SCALE`, `RADAR_HIDE_INVISIBLE`, `ARROW_SCALE`, `ARROW_COLOR`, `ARROW_OPACITY`, and WM `MINIMAP_RADAR`, `ARROW`.
Per-category radar settings (what the user means by "Display Icons: Always / Player List", icon scale, dot size, render over minimap) live in `EntityRadarCategorySettings.ICONS` (`ENTITY_ICONS_OPTIONS`: No Icons / Player List / Always), `ICON_SCALE`, `DOT_SIZE`, `RENDER_OVER_MINIMAP`, stored inside the `RADAR_CATEGORIES` option (`EntityRadarCategoryData` tree); the category tree API is the harder part (unverified write path).
Read `manager.getEffective(option)`; write `manager.getCurrentProfile().set(option, value)` (`ConfigProfile extends Config`, `Config.set` marks the option dirty and fires the change listener; Xaero's own save loop persists it). `getEffective` is read per frame by the renderer so the change is immediate, **except** icon sprites: `RadarIconManager` caches per variant, so after changing icon-affecting options call what `XaeroSync.reloadXaero` already calls (`resetEntityIconsResources/resetEntityIcons`).
Manager acquisition: `WorldMap.INSTANCE.getConfigs().getClientConfigManager()` (WM) is documented; for the minimap the equivalent getter on `HudMod.INSTANCE` is unverified. Safest for the simple booleans/ranges (S-M); category icon mode and scales (L) -> offer a button that opens Xaero's own screen instead (`xaero.common.gui.GuiEntityRadarSettings`).

### 3. Effort

| piece | effort |
|---|---|
| Context detection + tab bar + our per-mod section | S |
| FTB own options (7 rows) | S |
| JourneyMap own options (reflection, preset-aware, ~9 rows) | M |
| Xaero own options (profile config, ~8 simple rows) | M; category icon settings L (link to Xaero's screen instead) |

---------------------------------------------------------------------------------------------------

## Part 3 - hiding config sections of absent map mods

**Verdict: yes, workable.** Build the spec conditionally; `ModConfigSpec` is built once per launch.

- `ClientConfig` builds `BUILDER` in its static initializer; that runs when `MobMapIcons`'s constructor first touches `ClientConfig.SPEC` (`container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC)`), i.e. inside mod construction. `ModList.get()` is set before mod constructors run in FML 4 (NeoForge 21.1), and the common pattern `if (ModList.get().isLoaded("x"))` in mod constructors is used widely;
  not run here. If a null/early guard is wanted, `net.neoforged.fml.loading.LoadingModList.get().getModFileById(id) != null` works even earlier and in datagen/unit contexts (that class is in the loader, safe to reference from `ClientConfig`).
- Define sections with `BUILDER.push("ftbChunks")...pop()` only when the mod is present. The existing flat keys `ftbChunks`, `xaero`, `journeymap` (`FTB_CHUNKS_ENABLED` ...) collide with section names in the TOML path and in translation defaults, so the sections need other path names (e.g. `ftb`, `xaeroSection`) or move the enable toggles inside with a migration (moving a value changes its TOML path -> users' old value silently resets; keep the three flat toggles hidden-free if migration is unwanted).
- **`ConfigurationScreen` and the spec:** it walks the spec's values/levels, nothing static. Section button title/tooltip keys (`ConfigurationScreen#getTranslationKey`, ~l.554): `valueSpec`/level translation set by `BUILDER.translation("...")` on the `push`, else the default `<modid>.configuration.<lastPathSegment>` (+ `.tooltip`, `.button` for the section button). Give each section an explicit `.translation("mob_map_icons.configuration.section.ftb")` + `.comment(...)` before `push`, and lang keys `...section.ftb`, `...section.ftb.tooltip`. The top-level screen title keys already exist (`mob_map_icons.configuration.section.mob_map_icons.client.toml`, `...title`); the lang file also has a duplicate `...mob.map.icons.client.toml` entry that is dead.
  Nothing in `ConfigurationScreen` assumes a fixed number of sections; a spec that differs between launches is fine because it is rebuilt from scratch each launch. The in-memory spec must keep the same shape for the whole session (do not add sections after `build()`), and a TOML written with a section for a mod that is later removed just keeps the stray table (NightConfig drops/ignores unknown keys with a warning, `ModConfigSpec` corrects it on load).
- `IConfigScreenFactory` registration in `MobMapIcons.java` (`new ConfigurationScreen(container, parent)`) is unaffected. `MobIconScene` (l.204) and `QuickOptions` (l.486) open the same screen from the spec, also unaffected.
- **Pitfall (the real one):** static `ModConfigSpec.*Value` fields for a section that was not built must still exist as non-spec values or be null. Code like `QuickOptions` (`ClientConfig.XAERO_SCALE`, ...) and getters (`xaeroScale()`, which only catches `IllegalStateException`) would NPE on a null field. Options: (1) keep *every* value defined, but only `push` sections for loaded mods and put only mod-specific values inside; values for absent mods are then never defined, so their fields must be null-checked (wrap in the getter, `FIELD == null ? default : ...`), and `QuickOptions` rows added only for loaded mods; or (2) define them in a throw-away second builder (`new ModConfigSpec.Builder().define(...)`) for absent mods so the field is never null but never in the real spec (value `.get()` on an unregistered spec throws `IllegalStateException` -> already caught by the existing getters, returns default).
  Option (2) is the least invasive.
- **Dev run without map mods:** `runClient` has none of the map mods, so every section would be hidden there: screenshots/selftests (`MobIconSelfTest`, `MobIconScene`) that read `ClientConfig.XAERO_SCALE` etc. keep working with the null/default getters. Verify with a run that has no map mod (config screen opens, no missing-translation warning logged: `ConfigurationScreen` logs untranslated keys once, `translationChecker`).

---------------------------------------------------------------------------------------------------

## Open points (not verified here)

- JM: default `colorSelf`; whether `MapState` refreshes within one tick after a property write; `TextureCache` hook point for the dynamic arrow texture (private map `dynamicTextureMap`).
- Xaero: `ARROW_OPACITY` min value; acquisition path of the minimap `ClientConfigManager`; write path for category settings.
- FTB: `FaceIcon.draw` layout of `head`/`hat` (offsets/size) when `head` is our texture; confirm the tooltip/hover does not rely on `skin`.
- Run once on rig (no GUI work was done here).
