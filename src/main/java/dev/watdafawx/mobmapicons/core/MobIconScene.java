package dev.watdafawx.mobmapicons.core;

import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Dev-only map test scene, on with {@code -Dmobmapicons.scene=true} (the {@code runScene} run).
 *
 * <p>Makes a fresh creative superflat world at noon with mob spawning off, then summons a fixed
 * spread of mobs around the player (NoAI, invulnerable, so they stand still for screenshots) and
 * leaves the game running. Unlike the self-test it never quits: it is the starting point for
 * checking a map mod by eye.
 */
final class MobIconScene {

    static final String PROPERTY = "mobmapicons.scene";
    private static final String WORLD = "mobmapicons-scene";
    private static final String TAGS = "NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Silent:1b";

    /** {@code type, x, z[, extra nbt]} relative to the player at 0,0, facing north (-Z is up on the maps). */
    private static final List<String[]> MOBS = List.of(
            new String[]{"zombie", "-18", "-16"},
            new String[]{"skeleton", "-6", "-20"},
            new String[]{"creeper", "6", "-20"},
            new String[]{"enderman", "18", "-16"},
            new String[]{"pig", "-24", "-4"},
            new String[]{"cow", "-12", "-8"},
            new String[]{"cow", "-12", "0", "Age:-1000000"},
            new String[]{"sheep", "12", "-8"},
            new String[]{"chicken", "24", "-4"},
            new String[]{"wolf", "-24", "10"},
            new String[]{"villager", "-12", "14"},
            new String[]{"iron_golem", "0", "18"},
            new String[]{"bat", "12", "14"},
            new String[]{"slime", "24", "10", "Size:1"},
            new String[]{"cat", "0", "-10"});

    private enum Phase { TITLE, LOADING, SETUP, IDLE }

    private static Phase phase = Phase.TITLE;
    private static int ticks;

    private MobIconScene() {}

