# Xaero radar entity icons: recon (NeoForge 1.21.1)

Jars (G:\Games\Minecraft\Curse\Instances\TNP Limitless 8\mods\):
xaerominimap 26.6.0, xaeroworldmap 1.47.0, XaeroPlus 2.37.2 (WM1.47.0-MM26.6.0).
Method: `jar xf` + `javap -p -c -v`. No decompiler, no builds. All bytecode below is **mojmap-named** (NeoForge 1.21.1 runtime names, no intermediary/SRG; neoforge mixin configs have no refmap).

## TL;DR
- **No Java API/event for custom radar icons. But there IS a data-driven way: JSON definitions + PNG sprites under namespace `xaerominimap`, shippable from ANY mod jar or resource pack. No Mixin needed for the "replace icon per entity type" goal.**
- World Map draws the *same* `RadarRenderer` instance (wrapped), so one JSON change covers both. World Map has its own radar toggle (see 4).
- Mixin only needed for per-entity-instance logic (e.g. icon depends on NBT/name). Target below.

## 1. Classes

Mod `xaerominimap` (classes live under `xaero.common.*` legacy + `xaero.hud.*` new):

| Role | Class |
|---|---|
| Per-frame radar draw, dot + icon | `xaero.hud.minimap.radar.render.element.RadarRenderer` (abstract, extends `xaero.common.minimap.element.render.MinimapElementRenderer<Entity, RadarRenderContext>`); concrete `xaero.common.minimap.render.radar.element.RadarRenderer` (final) |
| Icon lookup + cache + creation | `xaero.hud.minimap.radar.icon.RadarIconManager` |
| JSON defs, per entity type | `...radar.icon.definition.RadarIconDefinition`, `RadarIconDefinitionManager`, `RadarIconDefinitionReloader` |
| Built-in variant/model helpers | `...radar.icon.definition.BuiltInRadarIconDefinitions` |
| Form types (`model`,`normal_sprite`,`outlined_sprite`,`sprite`,`item`,`dot`) | `...definition.form.type.RadarIconFormTypes` / `RadarIconFormType` / `IRadarIconFormReader` |
| Forms | `...definition.form.RadarIconForm`, `RadarIconBasicForms`, `form.sprite.RadarIconSpriteForm`, `form.model.RadarIconModelForm`, `form.item.RadarIconItemForm` |
| Model prerender | `...radar.icon.creator.RadarIconCreator`, `creator.render.form.*Prerenderer` |
| Atlas/texture | `xaero.common.icon.XaeroIcon`, `XaeroIconAtlas`, `XaeroIconAtlasManager` |
| Cache | `...radar.icon.cache.RadarIconCache`, `RadarIconEntityCache`, `id.RadarIconKey`, `id.variant.RadarIconVariantHandler` |
| Category rules (which entities show) | `xaero.hud.minimap.radar.category.EntityRadarCategory*`, `...setting.EntityRadarCategorySettings` |
| Legacy shim | `xaero.common.minimap.render.radar.EntityIconDefinitions` (old name; JSON `variantMethod` now points to `BuiltInRadarIconDefinitions.getVariant`) |

Selection flow (`RadarIconManager`, bytecode order):
```java
public <T extends Entity> XaeroIcon get(T, float, boolean, boolean, GuiGraphics, RenderTarget)
```
1. `def = definitionManager.get(EntityType.getKey(e.getType()))` (null OK)
2. variant id = `variantHandler.getEntityVariant(def, e, renderer)` (default method `BuiltInRadarIconDefinitions.getVariant(ResourceLocation, EntityRenderer, Entity)` -> main texture RL + vanilla variant bits, e.g. `minecraft:textures/entity/pig/pig.png%false`)
3. cache key `new RadarIconKey(variantId, armorOrNull)` (armor only for non-player LivingEntity) -> `RadarIconEntityCache.get(key)`; hit -> return
4. miss: `form = def.getVariantForm(variantString)`; fallback `getVariantForm("default")`; if still null: `LivingEntity ? RadarIconBasicForms.DEFAULT_MODEL : RadarIconBasicForms.SELF_ITEM`
5. `form.getType()==DOT` (or `!canPrerender`) -> `RadarIconManager.DOT`; else `iconCreator.create(...)` -> `XaeroIcon`, cached. On error `RadarIconManager.FAILED`.

