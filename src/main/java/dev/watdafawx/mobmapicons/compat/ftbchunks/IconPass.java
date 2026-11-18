package dev.watdafawx.mobmapicons.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.mapicon.EntityMapIcon;
import dev.ftb.mods.ftblibrary.util.client.ClientUtils;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * One pass over FTB's freshly built icon list: swaps mob heads for our icons, folds crowds into
 * {@link ClusterIcon}s (farm mode) and caps the number of mob icons (maxIcons).
 *
 * <p><b>Cells.</b> A group is every mob of one entity type and one icon texture (so one look) inside a
 * square cell of {@code clusterRadius} <i>screen</i> pixels. The cell is converted to blocks with the
 * map's current pixels per block, so zooming in shrinks the cell in world space and groups split by
 * themselves. FTB only builds the list when it is asked to, so {@link #stale} lets the adapter spot a
 * zoom or config change and ask for a rebuild.
 *
 * <p><b>Cost.</b> One O(n) walk plus a hash lookup per mob. All working storage is reused between
 * passes; only the icons that end up on the map are allocated.
 */
final class IconPass {

    private static final class Mob {
        int slot;
        Entity entity;
        MapIcon ftbIcon;
        double distSq;
        boolean drop;
    }

    private static final class Group {
        final ArrayList<Mob> members = new ArrayList<>();
    }

    /** Hash key: cell + entity type + icon texture. One mutable probe instance serves every lookup. */
    private static final class Key {
        int cx, cz;
        EntityType<?> type;
        ResourceLocation texture;

        Key copy() {
            Key k = new Key();
            k.cx = cx;
            k.cz = cz;
            k.type = type;
            k.texture = texture;
            return k;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.cx == cx && k.cz == cz && k.type == type && k.texture.equals(texture);
        }

        @Override
        public int hashCode() {
            return ((cx * 31 + cz) * 31 + System.identityHashCode(type)) * 31 + texture.hashCode();
        }
    }

    // Reused between passes (the render thread is the only caller).
    private static final ArrayList<Mob> MOBS = new ArrayList<>();
    private static final ArrayList<Group> GROUPS = new ArrayList<>();
    private static final HashMap<Key, Group> CELLS = new HashMap<>();
    private static final Key PROBE = new Key();
    private static int mobCount;
    private static int groupCount;
    private static boolean[] dropSlot = new boolean[0];
    private static double[] distScratch = new double[0];

    /** What the last pass of each map type (0 minimap, 1 large map) was built from; see {@link #stale}. */
    private static final double[] BUILT_PX = {Double.NaN, Double.NaN};
    private static final int[] BUILT_CFG = {0, 0};

    /** Perf counters since the last log line, see {@link FtbPerf}. */
    static long passNanos;
    static int passCount;
    static int lastIn, lastOut;

    private IconPass() {}

    // ---------------------------------------------------------------------------------------------
    // Zoom / config signature

    /** GUI pixels per block of this map right now, or -1 when it cannot be known. */
    static double pixelsPerBlock(MapType type) {
        try {
            if (type.isLargeMap()) {
                LargeMapScreen screen = ClientUtils.getCurrentGuiAs(LargeMapScreen.class);
                // A region is 512 blocks and getRegionTileSize() pixels wide.
                return screen == null ? -1 : screen.getRegionTileSize() / 512.0;
            }
            if (type.isMinimap()) {
                Minecraft mc = Minecraft.getInstance();
                double userScale = FTBChunksClientConfig.MINIMAP_SCALE.get();
                double scale;
                if (FTBChunksClientConfig.MINIMAP_PROPORTIONAL.get()) {
                    // FTB: s = 4/guiScale; s *= (width/10)/(s*64) * userScale  ==  width/640 * userScale
                    scale = mc.getWindow().getGuiScaledWidth() / 640.0 * userScale;
                } else {
                    scale = userScale * 4.0 / mc.getWindow().getGuiScale();
                }
                // FTB draws at distance * scale * (zoom / 3.5); use the target zoom, not the animated one.
                return scale * (FTBChunksClientConfig.MINIMAP_ZOOM.get() / 3.5);
            }
        } catch (LinkageError | RuntimeException e) {
            // an FTB version without these fields: no clustering, icons still work
        }
        return -1;
    }

    private static int configHash() {
        return Objects.hash(ClientConfig.farmMode(), ClientConfig.clusterRadius(), ClientConfig.clusterMinCount(),
                ClientConfig.maxIcons());
    }

    /** {@code true} when the zoom or config the last pass used no longer matches, so FTB should rebuild. */
    static boolean stale(MapType type) {
        int i = type.isLargeMap() ? 1 : 0;
        double now = pixelsPerBlock(type);
        double built = BUILT_PX[i];
        boolean pxChanged = Double.isNaN(built) || (now <= 0) != (built <= 0)
                || (now > 0 && Math.abs(now - built) > 0.005 * built);
        return pxChanged || BUILT_CFG[i] != configHash();
    }

    // ---------------------------------------------------------------------------------------------
    // The pass

    static void process(MapType type, List<MapIcon> icons) {
        long t0 = System.nanoTime();
        Player player = Minecraft.getInstance().player;
        int n = icons.size();
        if (player == null || n == 0) {
            return;
        }
        int ti = type.isLargeMap() ? 1 : 0;
        double px = pixelsPerBlock(type);
        BUILT_PX[ti] = px;
        BUILT_CFG[ti] = configHash();
        boolean farm = ClientConfig.farmMode() && px > 0;
        double invCell = px / ClientConfig.clusterRadius(); // cells per block
        int minCount = ClientConfig.clusterMinCount();
        int maxIcons = ClientConfig.maxIcons();

        if (dropSlot.length < n) {
            dropSlot = new boolean[Math.max(n, dropSlot.length * 2)];
        } else {
            Arrays.fill(dropSlot, 0, n, false);
        }
        mobCount = 0;
        groupCount = 0;
        CELLS.clear();

        // 1. Swap every mob icon for ours and sort the mergeable ones into cells.
        for (int i = 0; i < n; i++) {
            if (!(icons.get(i) instanceof EntityMapIcon ftb)) {
                continue;
            }
            Entity e = FtbChunksAdapter.entityOf(ftb);
            if (e == null || e instanceof Player) {
                continue; // FTB draws player faces itself and the local player is never touched
            }
            Mob m = mob();
            m.slot = i;
            m.entity = e;
            m.drop = false;
            m.distSq = e.distanceToSqr(player);
            m.ftbIcon = ftb;
            if (ClientConfig.isBlacklisted(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))) {
                continue; // keep FTB's own icon for this type
            }
            icons.set(i, new OverrideIcon(ftb, e));
            if (!farm || !mergeable(e)) {
                continue;
            }
            ResourceLocation tex = MobIconApi.getIcon(e, 0);
            if (tex == null) {
                continue; // still rendering: stays its own icon until the next pass
            }
            PROBE.cx = (int) Math.floor(e.getX() * invCell);
            PROBE.cz = (int) Math.floor(e.getZ() * invCell);
            PROBE.type = e.getType();
            PROBE.texture = tex;
            Group g = CELLS.get(PROBE);
            if (g == null) {
                g = group();
                CELLS.put(PROBE.copy(), g);
            }
            g.members.add(m);
        }

        // 2. Fold big enough groups into one cluster icon at the first member's slot.
        if (farm) {
            for (int gi = 0; gi < groupCount; gi++) {
                Group g = GROUPS.get(gi);
                int size = g.members.size();
                if (size < minCount) {
                    continue;
                }
                double cx = 0, cy = 0, cz = 0;
                for (int k = 0; k < size; k++) {
                    Entity e = g.members.get(k).entity;
                    cx += e.getX();
                    cy += e.getY();
                    cz += e.getZ();
                }
                cx /= size;
                cy /= size;
                cz /= size;
                int repIdx = 0;
                double best = Double.MAX_VALUE;
                Entity[] arr = new Entity[size];
                for (int k = 0; k < size; k++) {
                    Entity e = g.members.get(k).entity;
                    arr[k] = e;
                    double dx = e.getX() - cx, dz = e.getZ() - cz;
                    double d = dx * dx + dz * dz;
                    if (d < best) {
                        best = d;
                        repIdx = k;
                    }
                }
                Mob first = g.members.get(0);
                icons.set(first.slot, new ClusterIcon(g.members.get(repIdx).ftbIcon, arr, repIdx, cx, cy, cz));
                double dx = cx - player.getX(), dy = cy - player.getY(), dz = cz - player.getZ();
                first.distSq = dx * dx + dy * dy + dz * dz;
                for (int k = 1; k < size; k++) {
                    g.members.get(k).drop = true;
                }
            }
        }

        // 3. Cap: keep the nearest maxIcons icons (a cluster counts once, at its centroid).
        if (maxIcons > 0) {
            int units = 0;
            for (int k = 0; k < mobCount; k++) {
                if (!MOBS.get(k).drop) units++;
            }
            if (units > maxIcons) {
                if (distScratch.length < units) {
                    distScratch = new double[Math.max(units, distScratch.length * 2)];
                }
                int c = 0;
                for (int k = 0; k < mobCount; k++) {
                    Mob m = MOBS.get(k);
                    if (!m.drop) distScratch[c++] = m.distSq;
                }
                Arrays.sort(distScratch, 0, units);
                double cutoff = distScratch[maxIcons - 1];
                int kept = 0;
                for (int k = 0; k < mobCount; k++) {
                    Mob m = MOBS.get(k);
                    if (m.drop) continue;
                    if (m.distSq > cutoff) {
                        m.drop = true;
                    } else {
                        kept++;
                    }
                }
                for (int k = 0; k < mobCount && kept > maxIcons; k++) { // trim ties at the cutoff
                    Mob m = MOBS.get(k);
                    if (!m.drop && m.distSq == cutoff) {
                        m.drop = true;
                        kept--;
                    }
                }
            }
        }

        // 4. Remove what was folded or capped, in place.
        boolean any = false;
        for (int k = 0; k < mobCount; k++) {
            Mob m = MOBS.get(k);
            if (m.drop) {
                dropSlot[m.slot] = true;
                any = true;
            }
        }
        int out = n;
        if (any) {
            int w = 0;
            for (int i = 0; i < n; i++) {
                if (!dropSlot[i]) {
                    if (w != i) icons.set(w, icons.get(i));
                    w++;
                }
            }
            icons.subList(w, n).clear();
            out = w;
        }
        // Do not keep entities alive through the pools between passes.
        for (int k = 0; k < mobCount; k++) {
            Mob m = MOBS.get(k);
            m.entity = null;
            m.ftbIcon = null;
        }
        CELLS.clear();
        for (int gi = 0; gi < groupCount; gi++) GROUPS.get(gi).members.clear();

        lastIn = n;
        lastOut = out;
        passNanos += System.nanoTime() - t0;
        passCount++;
    }

    /** Named, leashed, tamed and boss mobs stay individual. */
    private static boolean mergeable(Entity e) {
        if (e.hasCustomName()) return false;
        if (e instanceof Leashable l && l.isLeashed()) return false;
        if (e instanceof OwnableEntity o && o.getOwnerUUID() != null) return false;
        return !(e instanceof WitherBoss || e instanceof EnderDragon || e.getType().is(Tags.EntityTypes.BOSSES));
    }

    private static Mob mob() {
        if (mobCount == MOBS.size()) MOBS.add(new Mob());
        return MOBS.get(mobCount++);
    }

    private static Group group() {
        if (groupCount == GROUPS.size()) GROUPS.add(new Group());
        return GROUPS.get(groupCount++);
    }
}
