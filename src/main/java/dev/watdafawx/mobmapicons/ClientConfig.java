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

    public static final ModConfigSpec.BooleanValue FTB_CHUNKS_ENABLED = BUILDER
            .comment("Draw mob icons on FTB Chunks maps.")
            .define("ftbChunks", true);

    public static final ModConfigSpec.BooleanValue XAERO_ENABLED = BUILDER
            .comment("Draw mob icons on Xaero's Minimap and Xaero's World Map.")
            .define("xaero", true);

    public static final ModConfigSpec.BooleanValue JOURNEYMAP_ENABLED = BUILDER
            .comment("Draw mob icons on JourneyMap.")
            .define("journeymap", true);

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

    public static boolean ftbChunksEnabled() {
        try {
            return FTB_CHUNKS_ENABLED.get();
        } catch (IllegalStateException configNotLoadedYet) {
            return true;
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
