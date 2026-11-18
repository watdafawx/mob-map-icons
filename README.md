# Mob Map Icons

Client-side NeoForge 1.21.1 mod that draws real icons for mobs on map mods - the whole body or just
the head, taken from the entity's own model - instead of a plain coloured dot.

Every supported map mod is optional and independent: FTB Chunks, Xaero's Minimap, Xaero's World Map
and JourneyMap. Install any subset; the rest stays dormant and no map mod is a hard dependency.

## Building

```bat
build.bat build
```

`build.bat` points `JAVA_HOME` at a portable JDK in `toolchain\` or an Eclipse Adoptium JDK 21, then
delegates to `gradlew`. Output: `build/libs/mob_map_icons-<version>+neoforge-1.21.1.jar`.

## Layout

| Package | What |
|---|---|
| `dev.watdafawx.mobmapicons` | Main class, `ClientConfig` (ModConfigSpec) |
| `api` | `MobIconApi` (the only read API), `IconMode` |
| `core` | `MobIconRenderer` + `MobIconCache` - icon baking, not written yet |
| `compat.ftbchunks`, `compat.xaero`, `compat.journeymap` | One adapter per map mod |

Adapters are loaded by class name from `MobMapIcons#clientSetup`, only when their mod is present, so a
missing map mod can never cause a `NoClassDefFoundError`. Each adapter owns its own `compileOnly`
dependency in its marked `// === compat:<name> ===` block in `build.gradle`.
