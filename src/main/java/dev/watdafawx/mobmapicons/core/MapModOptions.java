package dev.watdafawx.mobmapicons.core;

import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MapView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A few of a map mod's OWN icon options, shown in the quick options panel next to ours. Everything is
 * reflection with a try/catch around every step: if the map mod is a different version and a class, field
 * or method is missing, that row is simply not offered. Values go through the map mod's own setters and
 * its own save call, so nothing here writes a file by hand.
 */
final class MapModOptions {

    /** A row: label key, current value text, and a press handler (+1 left click, -1 right click). */
    record Entry(String key, Supplier<String> value, IntConsumer press) {}

    private MapModOptions() {}

    static List<Entry> of(MapMod mod) {
        return of(mod, () -> MapView.MINIMAP);
    }

    /** The map mod's own options; JourneyMap's act on the properties of {@code view}'s map (minimap or full-screen). */
    static List<Entry> of(MapMod mod, Supplier<MapView> view) {
        List<Entry> out = new ArrayList<>();
        try {
            switch (mod) {
                case FTB -> ftb(out);
                case JOURNEYMAP -> journeymap(out, view);
                default -> { }
            }
        } catch (Throwable t) {
            MobMapIcons.LOG.debug("Quick options: no native rows for {}", mod, t);
            out.clear();
        }
        return out;
    }

    // ---- FTB Chunks: FTBChunksClientConfig public static *Value fields -----------------------------

    private static void ftb(List<Entry> out) throws ReflectiveOperationException {
        Class<?> cfg = Class.forName("dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig");
        Method save = cfg.getMethod("saveConfig");
        for (String name : new String[] {"MINIMAP_LARGE_ENTITIES", "MINIMAP_ENTITY_HEADS", "MINIMAP_PLAYER_HEADS"}) {
            try {
                Object value = cfg.getField(name).get(null);
                Method get = value.getClass().getMethod("get");
                Method set = setter(value.getClass());
                if (set == null || !(get.invoke(value) instanceof Boolean)) continue;
                out.add(new Entry("ftb." + name, () -> state(value, get), dir -> {
                    try {
                        set.invoke(value, !(Boolean) get.invoke(value));
                        save.invoke(null);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        MobMapIcons.LOG.debug("Quick options: FTB write failed", e);
                    }
                }));
            } catch (ReflectiveOperationException | RuntimeException e) {
                MobMapIcons.LOG.debug("Quick options: FTB option {} unavailable", name, e);
            }
        }
    }

    // ---- JourneyMap: ConfigField members of the minimap's or the full-screen map's properties -------

    private static final float[] SCALES = {0.5F, 0.75F, 1.0F, 1.5F, 2.0F};

    private static void journeymap(List<Entry> out, Supplier<MapView> view) throws ReflectiveOperationException {
        JmDisplay.client();
        for (String name : JmDisplay.DISPLAY_FIELDS) addJmDisplay(out, name, view);
        for (String name : new String[] {"showMobHeading", "showSelf"}) addJm(out, name, false, view);
        for (String name : new String[] {"mobDisplayScale", "selfDisplayScale"}) addJm(out, name, true, view);
    }

    private static void addJmDisplay(List<Entry> out, String name, Supplier<MapView> view) {
        if (JmDisplay.field(JmDisplay.props(MapView.MINIMAP), name) == null) return;
        out.add(new Entry("jm." + name, () -> shortDisplay(JmDisplay.show(view.get(), name)), dir -> {
            try {
                JmDisplay.cycle(view.get(), name, dir);
            } catch (ReflectiveOperationException | RuntimeException e) {
                MobMapIcons.LOG.debug("Quick options: JourneyMap display write failed", e);
            }
        }));
    }

    /** JourneyMap's display names are long ("DotsAndOutlinedIcons"); the panel cell is not. */
    private static String shortDisplay(String name) {
        return switch (name) {
            case "OutlinedIcons" -> "Outlined";
            case "DotsAndIcons" -> "Dots+Icons";
            case "DotsAndOutlinedIcons" -> "Dots+Outl.";
            default -> name;
        };
    }

    private static void addJm(List<Entry> out, String name, boolean scale, Supplier<MapView> view) {
        try {
            // Re-resolve every time: JourneyMap swaps the properties object when the minimap preset changes.
            Supplier<Object> field = () -> JmDisplay.field(JmDisplay.props(view.get()), name);
            Object probe = field.get();
            if (probe == null) return;
            Method get = probe.getClass().getMethod("get");
            Method set = setter(probe.getClass());
            Method save = probe.getClass().getMethod("save");
            if (set == null) return;
            out.add(new Entry("jm." + name, () -> {
                Object f = field.get();
                try {
                    Object v = f == null ? null : get.invoke(f);
                    if (v instanceof Boolean b) return b ? "on" : "off";
                    if (v instanceof Number n) return String.format(java.util.Locale.ROOT, "%.2f", n.floatValue());
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // fall through
                }
                return "?";
            }, dir -> {
                try {
                    Object f = field.get();
                    if (f == null) return;
                    Object v = get.invoke(f);
                    if (scale && v instanceof Number n) {
                        int i = 0;
                        for (int k = 0; k < SCALES.length; k++) if (Math.abs(SCALES[k] - n.floatValue()) < Math.abs(SCALES[i] - n.floatValue())) i = k;
                        set.invoke(f, SCALES[Math.floorMod(i + (dir < 0 ? -1 : 1), SCALES.length)]);
                    } else if (v instanceof Boolean b) {
                        set.invoke(f, !b);
                    } else {
                        return;
                    }
                    save.invoke(f);
                    if (view.get() == MapView.MINIMAP) JmDisplay.syncMinimap();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    MobMapIcons.LOG.debug("Quick options: JourneyMap write failed", e);
                }
            }));
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobMapIcons.LOG.debug("Quick options: JourneyMap option {} unavailable", name, e);
        }
    }

