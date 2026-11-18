package dev.watdafawx.mobmapicons.core;

import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Live PNG view of the icon cache. No reload needed when an icon finishes rendering.
 *
 * <p>Serves {@code mob_map_icons:textures/mob_map_icons/<namespace>/<path>.png} (a type's default
 * icon) and {@code .../<namespace>/<path>/<variant>_<bucket>_g<generation>.png} (one entity variant
 * at one heading), for every icon that is ready.
 */
@EventBusSubscriber(modid = MobMapIcons.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public record VirtualIconPack(PackLocationInfo location) implements PackResources {
    private static final String PREFIX = "textures/mob_map_icons/";

    @SubscribeEvent
    public static void addPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) return;
        var info = new PackLocationInfo("mob_map_icons/runtime", Component.literal("Mob Map Icons"),
                PackSource.BUILT_IN, Optional.empty());
        var supplier = new Pack.ResourcesSupplier() {
            public PackResources openPrimary(PackLocationInfo location) { return new VirtualIconPack(location); }
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) { return openPrimary(location); }
        };
        var metadata = new Pack.Metadata(info.title(), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        event.addRepositorySource(output -> output.accept(new Pack(info, supplier, metadata,
                new PackSelectionConfig(true, Pack.Position.TOP, true))));
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        if (packType != PackType.CLIENT_RESOURCES || !location.getNamespace().equals(MobMapIcons.MODID)) return null;
        String path = location.getPath();
        if (!path.startsWith(PREFIX) || !path.endsWith(".png")) return null;
        byte[] png = MobIconCache.pngForResource(path);
        return png == null ? null : () -> new ByteArrayInputStream(png);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !namespace.equals(MobMapIcons.MODID)) return;
        for (String resource : MobIconCache.resourcePaths()) {
            if (!resource.startsWith(path + "/")) continue;
            byte[] png = MobIconCache.pngForResource(resource);
            if (png != null) {
                output.accept(ResourceLocation.fromNamespaceAndPath(MobMapIcons.MODID, resource),
                        () -> new ByteArrayInputStream(png));
            }
        }
    }

    @Override public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(MobMapIcons.MODID) : Set.of();
    }
    @Override public IoSupplier<InputStream> getRootResource(String... elements) { return null; }
    @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) { return null; }
    @Override public void close() {}
}
