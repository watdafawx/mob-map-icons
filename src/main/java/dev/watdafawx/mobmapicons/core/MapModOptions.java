package dev.watdafawx.mobmapicons.core;

import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MapMod;

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
        List<Entry> out = new ArrayList<>();
        try {
            switch (mod) {
                case FTB -> ftb(out);
                case JOURNEYMAP -> journeymap(out);
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

    // ---- JourneyMap: ConfigField members of the active minimap properties ---------------------------

    private static final float[] SCALES = {0.5F, 0.75F, 1.0F, 1.5F, 2.0F};

    private static void journeymap(List<Entry> out) throws ReflectiveOperationException {
        Object client = Class.forName("journeymap.client.JourneymapClient").getMethod("getInstance").invoke(null);
        for (String name : new String[] {"showMobHeading", "showSelf"}) {
            addJm(out, client, name, false);
        }
        for (String name : new String[] {"mobDisplayScale", "selfDisplayScale"}) {
            addJm(out, client, name, true);
        }
    }

    private static void addJm(List<Entry> out, Object client, String name, boolean scale) {
        try {
            // Re-resolve every time: JourneyMap swaps the properties object when the minimap preset changes.
            Supplier<Object> field = () -> {
                try {
                    Object props = client.getClass().getMethod("getActiveMiniMapProperties").invoke(client);
                    Field f = props.getClass().getField(name);
                    return f.get(props);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            };
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
                    syncJmMinimap(client);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    MobMapIcons.LOG.debug("Quick options: JourneyMap write failed", e);
                }
            }));
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobMapIcons.LOG.debug("Quick options: JourneyMap option {} unavailable", name, e);
        }
    }

    /**
     * The minimap holds its own reference to a properties object ({@code MiniMap.miniMapProperties}) that is not
     * always the one {@code getActiveMiniMapProperties()} returns, and it draws mob and self scale from that one.
     * Point it at the object we just wrote, the way JourneyMap's own preset switch does.
     */
    private static void syncJmMinimap(Object client) {
        try {
            Object props = client.getClass().getMethod("getActiveMiniMapProperties").invoke(client);
            Object ui = Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
            Object mini = ui.getClass().getMethod("getMinimap").invoke(ui);
            if (mini == null || props == null) return;
            Object cur = mini.getClass().getMethod("getCurrentMinimapProperties").invoke(mini);
            if (cur == props) return;
            for (Method m : mini.getClass().getMethods()) {
                if (m.getName().equals("setMiniMapProperties") && m.getParameterCount() == 1) {
                    m.invoke(mini, props);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobMapIcons.LOG.debug("JourneyMap minimap not re-pointed", e);
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
            for (String name : new String[] {"showMobHeading", "showSelf", "mobDisplayScale", "selfDisplayScale"}) {
                Object v = props.getClass().getField(name).get(props);
                MobMapIcons.LOG.info("scene: maps jm.{}={}", name, v.getClass().getMethod("get").invoke(v));
            }
            Object ui = Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
            Object mini = ui.getClass().getMethod("getMinimap").invoke(ui);
            Object cur = mini.getClass().getMethod("getCurrentMinimapProperties").invoke(mini);
            Object cf = cur.getClass().getField("mobDisplayScale").get(cur);
            MobMapIcons.LOG.info("scene: maps jm.minimapProps same={} cur.mobDisplayScale={} activeId={}",
                    cur == props, cf.getClass().getMethod("get").invoke(cf), System.identityHashCode(props));
            Object core = client.getClass().getMethod("getCoreProperties").invoke(client);
            Object colour = core.getClass().getField("colorSelf").get(core);
            MobMapIcons.LOG.info("scene: maps jm.colorSelf(live)={}", colour.getClass().getMethod("get").invoke(colour));
        } catch (Throwable t) {
            MobMapIcons.LOG.info("scene: maps jm n/a {}", t.toString());
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