    // ---- dev harness (MobIconScene): log live values / force a JourneyMap save ----

    static void dump() {
        try {
            Class<?> cfg = Class.forName("dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig");
            for (String name : new String[] {"MINIMAP_LARGE_ENTITIES", "MINIMAP_ENTITY_HEADS", "MINIMAP_PLAYER_HEADS"}) {
                Object v = cfg.getField(name).get(null);
                MobMapIcons.LOG.info("scene: maps ftb.{}={}", name, v.getClass().getMethod("get").invoke(v));
            }
        } catch (Throwable t) {
            MobMapIcons.LOG.info("scene: maps ftb n/a {}", t.toString());
        }
        try {
            Object client = Class.forName("journeymap.client.JourneymapClient").getMethod("getInstance").invoke(null);
            Object props = client.getClass().getMethod("getActiveMiniMapProperties").invoke(client);
            for (String name : new String[] {"showMobHeading", "showSelf", "mobDisplayScale", "selfDisplayScale", "mobDisplay", "playerDisplay"}) {
                Object v = props.getClass().getField(name).get(props);
                MobMapIcons.LOG.info("scene: maps jm.{}={}", name, v.getClass().getMethod("get").invoke(v));
            }
            Object ui = Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
            Object mini = ui.getClass().getMethod("getMinimap").invoke(ui);
            Object cur = mini.getClass().getMethod("getCurrentMinimapProperties").invoke(mini);
            Object cf = cur.getClass().getField("mobDisplayScale").get(cur);
            MobMapIcons.LOG.info("scene: maps jm.minimapProps same={} cur.mobDisplayScale={} activeId={}",
                    cur == props, cf.getClass().getMethod("get").invoke(cf), System.identityHashCode(props));
            for (MapView v : MapView.values()) {
                for (String name : JmDisplay.DISPLAY_FIELDS) {
                    MobMapIcons.LOG.info("scene: maps jm.{}.{}={}", v, name, JmDisplay.show(v, name));
                }
                for (String name : new String[] {"showMobHeading", "mobDisplayScale"}) {
                    Object f = JmDisplay.field(JmDisplay.props(v), name);
                    MobMapIcons.LOG.info("scene: maps jm.{}.{}={}", v, name, f == null ? "n/a" : JmDisplay.get(f));
                }
            }
            MobMapIcons.LOG.info("scene: maps jm.savedDisplay={}", JmDisplay.savedSummary());
            Object core = client.getClass().getMethod("getCoreProperties").invoke(client);
            Object colour = core.getClass().getField("colorSelf").get(core);
            MobMapIcons.LOG.info("scene: maps jm.colorSelf(live)={}", colour.getClass().getMethod("get").invoke(colour));
        } catch (Throwable t) {
            MobMapIcons.LOG.info("scene: maps jm n/a {}", t.toString());
        }
    }

    /** Dev harness: write JourneyMap's own mobDisplay / playerDisplay for a view, bypassing our auto rule. */
    static void jmSetDisplay(MapView view, String name, String value) {
        try {
            Object f = JmDisplay.field(JmDisplay.props(view), name);
            Object c = f == null ? null : JmDisplay.constant(JmDisplay.get(f), value);
            if (c == null) {
                MobMapIcons.LOG.info("scene: jmdisplay {} {} {} not available", view, name, value);
                return;
            }
            JmDisplay.set(f, c);
            JmDisplay.save(f);
            if (view == MapView.MINIMAP) JmDisplay.syncMinimap();
            MobMapIcons.LOG.info("scene: jmdisplay {} {} = {}", view, name, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobMapIcons.LOG.info("scene: jmdisplay failed {}", e.toString());
        }
    }

    /** Dev harness: open JourneyMap's options screen (the one that saves its core config when closed). */
    static void jmOptions() {
        try {
            Object ui = Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
            Object screen = ui.getClass().getMethod("openOptionsManager", net.minecraft.client.gui.screens.Screen.class, boolean.class)
                    .invoke(ui, null, false);
            MobMapIcons.LOG.info("scene: jmoptions opened {}", screen == null ? null : screen.getClass().getName());
        } catch (Throwable t) {
            MobMapIcons.LOG.info("scene: jmoptions failed {}", t.toString());
        }
    }

    static void jmReset() {
        try {
            Object ui = Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
            Object mini = ui.getClass().getMethod("getMinimap").invoke(ui);
            mini.getClass().getMethod("reset").invoke(mini);
            MobMapIcons.LOG.info("scene: jmreset done");
        } catch (Throwable t) {
            MobMapIcons.LOG.info("scene: jmreset failed {}", t.toString());
        }
    }

    static void jmSaveCore() {
        try {
            Object client = Class.forName("journeymap.client.JourneymapClient").getMethod("getInstance").invoke(null);
            Object core = client.getClass().getMethod("getCoreProperties").invoke(client);
            core.getClass().getMethod("save").invoke(core);
            MobMapIcons.LOG.info("scene: jmsave core saved");
        } catch (Throwable t) {
            MobMapIcons.LOG.info("scene: jmsave failed {}", t.toString());
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** The one-argument {@code set} that is not a bridge to something odd; works for both map mods' value classes. */
    private static Method setter(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals("set") && m.getParameterCount() == 1 && !m.isBridge()) return m;
        }
        return null;
    }

    private static String state(Object value, Method get) {
        try {
            return (Boolean) get.invoke(value) ? "on" : "off";
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }
}
