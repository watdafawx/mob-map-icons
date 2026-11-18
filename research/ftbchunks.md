# FTB Chunks — entity map icons (recon from jar)

Analysed: `ftb-chunks-neoforge-2101.1.24.jar` (1.21.1, NeoForge >=21.1.0), `ftb-library-neoforge-2101.1.40.jar`, `architectury-13.0.12-neoforge.jar`. Decompiled with CFR 0.152.

## 1. Draw path + public icon API

Both surfaces pump icons through `dev.ftb.mods.ftbchunks.api.client.event.MapIconEvent`:

```java
public static final Event<Consumer<MapIconEvent>> MINIMAP;    // EventFactory.createLoop
public static final Event<Consumer<MapIconEvent>> LARGE_MAP;  // EventFactory.createConsumerLoop
public ResourceKey<Level> getDimension();
public void add(MapIcon mapIcon);
public MapType getMapType();
```

Register on client init: `MapIconEvent.MINIMAP.register(consumer)` / `MapIconEvent.LARGE_MAP.register(consumer)`.
`MapType` = `LARGE_MAP`, `MINIMAP`, `WORLD_ICON` (+ `isLargeMap()/isMinimap()/isWorldIcon()`). The event is append-only — no list getter, no remove/replace.

`dev.ftb.mods.ftbchunks.api.client.icon.MapIcon` (3 abstract members):

```java
Vec3 getPos(float partialTick);                                             // abstract
boolean onMousePressed(BaseScreen screen, MouseButton button);              // abstract
boolean onKeyPressed(BaseScreen screen, Key key);                           // abstract
void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h, boolean outsideVisibleArea, int iconAlpha); // abstract
default boolean isVisible(MapType mapType, double distanceToPlayer, boolean outsideVisibleArea) { return !mapType.isWorldIcon(); }
default double getIconScale(MapType mapType) { return 1.0; }
default boolean isIconOnEdge(MapType mapType, boolean outsideVisibleArea) { return false; }
default boolean isZoomDependant(MapType mapType) { return false; }
default int getPriority() { return 0; }   // EntityMapIcon: local player 150, other players 100, mobs 0
default void addTooltip(TooltipList list) { /* "<n> m" distance */ }
```

Base class: `MapIcon.SimpleMapIcon` — `(Vec3 pos)`, `(Vec3 pos, Icon icon)`, `getIcon()/setIcon(Icon)`; its `draw` is `icon.draw(graphics, x, y, w, h)`.

**Gotcha — `x,y,w,h` differ per surface:**
- LARGE_MAP = pixel rect: `MapIconWidget.draw` → `mapIcon.draw(MapType.LARGE_MAP, g, x, y, w, h, false, 255)`; size from `RegionMapPanel.alignWidgets` = `max(isZoomDependant?0:6, tileSize/128 * getIconScale)`.
- MINIMAP = 1x1 unit square: `FTBChunksClient#renderMinimap` does `pose.translate(...); pose.scale(ws*2, ws*2, 1)` then `icon.draw(MapType.MINIMAP, graphics, 0, 0, 1, 1, d >= halfSizeD, 255)`, with `ws = size / (32.0 / getIconScale(MINIMAP))`.
- Sorted by `client.mapicon.MapIconComparator` (`getPriority` first). `WORLD_ICON` pass = in-world labels; entities never draw there.

Fire sites / producers:
- `dev.ftb.mods.ftbchunks.client.FTBChunksClient#mapIcons(MapIconEvent)` — private, registered on both buses; adds waypoints, entity icons, player heads, pointer.
- Minimap: rebuild only every `MINIMAP_ICON_UPDATE_TIMER` ms (default 500); force with `FTBChunksAPI.clientApi().requestMinimapIconRefresh()`.
- Large map: `client.gui.RegionMapPanel#addWidgets` clears + fires `MapIconEvent.LARGE_MAP`, then one `MapIconWidget` per visible icon.

How a head is drawn today — `client.mapicon.EntityMapIcon` (public; `(Entity, Icon)` / `(Entity, Icon, EntityIconLoader.WidthHeight)`):

```java
PoseStack poseStack = graphics.pose();
poseStack.pushPose();
poseStack.translate((float)x, (float)y, 0.0f);
poseStack.scale((float)w / 18.0f, (float)h / 18.0f, 1.0f);
if (this.icon instanceof EntityImageIcon) {
    Color4I.BLACK.withAlpha(160).draw(graphics, 0, 0, this.widthHeight.width() + 2, this.widthHeight.height() + 2);
}
this.icon.draw(graphics, 1, 1, this.widthHeight.width(), this.widthHeight.height());
poseStack.popPose();
```

