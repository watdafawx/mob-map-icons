package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Dev-only render check, on with {@code -Dmobmapicons.selftest=true} (the {@code runSelftest} run).
 *
 * <p>Makes a flat scratch world, then:
 * <ol>
 *   <li>asks the cache for the default icon of a handful of awkward types (huge, tiny, wide, tall);</li>
 *   <li>summons real variant mobs (cow and baby cow, white/red/blue sheep, black and tabby cats, a
 *       librarian, a big slime) and asks for their per-entity icons, plus all
 *       {@link MobIconApi#YAW_BUCKETS} headings of the cow, and checks the variants differ, the
 *       buckets differ and nothing leaked into the world (entity count, the real mobs untouched);</li>
 *   <li>writes contact sheets for eyeballing ({@code selftest_*.png});</li>
 *   <li>reloads resources and checks the cache was cleared and refills.</li>
 * </ol>
 * Everything goes to {@code <gamedir>/mob_map_icons_dump/}, then the game quits. The log line
 * {@code selftest: finished} says whether every check passed.
 */
final class MobIconSelfTest {

    static final String PROPERTY = "mobmapicons.selftest";
    private static final String WORLD = "mobmapicons-selftest";
    private static final List<EntityType<?>> TYPES = List.of(
            EntityType.ZOMBIE, EntityType.CREEPER, EntityType.ENDER_DRAGON, EntityType.BAT,
            EntityType.IRON_GOLEM, EntityType.COW, EntityType.CHICKEN, EntityType.SLIME, EntityType.PLAYER);
    /** Types shown in HEAD mode on {@code selftest_heads.png}. */
    private static final List<EntityType<?>> HEAD_TYPES = List.of(
            EntityType.ZOMBIE, EntityType.CREEPER, EntityType.SKELETON, EntityType.ENDERMAN, EntityType.VILLAGER,
            EntityType.IRON_GOLEM, EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN,
            EntityType.WOLF, EntityType.CAT, EntityType.HORSE, EntityType.SPIDER, EntityType.SLIME,
            EntityType.BAT, EntityType.ENDER_DRAGON, EntityType.PHANTOM, EntityType.GHAST, EntityType.SQUID);
    private static final String TAGS = "NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Silent:1b";
    private static final int MOB_Y = -60;
    private static final int MOB_Z = 6;

    /** Variant mobs: label, type, extra NBT. Summoned along x at MOB_Z, 3 blocks apart. */
    private static final String[][] MOBS = {
            {"cow", "cow", ""},
            {"cow_baby", "cow", "Age:-24000"},
            {"sheep_white", "sheep", "Color:0b"},
            {"sheep_red", "sheep", "Color:14b"},
            {"sheep_blue", "sheep", "Color:11b"},
            {"cat_black", "cat", "variant:\"minecraft:black\""},
            {"cat_tabby", "cat", "variant:\"minecraft:tabby\""},
            {"villager_librarian", "villager", "VillagerData:{profession:\"minecraft:librarian\",type:\"minecraft:plains\",level:2}"},
            {"slime_big", "slime", "Size:3"},
    };
    private static final int TIMEOUT_TICKS = 20 * 30;

    private enum Phase { TITLE, LOADING, RENDERING, SUMMONING, VARIANTS, EXTRAS, RELOADING, RERENDERING, QUITTING, DONE }

    private static Phase phase = Phase.TITLE;
    private static int ticks;
    private static volatile boolean extrasDone;
    private static volatile boolean reloadDone;
    private static boolean pass = true;
    /** label -> the client-side entity summoned for it. */
    private static final Map<String, Entity> FOUND = new LinkedHashMap<>();
    private static int entitiesBefore;
    private static final Map<String, String> STATE_BEFORE = new LinkedHashMap<>();

    private MobIconSelfTest() {}

    static void enable() {
        MobMapIcons.LOG.info("selftest: enabled");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        switch (phase) {
            case TITLE -> {
                if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen && mc.getOverlay() == null) {
                    // First run of a fresh run dir: skip the Narrator prompt so the title screen shows.
                    mc.options.onboardAccessibility = false;
                    mc.options.save();
                    mc.setScreen(new TitleScreen());
                }
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
                    mc.options.pauseOnLostFocus = false;
                    createScratchWorld(mc);
                    next(Phase.LOADING);
                }
            }
            case LOADING -> {
                if (mc.level != null && mc.player != null && ticks > 40) {
                    log("world loaded, requesting icons (" + ClientConfig.iconMode() + ", " + ClientConfig.iconSize()
                            + "px, rotateWithHeading=" + ClientConfig.rotateWithHeading() + ")");
                    TYPES.forEach(MobIconApi::getIcon);
                    var conn = mc.player.connection;
                    conn.sendCommand("tp @s 0.5 " + MOB_Y + " 0.5 0 20");
                    conn.sendCommand("time set 6000");
                    for (int i = 0; i < MOBS.length; i++) {
                        String nbt = TAGS + (MOBS[i][2].isEmpty() ? "" : "," + MOBS[i][2]);
                        conn.sendCommand("summon minecraft:" + MOBS[i][1] + " " + mobX(i) + " " + MOB_Y + " "
                                + (MOB_Z + 0.5) + " {" + nbt + "}");
                    }
                    next(Phase.RENDERING);
                }
            }
            case RENDERING -> {
                TYPES.forEach(MobIconApi::getIcon);
                boolean settled = TYPES.stream().allMatch(t -> MobIconCache.isReady(t) || MobIconCache.isFailed(t));
                if (settled || ticks > TIMEOUT_TICKS) {
                    report();
                    next(Phase.SUMMONING);
                }
            }
            case SUMMONING -> {
                findMobs(mc);
                if (FOUND.size() == MOBS.length && ticks > 20 || ticks > TIMEOUT_TICKS) {
                    boolean all = FOUND.size() == MOBS.length;
                    log("summoned mobs visible: " + (all ? "PASS" : "FAIL") + " " + FOUND.keySet());
                    pass &= all;
                    entitiesBefore = countEntities(mc);
                    FOUND.forEach((label, e) -> STATE_BEFORE.put(label, state(e)));
                    requestVariants();
                    next(Phase.VARIANTS);
                }
            }
            case VARIANTS -> {
                requestVariants();
                if (variantsSettled() || ticks > TIMEOUT_TICKS) {
                    checkVariants(mc);
                    MobIconCache.runInFrame(MobIconSelfTest::renderExtras);
                    next(Phase.EXTRAS);
                }
            }
            case EXTRAS -> {
                if (extrasDone || ticks > TIMEOUT_TICKS) {
                    checkLeaks(mc);
                    dump();
                    // A resource reload must throw every icon away and the next lookups rebuild them.
                    mc.reloadResourcePacks().thenRun(() -> reloadDone = true);
                    next(Phase.RELOADING);
                }
            }
            case RELOADING -> {
                if (reloadDone && mc.getOverlay() == null) {
                    boolean cleared = !MobIconApi.isReady(EntityType.ZOMBIE);
                    log("reload cleared cache: " + (cleared ? "PASS" : "FAIL"));
                    pass &= cleared;
                    TYPES.forEach(MobIconApi::getIcon);
                    next(Phase.RERENDERING);
                } else if (ticks > TIMEOUT_TICKS) {
                    log("reload: FAIL (timed out)");
                    pass = false;
                    next(Phase.QUITTING);
                }
            }
            case RERENDERING -> {
                TYPES.forEach(MobIconApi::getIcon);
                boolean ready = TYPES.stream().filter(t -> t != EntityType.PLAYER).allMatch(MobIconApi::isReady);
                if (ready || ticks > TIMEOUT_TICKS) {
                    log("re-render after reload: " + (ready ? "PASS" : "FAIL"));
                    pass &= ready;
                    // The world frame after all that rendering, to check no GL state leaked into it.
                    Screenshot.grab(mc.gameDirectory, "mobmapicons-selftest.png", mc.getMainRenderTarget(), msg -> {});
                    log("finished: " + (pass ? "ALL PASS" : "FAILURES"));
                    next(Phase.QUITTING);
                }
            }
            case QUITTING -> {
                if (ticks > 20) {
                    next(Phase.DONE);
                    mc.stop();
                }
            }
            case DONE -> { }
        }
    }

    private static double mobX(int i) {
        return (i - MOBS.length / 2) * 3 + 0.5;
    }

    /** Finds each summoned mob on the client by type and spawn spot. */
    private static void findMobs(Minecraft mc) {
        if (mc.level == null) return;
        for (int i = 0; i < MOBS.length; i++) {
            String label = MOBS[i][0];
            if (FOUND.containsKey(label)) continue;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.withDefaultNamespace(MOBS[i][1]));
            Vec3 spot = new Vec3(mobX(i), MOB_Y, MOB_Z + 0.5);
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e.getType() == type && e.position().distanceToSqr(spot.x, e.getY(), spot.z) < 0.25) {
                    FOUND.put(label, e);
                    break;
                }
            }
        }
    }

    @Nullable
    private static Entity cow() {
        return FOUND.get("cow");
    }

    private static void requestVariants() {
        FOUND.values().forEach(MobIconApi::getIcon);
        Entity cow = cow();
        if (cow != null) {
            for (int b = 0; b < MobIconApi.YAW_BUCKETS; b++) MobIconApi.getIcon(cow, b);
        }
    }

    private static boolean variantsSettled() {
        for (Entity e : FOUND.values()) {
            if (!MobIconCache.isExactReady(e, 0) && !MobIconCache.isExactFailed(e, 0)) return false;
        }
        Entity cow = cow();
        if (cow != null) {
            for (int b = 0; b < MobIconApi.YAW_BUCKETS; b++) {
                if (!MobIconCache.isExactReady(cow, b)) return false;
            }
        }
        return true;
    }

    private static void checkVariants(Minecraft mc) {
        List<String> notReady = new ArrayList<>();
        FOUND.forEach((label, e) -> {
            if (!MobIconCache.isExactReady(e, 0)) notReady.add(label);
        });
        log("variant icons ready: " + (notReady.isEmpty() ? "PASS" : "FAIL missing=" + notReady));
        pass &= notReady.isEmpty();
        FOUND.forEach((label, e) -> log("  " + label + " -> " + IconVariant.of(e) + " = " + MobIconApi.getIcon(e)));

        differ("cow", "cow_baby");
        differ("sheep_white", "sheep_red");
        differ("sheep_red", "sheep_blue");
        differ("sheep_white", "sheep_blue");
        differ("cat_black", "cat_tabby");
        Entity librarian = FOUND.get("villager_librarian");
        if (librarian != null) {
            boolean ok = !Arrays.equals(MobIconApi.getPng(librarian, 0), MobIconApi.getPng(EntityType.VILLAGER))
                    || MobIconApi.getPng(EntityType.VILLAGER) == null;
            log("librarian differs from default villager: " + (ok ? "PASS" : "FAIL"));
            pass &= ok;
        }
        Entity slime = FOUND.get("slime_big");
        if (slime != null) {
            IconVariant v = IconVariant.of(slime);
            boolean ok = v.scale() != 0;
            log("big slime has its own size key: " + (ok ? "PASS" : "FAIL") + " " + v);
            pass &= ok;
        }

        Entity cow = cow();
        if (cow != null) {
            List<byte[]> buckets = new ArrayList<>();
            for (int b = 0; b < MobIconApi.YAW_BUCKETS; b++) buckets.add(MobIconApi.getPng(cow, b));
            boolean allReady = buckets.stream().allMatch(Objects::nonNull);
            boolean distinct = allReady;
            for (int a = 0; a < buckets.size() && distinct; a++) {
                for (int b = a + 1; b < buckets.size(); b++) {
                    if (Arrays.equals(buckets.get(a), buckets.get(b))) {
                        distinct = false;
                        log("  cow buckets " + a + " and " + b + " are identical");
                    }
                }
            }
            log("cow " + MobIconApi.YAW_BUCKETS + " headings ready and distinct: " + (distinct ? "PASS" : "FAIL"));
            pass &= distinct;
            // Bucket mapping sanity: yRot 0 = south = bucket 0, 90 = west = 2, 180 = north = 4, -90 = east = 6.
            boolean mapping = MobIconApi.yawBucket(0) == 0 && MobIconApi.yawBucket(90) == 2
                    && MobIconApi.yawBucket(180) == 4 && MobIconApi.yawBucket(-90) == 6
                    && MobIconApi.yawBucket(22.4F) == 0 && MobIconApi.yawBucket(22.6F) == 1
                    && MobIconApi.yawBucket(-22.6F) == 7 && MobIconApi.yawBucket(720 + 45) == 1;
            log("yawBucket mapping: " + (mapping ? "PASS" : "FAIL"));
            pass &= mapping;
        }
    }

    private static void differ(String a, String b) {
        Entity ea = FOUND.get(a), eb = FOUND.get(b);
        if (ea == null || eb == null) return;
        byte[] pa = MobIconApi.getPng(ea, 0), pb = MobIconApi.getPng(eb, 0);
        boolean ok = pa != null && pb != null && !Arrays.equals(pa, pb);
        log(a + " differs from " + b + ": " + (ok ? "PASS" : "FAIL"));
        pass &= ok;
    }

    /** No entity added to the level, and the real mobs' position, rotation and synced data untouched. */
    private static void checkLeaks(Minecraft mc) {
        int after = countEntities(mc);
        boolean count = after == entitiesBefore;
        List<String> changed = new ArrayList<>();
        FOUND.forEach((label, e) -> {
            if (!state(e).equals(STATE_BEFORE.get(label))) changed.add(label + ": " + STATE_BEFORE.get(label) + " -> " + state(e));
        });
        log("no leak into the world: " + (count && changed.isEmpty() ? "PASS" : "FAIL")
                + " entities " + entitiesBefore + " -> " + after + (changed.isEmpty() ? "" : " changed=" + changed));
        pass &= count && changed.isEmpty();
    }

    private static int countEntities(Minecraft mc) {
        if (mc.level == null) return -1;
        int n = 0;
        for (Entity ignored : mc.level.entitiesForRendering()) n++;
        return n;
    }

    private static String state(Entity e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.position()).append(" yRot=").append(e.getYRot()).append(" removed=").append(e.isRemoved());
        if (e instanceof LivingEntity l) sb.append(" body=").append(l.yBodyRot).append(" head=").append(l.yHeadRot);
        List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
        if (values != null) {
            for (var v : values) sb.append(" #").append(v.id()).append('=').append(v.value());
        }
        return sb.toString();
    }

    /** Contact sheets for eyeballing, outside the cache. Render thread. */
    private static void renderExtras() {
        Path dir = MobIconCommands.dumpDir();
        int big = 64;
        try {
            StyleContactSheet.write(dir, new ArrayList<>(FOUND.values()));
            // Variants, big, facing the viewer.
            List<NativeImage> variants = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (var e : FOUND.entrySet()) {
                NativeImage image = MobIconCache.renderOnce(e.getValue().getType(), e.getValue(), ClientConfig.iconMode(), big, 0, false);
                if (image != null) {
                    variants.add(image);
                    names.add(e.getKey());
                }
            }
            ContactSheet.write(dir.resolve("selftest_variants.png"), variants, 5, 1);
            Files.write(dir.resolve("selftest_variants.txt"), names);

            // The same variants as the cache serves them (iconSize * 2).
            List<byte[]> cached = new ArrayList<>();
            for (Entity e : FOUND.values()) {
                byte[] png = MobIconApi.getPng(e, 0);
                if (png != null) cached.add(png);
            }
            ContactSheet.write(dir.resolve("selftest_variants_cache.png"), ContactSheet.decode(cached), 5, 2);

            Entity cow = cow();
            if (cow != null) {
                // All 8 headings: from the cache, and big in both modes.
                List<byte[]> buckets = new ArrayList<>();
                for (int b = 0; b < MobIconApi.YAW_BUCKETS; b++) {
                    byte[] png = MobIconApi.getPng(cow, b);
                    if (png != null) buckets.add(png);
                }
                ContactSheet.write(dir.resolve("selftest_headings_cache.png"), ContactSheet.decode(buckets), MobIconApi.YAW_BUCKETS, 2);
                MobIconCommands.writeHeadings(dir, cow, IconMode.FULL_BODY, big, true, "_full");
                MobIconCommands.writeHeadings(dir, cow, IconMode.HEAD, big, true, "_head");
                Entity villager = FOUND.get("villager_librarian");
                if (villager != null) MobIconCommands.writeHeadings(dir, villager, IconMode.FULL_BODY, big, true, "_full");
                Entity cat = FOUND.get("cat_black");
                if (cat != null) MobIconCommands.writeHeadings(dir, cat, IconMode.FULL_BODY, big, true, "_full");
            }

            // HEAD mode across many shapes, and the same in FULL_BODY for comparison.
            for (IconMode mode : IconMode.values()) {
                List<NativeImage> heads = new ArrayList<>();
                for (EntityType<?> type : HEAD_TYPES) {
                    NativeImage image = MobIconCache.renderOnce(type, null, mode, big, 0, false);
                    heads.add(image != null ? image : new NativeImage(big, big, true));
                }
                ContactSheet.write(dir.resolve("selftest_types_" + mode.name().toLowerCase() + ".png"), heads, 5, 1);
            }
            Files.write(dir.resolve("selftest_types.txt"),
                    HEAD_TYPES.stream().map(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath()).toList());
            log("contact sheets written to " + dir);
        } catch (Throwable t) {
            log("extras failed: " + t);
            pass = false;
            MobMapIcons.LOG.error("selftest extras", t);
        } finally {
            extrasDone = true;
        }
    }

    private static void report() {
        List<String> ok = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (EntityType<?> type : TYPES) {
            (MobIconApi.isReady(type) ? ok : failed).add(key(type).toString());
        }
        // PLAYER renders too now (a RemotePlayer wearing the local player's profile), so nothing may fail.
        boolean expected = failed.isEmpty();
        pass &= expected;
        log("first render: " + (expected ? "PASS" : "FAIL") + " ready=" + ok + " failed=" + failed);
    }

    private static void dump() {
        Path dir = MobIconCommands.dumpDir();
        try {
            int n = MobIconCache.dumpAll(dir);
            log("dumped " + n + " cached icon(s) to " + dir);
        } catch (IOException e) {
            log("dump failed: " + e);
            pass = false;
        }
    }

    private static void createScratchWorld(Minecraft mc) {
        Path saves = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.exists(saves)) {
            try (Stream<Path> walk = Files.walk(saves)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                log("could not delete old scratch world: " + e);
            }
        }
        // Old dumps would hide a missing file.
        Path dump = MobIconCommands.dumpDir();
        if (Files.exists(dump)) {
            try (Stream<Path> walk = Files.walk(dump)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                log("could not clear old dump: " + e);
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        // EASY, not PEACEFUL: peaceful removes the slime.
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.EASY, true,
                rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private static ResourceLocation key(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type);
    }

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private static void log(String msg) {
        MobMapIcons.LOG.info("selftest: {}", msg);
    }
}
