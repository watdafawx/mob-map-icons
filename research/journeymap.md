# JourneyMap 6.0.9 (NeoForge 1.21.1) — mob icon override recon
Jar `journeymap-neoforge-1.21.1-6.0.9.jar`, modId `journeymap`, built Java 21, API **v2**
(`journeymap.api.v2.*`). API is nested: `META-INF/jarjar/journeymap-api-neoforge-2.0.0-1.21.1.jar`
(mod id `journeymap_api`, `IClientAPI.API_VERSION = "2.0.0"`, `API_OWNER = "journeymap"`).
JM also hard-depends on `commonnetworking >= 1.0.16` (jarjar'd).

## 1. API entry points / registration
Maven (docs `TeamJM/journeymap-api`, branch `1.21.1_2.0.0`): repo `https://maven.blamejared.com`
(fallback `https://jm.gserv.me/repository/maven-public/`); `compileOnly group: 'info.journeymap',
name: 'journeymap-api-neoforge', version: '2.0.0-1.21.1'` (siblings `-common`, `-fabric`, `-forge`).
Never shade. Runtime = the mod itself; optional dep in `neoforge.mods.toml`: modId `journeymap`,
`type="optional"`, `side="CLIENT"`, range `[1.21-6.0.0-beta.1,)`.

```java
public interface journeymap.api.v2.client.IClientPlugin extends journeymap.api.v2.common.IJourneyMapPlugin<journeymap.api.v2.client.IClientAPI> {
  void initialize(journeymap.api.v2.client.IClientAPI api);
}
public interface journeymap.api.v2.common.JourneyMapPlugin extends java.lang.annotation.Annotation {
  java.lang.String apiVersion();  java.lang.String[] dependencies();
}   // client-side twin: journeymap.api.v2.client.JourneyMapPlugin { String apiVersion(); }
public interface journeymap.api.v2.common.IJourneyMapPlugin<T extends journeymap.api.v2.common.CommonAPI> {
  java.lang.String getModId();  void initialize(T api);
}
```
Annotate the plugin class `@journeymap.api.v2.common.JourneyMapPlugin(apiVersion = "2.0.0")`; no register
call. Discovery: `journeymap.common.helpers.NeoForgeClientHooks.getClientPluginScanResult()` scans NeoForge
`ModFileScanData` (`isValidAnnotation` accepts both annotation types) filtered by
`implements journeymap/api/v2/client/IClientPlugin`, then
`journeymap.api.common.PluginHelper.preInitPlugins(List<Class<?>>)` / `initPlugins(CommonAPI)`; keep JM
types out of the rest of your mod. Events: static fields on
`journeymap.api.v2.common.event.ClientEventRegistry` (`ENTITY_RADAR_UPDATE_EVENT`,
`ENTITY_REGISTRATION_EVENT`, `DISPLAY_UPDATE_EVENT`, ...) of type
`journeymap.api.v2.common.event.impl.Event<E>`: `void subscribe(String modId, java.util.function.Consumer<E>)`.

## 2. Replacing the radar icon: YES, per entity, no overlays needed
```java
public class journeymap.api.v2.client.event.EntityRadarUpdateEvent extends ClientEvent {
  public UIState getActiveUiState();
  public EntityRadarUpdateEvent.EntityType getType();      // MOB | PLAYER
  public WrappedEntity getWrappedEntity();
}
public interface journeymap.api.v2.client.entity.WrappedEntity {
  java.lang.ref.WeakReference<net.minecraft.world.entity.Entity> getEntityRef();
  net.minecraft.resources.ResourceLocation getEntityIconLocation();
  void setEntityIconLocation(net.minecraft.resources.ResourceLocation iconLocation);
  void setCustomName(net.minecraft.network.chat.Component);  void setTeamColor(int);  void setColor(int);
  void setLabelColor(int);  void setDisable(boolean);  void setDrawOutline(boolean);
  java.util.List<net.minecraft.network.chat.Component> getEntityToolTips();
  void setEntityToolTips(java.util.List<net.minecraft.network.chat.Component>);
  // getEntityId/getHostile/getAggro/getPosition/getChunkPos/getHeading/getBiome/getDimension/isInvisible/...
}
```
Impl `journeymap.client.model.entity.EntityDTO`: `public void setEntityIconLocation(ResourceLocation)`
sets `hasCustomIcon = true`, `entityIconLocation`, `iconLocation` (String, webmap form). Relevant fields:
`public transient ResourceLocation entityTextureLocation;` (renderer skin, legacy crop source),
`entityIconLocation` (drawn icon), `entityTypeLocation` (cache key), `public transient boolean hasCustomIcon;`.
- `EntityDTO.update(LivingEntity, boolean)` resets `hasCustomIcon=false` + `entityIconLocation=null` at entry,
  then calls `EntityHelper.getIconData(Entity)`, which dispatches the event via
  `CustomEventDispatcher.entityRadarMobUpdateEvent(EntityDTO)` -> `EventBus.post(EntityRadarUpdateEvent)`.
  So set the icon on every event; `event.setCancelled(true)` or `setDisable(true)` hides the entity.
- The ResourceLocation is resolved by `journeymap.client.texture.TextureCache.getTexture(ResourceLocation)`
  -> `Minecraft.getResourceManager().getResource(rl)` (path repair only for the `journeymap` namespace),
  so pass `mymod:textures/gui/mob_icons/creeper.png`.
- Display mode must be an icon mode (`EntityDisplay.Icons|OutlinedIcons|DotsAndIcons|DotsAndOutlinedIcons`);
  `Dots` = dot only. Custom icons get no outline variant (`IconTexture(solid, null)`), so both modes use your
  image. Players use `EntityDisplay.getEntityTexture(EntityDisplay, GameProfile)` (real skin) -> override is
  effectively MOB-only; players need a mixin.
- `EntityRegistrationEvent.addPassiveEntity/addHostileEntity/addVillagerEntity/addAmbientEntity(Class<? extends LivingEntity>)`
  registers modded mobs JM would otherwise not track.
Overlay-only alternative: `MarkerOverlay(String modId, BlockPos, MapImage)` (+`setPoint`, `setIcon`, inherited
`setDisplayOrder/setActiveUIs/setActiveMapTypes/setMinZoom/setMaxZoom/setOverlayListener/setDimension`) shown
via `IClientAPI.show(Displayable)`. Cost: one `DrawMarkerStep` (texture lease + `updatePositions` + draw) per
marker per frame, culled by `BaseOverlayDrawStep.isOnScreen`. Fast path only when mutating the **same
instance** (`setPoint`) and calling `show` again: `PluginWrapper.applyOverlay` -> `tryApplyMarkerFastPath` ->
`OverlayRegistry.commitMarkerPosition` (position only); a new MarkerOverlay per tick takes the slow path.
`ImageOverlay(modId, northWest, southEast, MapImage)` re-projects per frame - worse. Ceiling: tens.

## 3. JM's own icon rendering (mixin fallback)
Shared pipeline: `journeymap.client.model.map.MapState.generateDrawSteps(Minecraft, Renderer,
WaypointDrawStepFactory, RadarDrawStepFactory, InGameMapProperties, boolean)` -> `List<DrawStep>`, used by
`journeymap.client.ui.minimap.MiniMap` and `journeymap.client.ui.fullscreen.Fullscreen`.
- `journeymap.client.render.draw.RadarDrawStepFactory.prepareSteps(List<EntityDTO>, Renderer, InGameMapProperties)`
  reads `mobDisplay`/`playerDisplay`/`mobDisplayScale`; per entity: `DataCache.INSTANCE.getDrawEntityStep(Entity)`
  (cached step), `EntityDisplay.getEntityTexture(EntityDisplay, EntityDTO)`,
  `EntityDisplay.getLocatorTexture/getLocatorBGTexture(EntityDisplay, boolean)`,
  `EntityDisplay.hasEntityIcon(...)` (false -> `EntityDisplay.getDot()`), then
  `DrawEntityStep.update(EntityDisplay, DynamicTexture entityTexture, DynamicTexture locatorTexture,
  DynamicTexture locatorBGTexture, int color, int labelColor, boolean showHeading, boolean showOutline,
  boolean hideSneaks, boolean hideSpectators, float drawScale)`.
- `journeymap.client.ui.minimap.EntityDisplay` enum (Dots, Icons, OutlinedIcons, DotsAndIcons, DotsAndOutlinedIcons;
  fields `defaultTexture/defaultBGTexture/showHeadingTexture/showHeadingBGTexture`) has
  `public static DynamicTexture getEntityTexture(EntityDisplay, EntityDTO)` -> `MobIconCache.getMobIcon(dto,
  isOutlined())`, uses `Tuple2.b()`, else `TextureCache.MobDotChevron`.
- `journeymap.client.render.draw.MobIconCache` (real decision point):
  `public static Tuple2<ResourceLocation, DynamicTexture> getMobIcon(EntityDTO dto, boolean outlined)`:
  `ensureMobIconsLoaded()`; key = `dto.entityTypeLocation`; `addIconIfMissing(dto, key)`; `IconTexture t = mobsIcons.get(key)`;
  `if (CoreProperties.legacyIcons || dto.hasCustomIcon || t == null)` and `dto.entityIconLocation != null` ->
  `TextureCache.getTexture(dto.entityIconLocation)` (cached into `mobsIcons` + `webMapIconCache`,
  `clampFallbackIconSize` when no type icon); else the cached vanilla icon. Members:
  `private static HashMap<ResourceLocation, MobIconCache$IconTexture> mobsIcons`,
  `IconTexture { public DynamicTexture solid; public DynamicTexture outlined; }`, `clearCache()`. Vanilla icons:
  `EntityRenderer.getTextureLocation(entity)` + head cropping (`getIconFromHead`), GeckoLib/AzureLib helpers via
  `EntityHelper.getIconData`, per-type PNGs from `FileHandler.getMobIconsDomainsDirectory()`.
- `journeymap.client.render.draw.DrawEntityStep implements DrawStep`: package-private `entityTexture`,
  `locatorTexture`, `locatorBGTexture`, `entityDTO`, `useDots`, `drawScale`, `entityRef`; `draw(GuiGraphics,
  MultiBufferSource, DrawStep.Pass, double, double, Renderer, double, double)`, `drawCreature(...)`,
  `drawOffscreen(...)`; `DrawStep.Pass` = PreObject/Object/PostObject/TextBG/Text/Tooltip. Same event's internal
  consumer: `EntityRadarUpdateEventHandler.onRadarEntityUpdateEvent(EntityRadarUpdateEvent)`.
Mixin targets, cheapest first:
1. `journeymap.client.ui.minimap.EntityDisplay#getEntityTexture(Ljourneymap/client/ui/minimap/EntityDisplay;Ljourneymap/client/model/entity/EntityDTO;)Lnet/minecraft/client/renderer/texture/DynamicTexture;`
   `@Inject(at = @At("HEAD"), cancellable = true)` -> return your own `DynamicTexture` (both UIs).
2. `MobIconCache#getMobIcon(Ljourneymap/client/model/entity/EntityDTO;Z)Ljourneymap/api/v2/common/tuple/Tuple2;`
   `@Inject(at = @At("RETURN"), cancellable = true)` -> return `new Tuple2<>(rl, yourTexture)` (also feeds
   `hasEntityIcon` and the web map).
3. `RadarDrawStepFactory#prepareSteps` `@Inject(at = @At("RETURN"))` -> re-`update(...)` returned `DrawEntityStep`s
   with your texture (fields are package-private: accessor mixin or reflection).
Note `CoreProperties.legacyIcons=true` also routes through `entityIconLocation` (why modded mobs show skin crops).