    static void enable() {
        MobMapIcons.LOG.info("scene: enabled");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        switch (phase) {
            case TITLE -> {
                if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
                    mc.options.pauseOnLostFocus = false;
                    createWorld(mc);
                    next(Phase.LOADING);
                }
            }
            case LOADING -> {
                if (mc.level != null && mc.player != null && mc.screen == null && ticks > 60) next(Phase.SETUP);
            }
            case SETUP -> {
                var conn = mc.player.connection;
                conn.sendCommand("tp @s 0.5 -60 0.5 180 30");
                conn.sendCommand("time set 6000");
                conn.sendCommand("weather clear 1000000");
                conn.sendCommand("gamerule doMobSpawning false");
                conn.sendCommand("gamerule doDaylightCycle false");
                conn.sendCommand("gamerule doWeatherCycle false");
                for (String[] mob : MOBS) {
                    String nbt = TAGS + (mob.length > 3 ? "," + mob[3] : "");
                    conn.sendCommand("summon minecraft:" + mob[0] + " " + (Integer.parseInt(mob[1]) + 0.5)
                            + " -60 " + (Integer.parseInt(mob[2]) + 0.5) + " {" + nbt + "}");
                }
                MobMapIcons.LOG.info("scene: ready, {} mobs summoned", MOBS.size());
                next(Phase.IDLE);
            }
            case IDLE -> script(mc);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Script channel: lets a test driver steer the game without window focus. Each line of
    // <gamedir>/scene-cmd.txt is one step; the file is deleted once read. Steps:
    //   key <glfw key name, e.g. m or f3>   press+release, to the open screen or as a key mapping
    //   mouse <x> <y>                        move the cursor (window pixels)
    //   click <x> <y>                        left click at window pixels
    //   scroll <x> <y> <amount>              mouse wheel at window pixels
    //   cmd <command without slash>          run a command
    //   shot <name>                          screenshot to screenshots/<name>.png
    //   wait <ticks>                         pause the script
    //   close                                close the open screen
    //   reload                               resource reload (like F3+T)
    //   cfg <ftbChunks|xaero|journeymap|tint> <true|false>, cfg mode <HEAD|FULL_BODY>, cfg size <n>
    //   log <text>                           echo to the log

    private static final java.util.ArrayDeque<String> SCRIPT = new java.util.ArrayDeque<>();
    private static int waitTicks;

    private static void script(Minecraft mc) {
        Path file = mc.gameDirectory.toPath().resolve("scene-cmd.txt");
        if (ticks % 5 == 0 && Files.exists(file)) {
            try {
                SCRIPT.addAll(Files.readAllLines(file));
                Files.delete(file);
            } catch (IOException e) {
                return; // still being written; next poll
            }
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        while (!SCRIPT.isEmpty() && waitTicks == 0) {
            String line = SCRIPT.poll().trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try {
                step(mc, line);
            } catch (Throwable t) {
                MobMapIcons.LOG.warn("scene: step failed: {}", line, t);
            }
        }
    }

    private static void step(Minecraft mc, String line) throws Exception {
        String[] a = line.split(" ", 2);
        String arg = a.length > 1 ? a[1] : "";
        String[] n = arg.split(" ");
        long window = mc.getWindow().getWindow();
        switch (a[0]) {
            case "key" -> {
                var key = com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + arg);
                int code = key.getValue();
                var onPress = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",
                        long.class, int.class, int.class, int.class, int.class);
                onPress.setAccessible(true);
                onPress.invoke(mc.keyboardHandler, window, code, 0, 1, 0);
                onPress.invoke(mc.keyboardHandler, window, code, 0, 0, 0);
            }
            case "mouse" -> move(mc, window, Double.parseDouble(n[0]), Double.parseDouble(n[1]));
            case "click" -> {
                move(mc, window, Double.parseDouble(n[0]), Double.parseDouble(n[1]));
                var press = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onPress",
                        long.class, int.class, int.class, int.class);
                press.setAccessible(true);
                press.invoke(mc.mouseHandler, window, 0, 1, 0);
                press.invoke(mc.mouseHandler, window, 0, 0, 0);
            }
            case "scroll" -> {
                move(mc, window, Double.parseDouble(n[0]), Double.parseDouble(n[1]));
                var scroll = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onScroll",
                        long.class, double.class, double.class);
                scroll.setAccessible(true);
                scroll.invoke(mc.mouseHandler, window, 0.0, Double.parseDouble(n[2]));
            }
            case "cmd" -> mc.player.connection.sendCommand(arg);
            case "shot" -> net.minecraft.client.Screenshot.grab(mc.gameDirectory, arg + ".png",
                    mc.getMainRenderTarget(), msg -> MobMapIcons.LOG.info("scene: shot {}", arg));
            case "wait" -> waitTicks = Integer.parseInt(arg);
            case "close" -> mc.setScreen(null);
            case "reload" -> mc.reloadResourcePacks();
            case "config" -> mc.setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(
                    net.neoforged.fml.ModList.get().getModContainerById(dev.watdafawx.mobmapicons.MobMapIcons.MODID).orElseThrow(), mc.screen));
            case "log" -> MobMapIcons.LOG.info("scene: {}", arg);
            case "cfg" -> {
                boolean on = Boolean.parseBoolean(n.length > 1 ? n[1] : "");
                switch (n[0]) {
                    case "ftbChunks" -> dev.watdafawx.mobmapicons.ClientConfig.FTB_CHUNKS_ENABLED.set(on);
                    case "xaero" -> dev.watdafawx.mobmapicons.ClientConfig.XAERO_ENABLED.set(on);
                    case "journeymap" -> dev.watdafawx.mobmapicons.ClientConfig.JOURNEYMAP_ENABLED.set(on);
                    case "tint" -> dev.watdafawx.mobmapicons.ClientConfig.TINT_BY_HOSTILITY.set(on);
                    case "mode" -> dev.watdafawx.mobmapicons.ClientConfig.ICON_MODE.set(
                            dev.watdafawx.mobmapicons.api.IconMode.valueOf(n[1]));
                    case "size" -> dev.watdafawx.mobmapicons.ClientConfig.ICON_SIZE.set(Integer.parseInt(n[1]));
                    default -> MobMapIcons.LOG.warn("scene: unknown cfg {}", n[0]);
                }
            }
            default -> MobMapIcons.LOG.warn("scene: unknown step {}", line);
        }
    }

    private static void move(Minecraft mc, long window, double x, double y) throws Exception {
        var onMove = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
        onMove.setAccessible(true);
        onMove.invoke(mc.mouseHandler, window, x, y);
    }

    private static void createWorld(Minecraft mc) {
        Path saves = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.exists(saves)) {
            try (Stream<Path> walk = Files.walk(saves)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                MobMapIcons.LOG.warn("scene: could not delete old world", e);
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.EASY, true,
                rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
    }
}
