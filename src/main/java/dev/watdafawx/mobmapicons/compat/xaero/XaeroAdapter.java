package dev.watdafawx.mobmapicons.compat.xaero;

import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.util.List;
import java.util.Optional;

/**
 * Xaero's Minimap / World Map adapter. Data-driven: no Xaero class is referenced at compile or load time.
 *
 * <p>Xaero's radar looks up {@code assets/xaerominimap/entity/icon/definition/<ns>/<entity>.json} for every
 * entity type and draws the sprite that file names. {@link XaeroIconPack} supplies those files (and the sprites)
 * from memory at the top of the pack stack, so they win over Xaero's own shipped definitions. World Map draws
 * the same {@code RadarRenderer}, so it follows for free.
 *
 * <p>The pack must exist before the first resource load, so it is added from the mod-bus pack finder event
 * (guarded by {@code ModList}); {@link #init()} only starts the polling that keeps it current.
 */
@EventBusSubscriber(modid = MobMapIcons.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class XaeroAdapter {
    private static boolean started;

    private XaeroAdapter() {}

    @SubscribeEvent
    public static void addPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES || !ModList.get().isLoaded(MobMapIcons.XAERO_MINIMAP)) {
            return;
        }
        var info = new PackLocationInfo("mob_map_icons/xaero", Component.literal("Mob Map Icons (Xaero)"),
                PackSource.BUILT_IN, Optional.empty());
        var supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new XaeroIconPack(location);
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return openPrimary(location);
            }
        };
        var metadata = new Pack.Metadata(info.title(), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        event.addRepositorySource(output -> output.accept(new Pack(info, supplier, metadata,
                new PackSelectionConfig(true, Pack.Position.TOP, true))));
    }

    /** Called reflectively from {@link MobMapIcons#loadAdapter}. */
    public static void init() {
        if (started || !ModList.get().isLoaded(MobMapIcons.XAERO_MINIMAP)) return;
        started = true;
        NeoForge.EVENT_BUS.addListener(XaeroSync::onTick);
        MobMapIcons.LOG.info("Xaero adapter active: mob icons are served as Xaero radar sprites");
    }
}
