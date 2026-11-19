package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.IconProfile;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MapView;
import dev.watdafawx.mobmapicons.api.IconStyle;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Icons keyed by (entity type, variant, heading bucket), rendered lazily and reused until evicted,
 * the next resource reload or an icon config change.
 *
 * <p>Map mods ask for the same handful of mobs hundreds of times a second, from whatever thread they
 * draw on, so a lookup is one short synchronized map read. A miss queues the key (with the entity to
 * copy the look from) and returns {@code null}; the queue is drained on the render thread at the
 * start of each frame, at most {@link #RENDERS_PER_FRAME} icons per frame, so opening a map with fifty
 * new mobs costs a few frames of a few milliseconds instead of one long hitch.
 *
 * <p>The cache holds at most {@link #MAX_ENTRIES} icons (pending and failed ones included); the least
 * recently looked-up one is dropped first and its texture freed on the render thread. Every built
 * icon gets its own texture id, so freeing an old one can never hit a newer one.
 *
 * <p>A key whose entity cannot be made (players, projectiles that need constructor arguments,
 * {@code create} returning null or throwing) or that draws nothing is cached as a failure and is not
 * retried until it is evicted or {@link #invalidateAll()} runs.
 */
public final class MobIconCache {

    /** Most icons rendered in one frame; the rest wait for the next. */
    public static final int RENDERS_PER_FRAME = 2;
    /** Most icons kept at once. */
    public static final int MAX_ENTRIES = 512;
    /** Most heading frames (one per variant) kept at once. Render thread only. */
    private static final int MAX_FRAMES = 256;
    private static final String RESOURCE_PREFIX = "textures/mob_map_icons/";

    public enum State { PENDING, READY, FAILED }

    /**
     * One cached icon. Immutable once published; READY entries own their texture.
     *
     * @param resource the resource-pack location serving {@code png}, for resource-based map mods
     */
    public record Entry(State state, @Nullable ResourceLocation texture, byte @Nullable [] png,
                        @Nullable ResourceLocation resource) {
        static final Entry PENDING = new Entry(State.PENDING, null, null, null);
        static final Entry FAILED = new Entry(State.FAILED, null, null, null);

        public boolean ready() {
            return state == State.READY;
        }

        public boolean failed() {
            return state == State.FAILED;
        }
    }

    /**
     * What an icon is of. {@code variant == null} is the type's default look (a fresh instance),
     * which the {@link EntityType} lookups use. {@code bucket} is the model's rotation (always 0 with
     * rotateWithHeading off); {@code arrow} is the heading drawn as an arrow, or -1 for none.
     */
    record Key(EntityType<?> type, @Nullable IconVariant variant, int bucket, IconProfile spec, int arrow) {
        IconStyle style() { return spec.style(); }

        ResourceLocation typeId() {
            return BuiltInRegistries.ENTITY_TYPE.getKey(type);
        }

        /** File name stem for dumps, e.g. {@code sheep__sheep_red_b0}; the type default is just {@code sheep}. */
        String fileStem() {
            String base = typeId().getPath();
            return variant == null && bucket == 0 && arrow < 0 ? base
                    : base + "__" + (variant == null ? "default" : variant.label()) + "_b" + bucket + (arrow < 0 ? "" : "_a" + arrow);
        }
    }

    private record Request(Key key, @Nullable Entity source) {}

    private record FrameKey(EntityType<?> type, @Nullable IconVariant variant, int outSize) {}

    /** What a style's pixels are made from, before the style and the arrow: the model render(s). */
    private record BaseKey(EntityType<?> type, @Nullable IconVariant variant, int bucket, IconProfile spec) {}

    private record Base(NativeImage raw, @Nullable NativeImage head) {
        void close() {
            raw.close();
            if (head != null) head.close();
        }
    }

    private static final Object LOCK = new Object();
    /** Access-ordered: the eldest entry is the least recently looked up. Guarded by {@link #LOCK}. */
    private static final LinkedHashMap<Key, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
            if (size() <= MAX_ENTRIES) return false;
            EVICTED.add(eldest.getValue());
            return true;
        }
    };
    /** Entries dropped from the cache whose textures still need freeing on the render thread. */
    private static final ConcurrentLinkedQueue<Entry> EVICTED = new ConcurrentLinkedQueue<>();
    /** Resource path (without namespace) of every ready icon, for the virtual resource pack. */
    private static final Map<String, Key> RESOURCES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedDeque<Request> QUEUE = new ConcurrentLinkedDeque<>();
    private static final Map<Key, List<Consumer<Entry>>> CALLBACKS = new ConcurrentHashMap<>();
    /** One-off render-thread jobs (commands, self-test), run in the same frame slot as icon renders. */
    private static final ConcurrentLinkedQueue<Runnable> FRAME_JOBS = new ConcurrentLinkedQueue<>();
    /** Heading frames per variant. Render thread only. */
    private static final LinkedHashMap<FrameKey, MobIconRenderer.Frame> FRAMES = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<FrameKey, MobIconRenderer.Frame> eldest) {
            return size() > MAX_FRAMES;
        }
    };

    /**
     * Model renders kept so the eight arrow directions of one look cost one render, not eight (the arrow
     * is drawn on a copy of the styled base). Only used while the model itself does not turn. 4 KB each
     * at 16 px icons. Render thread only.
     */
    private static final int MAX_BASES = 128;
    private static final LinkedHashMap<BaseKey, Base> BASES = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BaseKey, Base> eldest) {
            if (size() <= MAX_BASES) return false;
            eldest.getValue().close();
            return true;
        }
    };

    /** Config the cached icons were rendered with; a change throws them all away. Render thread. */
    private static List<Object> renderedSettings;
    private static final Map<Key, Entry> PREVIOUS = new LinkedHashMap<>();
    /** Bumped whenever every icon is thrown away; part of variant resource paths. */
    private static volatile int generation;
    /** Makes every built texture id unique. Render thread. */
    private static int textureSerial;

    private MobIconCache() {}

    // ---------------------------------------------------------------------------------------------
    // Lookups (any thread)

    /** The render-defining part of a profile; the arrow is the key's own field. */
    private static IconProfile specOf(IconProfile p) {
        return p.arrow() ? new IconProfile(p.style(), p.mode(), p.size(), p.rotate(), false) : p;
    }

    private static Key typeKey(EntityType<?> type, IconProfile profile) {
        return new Key(type, null, 0, specOf(profile), -1);
    }

    private static IconProfile global() {
        return ClientConfig.profile(MapMod.GLOBAL);
    }

    /** The type's default icon texture id, or {@code null} (pending, failed or blacklisted). Queues a miss. */
    @Nullable
    public static ResourceLocation getIcon(EntityType<?> type) {
        return getIcon(type, MapMod.GLOBAL);
    }

    /** Like {@link #getIcon(EntityType)}, in the look {@code mod}'s config asks for. */
    @Nullable
    public static ResourceLocation getIcon(EntityType<?> type, MapMod mod) {
        return getIcon(type, mod, MapView.MINIMAP);
    }

    /** Like {@link #getIcon(EntityType, MapMod)} at the icon size {@code mod} uses in {@code view}. */
    @Nullable
    public static ResourceLocation getIcon(EntityType<?> type, MapMod mod, MapView view) {
        if (isBlacklisted(type)) return null;
        return lookup(typeKey(type, ClientConfig.profile(mod, view)), null).texture();
    }

    public static boolean isReady(EntityType<?> type) {
        return isReady(type, MapMod.GLOBAL);
    }

    public static boolean isReady(EntityType<?> type, MapMod mod) {
        return isReady(type, mod, MapView.MINIMAP);
    }

    public static boolean isReady(EntityType<?> type, MapMod mod, MapView view) {
        return !isBlacklisted(type) && peek(typeKey(type, ClientConfig.profile(mod, view))).ready();
    }

    /** {@code true} once a type's default icon has been tried and cannot be made. */
    public static boolean isFailed(EntityType<?> type) {
        return peek(typeKey(type, global())).failed();
    }

    /** PNG bytes of the type's ready default icon, or {@code null}. Does not queue a render. */
    public static byte @Nullable [] getPng(EntityType<?> type) {
        return getPng(type, MapMod.GLOBAL);
    }

    public static byte @Nullable [] getPng(EntityType<?> type, MapMod mod) {
        return getPng(type, mod, MapView.MINIMAP);
    }

    public static byte @Nullable [] getPng(EntityType<?> type, MapMod mod, MapView view) {
        if (isBlacklisted(type)) return null;
        synchronized (LOCK) {
            Key k = typeKey(type, ClientConfig.profile(mod, view));
            Entry e = peek(k);
            return e.ready() ? e.png() : fallback(k).png();
        }
    }

    /** Resource location of the type's default icon, or {@code null}. Queues a miss. */
    @Nullable
    public static ResourceLocation getResourceIcon(EntityType<?> type) {
        return getResourceIcon(type, MapMod.GLOBAL);
    }

    @Nullable
    public static ResourceLocation getResourceIcon(EntityType<?> type, MapMod mod) {
        if (isBlacklisted(type)) return null;
        return lookup(typeKey(type, ClientConfig.profile(mod)), null).resource();
    }

    /**
     * Texture id of the best icon for this entity and heading bucket (exact or stand-in), or {@code null}.
     * Queues a miss. The bucket turns the model with rotateWithHeading and picks the arrow with headingArrow.
     */
    @Nullable
    public static ResourceLocation getIcon(Entity entity, int bucket) {
        return getIcon(entity, bucket, MapMod.GLOBAL);
    }

    @Nullable
    public static ResourceLocation getIcon(Entity entity, int bucket, MapMod mod) {
        return best(entity, bucket, ClientConfig.profile(mod), true, true).texture();
    }

    /** Like {@link #getIcon(Entity, int, MapMod)}, with the heading arrow left out when {@code arrow} is false. */
    @Nullable
    public static ResourceLocation getIcon(Entity entity, int bucket, MapMod mod, boolean arrow) {
        return getIcon(entity, bucket, mod, MapView.MINIMAP, arrow);
    }

    /** Like {@link #getIcon(Entity, int, MapMod, boolean)} at the icon size {@code mod} uses in {@code view}. */
    @Nullable
    public static ResourceLocation getIcon(Entity entity, int bucket, MapMod mod, MapView view, boolean arrow) {
        return best(entity, bucket, ClientConfig.profile(mod, view), arrow, true).texture();
    }

    /** The icon facing the viewer with no heading arrow (for callers that do not know the heading). */
    @Nullable
    public static ResourceLocation getIcon(Entity entity) {
        return getIcon(entity, MapMod.GLOBAL);
    }

    @Nullable
    public static ResourceLocation getIcon(Entity entity, MapMod mod) {
        return getIcon(entity, mod, MapView.MINIMAP);
    }

    @Nullable
    public static ResourceLocation getIcon(Entity entity, MapMod mod, MapView view) {
        return best(entity, 0, ClientConfig.profile(mod, view), false, true).texture();
    }

    /** PNG of what {@link #getIcon(Entity, int)} would return. Does not queue. */
    public static byte @Nullable [] getPng(Entity entity, int bucket) {
        return best(entity, bucket, global(), true, false).png();
    }

    /** Like {@link #getPng(Entity, int)}, but in {@code style} instead of the configured one. */
    public static byte @Nullable [] getPng(Entity entity, int bucket, IconStyle style) {
        return best(entity, bucket, global().withStyle(style), true, false).png();
    }

    /** Like {@link #getPng(Entity, int, IconStyle)}; {@code arrow == false} leaves out the heading arrow. */
    public static byte @Nullable [] getPng(Entity entity, int bucket, IconStyle style, boolean arrow) {
        return best(entity, bucket, global().withStyle(style), arrow, false).png();
    }

    /** PNG for an explicit profile (a map mod's effective look, possibly with its own style override). */
    public static byte @Nullable [] getPng(Entity entity, int bucket, IconProfile profile, boolean arrow) {
        return best(entity, bucket, profile, arrow, false).png();
    }

    /** Resource location of what {@link #getIcon(Entity, int)} would return. Queues a miss. */
    @Nullable
    public static ResourceLocation getResourceIcon(Entity entity, int bucket) {
        return best(entity, bucket, global(), true, true).resource();
    }

    /** Like {@link #getResourceIcon(Entity, int)}, but in {@code style} instead of the configured one. */
    @Nullable
    public static ResourceLocation getResourceIcon(Entity entity, int bucket, IconStyle style) {
        return best(entity, bucket, global().withStyle(style), true, true).resource();
    }

    /** Like {@link #getResourceIcon(Entity, int, IconStyle)}; {@code arrow == false} leaves out the heading arrow. */
    @Nullable
    public static ResourceLocation getResourceIcon(Entity entity, int bucket, IconStyle style, boolean arrow) {
        return best(entity, bucket, global().withStyle(style), arrow, true).resource();
    }

    @Nullable
    public static ResourceLocation getResourceIcon(Entity entity, int bucket, IconProfile profile, boolean arrow) {
        return best(entity, bucket, profile, arrow, true).resource();
    }

    /** {@code true} when the exact icon for this entity and bucket is ready (no stand-in). Does not queue. */
    public static boolean isExactReady(Entity entity, int bucket) {
        Key key = keyOf(entity, bucket, global(), true);
        return key != null && peek(key).ready();
    }

    /** {@code true} when the exact icon for this entity and bucket has been tried and cannot be made. */
    public static boolean isExactFailed(Entity entity, int bucket) {
        Key key = keyOf(entity, bucket, global(), true);
        return key == null || peek(key).failed();
    }

    /** PNG bytes for a resource path served by the virtual pack ({@code textures/mob_map_icons/...}). */
    static byte @Nullable [] pngForResource(String path) {
        Key key = RESOURCES.get(path);
        if (key == null || isBlacklisted(key.type())) return null;
        Entry e=peek(key);
        synchronized(LOCK) { return e.ready()?e.png():fallback(key).png(); }
    }

    /** Every resource path the virtual pack currently serves. */
    static List<String> resourcePaths() {
        return new ArrayList<>(RESOURCES.keySet());
    }

    @Nullable
    private static Key keyOf(Entity entity, int bucket, IconProfile profile, boolean arrow) {
        EntityType<?> type = entity.getType();
        if (isBlacklisted(type)) return null;
        return headingKey(type, IconVariant.of(entity), bucket, profile, arrow);
    }

    /**
     * The key for a heading bucket: the model turns only with the profile's rotate (else bucket 0, so one
     * render serves every heading) and the arrow exists only with its arrow flag and when the caller
     * wants it ({@code arrow}; lookups without a heading do not).
     */
    private static Key headingKey(EntityType<?> type, @Nullable IconVariant variant, int bucket, IconProfile profile, boolean arrow) {
        int b = Math.floorMod(bucket, MobIconApi.YAW_BUCKETS);
        return new Key(type, variant, profile.rotate() ? b : 0, specOf(profile), arrow && profile.arrow() ? b : -1);
    }

    /**
     * The exact entry if ready, otherwise the nearest ready bucket of the same variant, otherwise the
     * ready type default; {@link Entry#PENDING}-like (all null) when there is none.
     */
    private static Entry best(Entity entity, int bucket, IconProfile profile, boolean arrow, boolean queue) {
        Key key = keyOf(entity, bucket, profile, arrow);
        if (key == null) return Entry.FAILED;
        Entry exact = queue ? lookup(key, entity) : peek(key);
        if (exact.ready()) return exact;
        synchronized (LOCK) {
            for (int d = 1; d <= MobIconApi.YAW_BUCKETS / 2; d++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    Entry e = ENTRIES.get(headingKey(key.type(), key.variant(), bucket + sign * d, profile, arrow));
                    if (e != null && e.ready()) return e;
                }
            }
            Entry type = ENTRIES.get(new Key(key.type(), null, 0, key.spec(), -1));
            if (type != null && type.ready()) return type;
        }
        synchronized (LOCK) { return fallback(key); }
    }

    /** The entry for {@code key}; queues a render (copying {@code source}'s look) when there is none. */
    private static Entry fallback(Key key) {
        for (var e : PREVIOUS.entrySet()) {
            Key k=e.getKey();
            if(k.equals(key)) return e.getValue();
        }
        return Entry.PENDING;
    }

    private static Entry lookup(Key key, @Nullable Entity source) {
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(key);
            if (entry != null) return entry.ready() || entry.failed() ? entry : fallback(key);
            ENTRIES.put(key, Entry.PENDING);
        }
        QUEUE.addLast(new Request(key, source));
        synchronized (LOCK) { return fallback(key); }
    }

    /** The entry for {@code key} without queueing; {@link Entry#PENDING} when unknown. */
    private static Entry peek(Key key) {
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(key);
            return entry == null ? Entry.PENDING : entry;
        }
    }

    private static boolean isBlacklisted(EntityType<?> type) {
        return ClientConfig.isBlacklisted(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    // ---------------------------------------------------------------------------------------------
    // Control

    /**
     * Drops this type's default icon and renders it again ahead of the queue, then hands the new
     * entry to {@code done} on the render thread. Ignores the blacklist: it is an explicit request.
     */
    public static void rerender(EntityType<?> type, @Nullable Consumer<Entry> done) {
        onRenderThread(() -> {
            Key key = typeKey(type, global());
            Entry old;
            synchronized (LOCK) {
                old = ENTRIES.remove(key);
                ENTRIES.put(key, Entry.PENDING);
            }
            release(old);
            QUEUE.removeIf(r -> r.key().equals(key));
            if (done != null) CALLBACKS.computeIfAbsent(key, k -> new ArrayList<>()).add(done);
            QUEUE.addFirst(new Request(key, null));
        });
    }

    /** Runs {@code job} on the render thread at the start of the next frame that has a level. */
    public static void runInFrame(Runnable job) {
        FRAME_JOBS.add(job);
    }

    /**
     * Forgets every icon and frees its texture; the next lookups render them again. Call on resource
     * reload and whenever iconMode, iconSize or rotateWithHeading changes (the cache also notices
     * those by itself). Safe from any thread.
     */
    public static void checkConfig() {
        onRenderThread(() -> {
            List<Object> settings=ClientConfig.iconSettings();
            if(renderedSettings != null && !settings.equals(renderedSettings)) invalidateAll();
            renderedSettings=settings;
        });
    }

    public static void invalidateAll() {
        onRenderThread(() -> {
            clearAll();
            MobIconRenderer.close();
        });
    }

    private static void clearAll() {
        QUEUE.clear();
        List<Entry> old;
        synchronized (LOCK) {
            old = new ArrayList<>();
            ENTRIES.forEach((key,value) -> {
                if(value.ready()) {
                    PREVIOUS.entrySet().removeIf(e -> {
                        Key k=e.getKey();
                        boolean same=k.equals(key);
                        if(same) old.add(e.getValue());
                        return same;
                    });
                    PREVIOUS.put(key,value);
                }
            });
            while(PREVIOUS.size()>MAX_ENTRIES) {var it=PREVIOUS.values().iterator();old.add(it.next());it.remove();}
            ENTRIES.clear();
        }
        old.forEach(MobIconCache::release);
        RESOURCES.entrySet().removeIf(e -> !PREVIOUS.containsKey(e.getValue()));
        FRAMES.clear();
        BASES.values().forEach(Base::close);
        BASES.clear();
        generation++;
        // Pending callbacks get a failure rather than hanging forever.
        CALLBACKS.values().forEach(list -> list.forEach(cb -> cb.accept(Entry.FAILED)));
        CALLBACKS.clear();
    }

    /** Every ready icon as (file stem relative to the dump dir, PNG), sorted by type then stem. */
    static void forEachReady(BiConsumer<Path, byte[]> out) {
        List<Map.Entry<Key, Entry>> ready = new ArrayList<>();
        synchronized (LOCK) {
            for (Map.Entry<Key, Entry> e : ENTRIES.entrySet()) {
                if (e.getValue().png() != null) ready.add(Map.entry(e.getKey(), e.getValue()));
            }
        }
        ready.sort(Comparator.comparing((Map.Entry<Key, Entry> e) -> e.getKey().typeId().toString())
                .thenComparing(e -> e.getKey().fileStem()));
        for (var e : ready) {
            out.accept(Path.of(e.getKey().typeId().getNamespace(), e.getKey().fileStem()), e.getValue().png());
        }
    }

    /**
     * Writes every ready icon to {@code dir/<namespace>/<stem>.png} plus {@code contact_sheet.png}
     * (all of them in one image, listed in {@code contact_sheet.txt}). Returns how many were written.
     */
    public static int dumpAll(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        List<byte[]> pngs = new ArrayList<>();
        forEachReady((stem, png) -> {
            names.add(stem.toString().replace('\\', '/'));
            pngs.add(png);
        });
        for (int i = 0; i < names.size(); i++) {
            Path file = dir.resolve(names.get(i) + ".png");
            Files.createDirectories(file.getParent());
            Files.write(file, pngs.get(i));
        }
        if (!pngs.isEmpty()) {
            ContactSheet.write(dir.resolve("contact_sheet.png"), ContactSheet.decode(pngs), 8, 2);
            Files.write(dir.resolve("contact_sheet.txt"), names);
        }
        return names.size();
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
        Entry evicted;
        while ((evicted = EVICTED.poll()) != null) {
            release(evicted);
            // Stop serving it, unless the same key was rebuilt (same path) since.
            if (evicted.resource() != null) {
                RESOURCES.computeIfPresent(evicted.resource().getPath(), (path, k) -> peek(k).ready() ? k : null);
            }
        }

        Minecraft mc = Minecraft.getInstance();
        // Needs a level to make entities in, a camera the renderers can read, and no reload in flight.
        if (mc.level == null || mc.getEntityRenderDispatcher().camera == null || mc.getOverlay() != null) return;

        if (!ClientConfig.iconSettings().equals(renderedSettings)) {
            if (renderedSettings != null) {
                MobMapIcons.LOG.info("Icon config changed ({} -> {}); re-rendering icons", renderedSettings.get(0),
                        ClientConfig.iconSettings().get(0));
                clearAll();
            }
            renderedSettings = ClientConfig.iconSettings();
        }

        Runnable job;
        while ((job = FRAME_JOBS.poll()) != null) {
            try {
                job.run();
            } catch (Throwable t) {
                MobMapIcons.LOG.error("Mob icon frame job failed", t);
            }
        }

        // Budget of render units per frame; a style that renders the model twice (chibi, head pin) costs two.
        for (int budget = RENDERS_PER_FRAME; budget > 0; ) {
            Request request = QUEUE.pollFirst();
            if (request == null) break;
            Key key = request.key();
            if (peek(key).state() != State.PENDING) continue; // a duplicate; already built
            budget -= key.style().extraRender() ? 2 : 1;
            IconProfile spec = key.spec();
            Entry entry = build(key, request.source(), spec.mode(), spec.size() * 2, spec.rotate());
            synchronized (LOCK) {
                ENTRIES.put(key, entry);
            }
            if (entry.resource() != null) RESOURCES.put(entry.resource().getPath(), key);
            List<Consumer<Entry>> callbacks = CALLBACKS.remove(key);
            if (callbacks != null) callbacks.forEach(cb -> cb.accept(entry));
        }
    }

    private static Entry build(Key key, @Nullable Entity source, IconMode mode, int outSize, boolean rotate) {
        ResourceLocation typeId = key.typeId();
        Minecraft mc = Minecraft.getInstance();
        Entity entity = makeCopy(key.type(), key.variant() == null ? null : source);
        if (entity == null) {
            MobMapIcons.LOG.debug("No icon for {}: could not create an instance", typeId);
            return Entry.FAILED;
        }

        NativeImage image = null;
        NativeImage raw = null, head = null;
        boolean shared = false; // raw/head live in BASES, which owns them
        try {
            // The eight arrow directions of one look share one model render while the model itself does not turn.
            BaseKey baseKey = new BaseKey(key.type(), key.variant(), key.bucket(), key.spec());
            Base base = key.arrow() >= 0 && !rotate ? BASES.get(baseKey) : null;
            if (base != null) {
                raw = base.raw();
                head = base.head();
                shared = true;
            } else {
                MobIconRenderer.Frame frame = null;
                if (rotate && (mode == IconMode.FULL_BODY || !MobIconRenderer.headCropApplies(entity))) {
                    FrameKey fk = new FrameKey(key.type(), key.variant(), outSize);
                    frame = FRAMES.get(fk);
                    if (frame == null) {
                        frame = MobIconRenderer.measureFrame(entity, outSize);
                        if (frame != null) FRAMES.put(fk, frame);
                        MobMapIcons.LOG.debug("Heading frame for {} {}: {} (bb {}x{})", typeId,
                                key.variant() == null ? "default" : key.variant().label(), frame,
                                entity.getBbWidth(), entity.getBbHeight());
                    }
                }
                raw = MobIconRenderer.render(entity, key.style()==IconStyle.CHIBI || key.style()==IconStyle.TOP_DOWN ? IconMode.FULL_BODY : mode, outSize, key.bucket(), key.style()==IconStyle.TOP_DOWN ? null : frame, key.style()==IconStyle.TOP_DOWN);
                if (raw == null) {
                    MobMapIcons.LOG.debug("No icon for {}: it draws nothing", typeId);
                    return Entry.FAILED;
                }
                if (key.style().extraRender()) head = MobIconRenderer.render(entity, IconMode.HEAD, outSize, key.bucket(), null);
                if (key.arrow() >= 0 && !rotate) {
                    BASES.put(baseKey, new Base(raw, head));
                    shared = true;
                }
            }
            image = IconStyles.apply(key.style(), raw, head, entity, key.arrow());
            byte[] png = image.asByteArray();
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MobMapIcons.MODID, "icons/"
                    + typeId.getNamespace() + "/" + typeId.getPath() + "/"
                    + (key.variant() == null ? "default" : key.variant().hash()) + "_" + key.bucket()
                    + (key.arrow() < 0 ? "" : "a" + key.arrow()) + "_" + (textureSerial++));
            DynamicTexture texture = new DynamicTexture(image); // takes ownership of image
            image = null;
            mc.getTextureManager().register(id, texture);
            return new Entry(State.READY, id, png, resourceLocation(key));
        } catch (Throwable t) {
            MobMapIcons.LOG.warn("No icon for {}: rendering it failed", typeId, t);
            return Entry.FAILED;
        } finally {
            if (image != null) image.close();
            if (!shared) {
                if (raw != null) raw.close();
                if (head != null) head.close();
            }
            IconVariant.discard(entity);
        }
    }

    /** Resource-pack location of a key's icon; the type default keeps the 0.1.0 path. */
    private static ResourceLocation resourceLocation(Key key) {
        ResourceLocation typeId = key.typeId();
        String path = RESOURCE_PREFIX + typeId.getNamespace() + "/" + typeId.getPath();
        path += "/" + key.style().name().toLowerCase(java.util.Locale.ROOT) + "_" + key.spec().mode().name().toLowerCase(java.util.Locale.ROOT) + key.spec().size() + (key.spec().rotate() ? "r" : "") + "_g" + generation;
        if (key.variant() != null || key.bucket() != 0 || key.arrow() >= 0) {
            path += "/" + (key.variant() == null ? "default" : key.variant().hash()) + "_" + key.bucket()
                    + (key.arrow() < 0 ? "" : "_a" + key.arrow()) + "_g" + generation;
        }
        return ResourceLocation.fromNamespaceAndPath(MobMapIcons.MODID, path + ".png");
    }

    /**
     * The instance to draw: a copy of {@code source}'s look placed at its position, or a fresh
     * instance of {@code type} at the player's position. Never added to the level. Render thread.
     */
    @Nullable
    static Entity makeCopy(EntityType<?> type, @Nullable Entity source) {
        Entity copy = source == null ? null : IconVariant.copyOf(source);
        Vec3 at;
        if (copy != null) {
            at = source.position();
        } else {
            if (source != null) MobMapIcons.LOG.debug("Icon of {} falls back to the type's default look", source);
            copy = IconVariant.fresh(type);
            if (copy == null) return null;
            Minecraft mc = Minecraft.getInstance();
            at = mc.player != null ? mc.player.position() : mc.gameRenderer.getMainCamera().getPosition();
        }
        copy.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        return copy;
    }

    /**
     * Renders {@code source}'s look (or {@code type}'s default when {@code source} is null) outside the
     * cache, e.g. for the self-test and commands. Render thread.
     *
     * @param sharedFrame use the rotation-independent scale, as the cache does with rotateWithHeading on
     */
    @Nullable
    public static NativeImage renderOnce(EntityType<?> type, @Nullable Entity source, IconMode mode, int outSize,
                                         int bucket, boolean sharedFrame) {
        Entity entity = makeCopy(type, source);
        if (entity == null) return null;
        try {
            MobIconRenderer.Frame frame = null;
            if (sharedFrame && (mode == IconMode.FULL_BODY || !MobIconRenderer.headCropApplies(entity))) {
                frame = MobIconRenderer.measureFrame(entity, outSize);
            }
            return MobIconRenderer.render(entity, mode, outSize, bucket, frame);
        } finally {
            IconVariant.discard(entity);
        }
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
