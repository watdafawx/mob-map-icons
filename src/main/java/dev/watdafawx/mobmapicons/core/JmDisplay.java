package dev.watdafawx.mobmapicons.core;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MapView;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * JourneyMap's own map properties, reached by reflection (nothing here needs JourneyMap to compile), and the
 * "auto icon display" rule built on them.
 *
 * <p>JourneyMap draws a dot or pin round every mob and player unless the map's {@code mobDisplay} and
 * {@code playerDisplay} options are {@code Icons}. With {@code [journeymap] autoIconDisplay} on and our adapter
 * enabled, {@link #tick()} sets both to {@code Icons} for the minimap and the full-screen map, remembering what
 * the user had (also on disk, so a restart does not lose it) and putting it back when the adapter is disabled or
 * the option turned off. A value the user changed away from {@code Icons} in the meantime is left alone.
 *
 * <p>Every step is guarded: with a different JourneyMap version a missing class or field means no row and no
 * auto-switch, never an exception.
 */
public final class JmDisplay {
    public static final String ICONS = "Icons";
    public static final String[] DISPLAY_FIELDS = {"mobDisplay", "playerDisplay"};

    private static final Map<String, String> SAVED = loadSaved();
    private static int ticks;
    private static boolean broken;
    /** Per view: does JourneyMap draw its marker (pin) round a mob icon? Refreshed once a second by {@link #tick()}. */
    private static final boolean[] MARKER = {true, true};

    private JmDisplay() {}

    // ---------------------------------------------------------------------------------------------
    // Reflection helpers (also used by MapModOptions)

    static Object client() throws ReflectiveOperationException {
        return Class.forName("journeymap.client.JourneymapClient").getMethod("getInstance").invoke(null);
    }

    /** The properties object for a view: the active minimap preset, or the full-screen map's. {@code null} when unreadable. */
    static Object props(MapView view) {
        try {
            Object client = client();
            return client.getClass().getMethod(view == MapView.FULLSCREEN ? "getFullMapProperties" : "getActiveMiniMapProperties").invoke(client);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A {@code ConfigField} member of a properties object, or {@code null}. */
    static Object field(Object props, String name) {
        try {
            return props == null ? null : props.getClass().getField(name).get(props);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static Object get(Object field) throws ReflectiveOperationException {
        return field.getClass().getMethod("get").invoke(field);
    }

    static void set(Object field, Object value) throws ReflectiveOperationException {
        for (Method m : field.getClass().getMethods()) {
            if (m.getName().equals("set") && m.getParameterCount() == 1 && !m.isBridge()) {
                m.invoke(field, value);
                return;
            }
        }
        throw new NoSuchMethodException("set");
    }

    static void save(Object field) throws ReflectiveOperationException {
        field.getClass().getMethod("save").invoke(field);
    }

    static String name(Object value) {
        return value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }

    static Object[] constants(Object enumValue) {
        return enumValue instanceof Enum<?> e ? e.getDeclaringClass().getEnumConstants() : new Object[0];
    }

    static Object constant(Object sample, String name) {
        for (Object c : constants(sample)) if (name(c).equals(name)) return c;
        return null;
    }

    /**
     * The minimap holds its own reference to a properties object that is not always the one
     * {@code getActiveMiniMapProperties()} returns, and draws from that one. Point it at the object we wrote.
     */
    static void syncMinimap() {
        try {
            Object client = client();
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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            MobMapIcons.LOG.debug("JourneyMap minimap not re-pointed", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Quick-panel rows: cycle a display option

    /**
     * Steps {@code mobDisplay} / {@code playerDisplay} of {@code view} through every value JourneyMap offers,
     * writes it live, saves JourneyMap's file. Picking anything but Icons while autoIconDisplay is on turns that
     * option off first (otherwise {@link #tick()} would put Icons straight back).
     */
    static void cycle(MapView view, String fieldName, int dir) throws ReflectiveOperationException {
        Object field = field(props(view), fieldName);
        if (field == null) return;
        Object cur = get(field);
        Object[] all = constants(cur);
        if (all.length == 0) return;
        int i = 0;
        for (int k = 0; k < all.length; k++) if (all[k] == cur) i = k;
        Object next = all[Math.floorMod(i + (dir < 0 ? -1 : 1), all.length)];
        if (!ICONS.equals(name(next)) && ClientConfig.autoIconDisplay()) {
            ClientConfig.JOURNEYMAP.autoIconDisplay.set(false);
            try {
                ClientConfig.SPEC.save();
            } catch (RuntimeException e) {
                MobMapIcons.LOG.warn("Could not save the config", e);
            }
        }
        set(field, next);
        save(field);
        refreshMarkers();
        if (view == MapView.MINIMAP) syncMinimap();
    }

    static String show(MapView view, String fieldName) {
        try {
            Object field = field(props(view), fieldName);
            return field == null ? "?" : name(get(field));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Auto icon display

    /**
     * Whether JourneyMap draws its marker (the pin) round a mob icon on {@code view}: only the two "Dots and ..."
     * displays do. With {@code Icons} or {@code OutlinedIcons} the icon is drawn alone, so an icon fitted into a
     * marker that is not there would look small and edgeless. {@code true} when unreadable (the old behaviour).
     */
    public static boolean markerShown(MapView view) {
        return MARKER[view.ordinal()];
    }

    private static void refreshMarkers() {
        for (MapView view : MapView.values()) {
            boolean shown = true;
            try {
                Object field = field(props(view), "mobDisplay");
                if (field != null) {
                    String n = name(get(field));
                    shown = !(ICONS.equals(n) || "OutlinedIcons".equals(n));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // keep the marker
            }
            MARKER[view.ordinal()] = shown;
        }
    }

    /**
     * On shutdown: puts the user's remembered display values back into JourneyMap's files, so JourneyMap never
     * keeps our {@code Icons} on disk while this mod is gone. The remembered values stay on file so the next
     * start (adapter and {@code autoIconDisplay} on) switches to Icons again, like {@link #tick()} does.
     */
    public static void restoreOnShutdown() {
        if (broken || SAVED.isEmpty()) return;
        try {
            for (MapView view : MapView.values()) {
                Object props = props(view);
                for (String name : DISPLAY_FIELDS) {
                    String old = SAVED.get(view.name().toLowerCase(java.util.Locale.ROOT) + "." + name);
                    Object field = field(props, name);
                    if (old == null || field == null) continue;
                    Object v = get(field);
                    if (!ICONS.equals(name(v))) continue; // the user already changed it: leave it alone
                    Object back = constant(v, old);
                    if (back == null) continue;
                    set(field, back);
                    save(field);
                    MobMapIcons.LOG.info("JourneyMap {}.{} put back to {} for shutdown", view.name().toLowerCase(java.util.Locale.ROOT), name, old);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            MobMapIcons.LOG.warn("Could not put JourneyMap's display options back on shutdown", e);
        }
    }

    /** Called every client tick; acts about once a second. */
    public static void tick() {
        if (broken) return;
        ticks++;
        if (ticks % 4 == 0) refreshMarkers(); // quick, so a switch to or from Icons is drawn right within ~0.2 s
        if (ticks % 20 != 0) return;
        try {
            boolean want = ClientConfig.journeyMapEnabled() && ClientConfig.autoIconDisplay();
            if (!want && SAVED.isEmpty()) return;
            boolean changed = false, minimap = false;
            for (MapView view : MapView.values()) {
                Object props = props(view);
                for (String name : DISPLAY_FIELDS) {
                    Object field = field(props, name);
                    if (field == null) continue;
                    Object v = get(field);
                    String key = view.name().toLowerCase(java.util.Locale.ROOT) + "." + name;
                    if (want) {
                        if (ICONS.equals(name(v))) continue;
                        Object icons = constant(v, ICONS);
                        if (icons == null) continue;
                        SAVED.put(key, name(v)); // whatever is there now is the user's value
                        set(field, icons);
                        save(field);
                        changed = true;
                        minimap |= view == MapView.MINIMAP;
                        MobMapIcons.LOG.info("JourneyMap {} was {}: set to Icons so it draws no dot round our icons", key, name(v));
                    } else if (SAVED.containsKey(key)) {
                        String old = SAVED.remove(key);
                        changed = true;
                        if (ICONS.equals(name(v))) {
                            Object back = constant(v, old);
                            if (back != null) {
                                set(field, back);
                                save(field);
                                minimap |= view == MapView.MINIMAP;
                                MobMapIcons.LOG.info("JourneyMap {} put back to {}", key, old);
                            }
                        }
                    }
                }
            }
            if (minimap) syncMinimap();
            if (changed) persist();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            MobMapIcons.LOG.warn("JourneyMap's mob/player display options are not readable; leaving them alone", e);
        }
    }

    /** Dev harness: what is remembered. */
    static String savedSummary() {
        return SAVED.toString();
    }

    private static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("mob_map_icons-journeymap-saved-display.properties");
    }

    private static Map<String, String> loadSaved() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                Properties p = new Properties();
                try (var in = Files.newBufferedReader(f)) {
                    p.load(in);
                }
                p.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
            }
        } catch (Throwable ignored) {
            // nothing remembered
        }
        return out;
    }

    private static void persist() {
        try {
            Path f = file();
            if (SAVED.isEmpty()) {
                Files.deleteIfExists(f);
                return;
            }
            Properties p = new Properties();
            p.putAll(SAVED);
            try (var out = Files.newBufferedWriter(f)) {
                p.store(out, "Mob Map Icons: JourneyMap display values from before autoIconDisplay switched them to Icons");
            }
        } catch (java.io.IOException | RuntimeException e) {
            MobMapIcons.LOG.warn("Could not remember JourneyMap's previous display options", e);
        }
    }
}
