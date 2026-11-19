package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.InputConstants;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.IconStyle;
import dev.watdafawx.mobmapicons.api.MapMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Live "quick options" panel drawn on top of whatever screen is open, so a style or size change can
 * be judged against the real map while it updates.
 *
 * <p>No mixins: the panel is painted from {@link ScreenEvent.Render.Post} on any screen (FTB Chunks'
 * large map, Xaero's World Map, JourneyMap's fullscreen map, ...) and takes the mouse through the
 * cancelable {@code ScreenEvent.Mouse*.Pre} events, only while the cursor is over it. With no
 * screen open (minimap HUD) it opens {@link HostScreen}, an invisible non-pausing screen, to get a
 * cursor. Closed, every listener returns on a single boolean check: zero render cost.
 *
 * <p>Every control writes through the normal {@link ClientConfig} setters and then calls
 * {@link MobIconCache#checkConfig()}, the same path as {@code /mobmapicons style}, so the icons
 * change on the map at once. The file is saved when a drag ends or a button is pressed.
 */
public final class QuickOptions {

    private static final String CATEGORY = "key.categories.mob_map_icons";

    /** Default unbound: map mods and chat claim most letters. Bind it under Controls > Mob Map Icons. */
    public static final KeyMapping KEY = new KeyMapping("key.mob_map_icons.options", KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CATEGORY);

    private static final int W = 184, ROW = 15, PAD = 5, HEADER = 14;

    private static boolean open;
    /** Screen the panel is painted on; it closes when another screen takes over. */
    private static Screen owner;
    private static boolean pendingOpen;
    private static Integer posX, posY;
    private static boolean draggingPanel;
    private static int dragDx, dragDy;
    private static Row activeSlider;
    private static boolean dirty;
    private static List<Row> rows;

    private QuickOptions() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> e.register(KEY));
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Pre.class, e -> tick());
        bus.addListener(ScreenEvent.Render.Post.class, QuickOptions::onRender);
        bus.addListener(ScreenEvent.KeyPressed.Pre.class, QuickOptions::onKey);
        bus.addListener(ScreenEvent.MouseButtonPressed.Pre.class, QuickOptions::onPress);
        bus.addListener(ScreenEvent.MouseButtonReleased.Pre.class, QuickOptions::onRelease);
        bus.addListener(ScreenEvent.MouseDragged.Pre.class, QuickOptions::onDrag);
        bus.addListener(ScreenEvent.MouseScrolled.Pre.class, QuickOptions::onScroll);
    }

    /** From {@code /mobmapicons options}: the chat screen is still open when the command runs, so open next tick. */
    public static void requestOpen() {
        pendingOpen = true;
    }

    // ---------------------------------------------------------------------------------------------
    // Open / close

    public static boolean isOpen() {
        return open;
    }

    /** Toggles on the current screen, or on a transparent host screen when none is open. */
    public static void toggle() {
        Minecraft mc = Minecraft.getInstance();
        if (open) {
            close();
            return;
        }
        if (mc.level == null) return;
        ctx = detect(mc.screen);
        if (mc.screen == null) mc.setScreen(new HostScreen());
        owner = mc.screen;
        open = true;
        rows = null;
    }

    public static void close() {
        Minecraft mc = Minecraft.getInstance();
        if (open) commit();
        open = false;
        draggingPanel = false;
        activeSlider = null;
        if (mc.screen instanceof HostScreen) mc.setScreen(null);
        owner = null;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (pendingOpen && mc.screen == null) {
            pendingOpen = false;
            if (!open) toggle();
        }
        if (mc.screen == null) {
            while (KEY.consumeClick()) toggle();
        } else if (open && mc.screen instanceof HostScreen) {
            // The host screen frees the cursor, which stops vanilla reading movement keys: feed them from the keyboard.
            long window = mc.getWindow().getWindow();
            for (KeyMapping m : List.of(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                    mc.options.keyJump, mc.options.keyShift, mc.options.keySprint)) {
                InputConstants.Key key = m.getKey();
                if (key.getType() != InputConstants.Type.KEYSYM) continue;
                KeyMapping.set(key, InputConstants.isKeyDown(window, key.getValue()));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Events

    private static boolean active(Screen screen) {
        if (!open) return false;
        if (screen != owner) {
            // Another screen replaced the one the panel was opened on.
            open = false;
            draggingPanel = false;
            activeSlider = null;
            return false;
        }
        return true;
    }

    private static void onKey(ScreenEvent.KeyPressed.Pre e) {
        Screen screen = e.getScreen();
        if (KEY.isActiveAndMatches(InputConstants.getKey(e.getKeyCode(), e.getScanCode()))
                && !(screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox)) {
            if (open) close();
            else {
                toggle();
            }
            e.setCanceled(true);
            return;
        }
        if (!open || !active(screen)) return;
        if (e.getKeyCode() == GLFW.GLFW_KEY_ESCAPE && !(screen instanceof HostScreen)) {
            close();
            e.setCanceled(true);
        }
    }

    private static void onRender(ScreenEvent.Render.Post e) {
        if (!open || !active(e.getScreen())) return;
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = e.getGuiGraphics();
        Screen screen = e.getScreen();
        build();
        int h = height();
        int x = x(screen.width), y = y(screen.height, h);
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        g.fill(x - 1, y - 1, x + W + 1, y + h + 1, 0xFF000000);
        g.fill(x, y, x + W, y + h, 0xC0181820);
        g.fill(x, y, x + W, y + HEADER, 0xE0303048);
        g.drawString(mc.font, Component.translatable("mob_map_icons.options.title"), x + PAD, y + 3, 0xFFFFFF, false);
        int ry = y + HEADER + PAD;
        for (Row r : rows) {
            r.draw(g, mc, x + PAD, ry, W - 2 * PAD, e.getMouseX(), e.getMouseY());
            ry += ROW + 2;
        }
        g.pose().popPose();
    }

    private static boolean over(Screen screen, double mx, double my) {
        int h = height();
        int x = x(screen.width), y = y(screen.height, h);
        return mx >= x && mx < x + W && my >= y && my < y + h;
    }

    private static void onPress(ScreenEvent.MouseButtonPressed.Pre e) {
        Screen screen = e.getScreen();
        if (!open || !active(screen) || !over(screen, e.getMouseX(), e.getMouseY())) return;
        e.setCanceled(true);
        build();
        int h = height();
        int x = x(screen.width), y = y(screen.height, h);
        double mx = e.getMouseX(), my = e.getMouseY();
        if (my < y + HEADER) {
            if (e.getButton() == 0) {
                draggingPanel = true;
                dragDx = (int) mx - x;
                dragDy = (int) my - y;
            }
            return;
        }
        int ry = y + HEADER + PAD;
        for (Row r : rows) {
            if (my >= ry && my < ry + ROW) {
                r.press(x + PAD, W - 2 * PAD, mx, e.getButton());
                if (r instanceof Slider) activeSlider = r;
                break;
            }
            ry += ROW + 2;
        }
    }

    private static void onRelease(ScreenEvent.MouseButtonReleased.Pre e) {
        if (!open) return;
        boolean was = draggingPanel || activeSlider != null;
        draggingPanel = false;
        activeSlider = null;
        commit();
        if (was || over(e.getScreen(), e.getMouseX(), e.getMouseY())) e.setCanceled(true);
    }

    private static void onDrag(ScreenEvent.MouseDragged.Pre e) {
        if (!open || !active(e.getScreen())) return;
        Screen screen = e.getScreen();
        if (draggingPanel) {
            posX = (int) e.getMouseX() - dragDx;
            posY = (int) e.getMouseY() - dragDy;
            e.setCanceled(true);
        } else if (activeSlider != null) {
            int x = x(screen.width);
            ((Slider) activeSlider).drag(x + PAD, W - 2 * PAD, e.getMouseX());
            e.setCanceled(true);
        } else if (over(screen, e.getMouseX(), e.getMouseY())) {
            e.setCanceled(true);
        }
    }

    private static void onScroll(ScreenEvent.MouseScrolled.Pre e) {
        Screen screen = e.getScreen();
        if (!open || !active(screen) || !over(screen, e.getMouseX(), e.getMouseY())) return;
        e.setCanceled(true);
        build();
        int h = height();
        int y = y(screen.height, h);
        int ry = y + HEADER + PAD;
        for (Row r : rows) {
            if (e.getMouseY() >= ry && e.getMouseY() < ry + ROW && r instanceof Slider s) {
                s.step(e.getScrollDeltaY() > 0 ? 1 : -1);
                commit();
            }
            ry += ROW + 2;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Layout

    private static int height() {
        build();
        return HEADER + PAD * 2 + rows.size() * (ROW + 2) - 2;
    }

    private static int x(int screenW) {
        int x = posX != null ? posX : 26;
        return Math.max(0, Math.min(x, screenW - W));
    }

    private static int y(int screenH, int h) {
        int y = posY != null ? posY : (screenH - h) / 2;
        return Math.max(0, Math.min(y, Math.max(0, screenH - h)));
    }

    /** A change was made: apply it to the live icons now; the file is written by {@link #commit()}. */
    private static void changed() {
        dirty = true;
        MobIconCache.checkConfig();
    }

    private static void commit() {
        if (!dirty) return;
        dirty = false;
        try {
            ClientConfig.SPEC.save();
        } catch (RuntimeException ex) {
            MobMapIcons.LOG.warn("Quick options: could not save the config", ex);
        }
    }

    /** Which section the panel edits: GLOBAL, or one loaded map mod. Picked from the open screen, switchable by tab. */
    private static MapMod ctx = MapMod.GLOBAL;

    private static List<MapMod> loadedMods() {
        List<MapMod> out = new ArrayList<>();
        for (MapMod m : new MapMod[] {MapMod.FTB, MapMod.XAERO, MapMod.JOURNEYMAP}) {
            if (ClientConfig.loaded(m)) out.add(m);
        }
        return out;
    }

    /** The map mod whose screen is open (by class name, so no map mod is needed to compile), else the first loaded one. */
    static MapMod detect(Screen screen) {
        String n = screen == null ? "" : screen.getClass().getName();
        if (n.endsWith(".ScreenWrapper")) {
            // FTB Library wraps its own GUI (the large map) in a vanilla screen; ask it what it wraps.
            try {
                n = screen.getClass().getMethod("getGui").invoke(screen).getClass().getName();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // keep the wrapper's name
            }
        }
        MapMod found = null;
        if (n.startsWith("dev.ftb.mods.ftbchunks.")) found = MapMod.FTB;
        else if (n.startsWith("journeymap.client.ui.")) found = MapMod.JOURNEYMAP;
        else if (n.startsWith("xaero.map.") || n.startsWith("xaero.common.gui.") || n.startsWith("xaero.hud.")
                || n.startsWith("xaero.lib.client.gui.")) found = MapMod.XAERO;
        if (found != null && ClientConfig.loaded(found)) return found;
        if (screen == null || screen instanceof HostScreen) {
            if (loadedMods().contains(ctx)) return ctx;
            List<MapMod> l = loadedMods();
            return l.isEmpty() ? MapMod.GLOBAL : l.get(0);
        }
        return MapMod.GLOBAL;
    }

    /** For the dev harness: open the panel on a given section. */
    public static void openOn(String mod) {
        if (open) close();
        toggle();
        for (MapMod m : MapMod.values()) {
            if (m.id().equals(mod) && (m == MapMod.GLOBAL || ClientConfig.loaded(m))) {
                ctx = m;
                rows = null;
            }
        }
    }

    /** Dev harness: panel geometry and rows as "idx y-in-window-pixels type label", for the scene driver. */
    static List<String> describe() {
        List<String> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (!open || screen == null) {
            out.add("panel closed");
            return out;
        }
        build();
        int h = height();
        int x = x(screen.width), y = y(screen.height, h);
        double scale = mc.getWindow().getGuiScale();
        out.add("panel x=" + x + " y=" + y + " w=" + W + " h=" + h + " scale=" + scale
                + " ctx=" + ctx + " px: x=" + (int) ((x + W / 2.0) * scale));
        int ry = y + HEADER + PAD;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String label = r instanceof Action a ? a.entry.key() + "=" + a.entry.value().get() : "";
            out.add("row " + i + " py=" + (int) ((ry + ROW / 2.0) * scale) + " " + r.getClass().getSimpleName() + " " + label);
            ry += ROW + 2;
        }
        return out;
    }

    private static void build() {
        if (rows != null) return;
        rows = new ArrayList<>();
        if (!loadedMods().isEmpty()) rows.add(new Tabs());
        if (ctx == MapMod.GLOBAL) {
            rows.add(new Cycle<>("style", ClientConfig.ICON_STYLE, IconStyle.values()));
            rows.add(new Cycle<>("mode", ClientConfig.ICON_MODE, IconMode.values()));
            rows.add(new IntSlider("iconSize", ClientConfig.ICON_SIZE, 8, 64, 1, null));
            rows.add(new Toggle("rotateWithHeading", ClientConfig.ROTATE_WITH_HEADING));
            rows.add(new Toggle("headingArrow", ClientConfig.HEADING_ARROW));
            rows.add(new Toggle("farmMode", ClientConfig.FARM_MODE));
            rows.add(new Toggle("players", ClientConfig.PLAYERS));
            rows.add(new Toggle("tintByHostility", ClientConfig.TINT_BY_HOSTILITY));
            rows.add(new IntSlider("maxIcons", ClientConfig.MAX_ICONS, 0, 1000, 10, null));
        } else {
            ClientConfig.Section s = ClientConfig.sectionOf(ctx);
            rows.add(new Toggle("enabled", s.enabled));
            rows.add(new Cycle<>("style", s.iconStyle, ClientConfig.StyleChoice.values()));
            rows.add(new Cycle<>("mode", s.iconMode, ClientConfig.ModeChoice.values()));
            rows.add(new IntSlider("iconSize", s.iconSize, 0, 64, 1, "inherit"));
            if (s.rotateWithHeading != null) rows.add(new Cycle<>("tri.rotateWithHeading", s.rotateWithHeading, ClientConfig.Tri.values()));
            if (s.headingArrow != null) rows.add(new Cycle<>("tri.headingArrow", s.headingArrow, ClientConfig.Tri.values()));
            if (s.farmMode != null) rows.add(new Cycle<>("tri.farmMode", s.farmMode, ClientConfig.Tri.values()));
            if (s.players != null) rows.add(new Cycle<>("tri.players", s.players, ClientConfig.Tri.values()));
            if (s.ownPlayer != null) rows.add(new Toggle("ownPlayer", s.ownPlayer));
            if (s.xaeroScale != null) rows.add(new DoubleSlider("xaeroScale", s.xaeroScale, 0.25, 1.0, 0.05));
            if (s.journeymapFrame != null) rows.add(new Cycle<>("journeymapFrame", s.journeymapFrame, ClientConfig.JourneyMapFrame.values()));
            rows.add(new Reset());
            rows.add(new Heading("native"));
            List<MapModOptions.Entry> own = MapModOptions.of(ctx);
            for (MapModOptions.Entry e : own) rows.add(new Action(e));
            if (own.isEmpty()) rows.add(new Heading(ctx == MapMod.XAERO ? "nativeXaero" : "nativeNone"));
        }
        rows.add(new Buttons());
    }

    private static void rebuild() {
        rows = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Rows

    private abstract static class Row {
        abstract void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my);

        abstract void press(int x, int w, double mx, int button);

        static boolean hover(int x, int y, int w, double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + ROW;
        }

        static void box(GuiGraphics g, int x, int y, int w, boolean hover) {
            g.fill(x, y, x + w, y + ROW, hover ? 0xFF58587A : 0xFF3A3A52);
        }

        static void text(GuiGraphics g, Minecraft mc, Component c, int x, int y, int w) {
            g.drawString(mc.font, c, x + (w - mc.font.width(c)) / 2, y + 4, 0xFFFFFF, false);
        }
    }

    private static final class Cycle<T extends Enum<T>> extends Row {
        final String key;
        final ModConfigSpec.EnumValue<T> value;
        final T[] all;

        Cycle(String key, ModConfigSpec.EnumValue<T> value, T[] all) {
            this.key = key;
            this.value = value;
            this.all = all;
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options." + key, value.get().name().toLowerCase(java.util.Locale.ROOT)), x, y, w);
        }

        @Override
        void press(int x, int w, double mx, int button) {
            int i = value.get().ordinal() + (button == 1 ? -1 : 1);
            value.set(all[Math.floorMod(i, all.length)]);
            changed();
            commit();
        }
    }

    private static final class Toggle extends Row {
        final String key;
        final ModConfigSpec.BooleanValue value;

        Toggle(String key, ModConfigSpec.BooleanValue value) {
            this.key = key;
            this.value = value;
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            boolean on = value.get();
            g.fill(x + 3, y + 3, x + 12, y + 12, 0xFF101018);
            if (on) g.fill(x + 4, y + 4, x + 11, y + 11, 0xFF55DD66);
            g.drawString(mc.font, Component.translatable("mob_map_icons.options." + key), x + 17, y + 4, 0xFFFFFF, false);
        }

        @Override
        void press(int x, int w, double mx, int button) {
            value.set(!value.get());
            changed();
            commit();
        }
    }

    private abstract static class Slider extends Row {
        final String key;
        final double min, max, step;

        Slider(String key, double min, double max, double step) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
        }

        abstract double get();

        abstract void set(double v);

        abstract String show();

        void apply(double raw) {
            double v = Math.round(Math.max(min, Math.min(max, raw)) / step) * step;
            v = Math.max(min, Math.min(max, v));
            if (Math.abs(v - get()) > step / 4) {
                set(v);
                changed();
            }
        }

        void step(int dir) {
            apply(get() + dir * step);
        }

        void drag(int x, int w, double mx) {
            apply(min + (max - min) * Math.max(0, Math.min(1, (mx - x) / w)));
        }

        @Override
        void press(int x, int w, double mx, int button) {
            drag(x, w, mx);
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my) || activeSlider == this);
            int fill = (int) (w * Math.max(0, Math.min(1, (get() - min) / (max - min))));
            g.fill(x, y, x + fill, y + ROW, 0xFF4A6FB0);
            text(g, mc, Component.translatable("mob_map_icons.options." + key, show()), x, y, w);
        }
    }

    /** Tab bar: Global plus one tab per loaded map mod. */
    private static final class Tabs extends Row {
        private List<MapMod> tabs() {
            List<MapMod> t = new ArrayList<>();
            t.add(MapMod.GLOBAL);
            t.addAll(loadedMods());
            return t;
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            List<MapMod> t = tabs();
            int cell = w / t.size();
            for (int i = 0; i < t.size(); i++) {
                int cx = x + i * cell, cw = i == t.size() - 1 ? w - i * cell : cell - 1;
                boolean sel = t.get(i) == ctx;
                g.fill(cx, y, cx + cw, y + ROW, sel ? 0xFF4A6FB0 : hover(cx, y, cw, mx, my) ? 0xFF58587A : 0xFF3A3A52);
                text(g, mc, Component.translatable("mob_map_icons.options.tab." + t.get(i).id()), cx, y, cw);
            }
        }

        @Override
        void press(int x, int w, double mx, int button) {
            List<MapMod> t = tabs();
            int cell = w / t.size();
            ctx = t.get(Math.max(0, Math.min(t.size() - 1, (int) ((mx - x) / cell))));
            rebuild();
        }
    }

    /** A small dim caption row. */
    private static final class Heading extends Row {
        final String key;

        Heading(String key) {
            this.key = key;
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            g.drawString(mc.font, Component.translatable("mob_map_icons.options." + key), x + 2, y + 4, 0xFFA0A0B8, false);
        }

        @Override
        void press(int x, int w, double mx, int button) {}
    }

    /** "Inherit global": puts every override of this map mod back to inherit. */
    private static final class Reset extends Row {
        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options.inheritAll"), x, y, w);
        }

        @Override
        void press(int x, int w, double mx, int button) {
            ClientConfig.Section s = ClientConfig.sectionOf(ctx);
            s.iconStyle.set(ClientConfig.StyleChoice.INHERIT);
            s.iconMode.set(ClientConfig.ModeChoice.INHERIT);
            s.iconSize.set(0);
            if (s.players != null) s.players.set(ClientConfig.Tri.INHERIT);
            if (s.rotateWithHeading != null) s.rotateWithHeading.set(ClientConfig.Tri.INHERIT);
            if (s.headingArrow != null) s.headingArrow.set(ClientConfig.Tri.INHERIT);
            if (s.farmMode != null) s.farmMode.set(ClientConfig.Tri.INHERIT);
            changed();
            commit();
        }
    }

    /** One of the map mod's own options (see {@link MapModOptions}); every call is guarded. */
    private static final class Action extends Row {
        final MapModOptions.Entry entry;

        Action(MapModOptions.Entry entry) {
            this.entry = entry;
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            String v;
            try {
                v = entry.value().get();
            } catch (RuntimeException e) {
                v = "?";
            }
            text(g, mc, Component.translatable("mob_map_icons.options.native." + entry.key(), v), x, y, w);
        }

        @Override
        void press(int x, int w, double mx, int button) {
            try {
                entry.press().accept(button == 1 ? -1 : 1);
            } catch (RuntimeException e) {
                MobMapIcons.LOG.debug("Quick options: native option failed", e);
            }
        }
    }

    private static final class IntSlider extends Slider {
        final ModConfigSpec.IntValue value;
        final String zeroLabel;

        IntSlider(String key, ModConfigSpec.IntValue value, int min, int max, int step, String zeroLabel) {
            super(key, min, max, step);
            this.value = value;
            this.zeroLabel = zeroLabel;
        }

        @Override
        double get() { return value.get(); }

        @Override
        void set(double v) { value.set((int) Math.round(v)); }

        @Override
        String show() { return value.get() == 0 && zeroLabel != null ? zeroLabel : Integer.toString(value.get()); }
    }

    private static final class DoubleSlider extends Slider {
        final ModConfigSpec.DoubleValue value;

        DoubleSlider(String key, ModConfigSpec.DoubleValue value, double min, double max, double step) {
            super(key, min, max, step);
            this.value = value;
        }

        @Override
        double get() { return value.get(); }

        @Override
        void set(double v) { value.set(Math.round(v * 100) / 100.0); }

        @Override
        String show() { return String.format(java.util.Locale.ROOT, "%.2f", value.get()); }
    }

    /** "Full config" and "Close" side by side. */
    private static final class Buttons extends Row {
        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            int half = (w - 4) / 2;
            box(g, x, y, half, hover(x, y, half, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options.fullConfig"), x, y, half);
            box(g, x + half + 4, y, half, hover(x + half + 4, y, half, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options.close"), x + half + 4, y, half);
        }

        @Override
        void press(int x, int w, double mx, int button) {
            int half = (w - 4) / 2;
            if (mx < x + half) {
                Minecraft mc = Minecraft.getInstance();
                Screen parent = mc.screen instanceof HostScreen ? null : mc.screen;
                close();
                net.neoforged.fml.ModList.get().getModContainerById(MobMapIcons.MODID).ifPresent(c ->
                        mc.setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(c, parent)));
            } else {
                close();
            }
        }
    }

    /** Invisible, non-pausing screen: gives the in-world HUD a cursor without dimming or blurring anything. */
    private static final class HostScreen extends Screen {
        HostScreen() {
            super(Component.empty());
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void removed() {
            if (open && owner == this) {
                commit();
                open = false;
                owner = null;
                draggingPanel = false;
                activeSlider = null;
            }
        }
    }
}