Mode choice (head/model vs dot vs flat sprite), in abstract `RadarRenderer.renderElement(Entity,...)`:
- global gate field `iconsAllowed` (set in `preRender` from `HudMod.getSettings()` / primary config; `RADAR_ICONS` style option); `dotsStyle` from `MinimapProfiledConfigOptions.RADAR_DOTS_STYLE` (RangeConfigOption), `RADAR_SMOOTH_DOTS`.
- if `iconsAllowed` -> `RadarIconManager.get(...)`; result `==RadarIconManager.DOT` or `FAILED` -> private `renderDot(Entity, Player, boolean, float, double, float, boolean, PoseStack)` (+ `displayNameWhenIconFails` label); else private `renderIcon(XaeroIcon, double, double, float, boolean, PoseStack)`.
- Per-category toggles: `EntityRadarCategorySettings.*` (ICON_NAME_FALLBACK, COLOR, NAMES, ...).
- Player heads are NOT in this path: `xaero.hud.minimap.player.tracker.PlayerTrackerIconRenderer` (tracked players only).
- "Head sprite" is not a mode here; icon forms are: model (prerendered entity model part, black outline), sprite, item, dot.

### Data-driven API (no Mixin)
Definition file path (built in `RadarIconDefinitionReloader.reloadResourcesAttempt`, namespace hard-coded `"xaerominimap"`):
```
assets/xaerominimap/entity/icon/definition/<entityNamespace>/<entityPath>.json
```
loaded for every key in `BuiltInRegistries.ENTITY_TYPE.keySet()` via `Minecraft.getResourceManager().getResource(...)`; Gson with `excludeFieldsWithoutExposeAnnotation` into `RadarIconDefinition`. Reloaded by `RadarIconDefinitionManager.reloadResources()` on resource reload (F3+T) -> `RadarIconManager.resetResources()`.

