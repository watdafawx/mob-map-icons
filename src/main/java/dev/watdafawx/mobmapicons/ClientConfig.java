package dev.watdafawx.mobmapicons;

import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.IconProfile;
import dev.watdafawx.mobmapicons.api.IconStyle;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MapView;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLIENT config - {@code config/mob_map_icons-client.toml}. Map icons are a client render detail, so
 * nothing here is registered on a server and nothing is synced.
 *
 * <p>The top level holds the global look and behaviour. Each <i>installed</i> map mod (FTB Chunks, Xaero,
 * JourneyMap) additionally gets its own section ({@code [ftb]}, {@code [xaero]}, {@code [journeymap]})
 * whose options default to {@code INHERIT} ("use the global value"), so one map can show OUTLINE icons
 * while another shows BADGE. A section of a map mod that is not installed is not built, so the config
 * screen never shows it; its values then live on a throw-away spec and read as their defaults.
 *
 * <p>Every getter has a fallback for the window before the config is loaded (adapters can be asked
 * for an icon during client setup), so no read throws {@link IllegalStateException}.
 */
public final class ClientConfig {

    /** 16x16 is what every map mod's own mob dots are sized for. */
    public static final int DEFAULT_ICON_SIZE = 16;

    /** A per-map-mod style: one of the global styles, or {@code INHERIT}. Names match {@link IconStyle}. */
    public enum StyleChoice {
        INHERIT, PLAIN, OUTLINE, SILHOUETTE, BADGE, RETRO, TOP_DOWN, CHIBI, PAPER_INK, SHADOW, HEAD_PIN;

        public IconStyle resolve(IconStyle global) {
            return this == INHERIT ? global : IconStyle.valueOf(name());
        }

        public static StyleChoice of(IconStyle style) {
            return valueOf(style.name());
        }
    }

    /** A per-map-mod icon mode: {@code FULL_BODY}, {@code HEAD}, or {@code INHERIT}. */
    public enum ModeChoice {
        INHERIT, FULL_BODY, HEAD;

        public IconMode resolve(IconMode global) {
            return this == INHERIT ? global : IconMode.valueOf(name());
        }
    }

    /** A per-map-mod switch: {@code ON}, {@code OFF}, or {@code INHERIT} (the global value). */
    public enum Tri {
        INHERIT, ON, OFF;

        public boolean resolve(boolean global) {
            return this == INHERIT ? global : this == ON;
        }
    }

    /** What JourneyMap's own marker around a mob icon does; see {@code compat/journeymap/FrameRule}. */
    public enum JourneyMapFrame { AUTO, ON, OFF }

    /**
     * One map mod's options. Fields a map mod has no use for (Xaero draws one static icon per mob type, so no
     * heading, farm mode or players) are {@code null}.
     */
    public static final class Section {
        public final MapMod mod;
        /** {@code false} for a map mod that is not installed: the values exist but are not in the real spec. */
        public final boolean built;
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.EnumValue<StyleChoice> iconStyle;
        public final ModConfigSpec.EnumValue<ModeChoice> iconMode;
        /** Minimap icon size; 0 = use the global minimap size. */
        public final ModConfigSpec.IntValue iconSizeMinimap;
        /** Full-screen map icon size; 0 = use the global one. For Xaero it sizes the World Map's own sprite set. */
        public final ModConfigSpec.IntValue iconSizeFullscreen;
        public final ModConfigSpec.EnumValue<Tri> rotateWithHeading;
        public final ModConfigSpec.EnumValue<Tri> headingArrow;
        public final ModConfigSpec.EnumValue<Tri> farmMode;
        public final ModConfigSpec.EnumValue<Tri> players;
        public final ModConfigSpec.BooleanValue ownPlayer;
        public final ModConfigSpec.DoubleValue xaeroScale;
        public final ModConfigSpec.EnumValue<JourneyMapFrame> journeymapFrame;
        /** JourneyMap only: switch JourneyMap's mob and player display to Icons so it draws no dot round ours. */
        public final ModConfigSpec.BooleanValue autoIconDisplay;

