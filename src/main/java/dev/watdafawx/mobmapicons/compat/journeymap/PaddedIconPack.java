package dev.watdafawx.mobmapicons.compat.journeymap;

import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JourneyMap's own copy of the icons: the core icons fitted into JourneyMap's round marker (see
 * {@link dev.watdafawx.mobmapicons.core.IconPadding}), served from
 * {@code mob_map_icons:textures/mob_map_icons_jm/...}, a path namespace nothing else reads, so the
 * unpadded icons other adapters use are untouched.
 *
 * <p>Content-addressed: every path ends in a CRC of the source PNG, so the bytes behind one path
 * never change and JourneyMap may keep the texture it loaded. Holds no JourneyMap type, so it is safe
 * to load without JourneyMap; it only registers itself when JourneyMap is installed.
 */
@EventBusSubscriber(modid = MobMapIcons.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public record PaddedIconPack(PackLocationInfo location) implements PackResources {
    static final String PREFIX = "textures/mob_map_icons_jm/";
    private static final int MAX_ENTRIES = 1024;

    /** Path -> PNG. Insertion-ordered LRU; JourneyMap re-reads a path only on its own cache miss. */
    private static final Map<String, byte[]> ICONS = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    static void put(String path, byte[] png) {
        ICONS.put(path, png);
    }

    static boolean has(String path) {
        return ICONS.containsKey(path);
    }

    @SubscribeEvent
    public static void addPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES || !ModList.get().isLoaded(MobMapIcons.JOURNEYMAP)) return;
        var info = new PackLocationInfo("mob_map_icons/journeymap", Component.literal("Mob Map Icons (JourneyMap)"),
                PackSource.BUILT_IN, Optional.empty());
        var supplier = new Pack.ResourcesSupplier() {
            public PackResources openPrimary(PackLocationInfo location) { return new PaddedIconPack(location); }
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
        if (!path.startsWith(PREFIX)) return null;
        byte[] png = ICONS.get(path);
        return png == null ? null : () -> new ByteArrayInputStream(png);
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !namespace.equals(MobMapIcons.MODID)) return;
        List<Map.Entry<String, byte[]>> snapshot;
        synchronized (ICONS) {
            snapshot = List.copyOf(ICONS.entrySet());
        }
        for (var e : snapshot) {
            if (e.getKey().startsWith(path + "/")) {
                byte[] png = e.getValue();
                output.accept(ResourceLocation.fromNamespaceAndPath(MobMapIcons.MODID, e.getKey()),
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
