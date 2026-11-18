# Changelog

## 0.3.0

- FTB Chunks: icons turn to face the mob's heading (8 directions) on the large map and on a rotating
  minimap, using `MobIconApi.getIcon(entity, yawBucket)`. Off when `rotateWithHeading` is false.
- FTB Chunks: farm mode. Mobs of the same type and icon that sit within `clusterRadius` screen pixels
  of each other merge into one icon with an `x37` badge (`99+` cap) and a `37 x Cow` tooltip, once a
  group reaches `clusterMinCount`. Named, leashed, tamed and boss mobs and the local player never
  merge. Zooming in splits clusters; FTB's icon list is rebuilt when the zoom or the config changes.
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