        private Section(MapMod mod, ModConfigSpec.Builder b, boolean built) {
            this.mod = mod;
            this.built = built;
            String id = mod.id();
            boolean heading = mod != MapMod.XAERO;
            b.comment("Options for " + label(mod) + " only. Style, mode, size and the heading, farm and player",
                            "switches default to INHERIT: they follow the top-level value until you set them here.")
                    .translation("mob_map_icons.configuration.section." + id)
                    .push(id);
            enabled = b.comment("Draw mob icons on " + label(mod) + ".")
                    .translation("mob_map_icons.configuration.mod.enabled")
                    .define("enabled", legacyBool(mod));
            iconStyle = b.comment("Icon style on this map mod. INHERIT = the top-level iconStyle.")
                    .translation("mob_map_icons.configuration.mod.iconStyle")
                    .defineEnum("iconStyle", StyleChoice.INHERIT);
            iconMode = b.comment("Full body or head and shoulders on this map mod. INHERIT = the top-level iconMode.")
                    .translation("mob_map_icons.configuration.mod.iconMode")
                    .defineEnum("iconMode", ModeChoice.INHERIT);
            iconSizeMinimap = b.comment("Icon edge length in pixels on this map mod's minimap. 0 = the top-level iconSizeMinimap.")
                    .translation("mob_map_icons.configuration.mod.iconSizeMinimap")
                    .defineInRange("iconSizeMinimap", legacySize(mod), 0, 64);
            iconSizeFullscreen = b.comment("Icon edge length in pixels on this map mod's full-screen map. 0 = the top-level iconSizeFullscreen.")
                    .translation("mob_map_icons.configuration.mod.iconSizeFullscreen")
                    .defineInRange("iconSizeFullscreen", legacySize(mod), 0, 64);
            rotateWithHeading = heading ? b.comment("Turn each icon to the mob's heading on this map mod. INHERIT = the top-level value.")
                    .translation("mob_map_icons.configuration.mod.rotateWithHeading")
                    .defineEnum("rotateWithHeading", Tri.INHERIT) : null;
            headingArrow = heading ? b.comment("Heading arrow on each icon on this map mod. INHERIT = the top-level value.")
                    .translation("mob_map_icons.configuration.mod.headingArrow")
                    .defineEnum("headingArrow", Tri.INHERIT) : null;
            farmMode = heading ? b.comment("Farm mode on this map mod. INHERIT = the top-level value.")
                    .translation("mob_map_icons.configuration.mod.farmMode")
                    .defineEnum("farmMode", Tri.INHERIT) : null;
            players = heading ? b.comment("Draw OTHER players as their skin model on this map mod. INHERIT = the top-level value.")
                    .translation("mob_map_icons.configuration.mod.players")
                    .defineEnum("players", Tri.INHERIT) : null;
            ownPlayer = heading ? b.comment("Draw YOUR OWN marker with our icon too, replacing the map mod's own one",
                            "(face on FTB Chunks, arrow on JourneyMap). Off by default. Xaero cannot do this.")
                    .translation("mob_map_icons.configuration.mod.ownPlayer")
                    .define("ownPlayer", false) : null;
            xaeroScale = mod == MapMod.XAERO ? b.comment("How much of the radar cell the icon fills (1.0 = Xaero's default 56 px box) at the default icon size of 16.",
                            "The minimap and the World Map each multiply it by their icon size / 16 (iconSizeMinimap, iconSizeFullscreen).",
                            "Lower it if icons look bigger than Xaero's own heads.")
                    .translation("mob_map_icons.configuration.mod.xaeroScale")
                    .defineInRange("xaeroScale", legacyDouble("xaeroScale", 0.75), 0.25, 1.0) : null;
            journeymapFrame = mod == MapMod.JOURNEYMAP ? b.comment("JourneyMap's own marker (coloured teardrop with a black border) around each mob icon.",
                            "AUTO = per icon style: badge, head pin, silhouette, retro, chibi and paper ink are finished",
                            "markers themselves, so JourneyMap's is hidden; outline and shadow only add an edge, so they",
                            "are drawn plain inside JourneyMap's marker; plain and top down keep it.",
                            "ON = always keep JourneyMap's marker (our outline, ring and shadow are dropped).",
                            "OFF = always hide it and draw our icon style as is.")
                    .translation("mob_map_icons.configuration.mod.journeymapFrame")
                    .defineEnum("journeymapFrame", legacyFrame()) : null;
            autoIconDisplay = mod == MapMod.JOURNEYMAP ? b.comment("JourneyMap draws its own dot or pin round our icons unless its Mob Display and Player Display",
                            "options are set to Icons. On: while this adapter is enabled and either display is not Icons,",
                            "both are set to Icons for the minimap and the full-screen map. Your previous values are",
                            "remembered and put back when the adapter is disabled or this option is turned off.")
                    .translation("mob_map_icons.configuration.mod.autoIconDisplay")
                    .define("autoIconDisplay", true) : null;
            b.pop();
        }
    }