The `Icon` is a `dev.ftb.mods.ftblibrary.icon.EntityImageIcon` (extends `Icon`), built from a PNG/JSON pair and drawn via
`Icon.draw(GuiGraphics, int x, int y, int w, int h)`; `Icon.getIcon(String|ResourceLocation)` builds any texture icon. Player heads use `FaceIcon.getFace(GameProfile)`.

## 2. "Hardcoded" mob list + red-dot fallback

There is **no list in FTB Chunks** — heads come from **FTB Library**, `dev.ftb.mods.ftblibrary.icon.EntityIconLoader` (a `SimplePreparableReloadListener`). On resource reload it probes every entity type under the namespace `ftblibrary` (always: `ResourceLocation.fromNamespaceAndPath("ftblibrary", path)`):

- `assets/ftblibrary/textures/faces/<entity_ns>/<entity_path>.json` (wins over png) / `.png` → settings
- `...invisible` → legacy hidden settings (logs an error)
- nothing found and `getCategory() != MobCategory.MISC` → `EntityIconSettings.legacy()` = `OLD_HIDDEN` (no texture, `useMobTexture=false`, `defaultEnabled=true`)

```java
// ftb-library
public static Icon NORMAL  = Icon.getIcon("ftblibrary:textures/faces/normal.png");   // 8x8 white dot
public static Icon HOSTILE = Icon.getIcon("ftblibrary:textures/faces/hostile.png");  // 8x8 red dot
public static Icon getIcon(Entity entity) {
    return EntityIconLoader.getIconCache(entity).orElseGet(() -> entity instanceof Enemy ? HOSTILE : NORMAL);
}
public static Optional<EntityIconSettings> getSettings(EntityType<?> entityType);
public static boolean isDynamicTexture(EntityType<?> type);
```

The red dot for unknown mobs = `getIconCache` empty for `legacy()` settings → `HOSTILE`/`NORMAL` (`net.minecraft.world.entity.monster.Enemy` decides). JSON schema (`EntityIconSettings`, codec-validated; texture required unless `use_mob_texture`):

`{"use_mob_texture":false,"texture":id?,"slice":{x,y,width,height}?,"children":[{texture?,slice,offset?}],"size":{width,height}=16x16,"default_image_size":{w,h}?,"scale":1.0,"default_enabled":true}`

`use_mob_texture:true` slices the head out of the entity's real renderer texture (`EntityRenderDispatcher.getRenderer(e).getTextureLocation(e)`).

Gatekeeper: `EntityIconUtils.shouldEntityRender(Entity, Player)` = `canTypeRender(type) && !entity.isInvisibleTo(player) && isIconEnabled(key, type)`;
`canTypeRender` = `EntityType.PLAYER || EntityIconLoader.getSettings(type).isPresent()` (so `MobCategory.MISC` types with no asset get **no** icon at all).
`EntityMapIcon.isVisible` re-checks `ENTITY_ICON` plus `!outsideVisibleArea` for non-players.

Config — `dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig`, file `config/ftbchunks-client.snbt`:
- `minimap.entities` (`MINIMAP_ENTITIES`, true) — master switch, gates entity icons on minimap **and** large map
- `minimap.entity_heads` (`MINIMAP_ENTITY_HEADS`, true) — false ⇒ all entities use the dot, not heads
- `minimap.entity_icon` (`EntityTypeBoolMapValue ENTITY_ICON`, `{}`) — SNBT `{"minecraft:zombie":false}`, key `ResourceKey<EntityType<?>>`, absent ⇒ `settings.defaultEnabled()`
- `minimap.large_entities` (`MINIMAP_LARGE_ENTITIES`) — minimap scale 1.0 vs 0.667; also `minimap.player_heads`, `advanced.minimap_icon_update_timer`, `advanced.only_surface_entities`
- per-type toggle GUI: `client.gui.EntityIconSettingsScreen`

## 3. Public API artifact / maven

No separate API jar — the API is package `dev.ftb.mods.ftbchunks.api.**` (`api/`, `api/client/{event,icon,minimap,waypoint}`, `api/event`) inside the mod jar.

