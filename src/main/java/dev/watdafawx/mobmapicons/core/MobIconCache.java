package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.IconMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * One icon per entity type, rendered lazily and reused until the next resource reload or icon config
 * change.
 *
 * <p>Map mods ask for the same handful of types hundreds of times a second, from whatever thread
 * they draw on, so a lookup is one concurrent map read. A miss queues the type and returns
 * {@code null}; the queue is drained on the render thread at the start of each frame, at most
 * {@link #RENDERS_PER_FRAME} types per frame, so opening a map with fifty new mob types costs a few
 * frames of a few milliseconds instead of one long hitch.
 *
 * <p>A type whose instance cannot be made (players, projectiles that need constructor arguments,
 * {@code create} returning null or throwing) or that draws nothing is cached as a failure and is not
 * retried until {@link #invalidateAll()}.
 */
public final class MobIconCache {

    /** Most types rendered in one frame; the rest wait for the next. */
    public static final int RENDERS_PER_FRAME = 2;

    public enum State { PENDING, READY, FAILED }

    /** One cached icon. Immutable once published; READY entries own their texture. */
    public record Entry(State state, @Nullable ResourceLocation texture, byte @Nullable [] png) {
        static final Entry PENDING = new Entry(State.PENDING, null, null);
        static final Entry FAILED = new Entry(State.FAILED, null, null);

        public boolean ready() {
            return state == State.READY;
        }

        public boolean failed() {
            return state == State.FAILED;
        }
    }

    private static final Map<EntityType<?>, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<EntityType<?>> QUEUE = new ConcurrentLinkedDeque<>();
    private static final Map<EntityType<?>, List<Consumer<Entry>>> CALLBACKS = new ConcurrentHashMap<>();
    /** One-off render-thread jobs (commands, self-test), run in the same frame slot as icon renders. */
    private static final ConcurrentLinkedQueue<Runnable> FRAME_JOBS = new ConcurrentLinkedQueue<>();

    /** Config the cached icons were rendered with; a change throws them all away. */
    private static IconMode renderedMode = null;
    private static int renderedSize = -1;

    private MobIconCache() {}

    // ---------------------------------------------------------------------------------------------
    // Lookups (any thread)

    /** The ready icon's texture id, or {@code null} (pending, failed or blacklisted). Queues a miss. */
    @Nullable
    public static ResourceLocation getIcon(EntityType<?> type) {
        if (isBlacklisted(type)) return null;
        Entry entry = ENTRIES.get(type);
        if (entry == null) {
            request(type);
            return null;
        }
        return entry.texture();
    }

    public static boolean isReady(EntityType<?> type) {
        Entry entry = ENTRIES.get(type);
        return entry != null && entry.ready() && !isBlacklisted(type);
    }

    /** {@code true} once a type has been tried and cannot get an icon. */
    public static boolean isFailed(EntityType<?> type) {
        Entry entry = ENTRIES.get(type);
        return entry != null && entry.failed();
    }

    /** PNG bytes of the ready icon, or {@code null}. Does not queue a render. */
    public static byte @Nullable [] getPng(EntityType<?> type) {
        if (isBlacklisted(type)) return null;
        Entry entry = ENTRIES.get(type);
        return entry == null ? null : entry.png();
    }

    /** Texture id an entity type's icon is registered under, ready or not. */
    public static ResourceLocation textureId(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return ResourceLocation.fromNamespaceAndPath(MobMapIcons.MODID, "icons/" + key.getNamespace() + "/" + key.getPath());
    }

    private static boolean isBlacklisted(EntityType<?> type) {
        return ClientConfig.isBlacklisted(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    private static void request(EntityType<?> type) {
        if (ENTRIES.putIfAbsent(type, Entry.PENDING) == null) QUEUE.addLast(type);
    }

    // ---------------------------------------------------------------------------------------------
    // Control

    /**
     * Drops this type's icon and renders it again ahead of the queue, then hands the new entry to
     * {@code done} on the render thread. Ignores the blacklist: it is an explicit request.
     */
    public static void rerender(EntityType<?> type, @Nullable Consumer<Entry> done) {
        onRenderThread(() -> {
            Entry old = ENTRIES.remove(type);
            release(old);
            QUEUE.remove(type);
            if (done != null) CALLBACKS.computeIfAbsent(type, t -> new ArrayList<>()).add(done);
            ENTRIES.put(type, Entry.PENDING);
            QUEUE.addFirst(type);
        });
    }

    /** Runs {@code job} on the render thread at the start of the next frame that has a level. */
    public static void runInFrame(Runnable job) {
        FRAME_JOBS.add(job);
    }

    /**
     * Forgets every icon and frees its texture; the next lookups render them again. Call on resource
     * reload and whenever iconMode or iconSize changes (the cache also notices those by itself).
     * Safe from any thread.
     */
    public static void invalidateAll() {
        onRenderThread(() -> {
            QUEUE.clear();
            for (Entry entry : ENTRIES.values()) release(entry);
            ENTRIES.clear();
            // Pending callbacks get a failure rather than hanging forever.
            CALLBACKS.values().forEach(list -> list.forEach(cb -> cb.accept(Entry.FAILED)));
            CALLBACKS.clear();
            MobIconRenderer.close();
        });
    }

    /** Writes every ready icon to {@code dir/<namespace>/<path>.png}. Returns how many were written. */
    public static int dumpAll(Path dir) throws IOException {
        int written = 0;
        for (Map.Entry<EntityType<?>, Entry> e : ENTRIES.entrySet()) {
            byte[] png = e.getValue().png();
            if (png == null) continue;
            writePng(dir, BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()), "", png);
            written++;
        }
        return written;
    }

    /** Writes one PNG to {@code dir/<namespace>/<path><suffix>.png} and returns the file. */
    public static Path writePng(Path dir, ResourceLocation id, String suffix, byte[] png) throws IOException {
        Path file = dir.resolve(id.getNamespace()).resolve(id.getPath() + suffix + ".png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        return file;
    }

    // ---------------------------------------------------------------------------------------------
    // Render thread

    /** Start-of-frame hook: applies config changes, runs jobs, renders up to the per-frame budget. */
    public static void onFrameStart() {
        Minecraft mc = Minecraft.getInstance();
        // Needs a level to make entities in, a camera the renderers can read, and no reload in flight.
        if (mc.level == null || mc.getEntityRenderDispatcher().camera == null || mc.getOverlay() != null) return;

        IconMode mode = ClientConfig.iconMode();
        int size = ClientConfig.iconSize();
        if (mode != renderedMode || size != renderedSize) {
            if (renderedMode != null) {
                MobMapIcons.LOG.info("Icon config changed ({} {}px -> {} {}px); re-rendering icons",
                        renderedMode, renderedSize, mode, size);
                // Keep what was asked for, so maps don't flicker to "no icon" for longer than needed.
                List<EntityType<?>> wanted = new ArrayList<>(ENTRIES.keySet());
                for (Entry entry : ENTRIES.values()) release(entry);
                ENTRIES.clear();
                QUEUE.clear();
                wanted.forEach(MobIconCache::request);
            }
            renderedMode = mode;
            renderedSize = size;
        }

        Runnable job;
        while ((job = FRAME_JOBS.poll()) != null) {
            try {
                job.run();
            } catch (Throwable t) {
                MobMapIcons.LOG.error("Mob icon frame job failed", t);
            }
        }

        for (int i = 0; i < RENDERS_PER_FRAME; i++) {
            EntityType<?> type = QUEUE.pollFirst();
            if (type == null) break;
            Entry entry = build(type, mode, size * 2);
            ENTRIES.put(type, entry);
            List<Consumer<Entry>> callbacks = CALLBACKS.remove(type);
            if (callbacks != null) callbacks.forEach(cb -> cb.accept(entry));
        }
    }

    private static Entry build(EntityType<?> type, IconMode mode, int outSize) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        Minecraft mc = Minecraft.getInstance();
        Entity entity;
        try {
            entity = type.create(mc.level);
        } catch (Throwable t) {
            MobMapIcons.LOG.debug("No icon for {}: could not create an instance ({})", key, t.toString());
            return Entry.FAILED;
        }
        if (entity == null) {
            MobMapIcons.LOG.debug("No icon for {}: EntityType.create returned null", key);
            return Entry.FAILED;
        }

        NativeImage image = null;
        try {
            image = MobIconRenderer.render(entity, mode, outSize);
            if (image == null) {
                MobMapIcons.LOG.debug("No icon for {}: it draws nothing", key);
                return Entry.FAILED;
            }
            byte[] png = image.asByteArray();
            ResourceLocation id = textureId(type);
            DynamicTexture texture = new DynamicTexture(image); // takes ownership of image
            image = null;
            mc.getTextureManager().register(id, texture);
            return new Entry(State.READY, id, png);
        } catch (Throwable t) {
            MobMapIcons.LOG.warn("No icon for {}: rendering it failed", key, t);
            return Entry.FAILED;
        } finally {
            if (image != null) image.close();
            try {
                entity.discard();
            } catch (Throwable ignored) {
                // Never in a level; a modded remove() hook failing here changes nothing.
            }
        }
    }

    /** Builds an icon image for an arbitrary instance (e.g. a baby), outside the cache. Render thread. */
    @Nullable
    public static NativeImage renderOnce(Entity entity, IconMode mode, int outSize) {
        return MobIconRenderer.render(entity, mode, outSize);
    }

    private static void release(@Nullable Entry entry) {
        if (entry != null && entry.texture() != null) {
            Minecraft.getInstance().getTextureManager().release(entry.texture());
        }
    }

    private static void onRenderThread(Runnable task) {
        if (RenderSystem.isOnRenderThread()) task.run();
        else RenderSystem.recordRenderCall(task::run);
    }
}
