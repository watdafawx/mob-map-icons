package dev.watdafawx.mobmapicons.api;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.core.MobIconCache;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * The one read API adapters use: which map icon, if any, an entity (or entity type) resolves to.
 *
 * <p>Adapters must go through this and never through {@code core/} directly. The contract stays
 * stable while the implementation under it changes: {@code null} means "draw nothing special", which
 * is what a blacklisted type, a type that cannot be drawn and a not-yet-built icon all return.
 *
 * <p>Icons are rendered lazily from the mob's own model and texture: the first lookup queues a render
 * and returns {@code null} (or a stand-in, see below), and a later lookup (usually a frame or two
 * later) returns the texture. Every method is safe to call from any thread and cheap enough to call
 * every frame for every visible mob.
 *
 * <h2>Variants</h2>
 * The {@link Entity} lookups render the <em>actual</em> entity: a red sheep, a black cat, a baby cow,
 * a big slime and a librarian villager each get their own icon. Internally the icon is keyed by the
 * entity type plus a variant key (the renderer's texture, baby or not, a coarse size step and a few
 * vanilla layer properties such as wool colour, villager profession and horse markings), and drawn
 * from a throwaway copy of the entity's synced state, so the real entity is never touched. The
 * {@link EntityType} lookups are the per-type default (a freshly created instance).
 *
 * <h2>Headings</h2>
 * An entity icon can be drawn facing one of {@link #YAW_BUCKETS} directions. Buckets are rendered
 * lazily, one per request, never all at once.
 * <ul>
 *   <li>Bucket {@code 0}: the mob faces the viewer (towards the bottom of a north-up map, i.e.
 *       south), as {@link #getIcon(Entity)} always draws it.</li>
 *   <li>Each step adds 45 degrees <b>clockwise as seen on the map</b>: 2 faces screen-left (west on
 *       a north-up map), 4 faces away from the viewer (north, the mob's back), 6 faces screen-right
 *       (east).</li>
 * </ul>
 * Minecraft's {@code yRot} grows the same way (0 = south, 90 = west, 180 = north, 270 = east), so:
 * <pre>
 *   relative = entity.getYRot() - (upYaw - 180)
 *   bucket   = MobIconApi.yawBucket(relative)
 * </pre>
 * where {@code upYaw} is the Minecraft yaw that points to the top of the map on screen. North-up map
 * (FTB Chunks large map, Xaero/JourneyMap fullscreen): {@code upYaw = 180}, so
 * {@code bucket = yawBucket(entity.getYRot())}. Minimap that rotates with the player (player's look
 * direction is up): {@code upYaw = player.getYRot()}, so
 * {@code bucket = yawBucket(entity.getYRot() - player.getYRot() + 180)}. Living mobs turn their body
 * separately from their head; {@code LivingEntity.yBodyRot} is usually the better heading. Every
 * bucket keeps the same fixed three-quarter camera (the model turned 30 degrees towards screen-left
 * and seen from 12 degrees above), and with {@link #rotateWithHeading()} on, all buckets of one mob
 * share the same scale so the icon does not grow and shrink as it turns.
 *
 * <h2>Stand-ins</h2>
 * While the exact icon for an entity/bucket is still rendering, the entity lookups return the closest
 * one that is ready (same variant at another bucket, then the type default) rather than {@code null},
 * so a map does not flicker back to its own marker when a mob turns or is first seen.
 *
 * <p>Icons are {@code 2 * iconSize()} pixels square, registered with the client texture manager
 * under {@code mob_map_icons:icons/<namespace>/<path>/...}. A texture id stays valid until the icon is
 * evicted (the cache holds {@value MobIconCache#MAX_ENTRIES} icons, least recently used out first) or
 * {@link #invalidateAll()} runs, so look the id up again each frame instead of keeping it.
 */
public final class MobIconApi {

    /** How many facing directions an icon can be rendered at: one every 45 degrees. */
    public static final int YAW_BUCKETS = 8;

    private MobIconApi() {}

    // ---------------------------------------------------------------------------------------------
    // Per type (the type's default look)

    /**
     * The default icon for this entity type, or {@code null} when it should keep the map mod's own
     * marker. Drawn from a freshly created instance, so it shows the type's default variant facing
     * the viewer (bucket 0).
     *
     * @param type the entity type being drawn; never null
     * @return texture id of the icon, or {@code null} for "no icon (yet)"
     */
    @Nullable
    public static ResourceLocation getIcon(EntityType<?> type) {
        return MobIconCache.getIcon(type);
    }

    /** {@code true} when {@link #getIcon(EntityType)} would return a texture right now. Does not queue. */
    public static boolean isReady(EntityType<?> type) {
        return MobIconCache.isReady(type);
    }

    /**
     * The type's default icon as PNG bytes (for map mods that load icons from a resource pack), or
     * {@code null} when it is not ready or the type is blacklisted. Does not queue a render; call
     * {@link #getIcon(EntityType)} for that.
     */
    public static byte @Nullable [] getPng(EntityType<?> type) {
        return MobIconCache.getPng(type);
    }

    /**
     * ResourceManager-readable location of the type's default icon, for map mods (JourneyMap, Xaero)
     * that load textures through the resource manager rather than a GPU texture id. Served by this
     * mod's built-in resource pack at {@code mob_map_icons:textures/mob_map_icons/<namespace>/<path>.png}.
     * Queues a render like {@link #getIcon(EntityType)}; {@code null} until ready or when blacklisted.
     */
    @Nullable
    public static ResourceLocation getResourceIcon(EntityType<?> type) {
        return MobIconCache.getResourceIcon(type);
    }

    // ---------------------------------------------------------------------------------------------
    // Per entity (its real variant), facing the viewer

    /**
     * The icon for this entity's real look (colour, variant, baby, size), facing the viewer
     * (bucket 0). Same as {@link #getIcon(Entity, int) getIcon(entity, 0)}.
     */
    @Nullable
    public static ResourceLocation getIcon(Entity entity) {
        return MobIconCache.getIcon(entity, 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Per entity, at a heading

    /**
     * The icon for this entity's real look, facing heading bucket {@code yawBucket} (see the class
     * javadoc for the convention and the formula from {@code yRot}). Renders that bucket lazily on
     * first request; until then returns the closest ready stand-in, or {@code null} if there is none.
     *
     * @param entity    the entity being drawn; read only, never modified
     * @param yawBucket {@code 0..YAW_BUCKETS-1}; other values wrap around
     */
    @Nullable
    public static ResourceLocation getIcon(Entity entity, int yawBucket) {
        return MobIconCache.getIcon(entity, yawBucket);
    }

    /**
     * PNG bytes of the icon {@link #getIcon(Entity, int)} would return right now (exact or stand-in),
     * or {@code null}. Does not queue a render.
     */
    public static byte @Nullable [] getPng(Entity entity, int yawBucket) {
        return MobIconCache.getPng(entity, yawBucket);
    }

    /**
     * ResourceManager-readable location of the icon {@link #getIcon(Entity, int)} would return (exact
     * or stand-in), served by this mod's built-in resource pack under
     * {@code mob_map_icons:textures/mob_map_icons/<namespace>/<path>/<variant>_<bucket>.png}. Queues a
     * render like {@link #getIcon(Entity, int)}. The bytes behind one location never change while it
     * is cached, so a map mod may keep the texture it loaded from it.
     */
    @Nullable
    public static ResourceLocation getResourceIcon(Entity entity, int yawBucket) {
        return MobIconCache.getResourceIcon(entity, yawBucket);
    }

    /**
     * Maps any yaw in degrees (relative to the map's "facing the viewer" direction, see the class
     * javadoc) to a bucket {@code 0..YAW_BUCKETS-1}: the nearest 45-degree step, so bucket 0 covers
     * {@code -22.5 <= relative < 22.5}. NaN and infinities map to 0.
     */
    public static int yawBucket(float relativeYawDegrees) {
        if (!Float.isFinite(relativeYawDegrees)) return 0;
        float step = 360.0F / YAW_BUCKETS;
        return Math.floorMod(Mth.floor(relativeYawDegrees / step + 0.5F), YAW_BUCKETS);
    }

    /**
     * Whether adapters should turn icons to the mob's heading (config {@code rotateWithHeading}).
     * When {@code false}, adapters should always ask for bucket 0.
     */
    public static boolean rotateWithHeading() {
        return ClientConfig.rotateWithHeading();
    }

    // ---------------------------------------------------------------------------------------------
    // Misc

    /**
     * Throws every icon away; they are rendered again on demand. Runs on resource reload and on an
     * iconMode / iconSize / rotateWithHeading change by itself; adapters that cache icons should
     * re-query afterwards.
     */
    public static void invalidateAll() {
        MobIconCache.invalidateAll();
    }

    /** Edge length in pixels of every icon this mod hands out, after config. Textures are twice this. */
    public static int iconSize() {
        return ClientConfig.iconSize();
    }
}