- Coords: `dev.ftb.mods:ftb-chunks-neoforge:2101.1.24` (Fabric `ftb-chunks-fabric`). Directory listing verified at
  `https://maven.ftb.dev/releases/dev/ftb/mods/ftb-chunks-neoforge/2101.1.24/` → only `.jar`, `-sources.jar`, `.pom`, `.module`; no `-api` classifier (FTB's `apiJar` task is commented out).
- Repo `maven.ftb.dev/releases` (+ `/snapshots`), content-filtered to `dev.ftb.mods`. `maven.feed-the-beast.com` does **not** resolve; `maven.ftb.dev` is what FTB's own build.gradle uses.
- Manifest: `Specification-Title: ftbchunks`, `Implementation-Title: neoforge`, `Implementation-Version: 2101.1.24`. `META-INF/accesstransformer.cfg` present; mixin config `ftbchunks-common.mixins.json`.
- mods.toml requires: architectury >=13.0.11, ftblibrary >=2101.1.39, ftbteams >=2101.1.9, neoforge >=21.1.0; ftbbackups3 optional.
- Use `compileOnly` (+ `localRuntime` for dev). Pin to the `2101.1.x` line — `2111.1.x` / `26.1.2.x` are the MC 26.1 build line.

## 4. Replacing built-in icons

**Best path, no mixin:** write `false` into `ENTITY_ICON` for the types you own (FTB then stops emitting its `EntityMapIcon`), and add your own icon on both buses:

```java
FTBChunksClientConfig.ENTITY_ICON.get().put(ResourceKey.create(Registries.ENTITY_TYPE, id), false); // public static field
MapIconEvent.MINIMAP.register(ev -> ev.add(new MyIcon(...)));
MapIconEvent.LARGE_MAP.register(ev -> ev.add(new MyIcon(...)));
```

Caveats: config is persisted per client, so restore keys on logout; minimap list rebuilds on the 500 ms timer, large map on `addWidgets`.

**Resource override (no mixin, order-dependent):** ship `assets/ftblibrary/textures/faces/minecraft/zombie.json` + PNG. FTB Library's pack carries the same path, so the winner depends on pack order — safe for adding icons to mobs that have none, not a guaranteed replacement.

**Mixin targets for a hard replace:**

| target | signature | hook |
|---|---|---|
| `dev.ftb.mods.ftblibrary.icon.EntityIconLoader` | `public static Icon getIcon(net.minecraft.world.entity.Entity)` desc `(Lnet/minecraft/world/entity/Entity;)Ldev/ftb/mods/ftblibrary/icon/Icon;` | `@Inject(at=@At("RETURN"), cancellable=true)` → `setReturnValue`. One hook, changes every FTB head icon (map + Quests). |
| same | `public static java.util.Optional<EntityIconSettings> getSettings(net.minecraft.world.entity.EntityType<?>)` desc `(Lnet/minecraft/world/entity/EntityType;)Ljava/util/Optional;` | RETURN/cancellable → `EntityIconSettings.forImage(yourRl)`; texture may be any namespace (`getIconCache` uses it raw). `new EntityIconSettings(false, Optional.of(id), Optional.empty(), List.of(), WidthHeight.DEFAULT, Optional.empty(), 1.0, true)` also sets scale/size. |
| same | `protected void apply(java.util.Map<EntityType<?>, EntityIconSettings>, ResourceManager, ProfilerFiller)` desc `(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V` | `@Inject(at=@At("HEAD"))`, mutate the map argument — it is `putAll`-ed into `ENTITY_SETTINGS` right after, and repeats on every reload. |
| `dev.ftb.mods.ftbchunks.client.mapicon.EntityMapIcon` | `public void draw(MapType, GuiGraphics, int, int, int, int, boolean, int)` desc `(Ldev/ftb/mods/ftbchunks/api/client/icon/MapType;Lnet/minecraft/client/gui/GuiGraphics;IIIIZI)V` | `@Shadow @Final @Mutable private final Icon icon;` then HEAD+cancel to draw yourself. |
| `dev.ftb.mods.ftbchunks.client.FTBChunksClient` | `private void mapIcons(dev.ftb.mods.ftbchunks.api.client.event.MapIconEvent)` desc `(Ldev/ftb/mods/ftbchunks/api/client/event/MapIconEvent;)V` | HEAD/cancellable to own list building, or TAIL + `@Shadow private final java.util.List<MapIcon> mapIcons` to filter out FTB's icons (the event can't remove). |

Rows 1–3 live in FTB Library and are shared with FTB Quests/Teams — a wrong `getSettings` return breaks quest icons too. Prefer the §4 config-map route unless you need FTB's zoom/edge/priority behaviour exactly.
