package dev.watdafawx.mobmapicons.compat.journeymap;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Farm mode and the icon cap for JourneyMap, computed from the world rather than from the radar
 * events: JourneyMap's radar event carries one entity at a time and no screen position, so the
 * adapter looks at every nearby mob itself, a few times a second, and answers per entity.
 *
 * <p>Same-kind mobs closer than about {@code clusterRadius} screen pixels at JourneyMap's current zoom
 * ({@code UIState.blockSize}) are chained into one group; a chain wider than three cells (a whole
 * field) is split back into square cells two radii wide. A group of at
 * least {@code clusterMinCount} keeps one representative (the mob nearest the group's centre) and
 * hides the rest. "Same kind" is the entity type plus the variant the icon API resolves to, so a
 * red sheep and a white sheep never merge. Named, leashed, tamed, ridden-for-a-owner and boss mobs
 * are never merged. Then {@code maxIcons} keeps the nearest N of what is left.
 */
final class FarmClusters {

    /** What to do with one mob: {@code hidden}, or shown standing for {@code count} mobs (1 = just itself). */
    record Verdict(boolean hidden, int count) {
        static final Verdict SHOW = new Verdict(false, 1);
        static final Verdict HIDE = new Verdict(true, 0);
    }

    private static final long REBUILD_NANOS = 250_000_000L;
    private static final Pattern VARIANT_SUFFIX = Pattern.compile("_[0-9]+(_a[0-9]+)?_g[0-9]+[.]png$");

    private Map<Integer, Verdict> verdicts = Map.of();
    private long builtAt;
    private Object builtLevel;
    private double builtBlockSize = -1;
    private long builtConfig;

    synchronized Verdict verdict(Entity entity, double blockSize) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return Verdict.SHOW;
        long config = configSignature();
        long now = System.nanoTime();
        if (builtLevel != mc.level || config != builtConfig || Math.abs(blockSize - builtBlockSize) > 0.01
                || now - builtAt > REBUILD_NANOS) {
            verdicts = build(mc, blockSize);
            builtAt = now;
            builtLevel = mc.level;
            builtBlockSize = blockSize;
            builtConfig = config;
        }
        return verdicts.getOrDefault(entity.getId(), Verdict.SHOW);
    }

    private static long configSignature() {
        return (ClientConfig.farmMode() ? 1L : 0L) | ((long) ClientConfig.maxIcons() << 1)
                | ((long) ClientConfig.clusterRadius() << 20) | ((long) ClientConfig.clusterMinCount() << 28);
    }

    /**
     * World-space edge of one cluster cell, in blocks. {@code UIState.blockSize} is JourneyMap's zoom as
     * "pixels per block" in GUI pixels (measured: about 1.3 GUI pixels per unit at GUI scale 2). The
     * cell is two cluster radii wide, so mobs that would overlap across a cell border are rare.
     */
    static double cellSize(double blockSize) {
        double edge = ClientConfig.clusterRadius() * 2.0;
        if (!(blockSize > 0.05)) return Math.max(3.0, edge / 6.0); // zoom unknown: 4 blocks at the default radius 12
        return Math.max(3.0, Math.min(64.0, edge / blockSize));
    }

    private Map<Integer, Verdict> build(Minecraft mc, double blockSize) {
        boolean farm = ClientConfig.farmMode();
        int max = ClientConfig.maxIcons();
        if (!farm && max <= 0) return Map.of();

        List<Mob> mobs = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Mob m && m.isAlive()
                    && !ClientConfig.isBlacklisted(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()))) {
                mobs.add(m);
            }
        }
        Map<Integer, Verdict> out = new HashMap<>();
        List<Mob> visible = new ArrayList<>(mobs.size());

        if (farm) {
            double cell = cellSize(blockSize);
            double link = cell / 2.0; // two mobs closer than this (about clusterRadius pixels) are chained
            int min = ClientConfig.clusterMinCount();
            Map<String, List<Mob>> kinds = new HashMap<>();
            for (Mob m : mobs) {
                if (!mergeable(m)) {
                    visible.add(m);
                    continue;
                }
                String key = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()) + "|" + variantOf(m);
                kinds.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
            for (List<Mob> kind : kinds.values()) {
                for (List<Mob> group : components(kind, link)) {
                    // A chain much wider than a cell (a whole field of sheep) would pick a far-off
                    // representative: split it back into plain cells.
                    if (group.size() >= min && extent(group) > 3.0 * cell) {
                        Map<String, List<Mob>> cells = new HashMap<>();
                        for (Mob m : group) {
                            cells.computeIfAbsent(Math.floor(m.getX() / cell) + "," + Math.floor(m.getZ() / cell),
                                    k -> new ArrayList<>()).add(m);
                        }
                        cells.values().forEach(c -> merge(c, min, out, visible));
                    } else {
                        merge(group, min, out, visible);
                    }
                }
            }
        } else {
            visible.addAll(mobs);
        }

        int shown = visible.size();
        if (max > 0 && visible.size() > max) {
            double px = mc.player.getX(), pz = mc.player.getZ();
            visible.sort(Comparator.comparingDouble(m -> (m.getX() - px) * (m.getX() - px) + (m.getZ() - pz) * (m.getZ() - pz)));
            for (int i = max; i < visible.size(); i++) out.put(visible.get(i).getId(), Verdict.HIDE);
            shown = max;
        }
        String summary = mobs.size() + " mobs, " + shown + " shown, " + (mobs.size() - shown)
                + " merged away or capped, cell " + String.format("%.1f", cellSize(blockSize)) + " blocks at blockSize " + blockSize;
        if (!summary.equals(lastSummary)) {
            lastSummary = summary;
            MobMapIcons.LOG.debug("journeymap farm: {}", summary);
        }
        return out;
    }

    private String lastSummary = "";

    /** One group becomes a single representative (nearest its centre) when it is big enough. */
    private static void merge(List<Mob> group, int min, Map<Integer, Verdict> out, List<Mob> visible) {
        if (group.size() < min) {
            visible.addAll(group);
            return;
        }
        double cx = 0, cz = 0;
        for (Mob m : group) { cx += m.getX(); cz += m.getZ(); }
        cx /= group.size();
        cz /= group.size();
        Mob rep = group.get(0);
        double best = Double.MAX_VALUE;
        for (Mob m : group) {
            double d = (m.getX() - cx) * (m.getX() - cx) + (m.getZ() - cz) * (m.getZ() - cz);
            if (d < best) { best = d; rep = m; }
        }
        for (Mob m : group) {
            if (m != rep) out.put(m.getId(), Verdict.HIDE);
        }
        out.put(rep.getId(), new Verdict(false, group.size()));
        visible.add(rep);
    }

    /** Connected components of {@code mobs} where two mobs less than {@code link} blocks apart (XZ) are joined. */
    private static List<List<Mob>> components(List<Mob> mobs, double link) {
        int n = mobs.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Mob m = mobs.get(i);
            long cx = (long) Math.floor(m.getX() / link), cz = (long) Math.floor(m.getZ() / link);
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<Integer> near = grid.get(((cx + dx) << 32) ^ ((cz + dz) & 0xFFFFFFFFL));
                    if (near == null) continue;
                    for (int j : near) {
                        Mob o = mobs.get(j);
                        double ddx = o.getX() - m.getX(), ddz = o.getZ() - m.getZ();
                        if (ddx * ddx + ddz * ddz < link * link) union(parent, i, j);
                    }
                }
            }
            grid.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(i);
        }
        Map<Integer, List<Mob>> byRoot = new HashMap<>();
        for (int i = 0; i < n; i++) byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(mobs.get(i));
        return new ArrayList<>(byRoot.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /** Longest side of the group's XZ bounding box, in blocks. */
    private static double extent(List<Mob> group) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Mob m : group) {
            minX = Math.min(minX, m.getX()); maxX = Math.max(maxX, m.getX());
            minZ = Math.min(minZ, m.getZ()); maxZ = Math.max(maxZ, m.getZ());
        }
        return Math.max(maxX - minX, maxZ - minZ);
    }

    /** The icon API's variant for this mob: its resource path minus the heading and generation suffix. */
    private static String variantOf(Mob m) {
        var res = MobIconApi.getResourceIcon(m, MobIconApi.yawBucket(m.yBodyRot), ClientConfig.iconStyle(), false);
        return res == null ? "?" : VARIANT_SUFFIX.matcher(res.getPath()).replaceFirst("");
    }

    /** Never merge a mob somebody cares about individually. */
    static boolean mergeable(Mob m) {
        if (m.hasCustomName() || m.isLeashed() || m.isPassenger() || m.isVehicle()) return false;
        if (m instanceof TamableAnimal t && t.isTame()) return false;
        if (m instanceof AbstractHorse h && h.isTamed()) return false;
        if (m instanceof OwnableEntity o && o.getOwnerUUID() != null) return false;
        if (m instanceof EnderDragon || m instanceof WitherBoss) return false;
        return !m.getType().is(Tags.EntityTypes.BOSSES);
    }
}