Sprite files (`RadarIconSpriteForm.read`): `xaerominimap:entity/icon/sprite/<name>` (i.e. `assets/xaerominimap/entity/icon/sprite/<name>.png`, name as written in JSON incl. `.png`). Recommended 64x64, lots of transparent margin (per Xaero's own comments).

JSON (verbatim keys, from `example_entity_uncommented.json`):
```json
{
  "variants": {
    "minecraft:textures/entity/pig/pig.png%false": "normal_sprite:my_pig.png",
    "default": "outlined_sprite:my_pig.png"
  },
  "modelConfigs": [],
  "variantMethod": "xaero.hud.minimap.radar.icon.definition.BuiltInRadarIconDefinitions.getVariant"
}
```
Value strings (`RadarIconFormTypes.readType`): `model`, `model:<n>`, `normal_sprite:<file>`, `outlined_sprite:<file>` (auto black outline), `sprite:<file>` (legacy, `OLD_SPRITE`), `item`, `item:<ns:id>`, `dot`. Key `default` = fallback. `variantMethod` = optional static `(ResourceLocation, EntityRenderer, Entity) -> Object` (must have good toString/equals/hashCode); ref by `pkg.Class.method` string, resolved reflectively (`RadarIconDefinition.convertStringToMethod`) -> a mod CAN supply its own class here (needs Java, no Mixin).
Debug: minimap config `debugEntityVariantIds=true` prints variant ids to chat/log; `debugEntityIcons` exists too.

Shipped defs: `entity/icon/definition/minecraft/{axolotl,cat,enderman,horse,iron_golem,llama,pig,strider,trader_llama,tropical_fish,villager,wolf,zombie_villager}.json` + `example_mod/example_entity*.json`. Sprite dir `entity/icon/sprite/` ships PNGs.

Caveats of the data route:
- Per entity TYPE + variant-string only; not per-instance (no NBT/name/size).
- Overriding a definition Xaero ships (e.g. `minecraft/pig.json`): two packs provide the same path; the higher-priority pack wins and replaces the WHOLE file. Resource pack > mod jar. Safe route = a built-in/high-priority resource pack from the client mod (NeoForge `AddPackFindersEvent`, `PackType.CLIENT_RESOURCES`, `Pack.Position.TOP`, `required=true`) rather than plain `assets/` in the mod jar (mod-jar assets can lose to Xaero's own jar depending on mod order). Not yet verified in-game.
- Entity types with no shipped JSON (most vanilla mobs) work with plain mod-jar assets: nothing to override.
- Sprites fully replace the icon: no model, no armor/variant overlays unless you make a variant key.
- Cache `RadarIconEntityCache` keyed by variant string; only cleared on resource reload.
- Format version-brittle: Xaero rewrote this in 2025 (added `hud` packages, `RadarIconForm*`); older (Minimap <= ~24.x) used `entity/icon/definition` too but different value syntax and a different class tree.

## 2. Mixin route (only if per-instance logic needed)
No mixin API/hook exists. Candidates (all mojmap, no refmap needed):

1. Best: `xaero.hud.minimap.radar.icon.RadarIconManager`
   `public <T extends net.minecraft.world.entity.Entity> xaero.common.icon.XaeroIcon get(T, float, boolean, boolean, net.minecraft.client.gui.GuiGraphics, com.mojang.blaze3d.pipeline.RenderTarget)`
   descriptor: `(Lnet/minecraft/world/entity/Entity;FZZLnet/minecraft/client/gui/GuiGraphics;Lcom/mojang/blaze3d/pipeline/RenderTarget;)Lxaero/common/icon/XaeroIcon;`
   `@Inject(at=HEAD, cancellable=true)` -> return your own `XaeroIcon` (needs building an atlas entry: `XaeroIconAtlasManager`/`XaeroIconAtlas$Builder`; hard) or `RETURN` + `@ModifyReturnValue` to swap to `RadarIconManager.DOT`/`FAILED` (easy but only dot/no icon).
2. Practical: add a custom form type instead of Mixin: `RadarIconFormTypes` has private static `Map<String,RadarIconFormType> ALL` (`readType(String)`); `new RadarIconFormType(String id, IRadarIconFormReader, IRadarIconFormPrerenderer, XaeroIcon failureResult).addTo(Map)` exists as a public registration helper (`addTo(Map)`), but `ALL` is private -> reflect/accessor mixin on `RadarIconFormTypes`. Not required for sprite replace.
3. Draw override after Xaero: `xaero.hud.minimap.radar.render.element.RadarRenderer.renderElement(Entity,boolean,boolean,double,float,double,double,MinimapElementRenderInfo,GuiGraphics,MultiBufferSource$BufferSource)` returns boolean (descriptor `(Lnet/minecraft/world/entity/Entity;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)Z`). XaeroPlus injects here.
   Private helpers (usable by `@Inject`/`@Shadow` in same class): `renderIcon(XaeroIcon,double,double,float,boolean,PoseStack)V`, `renderDot(Entity,Player,boolean,float,double,float,boolean,PoseStack)F`, `setupRenderForEntity(Entity)V`.

Version-brittle bits: package names `xaero.hud.*` vs `xaero.common.*` (both present, 26.x-era); the abstract/concrete `RadarRenderer` pair (use the abstract in `xaero.hud...`); `RadarIconManager.get` signature has changed across 24.x->25.x->26.x; private method names (`renderIcon`/`renderDot`/`setupRenderForEntity`) can be renamed any release; XaeroPlus pins `[26.6.0]`/`[1.47.0]` exact, which shows how often these move. Mixin plugin class `xaero.lib.mixin.plugin.MixinPlugin` is Xaero's own; Xaero jars' own mixin lists are mostly empty (minimap uses `coremods.json` + `META-INF/xaerohud_core.js` ASM transformers).

## 3. How other add-ons hook in
- **No public API/event for radar icons.** No `xaero.common.api.*` package exists in minimap 26.6.0 (only `xaero.common.minimap.MinimapInterface`, `xaero.common.IXaeroMinimap`, `xaero.common.HudMod`).
- **XaeroPlus** (`xaeroplus.mixin.client.MixinRadarRenderer`): `@Mixin(xaero.hud.minimap.radar.render.element.RadarRenderer.class)`; `@Inject(method="setupRenderForEntity", at=RETURN)` and `@Inject(method="renderElement(Lnet/minecraft/world/entity/Entity;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;)Z", at=HEAD)`. Also `MixinGuiEntityRadarSettings` (UI). It extends `MinimapElementRenderer<Entity,RadarRenderContext>` as the mixin shell to access protected fields (`context`, `name`). Uses own `xaeroplus.event.*` bus (e.g. `MinimapInitCompletedEvent`) for its own modules, not a Xaero API.
- **ftbxaerocompat 1.4.0**: Mixin only (`GuiMapMixin, GuiMinimapOverlaysSettingsMixin, HudModConfig*RegistryHandlerMixin, MapChunkMixin, MapWorldMixin, SupportXaeroMinimapMixin, WorldMapSessionMixin`) + `xaero.map.highlight`/`MinimapClaimsHighlighter` highlighter registration. Nothing radar-icon related; shows the usual "mixin + hardcoded access" pattern. Package `dev.satherov.ftbxaerocompat`, JAVA_21.
- **minimapshapes_xaero 0.1.0**: Mixin only (`XaeroRendererMixin, XaeroHelperMixin, XaeroOverMapMixin, XaeroFboMixin, XaeroSafeModeMixin`), pure rendering of minimap frame. Has a refmap `minimapshapes_xaero.refmap.json` (so built against a remapped env). No icon logic.
- Only first-class extension points found: (a) the JSON/PNG resource route above, (b) `variantMethod` reflective static method, (c) Xaero `ElementRenderer` registration used inside Xaero itself (`xaero.map.element.MapElementRenderHandler.add(ElementRenderer)`, World Map side), (d) `PlayerTrackerSystemManager`/`IPlayerTrackerSystem` for player tracking (not mobs).

## 4. World Map
- Radar entities in World Map: `xaero.map.mods.minimap.element.RadarRendererWrapperHelper.createWrapper(xaero.common.IXaeroMinimap, xaero.common.minimap.render.radar.element.RadarRenderer)` wraps the **minimap's** `RadarRenderer` in `xaero.map.mods.minimap.element.MinimapElementRendererWrapper` and adds it via `WorldMap.mapElementRenderHandler.add(...)`. So World Map icons = exactly the minimap's `RadarIconManager` output (same defs, same cache). No separate World Map icon system.
- Gate (shouldRender supplier in that wrapper): `WorldMap.INSTANCE.getConfigs().getClientConfigManager().getEffective(xaero.map.common.config.option.WorldMapProfiledConfigOptions.MINIMAP_RADAR)` (a WORLD MAP option, "Minimap Radar"), AND minimap-side `RadarRenderer.shouldRender(MinimapElementRenderLocation)`:
  - `!minimap.usingFBO()` -> false
  - location `WORLD_MAP` / `WORLD_MAP_MENU` -> **true without checking `MinimapProfiledConfigOptions.DISPLAY_RADAR`**
  - otherwise (minimap itself) -> `DISPLAY_RADAR`.
- But `xaero.hud.minimap.radar.state.RadarStateUpdater` (radar list build) reads `MinimapProfiledConfigOptions.DISPLAY_RADAR` once (offset 85). **Unverified**: whether the list is still produced with minimap radar off. Treat "needs minimap radar on" as likely-true; confirm in-game on rig (toggle `DISPLAY_RADAR` off, see if WM still shows mobs).
- World Map radar draws via `renderEntityDotToFBO`/`renderSingleEntity(Entity,boolean,boolean,float,boolean,boolean,MinimapElementRenderLocation,RenderTarget,GuiGraphics)` paths that also call `RadarIconManager.get`.

## Recommendation
Resource route first: ship `assets/xaerominimap/entity/icon/definition/<ns>/<entity>.json` + `assets/xaerominimap/entity/icon/sprite/*.png` from the client mod, in a high-priority built-in pack (AddPackFindersEvent TOP) so it also replaces Xaero's shipped JSONs. Generate defs for all `BuiltInRegistries.ENTITY_TYPE` keys at pack-build time (no runtime hook needed) or via a runtime `PackResources` returning generated JSON/PNG. Fallback: Mixin on `RadarIconManager.get` (descriptor above) only if icons must vary per entity instance. Pin to minimap 26.6.0 / WM 1.47.0 and add a startup self-check (log if `RadarIconDefinitionManager.get(<test key>)` returns null/class missing).

## Two sizes (minimap vs World Map): sprite swap

Both maps draw from one `RadarIconManager`, so one set of sprites. Per-view sizes need two sets, swapped by view:
- Sprites are fixed 64x64 quads; `renderIcon` scales by `max(1, ctx.iconScale * scale param)` where `iconScale` is Xaero's per-category `ICON_SCALE`. The scale cannot go below 1, so a smaller icon has to be smaller in the PNG itself (what `xaeroScale` already does), not through Xaero's scale.
- Design: `XaeroSync` keeps a snapshot per view and serves the one of the active view (`xaero.map.gui.GuiMap` or a subclass open = World Map, else minimap) through the top-priority in-memory pack. On a change of view, in the client tick, it republishes (only if the two snapshots differ), releases changed sprite textures and calls `MinimapFBORenderer.resetEntityIconsResources` / `resetEntityIcons`. Nothing per frame; the screen check is one class-name walk per screen instance.
- Effective sprite scale = `xaeroScale * size(view) / 16`, clamped 0.05 to 60/56 (56 px is 1.0, the box is 64).
- Verified on rig (Xaero Minimap 26.6.0, World Map 1.47.0): minimap at 16 and 24 differ, World Map at 8, 16 and 24 differ, swapping back and forth works. The quick panel's Xaero tab (size, Mini/Full, scale, style, enabled, same-as-global, More) stays stable over the World Map: no exception, every row clickable.
- Still true: one icon per mob type; the set of a view whose screen is not open is prepared but not shown.

