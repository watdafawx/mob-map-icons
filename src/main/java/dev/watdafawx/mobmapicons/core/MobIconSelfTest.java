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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dev-only render check, on with {@code -Dmobmapicons.selftest=true} (the {@code runSelftest} run).
 *
 * <p>Makes a flat scratch world, asks the cache for a handful of awkward mobs (huge, tiny, wide,
 * tall), renders a baby cow and every test mob in HEAD mode on the side, writes all PNGs to
 * {@code <gamedir>/mob_map_icons_dump/} and quits. Look at the PNGs to judge the result; the log line
 * {@code selftest: done} says which types rendered.
 */
final class MobIconSelfTest {

    static final String PROPERTY = "mobmapicons.selftest";
    private static final String WORLD = "mobmapicons-selftest";
    private static final List<EntityType<?>> TYPES = List.of(
            EntityType.ZOMBIE, EntityType.CREEPER, EntityType.ENDER_DRAGON, EntityType.BAT,
            EntityType.IRON_GOLEM, EntityType.COW, EntityType.CHICKEN, EntityType.SLIME, EntityType.PLAYER);
    private static final int TIMEOUT_TICKS = 20 * 30;

    private enum Phase { TITLE, LOADING, RENDERING, EXTRAS, RELOADING, RERENDERING, QUITTING, DONE }

    private static Phase phase = Phase.TITLE;
    private static int ticks;
    private static volatile boolean extrasDone;
    private static volatile boolean reloadDone;
    private static boolean pass = true;

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
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
                    createScratchWorld(mc);
                    next(Phase.LOADING);
                }
            }
            case LOADING -> {
                if (mc.level != null && mc.player != null && ticks > 40) {
                    log("world loaded, requesting icons (" + ClientConfig.iconMode() + ", " + ClientConfig.iconSize() + "px)");
                    TYPES.forEach(MobIconApi::getIcon);
                    next(Phase.RENDERING);
                }
            }
            case RENDERING -> {
                TYPES.forEach(MobIconApi::getIcon);
                boolean settled = TYPES.stream().allMatch(t -> MobIconCache.isReady(t) || MobIconCache.isFailed(t));
                if (settled || ticks > TIMEOUT_TICKS) {
                    MobIconCache.runInFrame(MobIconSelfTest::renderExtras);
                    next(Phase.EXTRAS);
                }
            }
            case EXTRAS -> {
                if (extrasDone || ticks > TIMEOUT_TICKS) {
                    report(mc);
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

    /** Baby cow plus the other mode for every test type, outside the cache. Render thread. */
    private static void renderExtras() {
        Minecraft mc = Minecraft.getInstance();
        Path dir = MobIconCommands.dumpDir();
        int out = ClientConfig.iconSize() * 2;
        IconMode other = ClientConfig.iconMode() == IconMode.HEAD ? IconMode.FULL_BODY : IconMode.HEAD;
        try {
            Entity cow = EntityType.COW.create(mc.level);
            if (cow instanceof AgeableMob baby) {
                baby.setBaby(true);
                write(dir, EntityType.COW, "_baby", MobIconCache.renderOnce(cow, ClientConfig.iconMode(), out));
            }
            for (EntityType<?> type : TYPES) {
                Entity e = type.create(mc.level);
                if (e == null) continue;
                write(dir, type, "_" + other.name().toLowerCase(), MobIconCache.renderOnce(e, other, out));
                // Big copy for eyeballing the shape: 4x the normal output.
                write(dir, type, "_large", MobIconCache.renderOnce(e, ClientConfig.iconMode(), 128));
            }
        } catch (Throwable t) {
            log("extras failed: " + t);
            MobMapIcons.LOG.error("selftest extras", t);
        } finally {
            extrasDone = true;
        }
    }

    private static void write(Path dir, EntityType<?> type, String suffix, NativeImage image) throws IOException {
        if (image == null) {
            log("  " + key(type) + suffix + ": draws nothing");
            return;
        }
        try (image) {
            MobIconCache.writePng(dir, key(type), suffix, image.asByteArray());
        }
    }

    private static void report(Minecraft mc) {
        Path dir = MobIconCommands.dumpDir();
        List<String> ok = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (EntityType<?> type : TYPES) {
            (MobIconApi.isReady(type) ? ok : failed).add(key(type).toString());
        }
        try {
            int n = MobIconCache.dumpAll(dir);
            log("dumped " + n + " cached icon(s) to " + dir);
        } catch (IOException e) {
            log("dump failed: " + e);
        }
        // PLAYER is expected to fail: it has no factory.
        boolean expected = failed.equals(List.of(key(EntityType.PLAYER).toString()));
        pass &= expected;
        log("first render: " + (expected ? "PASS" : "FAIL") + " ready=" + ok + " failed=" + failed);
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
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
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
