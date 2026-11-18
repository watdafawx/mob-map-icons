package dev.watdafawx.mobmapicons;

import dev.watdafawx.mobmapicons.api.IconMode;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * CLIENT config - {@code config/mob_map_icons-client.toml}. Map icons are a client render detail, so
 * nothing here is registered on a server and nothing is synced.
 *
 * <p>Every getter has a fallback for the window before the config is loaded (adapters can be asked
 * for an icon during client setup), so no read throws {@link IllegalStateException}.
 */
public final class ClientConfig {

    /** 16x16 is what every map mod's own mob dots are sized for. */
    public static final int DEFAULT_ICON_SIZE = 16;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<IconMode> ICON_MODE = BUILDER
            .comment("What to draw: the whole mob model (FULL_BODY) or just its head (HEAD).")
            .defineEnum("iconMode", IconMode.FULL_BODY);

    public static final ModConfigSpec.IntValue ICON_SIZE = BUILDER
            .comment("Icon edge length in pixels, before the map mod scales it.")
            .defineInRange("iconSize", DEFAULT_ICON_SIZE, 8, 64);

    public static final ModConfigSpec.BooleanValue ROTATE_WITH_HEADING = BUILDER
            .comment("Turn each mob's icon to face the way the mob is heading on the map (8 directions,",
                    "rendered on demand). Off: every icon faces the viewer. Map adapters read this.")
            .define("rotateWithHeading", true);

    public static final ModConfigSpec.BooleanValue FTB_CHUNKS_ENABLED = BUILDER
            .comment("Draw mob icons on FTB Chunks maps.")
            .define("ftbChunks", true);

    public static final ModConfigSpec.BooleanValue TINT_BY_HOSTILITY = BUILDER
            .comment("Tint hostile mobs' map icons red (FTB Chunks).")
            .define("tintByHostility", false);

    public static final ModConfigSpec.BooleanValue XAERO_ENABLED = BUILDER
            .comment("Draw mob icons on Xaero's Minimap and Xaero's World Map.")
            .define("xaero", true);

    public static final ModConfigSpec.BooleanValue JOURNEYMAP_ENABLED = BUILDER
            .comment("Draw mob icons on JourneyMap.")
            .define("journeymap", true);

    public static final ModConfigSpec.BooleanValue FARM_MODE = BUILDER
            .comment("Farm mode: when many mobs of one kind stand close together on the map, draw one icon",
                    "with a count badge instead of a stack (FTB Chunks), or hide the duplicates",
                    "(JourneyMap). Named, leashed, tamed and boss mobs are never merged.")
            .define("farmMode", true);

    public static final ModConfigSpec.IntValue MAX_ICONS = BUILDER
            .comment("Most mob icons drawn at once, nearest to the player first. 0 = no limit.")
            .defineInRange("maxIcons", 150, 0, 5000);

    public static final ModConfigSpec.IntValue CLUSTER_RADIUS = BUILDER
            .comment("Farm mode: mobs of the same kind closer than this many screen pixels merge into one icon.")
            .defineInRange("clusterRadius", 12, 2, 64);

    public static final ModConfigSpec.IntValue CLUSTER_MIN_COUNT = BUILDER
            .comment("Farm mode: a group needs at least this many mobs before it is merged.")
            .defineInRange("clusterMinCount", 3, 2, 100);

    public static final ModConfigSpec.DoubleValue XAERO_SCALE = BUILDER
            .comment("Xaero only: how much of the radar cell the icon fills (1.0 = Xaero's default 56 px box).",
                    "Lower it if icons look bigger than Xaero's own heads.")
            .defineInRange("xaeroScale", 0.75, 0.25, 1.0);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLACKLIST = BUILDER
            .comment("Entity type ids that get no icon at all, e.g. \"minecraft:armor_stand\".")
            .defineListAllowEmpty("blacklist", List.<String>of(), () -> "minecraft:armor_stand",
                    ClientConfig::isEntityTypeId);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}

    public static IconMode iconMode() {
        try {
            return ICON_MODE.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return IconMode.FULL_BODY;
        }
    }

    public static int iconSize() {
        try {
            return ICON_SIZE.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return DEFAULT_ICON_SIZE;
        }
    }

    public static boolean rotateWithHeading() {
        try {
            return ROTATE_WITH_HEADING.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return true;
        }
    }

    public static boolean ftbChunksEnabled() {
        try {
            return FTB_CHUNKS_ENABLED.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return true;
        }
    }

    public static boolean tintByHostility() {
        try {
            return TINT_BY_HOSTILITY.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return false;
        }
    }

    public static boolean xaeroEnabled() {
        try {
            return XAERO_ENABLED.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return true;
        }
    }

    public static boolean journeyMapEnabled() {
        try {
            return JOURNEYMAP_ENABLED.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return true;
        }
    }

    public static boolean farmMode() {
        try { return FARM_MODE.get(); } catch (IllegalStateException notLoaded) { return true; }
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

    public static double xaeroScale() {
        try { return XAERO_SCALE.get(); } catch (IllegalStateException notLoaded) { return 0.75; }
    }

    public static boolean isBlacklisted(ResourceLocation entityTypeId) {
        try {
            return BLACKLIST.get().contains(entityTypeId.toString());
        } catch (IllegalStateException configNotLoadedYet) {
            return false;
        }
    }

    /** Keeps a typo out of the list: every entry has to parse as a resource location. */
    private static boolean isEntityTypeId(Object value) {
        return value instanceof String id && ResourceLocation.tryParse(id) != null;
    }
}
