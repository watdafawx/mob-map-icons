package dev.watdafawx.mobmapicons.compat.xaero;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * In-memory resources in Xaero's own namespace: one entity icon definition JSON plus one 64x64 sprite per
 * entity type whose mob icon is ready. Served from the {@link Snapshot} that {@link XaeroSync} publishes, so
 * a definition and the sprite it names always come from the same moment.
 *
 * <p>Xaero reads these with {@code ResourceManager.getResource(...)} each time it reloads its definitions
 * (on a resource reload, and again whenever {@link XaeroSync} nudges it), which is why no full client
 * resource reload is needed when a new icon becomes ready.
 */
public record XaeroIconPack(PackLocationInfo location) implements PackResources {
    static final String XAERO_NAMESPACE = "xaerominimap";
    static final String DEFINITION_DIR = "entity/icon/definition";
    static final String SPRITE_DIR = "entity/icon/sprite";
    /** Sprite sub-folder, so our files never collide with Xaero's own sprites. */
    static final String SPRITE_SUBDIR = "mob_map_icons";

    /** Same layout as Xaero's shipped definitions (assets/xaerominimap/entity/icon/definition/minecraft/pig.json). */
    private static final String DEFINITION_TEMPLATE = "{\"variants\":{\"default\":\"outlined_sprite:%s\"},"
            + "\"modelConfigs\":[],"
            + "\"variantMethod\":\"xaero.hud.minimap.radar.icon.definition.BuiltInRadarIconDefinitions.getVariant\"}";

    private static volatile Snapshot served = Snapshot.EMPTY;

    static Snapshot served() {
        return served;
    }

    static void serve(Snapshot snapshot) {
        served = snapshot;
    }

    static String spriteName(ResourceLocation entityId) {
        return SPRITE_SUBDIR + "/" + entityId.getNamespace() + "/" + entityId.getPath() + ".png";
    }

    static ResourceLocation definitionLocation(ResourceLocation entityId) {
        return ResourceLocation.fromNamespaceAndPath(XAERO_NAMESPACE,
                DEFINITION_DIR + "/" + entityId.getNamespace() + "/" + entityId.getPath() + ".json");
    }

    static ResourceLocation spriteLocation(ResourceLocation entityId) {
        return ResourceLocation.fromNamespaceAndPath(XAERO_NAMESPACE, SPRITE_DIR + "/" + spriteName(entityId));
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        if (packType != PackType.CLIENT_RESOURCES || !location.getNamespace().equals(XAERO_NAMESPACE)) return null;
        String path = location.getPath();
        Snapshot snapshot = served;
        if (snapshot.isEmpty()) return null;
        if (path.startsWith(DEFINITION_DIR + "/") && path.endsWith(".json")) {
            var id = idOf(path.substring(DEFINITION_DIR.length() + 1, path.length() - ".json".length()));
            if (id == null || snapshot.get(id) == null) return null;
            byte[] json = DEFINITION_TEMPLATE.formatted(spriteName(id)).getBytes(StandardCharsets.UTF_8);
            return () -> new ByteArrayInputStream(json);
        }
        String spritePrefix = SPRITE_DIR + "/" + SPRITE_SUBDIR + "/";
        if (path.startsWith(spritePrefix) && path.endsWith(".png")) {
            var id = idOf(path.substring(spritePrefix.length(), path.length() - ".png".length()));
            var entry = id == null ? null : snapshot.get(id);
            if (entry == null) return null;
            byte[] sprite = entry.sprite();
            return () -> new ByteArrayInputStream(sprite);
        }
        return null;
    }

    /** {@code namespace/path} -> entity type id. */
    private static ResourceLocation idOf(String namespaceSlashPath) {
        int slash = namespaceSlashPath.indexOf('/');
        return slash <= 0 ? null : ResourceLocation.tryBuild(namespaceSlashPath.substring(0, slash),
                namespaceSlashPath.substring(slash + 1));
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !namespace.equals(XAERO_NAMESPACE)) return;
        for (ResourceLocation id : served.ids()) {
            for (ResourceLocation location : new ResourceLocation[]{definitionLocation(id), spriteLocation(id)}) {
                if (location.getPath().startsWith(path)) {
                    var resource = getResource(type, location);
                    if (resource != null) output.accept(location, resource);
                }
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(XAERO_NAMESPACE) : Set.of();
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
        return null;
    }

    @Override
    public void close() {}
}
