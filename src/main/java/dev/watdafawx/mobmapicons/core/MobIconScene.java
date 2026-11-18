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
                if (mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen && mc.getOverlay() == null) {
                    // First run of a fresh run dir: skip the Narrator prompt so the title screen shows.
                    mc.options.onboardAccessibility = false;
                    mc.options.save();
                    mc.setScreen(new TitleScreen());
                }
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
                spawnPlayers(mc);
                MobMapIcons.LOG.info("scene: ready, {} mobs summoned", MOBS.size());
                next(Phase.IDLE);
            }
            case IDLE -> {
                if (ticks % 10 == 0) markPlayersVisibleToFtb();
                script(mc);
            }
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
    //   type <text>                          type characters into the focused text field
    //   shot <name>                          screenshot to screenshots/<name>.png
    //   wait <ticks>                         pause the script
    //   close                                close the open screen
    //   reload                               resource reload (like F3+T)
    //   cfg <ftbChunks|xaero|journeymap|tint|rotate|arrow> <true|false>, cfg mode <HEAD|FULL_BODY>, cfg size <n>,
    //   cfg style <ICON_STYLE>, cfg jmframe <AUTO|ON|OFF>
    //   log <text>                           echo to the log
    //   players                              (re)spawn the two fake remote players
    //   cfgplayers <true|false>              the `players` config option

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
            case "type" -> {
                var typed = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("charTyped",
                        long.class, int.class, int.class);
                typed.setAccessible(true);
                for (char c : arg.toCharArray()) typed.invoke(mc.keyboardHandler, window, (int) c, 0);
            }
            case "shot" -> net.minecraft.client.Screenshot.grab(mc.gameDirectory, arg + ".png",
                    mc.getMainRenderTarget(), msg -> MobMapIcons.LOG.info("scene: shot {}", arg));
            case "wait" -> waitTicks = Integer.parseInt(arg);
            case "close" -> mc.setScreen(null);
            case "reload" -> mc.reloadResourcePacks();
            case "config" -> mc.setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(
                    net.neoforged.fml.ModList.get().getModContainerById(dev.watdafawx.mobmapicons.MobMapIcons.MODID).orElseThrow(), mc.screen));
            case "log" -> MobMapIcons.LOG.info("scene: {}", arg);
            case "players" -> spawnPlayers(mc);
            case "opts" -> { if (arg.isEmpty()) QuickOptions.toggle(); else QuickOptions.openOn(arg); }
            case "bindkey" -> {
                mc.options.setKey(QuickOptions.KEY, com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard." + arg));
                net.minecraft.client.KeyMapping.resetMapping();
            }
            case "cfgstyle" -> { dev.watdafawx.mobmapicons.ClientConfig.ICON_STYLE.set(
                    dev.watdafawx.mobmapicons.api.IconStyle.valueOf(arg.toUpperCase(java.util.Locale.ROOT)));
                MobIconCache.checkConfig(); }
            case "cfgplayers" -> dev.watdafawx.mobmapicons.ClientConfig.PLAYERS.set(Boolean.parseBoolean(arg));
            case "cfg" -> {
                String key = n[0], val = n.length > 1 ? n[1] : "";
                boolean on = Boolean.parseBoolean(val);
                int dot = key.indexOf('.');
                if (dot > 0) {
                    // Per map mod: cfg ftb.style OUTLINE | xaero.mode HEAD | journeymap.size 24 | ftb.own true
                    var sec = dev.watdafawx.mobmapicons.ClientConfig.sectionOf(switch (key.substring(0, dot)) {
                        case "ftb" -> dev.watdafawx.mobmapicons.api.MapMod.FTB;
                        case "xaero" -> dev.watdafawx.mobmapicons.api.MapMod.XAERO;
                        default -> dev.watdafawx.mobmapicons.api.MapMod.JOURNEYMAP;
                    });
                    switch (key.substring(dot + 1)) {
                        case "enabled" -> sec.enabled.set(on);
                        case "style" -> sec.iconStyle.set(dev.watdafawx.mobmapicons.ClientConfig.StyleChoice.valueOf(val.toUpperCase(java.util.Locale.ROOT)));
                        case "mode" -> sec.iconMode.set(dev.watdafawx.mobmapicons.ClientConfig.ModeChoice.valueOf(val.toUpperCase(java.util.Locale.ROOT)));
                        case "size" -> sec.iconSize.set(Integer.parseInt(val));
                        case "own" -> { if (sec.ownPlayer != null) sec.ownPlayer.set(on); }
                        case "players" -> sec.players.set(dev.watdafawx.mobmapicons.ClientConfig.Tri.valueOf(val.toUpperCase(java.util.Locale.ROOT)));
                        case "rotate" -> { if (sec.rotateWithHeading != null) sec.rotateWithHeading.set(dev.watdafawx.mobmapicons.ClientConfig.Tri.valueOf(val.toUpperCase(java.util.Locale.ROOT))); }
                        case "arrow" -> { if (sec.headingArrow != null) sec.headingArrow.set(dev.watdafawx.mobmapicons.ClientConfig.Tri.valueOf(val.toUpperCase(java.util.Locale.ROOT))); }
                        case "frame" -> sec.journeymapFrame.set(dev.watdafawx.mobmapicons.ClientConfig.JourneyMapFrame.valueOf(val.toUpperCase(java.util.Locale.ROOT)));
                        default -> MobMapIcons.LOG.warn("scene: unknown cfg {}", key);
                    }
                    MobIconCache.checkConfig();
                } else switch (key) {
                    case "ftbChunks" -> dev.watdafawx.mobmapicons.ClientConfig.FTB.enabled.set(on);
                    case "xaero" -> dev.watdafawx.mobmapicons.ClientConfig.XAERO.enabled.set(on);
                    case "journeymap" -> dev.watdafawx.mobmapicons.ClientConfig.JOURNEYMAP.enabled.set(on);
                    case "rotate" -> dev.watdafawx.mobmapicons.ClientConfig.ROTATE_WITH_HEADING.set(on);
                    case "arrow" -> dev.watdafawx.mobmapicons.ClientConfig.HEADING_ARROW.set(on);
                    case "tint" -> dev.watdafawx.mobmapicons.ClientConfig.TINT_BY_HOSTILITY.set(on);
                    case "mode" -> dev.watdafawx.mobmapicons.ClientConfig.ICON_MODE.set(
                            dev.watdafawx.mobmapicons.api.IconMode.valueOf(val));
                    case "size" -> dev.watdafawx.mobmapicons.ClientConfig.ICON_SIZE.set(Integer.parseInt(val));
                    case "style" -> dev.watdafawx.mobmapicons.ClientConfig.ICON_STYLE.set(
                            dev.watdafawx.mobmapicons.api.IconStyle.valueOf(val));
                    case "jmframe" -> dev.watdafawx.mobmapicons.ClientConfig.JOURNEYMAP.journeymapFrame.set(
                            dev.watdafawx.mobmapicons.ClientConfig.JourneyMapFrame.valueOf(val));
                    default -> MobMapIcons.LOG.warn("scene: unknown cfg {}", key);
                }
            }
            default -> MobMapIcons.LOG.warn("scene: unknown step {}", line);
        }
    }

    /** {@code name, uuid low bits, x, z, yaw}: two fake players next to the mobs, with different default skins and headings. */
    private static final Object[][] PLAYERS = {
            {"Steve_Test", 1L, -9.5, -6.5, 90.0F},
            {"Alex_Test", 2L, 9.5, -4.5, 225.0F},
    };

    @SuppressWarnings("unchecked")
    private static void spawnPlayers(Minecraft mc) {
        var level = mc.level;
        if (level == null) return;
        for (Object[] p : PLAYERS) {
            java.util.UUID uuid = new java.util.UUID(0L, (Long) p[1]);
            var profile = new com.mojang.authlib.GameProfile(uuid, (String) p[0]);
            var old = level.getPlayerByUUID(uuid);
            if (old != null) old.discard();
            var remote = new net.minecraft.client.player.RemotePlayer(level, profile);
            float yaw = (Float) p[4];
            remote.moveTo((Double) p[2], -60.0, (Double) p[3], yaw, 0.0F);
            remote.yBodyRot = yaw;
            remote.yHeadRot = yaw;
            remote.yBodyRotO = yaw;
            remote.yHeadRotO = yaw;
            // Tab-list entry, like a server sends: the skin resolves and map mods that list players from the
            // tab list (JourneyMap) see it. The skin is the uuid's default one.
            try {
                var info = new net.minecraft.client.multiplayer.PlayerInfo(profile, false);
                var listener = net.minecraft.client.multiplayer.ClientPacketListener.class;
                var infoMap = listener.getDeclaredField("playerInfoMap");
                infoMap.setAccessible(true);
                ((java.util.Map<java.util.UUID, net.minecraft.client.multiplayer.PlayerInfo>) infoMap.get(mc.player.connection)).put(uuid, info);
                var listed = listener.getDeclaredField("listedPlayers");
                listed.setAccessible(true);
                ((java.util.Collection<net.minecraft.client.multiplayer.PlayerInfo>) listed.get(mc.player.connection)).add(info);
            } catch (Throwable t) {
                MobMapIcons.LOG.warn("scene: could not add tab entry for {}", p[0], t);
            }
            level.addEntity(remote);
            registerWithJourneyMap(uuid, remote);
        }
        MobMapIcons.LOG.info("scene: {} fake players spawned", PLAYERS.length);
    }

    /**
     * FTB Chunks only maps players its server side lists as visible (a packet), and the fake players
     * exist on no server, so put their uuids in FTB's client list by reflection. No-op without FTB.
     */
    @SuppressWarnings("unchecked")
    private static void markPlayersVisibleToFtb() {
        if (!net.neoforged.fml.ModList.get().isLoaded("ftbchunks")) return;
        try {
            var field = Class.forName("dev.ftb.mods.ftbchunks.client.VisibleClientPlayers").getDeclaredField("CLIENT_LIST");
            field.setAccessible(true);
            var set = (java.util.Set<java.util.UUID>) field.get(null);
            for (Object[] p : PLAYERS) set.add(new java.util.UUID(0L, (Long) p[1]));
        } catch (Throwable t) {
            MobMapIcons.LOG.warn("scene: could not mark fake players visible to FTB Chunks", t);
        }
    }

    /**
     * JourneyMap's integrated server radar lists players from a manager fed by its own server packets,
     * which the fake players never produce, so put them in by reflection. No-op without JourneyMap.
     */
    @SuppressWarnings("unchecked")
    private static void registerWithJourneyMap(java.util.UUID uuid, net.minecraft.world.entity.player.Player player) {
        if (!net.neoforged.fml.ModList.get().isLoaded("journeymap")) return;
        try {
            var cls = Class.forName("journeymap.common.util.PlayerRadarManager");
            Object manager = cls.getMethod("getInstance").invoke(null);
            var map = (java.util.Map<java.util.UUID, net.minecraft.world.entity.player.Player>) cls.getMethod("getPlayers").invoke(manager);
            map.put(uuid, player);
        } catch (Throwable t) {
            MobMapIcons.LOG.warn("scene: could not register fake players with JourneyMap", t);
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
