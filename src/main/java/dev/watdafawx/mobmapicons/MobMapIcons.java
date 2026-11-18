package dev.watdafawx.mobmapicons;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mob Map Icons - draws a real icon for every mob, then lets whichever map mod is installed use it.
 *
 * <p><b>Optional dependencies.</b> Nothing but NeoForge is required. FTB Chunks, Xaero's Minimap,
 * Xaero's World Map and JourneyMap are all optional and independent, and every one of them is
 * reached through {@code compat/}, never through an import from an eagerly loaded class: FML loads
 * this class on every client, so a direct reference to an adapter type would throw
 * {@link NoClassDefFoundError} on a pack without that map mod.
 *
 * <p>Client-only by design. There is no common or server half - map icons are a client render
 * detail - so the config is a CLIENT config and nothing is registered on the server.
 */
@Mod(MobMapIcons.MODID)
public class MobMapIcons {
    public static final String MODID = "mob_map_icons";
    public static final Logger LOG = LoggerFactory.getLogger("Mob Map Icons");

    /** FTB Chunks and its minimap. */
    public static final String FTB_CHUNKS = "ftbchunks";
    /** Xaero's Minimap, the "xaero" adapter's first half. */
    public static final String XAERO_MINIMAP = "xaerominimap";
    /** Xaero's World Map, the "xaero" adapter's second half - it shares the minimap's icon set. */
    public static final String XAERO_WORLD_MAP = "xaeroworldmap";
    public static final String JOURNEYMAP = "journeymap";

    public MobMapIcons(IEventBus modBus, ModContainer container) {
        if (FMLEnvironment.dist.isClient()) {
            container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            modBus.addListener(MobMapIcons::clientSetup);
        }
    }

    /**
     * Wires up one adapter per installed map mod. Each lookup is by class name and its {@code init()}
     * is called reflectively, so a pack without that map mod never classloads the adapter at all.
     */
    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded(FTB_CHUNKS)) {
                // TODO(compat:ftbchunks): loadAdapter(FTB_CHUNKS, "dev.watdafawx.mobmapicons.compat.ftbchunks.FtbChunksAdapter");
            }

            if (ModList.get().isLoaded(XAERO_MINIMAP) || ModList.get().isLoaded(XAERO_WORLD_MAP)) {
                // TODO(compat:xaero): loadAdapter("xaero", "dev.watdafawx.mobmapicons.compat.xaero.XaeroAdapter");
            }

            if (ModList.get().isLoaded(JOURNEYMAP)) {
                // TODO(compat:journeymap): loadAdapter(JOURNEYMAP, "dev.watdafawx.mobmapicons.compat.journeymap.JourneyMapAdapter");
            }
        });
    }

    /**
     * Loads an adapter class by name and calls its static {@code init()}.
     *
     * <p>This is the only way an adapter is ever touched, which is what keeps a missing map mod from
     * turning into a {@link NoClassDefFoundError}: nothing in this class names an adapter type, so the
     * classloader is never asked for one unless the mod it belongs to is present.
     *
     * @param label     mod id the adapter belongs to, for logging only
     * @param className fully qualified adapter class name
     */
    public static void loadAdapter(String label, String className) {
        try {
            var adapter = Class.forName(className);
            adapter.getMethod("init").invoke(null);
        } catch (ClassNotFoundException notWrittenYet) {
            // No adapter class in this build; the compat package holds only its package-info.
        } catch (ReflectiveOperationException e) {
            LOG.error("Could not initialise the {} map-icon adapter; icons stay off there", label, e);
        }
    }
}