    private static String label(MapMod mod) {
        return switch (mod) {
            case FTB -> "FTB Chunks";
            case XAERO -> "Xaero's Minimap and World Map";
            case JOURNEYMAP -> "JourneyMap";
            default -> "all map mods";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Which map mods are installed. The spec is built once, at class load, so this has to work before
    // FML's mod list is final: LoadingModList is available from the very start.

    public static boolean loaded(MapMod mod) {
        return switch (mod) {
            case GLOBAL -> true;
            case FTB -> modLoaded(MobMapIcons.FTB_CHUNKS);
            case XAERO -> modLoaded(MobMapIcons.XAERO_MINIMAP) || modLoaded(MobMapIcons.XAERO_WORLD_MAP);
            case JOURNEYMAP -> modLoaded(MobMapIcons.JOURNEYMAP);
        };
    }

    private static boolean modLoaded(String id) {
        try {
            var loading = net.neoforged.fml.loading.LoadingModList.get();
            if (loading != null && loading.getModFileById(id) != null) return true;
        } catch (Throwable ignored) {
            // fall through to the mod list
        }
        try {
            var list = net.neoforged.fml.ModList.get();
            return list != null && list.isLoaded(id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Legacy keys. Before 0.4.3 the file had flat booleans "ftbChunks", "xaero", "journeymap" (now
    // [ftb]/[xaero]/[journeymap] enabled) and flat "xaeroScale" / "journeymapFrame" (now in their sections).
    // Their old values are read once from the file on disk and become the new keys' defaults, so a
    // pre-0.4.3 file keeps its choices when the sections appear.

    private static final Map<String, Object> LEGACY = readLegacy();

    private static Map<String, Object> readLegacy() {
        Map<String, Object> out = new HashMap<>();
        try {
            var file = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("mob_map_icons-client.toml");
            if (!java.nio.file.Files.isRegularFile(file)) return out;
            try (var in = java.nio.file.Files.newBufferedReader(file)) {
                var cfg = new com.electronwill.nightconfig.toml.TomlParser().parse(in);
                for (String key : List.of("ftbChunks", "xaero", "journeymap", "xaeroScale", "journeymapFrame",
                        "iconSize", "ftb.iconSize", "xaero.iconSize", "journeymap.iconSize")) {
                    Object v = cfg.get(key);
                    if (v instanceof Boolean || v instanceof Number || v instanceof String) out.put(key, v);
                }
            }
        } catch (Throwable t) {
            // unreadable or not there yet: plain defaults
        }
        return out;
    }

    private static boolean legacyBool(MapMod mod) {
        String key = switch (mod) {
            case FTB -> "ftbChunks";
            case XAERO -> "xaero";
            case JOURNEYMAP -> "journeymap";
            default -> null;
        };
        return key == null || !(LEGACY.get(key) instanceof Boolean b) || b;
    }

    /** Pre-0.4.4 {@code iconSize} (top level or the map mod's section) becomes the default of both new size keys. */
    private static int legacySize(MapMod mod) {
        boolean global = mod == MapMod.GLOBAL;
        String key = global ? "iconSize" : mod.id() + ".iconSize";
        if (LEGACY.get(key) instanceof Number n) return Math.max(global ? 8 : 0, Math.min(64, n.intValue()));
        return global ? DEFAULT_ICON_SIZE : 0;
    }

    private static double legacyDouble(String key, double def) {
        return LEGACY.get(key) instanceof Number n ? Math.max(0.25, Math.min(1.0, n.doubleValue())) : def;
    }

    private static JourneyMapFrame legacyFrame() {
        try {
            return LEGACY.get("journeymapFrame") instanceof String s ? JourneyMapFrame.valueOf(s) : JourneyMapFrame.AUTO;
        } catch (IllegalArgumentException e) {
            return JourneyMapFrame.AUTO;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Global options

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<IconStyle> ICON_STYLE = BUILDER
            .comment("Icon appearance. CHIBI uses body plus head; HEAD_PIN uses head; TOP_DOWN uses body from above.")
            .translation("mob_map_icons.configuration.iconStyle")
            .defineEnum("iconStyle", IconStyle.OUTLINE);

    public static final ModConfigSpec.EnumValue<IconMode> ICON_MODE = BUILDER
            .comment("What to draw: the whole mob model (FULL_BODY) or a head-and-shoulders crop (HEAD). Flying and very wide mobs always show the full body.")
            .translation("mob_map_icons.configuration.iconMode")
            .defineEnum("iconMode", IconMode.FULL_BODY);

    public static final ModConfigSpec.IntValue ICON_SIZE_MINIMAP = BUILDER
            .comment("Icon edge length in pixels on minimaps (the small in-world map), before the map mod scales it.",
                    "Replaces the single iconSize of 0.4.3 and older, whose value both size keys start from.")
            .translation("mob_map_icons.configuration.iconSizeMinimap")
            .defineInRange("iconSizeMinimap", legacySize(MapMod.GLOBAL), 8, 64);

    public static final ModConfigSpec.IntValue ICON_SIZE_FULLSCREEN = BUILDER
            .comment("Icon edge length in pixels on full-screen maps (FTB Chunks' large map, JourneyMap's full-screen map,",
                    "Xaero's World Map).")
            .translation("mob_map_icons.configuration.iconSizeFullscreen")
            .defineInRange("iconSizeFullscreen", legacySize(MapMod.GLOBAL), 8, 64);

    public static final ModConfigSpec.BooleanValue ROTATE_WITH_HEADING = BUILDER
            .comment("Turn each mob's icon to face the way the mob is heading on the map (8 directions,",
                    "rendered on demand). Off (default): every icon faces the viewer. Map adapters read this.")
            .translation("mob_map_icons.configuration.rotateWithHeading")
            .define("rotateWithHeading", false);

    public static final ModConfigSpec.BooleanValue HEADING_ARROW = BUILDER
            .comment("Small arrow on each icon pointing the way the mob faces (8 directions, drawn into the icon).",
                    "Independent of rotateWithHeading. FTB Chunks and JourneyMap only: Xaero draws one static icon per",
                    "mob type. JourneyMap skips it while JourneyMap's own mob heading arrow is on.")
            .translation("mob_map_icons.configuration.headingArrow")
            .define("headingArrow", true);

    public static final ModConfigSpec.BooleanValue FARM_MODE = BUILDER
            .comment("Farm mode: when many mobs of one kind stand close together on the map, draw one icon",
                    "with a count badge instead of a stack (FTB Chunks), or hide the duplicates",
                    "(JourneyMap). Named, leashed, tamed and boss mobs are never merged.")
            .translation("mob_map_icons.configuration.farmMode")
            .define("farmMode", true);

    public static final ModConfigSpec.BooleanValue PLAYERS = BUILDER
            .comment("Draw other players as their skin's model instead of the map mod's face (FTB Chunks,",
                    "JourneyMap). This is about OTHER players only; your own marker is the per-map-mod",
                    "ownPlayer option. Xaero keeps its own player heads.")
            .translation("mob_map_icons.configuration.players")
            .define("players", true);

    public static final ModConfigSpec.BooleanValue TINT_BY_HOSTILITY = BUILDER
            .comment("Tint hostile mobs' map icons red (FTB Chunks).")
            .translation("mob_map_icons.configuration.tintByHostility")
            .define("tintByHostility", false);

    public static final ModConfigSpec.IntValue MAX_ICONS = BUILDER
            .comment("Most mob icons drawn at once, nearest to the player first. 0 = no limit.")
            .translation("mob_map_icons.configuration.maxIcons")
            .defineInRange("maxIcons", 150, 0, 5000);

    public static final ModConfigSpec.IntValue CLUSTER_RADIUS = BUILDER
            .comment("Farm mode: mobs of the same kind closer than this many screen pixels merge into one icon.")
            .translation("mob_map_icons.configuration.clusterRadius")
            .defineInRange("clusterRadius", 12, 2, 64);

    public static final ModConfigSpec.IntValue CLUSTER_MIN_COUNT = BUILDER
            .comment("Farm mode: a group needs at least this many mobs before it is merged.")
            .translation("mob_map_icons.configuration.clusterMinCount")
            .defineInRange("clusterMinCount", 3, 2, 100);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLIST = BUILDER
            .comment("Entity type ids that get no icon at all, e.g. \"minecraft:armor_stand\".")
            .translation("mob_map_icons.configuration.blacklist")
            .defineListAllowEmpty("blacklist", List.<String>of(), () -> "minecraft:armor_stand",
                    ClientConfig::isEntityTypeId);

    // Per-map-mod sections, last so the config screen lists the global options first. Only installed map
    // mods get a section in the real spec; the others are defined on a spec nobody registers (reads of
    // those values throw IllegalStateException, which every getter below turns into the default).
    public static final Section FTB = section(MapMod.FTB);
    public static final Section XAERO = section(MapMod.XAERO);
    public static final Section JOURNEYMAP = section(MapMod.JOURNEYMAP);

    private static Section section(MapMod mod) {
        boolean real = loaded(mod);
        ModConfigSpec.Builder b = real ? BUILDER : new ModConfigSpec.Builder();
        Section s = new Section(mod, b, real);
        if (!real) b.build();
        return s;
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}

    /** The section of a map mod; {@code null} for {@link MapMod#GLOBAL}. */
    public static Section sectionOf(MapMod mod) {
        return switch (mod) {
            case FTB -> FTB;
            case XAERO -> XAERO;
            case JOURNEYMAP -> JOURNEYMAP;
            case GLOBAL -> null;
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Global getters

    public static IconStyle iconStyle() {
        try { return ICON_STYLE.get(); } catch (IllegalStateException notLoaded) { return IconStyle.OUTLINE; }
    }

    public static IconMode iconMode() {
        try { return ICON_MODE.get(); } catch (IllegalStateException notLoaded) { return IconMode.FULL_BODY; }
    }

    public static int iconSize() {
        return iconSize(MapView.MINIMAP);
    }

    public static int iconSize(MapView view) {
        try {
            return (view == MapView.FULLSCREEN ? ICON_SIZE_FULLSCREEN : ICON_SIZE_MINIMAP).get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_ICON_SIZE;
        }
    }

    public static boolean rotateWithHeading() {
        try { return ROTATE_WITH_HEADING.get(); } catch (IllegalStateException notLoaded) { return false; }
    }

    public static boolean headingArrow() {
        try { return HEADING_ARROW.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    /** Whether adapters should compute a heading bucket at all: the icon turns, or it carries an arrow. */
    public static boolean usesHeading() {
        return rotateWithHeading() || headingArrow();
    }

    public static boolean tintByHostility() {
        try { return TINT_BY_HOSTILITY.get(); } catch (IllegalStateException notLoaded) { return false; }
    }

    public static boolean farmMode() {
        try { return FARM_MODE.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    public static boolean players() {
        try { return PLAYERS.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    public static int maxIcons() {
        try { return MAX_ICONS.get(); } catch (IllegalStateException notLoaded) { return 150; }
    }

    public static int clusterRadius() {
        try { return CLUSTER_RADIUS.get(); } catch (IllegalStateException notLoaded) { return 12; }
    }

    public static int clusterMinCount() {
        try { return CLUSTER_MIN_COUNT.get(); } catch (IllegalStateException notLoaded) { return 3; }
    }

    private static Object blacklist() {
        try { return List.copyOf(BLACKLIST.get()); } catch (IllegalStateException notLoaded) { return List.of(); }
    }

    public static boolean isBlacklisted(ResourceLocation entityTypeId) {
        try {
            return BLACKLIST.get().contains(entityTypeId.toString());
        } catch (IllegalStateException configNotLoadedYet) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Effective values per map mod: the section's value, or the global one when it says INHERIT.

    public static boolean enabled(MapMod mod) {
        Section s = sectionOf(mod);
        if (s == null) return true;
        try { return s.enabled.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    public static IconStyle style(MapMod mod) {
        Section s = sectionOf(mod);
        IconStyle global = iconStyle();
        if (s == null) return global;
        try { return s.iconStyle.get().resolve(global); } catch (IllegalStateException notLoaded) { return global; }
    }

    public static IconMode mode(MapMod mod) {
        Section s = sectionOf(mod);
        IconMode global = iconMode();
        if (s == null) return global;
        try { return s.iconMode.get().resolve(global); } catch (IllegalStateException notLoaded) { return global; }
    }

    /** Minimap icon size on {@code mod}. */
    public static int size(MapMod mod) {
        return size(mod, MapView.MINIMAP);
    }

    /** Icon size on {@code mod} in {@code view}: the section's value, or the global one for that view when it is 0. */
    public static int size(MapMod mod, MapView view) {
        Section s = sectionOf(mod);
        int global = iconSize(view);
        if (s == null) return global;
        ModConfigSpec.IntValue value = sizeValue(mod, view);
        try {
            int v = value.get();
            return v <= 0 ? global : Math.max(8, v);
        } catch (IllegalStateException notLoaded) {
            return global;
        }
    }

    /** The config value that holds the size of {@code mod} in {@code view} (0 = inherit on a section). */
    public static ModConfigSpec.IntValue sizeValue(MapMod mod, MapView view) {
        Section s = sectionOf(mod);
        if (s == null) return view == MapView.FULLSCREEN ? ICON_SIZE_FULLSCREEN : ICON_SIZE_MINIMAP;
        return view == MapView.FULLSCREEN ? s.iconSizeFullscreen : s.iconSizeMinimap;
    }

    private static boolean tri(ModConfigSpec.EnumValue<Tri> value, boolean global) {
        if (value == null) return global;
        try { return value.get().resolve(global); } catch (IllegalStateException notLoaded) { return global; }
    }

    public static boolean rotate(MapMod mod) {
        Section s = sectionOf(mod);
        return s == null ? rotateWithHeading() : tri(s.rotateWithHeading, rotateWithHeading());
    }

    public static boolean arrow(MapMod mod) {
        Section s = sectionOf(mod);
        return s == null ? headingArrow() : tri(s.headingArrow, headingArrow());
    }

    public static boolean usesHeading(MapMod mod) {
        return rotate(mod) || arrow(mod);
    }

    public static boolean farm(MapMod mod) {
        Section s = sectionOf(mod);
        return s == null ? farmMode() : tri(s.farmMode, farmMode());
    }

    public static boolean players(MapMod mod) {
        Section s = sectionOf(mod);
        return s == null ? players() : tri(s.players, players());
    }

    /** Draw our icon for the local player too (never for {@link MapMod#GLOBAL}; off by default). */
    public static boolean ownPlayer(MapMod mod) {
        Section s = sectionOf(mod);
        if (s == null || s.ownPlayer == null) return false;
        try { return s.ownPlayer.get(); } catch (IllegalStateException notLoaded) { return false; }
    }

    /** What an icon looks like on this map mod, after the overrides. The cache keys on exactly this. */
    public static IconProfile profile(MapMod mod) {
        return profile(mod, MapView.MINIMAP);
    }

    /** Like {@link #profile(MapMod)} for one view: only the size differs between views. */
    public static IconProfile profile(MapMod mod, MapView view) {
        return new IconProfile(style(mod), mode(mod), size(mod, view), rotate(mod), arrow(mod));
    }

    // Legacy accessors the adapters and the dev harness already use, now per section.

    public static boolean ftbChunksEnabled() {
        return enabled(MapMod.FTB);
    }

    public static boolean xaeroEnabled() {
        return enabled(MapMod.XAERO);
    }

    public static boolean journeyMapEnabled() {
        return enabled(MapMod.JOURNEYMAP);
    }

    public static double xaeroScale() {
        try { return XAERO.xaeroScale.get(); } catch (IllegalStateException notLoaded) { return 0.75; }
    }

    /** Largest fraction of Xaero's 56 px box that still fits its 64 px sprite cell with room for Xaero's own outline. */
    public static final double XAERO_MAX_SCALE = 60.0 / 56.0;

    /**
     * How much of Xaero's 56 px box the sprite of one view fills: {@code xaeroScale} times that view's icon size over the
     * 16 px default (so 16 / 16 is the plain {@code xaeroScale}), capped to what fits the sprite cell.
     */
    public static double xaeroSpriteScale(MapView view) {
        double k = xaeroScale() * size(MapMod.XAERO, view) / (double) DEFAULT_ICON_SIZE;
        return Math.max(0.05, Math.min(XAERO_MAX_SCALE, k));
    }

    /** JourneyMap: keep its Mob/Player Display on Icons while the adapter is on. */
    public static boolean autoIconDisplay() {
        try { return JOURNEYMAP.autoIconDisplay.get(); } catch (IllegalStateException notLoaded) { return true; }
    }

    public static JourneyMapFrame journeyMapFrame() {
        try { return JOURNEYMAP.journeymapFrame.get(); } catch (IllegalStateException notLoaded) { return JourneyMapFrame.AUTO; }
    }

    /** Everything the rendered icons depend on; a change throws the cache away. */
    public static java.util.List<Object> iconSettings() {
        return java.util.List.of(profile(MapMod.GLOBAL), profile(MapMod.FTB), profile(MapMod.XAERO), profile(MapMod.JOURNEYMAP),
                profile(MapMod.GLOBAL, MapView.FULLSCREEN), profile(MapMod.FTB, MapView.FULLSCREEN),
                profile(MapMod.XAERO, MapView.FULLSCREEN), profile(MapMod.JOURNEYMAP, MapView.FULLSCREEN), tintByHostility(), farmMode(), players(), maxIcons(), clusterRadius(), clusterMinCount(), xaeroScale(), blacklist());
    }

    /** Keeps a typo out of the list: every entry has to parse as a resource location. */
    private static boolean isEntityTypeId(Object value) {
        return value instanceof String id && ResourceLocation.tryParse(id) != null;
    }
}
