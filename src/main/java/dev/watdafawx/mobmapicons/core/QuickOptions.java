package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.platform.InputConstants;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.IconStyle;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MapView;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

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
 * <p><b>Built for speed.</b> One compact page holds everything people change often: style, mode, icon size,
 * heading, arrow, farm, players, own icon. Style, mode and the on/off/inherit rows cycle on a left click (right
 * click or shift+click goes back, the mouse wheel steps either way), so changing a map mod's style and size is
 * at most three clicks (its tab, style, size) and two when the panel was opened over that map. The size row
 * edits the size of the view you are on, the full-screen map's when opened over one, else the minimap's; its
 * small button switches to the other view. Rarely used rows (the map mod's own options, limits) sit on a second
 * "More" page.
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

    private static final int W = 224, ROW = 14, GAP = 2, PAD = 5, HEADER = 14;
    private static final int[] SIZE_PRESETS = {8, 12, 16, 20, 24, 32, 40, 48, 64};

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
    /** 0 = the compact page, 1 = "More". */
    private static int page;
    /** Which map's icon size the size row edits. */
    private static MapView sizeView = MapView.MINIMAP;
    /** Dev harness: clicks that landed on a control since the panel opened or the counter was reset. */
    private static int clicks;

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
        sizeView = detectView(mc.screen);
        page = 0;
        clicks = 0;
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
            ry += ROW + GAP;
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
                clicks++;
                r.press(x + PAD, W - 2 * PAD, mx, e.getButton() == 1 || Screen.hasShiftDown() ? -1 : 1, e.getButton());
                if (r instanceof Slider) activeSlider = r;
                break;
            }
            ry += ROW + GAP;
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
        int x = x(screen.width), y = y(screen.height, h);
        int ry = y + HEADER + PAD;
        for (Row r : rows) {
            if (e.getMouseY() >= ry && e.getMouseY() < ry + ROW) {
                r.wheel(x + PAD, W - 2 * PAD, e.getMouseX(), e.getScrollDeltaY() > 0 ? 1 : -1);
                commit();
                break;
            }
            ry += ROW + GAP;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Layout

    private static int height() {
        build();
        return HEADER + PAD * 2 + rows.size() * (ROW + GAP) - GAP;
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

    /** The class name of the screen, looking through FTB Library's wrapper screen. */
    private static String screenClass(Screen screen) {
        String n = screen == null ? "" : screen.getClass().getName();
        if (n.endsWith(".ScreenWrapper")) {
            // FTB Library wraps its own GUI (the large map) in a vanilla screen; ask it what it wraps.
            try {
                n = screen.getClass().getMethod("getGui").invoke(screen).getClass().getName();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // keep the wrapper's name
            }
        }
        return n;
    }

    /** The map mod whose screen is open (by class name, so no map mod is needed to compile), else the first loaded one. */
    static MapMod detect(Screen screen) {
        String n = screenClass(screen);
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

    /** FULLSCREEN over a map mod's full-screen map screen (FTB large map, JourneyMap fullscreen, Xaero world map), else MINIMAP. */
    static MapView detectView(Screen screen) {
        String n = screenClass(screen);
        boolean full = n.contains("LargeMapScreen") || n.startsWith("journeymap.client.ui.fullscreen.")
                || n.startsWith("xaero.map.gui.GuiMap");
        return full ? MapView.FULLSCREEN : MapView.MINIMAP;
    }

    /** For the dev harness: open the panel on a given section (and optionally the size view: {@code ftb:fullscreen}). */
    public static void openOn(String arg) {
        String mod = arg, view = null;
        int colon = arg.indexOf(':');
        if (colon > 0) {
            mod = arg.substring(0, colon);
            view = arg.substring(colon + 1);
        }
        if (open) close();
        toggle();
        for (MapMod m : MapMod.values()) {
            if (m.id().equals(mod) && (m == MapMod.GLOBAL || ClientConfig.loaded(m))) {
                ctx = m;
                rows = null;
            }
        }
        if (view != null) sizeView = "fullscreen".equalsIgnoreCase(view) ? MapView.FULLSCREEN : MapView.MINIMAP;
    }

    static int clicks() {
        return clicks;
    }

    static void resetClicks() {
        clicks = 0;
    }

    /** Dev harness: panel geometry and rows as "row idx cell px py Type label", for the scene driver. */
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
                + " ctx=" + ctx + " page=" + page + " sizeView=" + sizeView + " clicks=" + clicks
                + " px: x=" + (int) ((x + W / 2.0) * scale));
        int ry = y + HEADER + PAD;
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).describe(out, i, x + PAD, ry, W - 2 * PAD, scale);
            ry += ROW + GAP;
        }
        return out;
    }

    private static String pretty(String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static void build() {
        if (rows != null) return;
        rows = new ArrayList<>();
        if (!loadedMods().isEmpty()) rows.add(new Tabs());
        if (page == 1) {
            buildMore();
        } else if (ctx == MapMod.GLOBAL) {
            rows.add(new Cycle<>("style", ClientConfig.ICON_STYLE, IconStyle.values(), v -> pretty(v.name())));
            rows.add(new Cycle<>("mode", ClientConfig.ICON_MODE, IconMode.values(), v -> pretty(v.name())));
            rows.add(new SizeRow(MapMod.GLOBAL));
            rows.add(new Pair(new Toggle("rotateWithHeading", ClientConfig.ROTATE_WITH_HEADING), new Toggle("headingArrow", ClientConfig.HEADING_ARROW)));
            rows.add(new Pair(new Toggle("farmMode", ClientConfig.FARM_MODE), new Toggle("players", ClientConfig.PLAYERS)));
            rows.add(new Pair(new Toggle("tintByHostility", ClientConfig.TINT_BY_HOSTILITY), new MoreButton(true)));
        } else {
            ClientConfig.Section s = ClientConfig.sectionOf(ctx);
            rows.add(new Pair(new Toggle("enabled", s.enabled), new SameAsGlobal()));
            rows.add(new Cycle<>("style", s.iconStyle, ClientConfig.StyleChoice.values(),
                    v -> v == ClientConfig.StyleChoice.INHERIT ? pretty(ClientConfig.iconStyle().name()) + " (g)" : pretty(v.name()))
                    .skipInherited(() -> ClientConfig.iconStyle().name()));
            rows.add(new Cycle<>("mode", s.iconMode, ClientConfig.ModeChoice.values(),
                    v -> v == ClientConfig.ModeChoice.INHERIT ? pretty(ClientConfig.iconMode().name()) + " (g)" : pretty(v.name()))
                    .skipInherited(() -> ClientConfig.iconMode().name()));
            rows.add(new SizeRow(ctx));
            if (s.rotateWithHeading != null) {
                rows.add(new Pair(tri("rotate", s.rotateWithHeading, ClientConfig.ROTATE_WITH_HEADING),
                        tri("arrow", s.headingArrow, ClientConfig.HEADING_ARROW)));
                rows.add(new Pair(tri("farm", s.farmMode, ClientConfig.FARM_MODE), tri("players", s.players, ClientConfig.PLAYERS)));
            }
            if (ctx == MapMod.XAERO) {
                rows.add(new DoubleSlider("xaeroScale", s.xaeroScale, 0.25, 1.0, 0.05));
            }
            Row left = s.ownPlayer != null ? new Toggle("ownPlayer", s.ownPlayer) : null;
            Row right = ctx == MapMod.JOURNEYMAP ? new Cycle<>("journeymapFrame", s.journeymapFrame, ClientConfig.JourneyMapFrame.values(),
                    v -> pretty(v.name())) : null;
            if (left != null && right != null) rows.add(new Pair(left, right));
            else if (left != null) rows.add(new Pair(left, new MoreButton(true)));
            if (ctx == MapMod.JOURNEYMAP) {
                List<MapModOptions.Entry> own = MapModOptions.of(ctx, () -> sizeView);
                Row mobDisplay = null, playerDisplay = null;
                for (MapModOptions.Entry e : own) {
                    if (e.key().equals("jm.mobDisplay")) mobDisplay = new Action(e);
                    if (e.key().equals("jm.playerDisplay")) playerDisplay = new Action(e);
                }
                if (mobDisplay != null && playerDisplay != null) rows.add(new Pair(mobDisplay, playerDisplay));
                rows.add(new Pair(new Toggle("autoIconDisplay", s.autoIconDisplay), new MoreButton(true)));
            } else if (left == null || right != null) {
                rows.add(new MoreButton(true));
            }
        }
        rows.add(new Buttons());
    }

    /** Page 2: the map mod's own options and the limits. */
    private static void buildMore() {
        rows.add(new MoreButton(false));
        if (ctx != MapMod.GLOBAL) {
            List<MapModOptions.Entry> own = MapModOptions.of(ctx, () -> sizeView);
            own.removeIf(e -> e.key().equals("jm.mobDisplay") || e.key().equals("jm.playerDisplay"));
            if (!own.isEmpty()) rows.add(new Heading("native"));
            for (int i = 0; i < own.size(); i += 2) {
                Row a = new Action(own.get(i));
                rows.add(i + 1 < own.size() ? new Pair(a, new Action(own.get(i + 1))) : a);
            }
            if (own.isEmpty()) rows.add(new Heading(ctx == MapMod.XAERO ? "nativeXaero" : "nativeNone"));
        }
        rows.add(new Heading("limits"));
        rows.add(new IntSlider("maxIcons", ClientConfig.MAX_ICONS, 0, 1000, 10, null));
        rows.add(new IntSlider("clusterRadius", ClientConfig.CLUSTER_RADIUS, 2, 64, 1, null));
    }

    private static Row tri(String key, ModConfigSpec.EnumValue<ClientConfig.Tri> value, ModConfigSpec.BooleanValue global) {
        return new Cycle<>("tri." + key, value, ClientConfig.Tri.values(), v -> v == ClientConfig.Tri.INHERIT
                ? (global.get() ? "on" : "off") + " (g)" : v == ClientConfig.Tri.ON ? "on" : "off")
                .skipInherited(() -> global.get() ? "ON" : "OFF");
    }

    private static void rebuild() {
        rows = null;
    }

    // ---------------------------------------------------------------------------------------------
    // Rows

    private abstract static class Row {
        /** Draws the row in the box {@code (x, y, w, ROW)}. */
        abstract void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my);

        /** A click at {@code mx}; {@code dir} is +1, or -1 for a right click or shift+click. */
        abstract void press(int x, int w, double mx, int dir, int button);

        /** The mouse wheel over the row: +1 up, -1 down. */
        void wheel(int x, int w, double mx, int dir) {}

        String label() {
            return "";
        }

        void describe(List<String> out, int idx, int x, int y, int w, double scale) {
            cell(out, idx, 0, x, y, w, scale, this);
        }

        static void cell(List<String> out, int idx, int c, int x, int y, int w, double scale, Row r) {
            out.add("row " + idx + " c" + c + " px=" + (int) ((x + w / 2.0) * scale) + " py=" + (int) ((y + ROW / 2.0) * scale)
                    + " " + r.getClass().getSimpleName() + " " + r.label());
        }

        static boolean hover(int x, int y, int w, double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + ROW;
        }

        static void box(GuiGraphics g, int x, int y, int w, boolean hover) {
            g.fill(x, y, x + w, y + ROW, hover ? 0xFF58587A : 0xFF3A3A52);
        }

        static void text(GuiGraphics g, Minecraft mc, Component c, int x, int y, int w) {
            g.drawString(mc.font, c, x + (w - mc.font.width(c)) / 2, y + 3, 0xFFFFFF, false);
        }
    }

    /** Two controls side by side. */
    private static final class Pair extends Row {
        final Row left, right;

        Pair(Row left, Row right) {
            this.left = left;
            this.right = right;
        }

        private int half(int w) {
            return (w - GAP) / 2;
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            int h = half(w);
            left.draw(g, mc, x, y, h, mx, my);
            right.draw(g, mc, x + h + GAP, y, w - h - GAP, mx, my);
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
            int h = half(w);
            if (mx < x + h + GAP / 2.0) left.press(x, h, mx, dir, button);
            else right.press(x + h + GAP, w - h - GAP, mx, dir, button);
        }

        @Override
        void wheel(int x, int w, double mx, int dir) {
            int h = half(w);
            if (mx < x + h + GAP / 2.0) left.wheel(x, h, mx, dir);
            else right.wheel(x + h + GAP, w - h - GAP, mx, dir);
        }

        @Override
        void describe(List<String> out, int idx, int x, int y, int w, double scale) {
            int h = half(w);
            cell(out, idx, 0, x, y, h, scale, left);
            cell(out, idx, 1, x + h + GAP, y, w - h - GAP, scale, right);
        }
    }

    /** One enum option that steps on a click (right click or shift+click: back) and on the mouse wheel. */
    private static final class Cycle<T extends Enum<T>> extends Row {
        final String key;
        final ModConfigSpec.EnumValue<T> value;
        final T[] all;
        final Function<T, String> display;

        /** Name of the global value an INHERIT entry resolves to; stepping off INHERIT skips that name (a click must change something). */
        Supplier<String> inherited;

        Cycle(String key, ModConfigSpec.EnumValue<T> value, T[] all, Function<T, String> display) {
            this.key = key;
            this.value = value;
            this.all = all;
            this.display = display;
        }

        Cycle<T> skipInherited(Supplier<String> inherited) {
            this.inherited = inherited;
            return this;
        }

        @Override
        String label() {
            return key + "=" + display.apply(value.get());
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options." + key, display.apply(value.get())), x, y, w);
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
            step(dir);
        }

        @Override
        void wheel(int x, int w, double mx, int dir) {
            step(dir);
        }

        private void step(int dir) {
            T cur = value.get();
            T next = all[Math.floorMod(cur.ordinal() + dir, all.length)];
            if (inherited != null && cur.name().equals("INHERIT") && next.name().equals(inherited.get())) {
                next = all[Math.floorMod(next.ordinal() + dir, all.length)];
            }
            value.set(next);
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
        String label() {
            return key + "=" + (value.get() ? "on" : "off");
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            boolean on = value.get();
            g.fill(x + 3, y + 2, x + 12, y + 11, 0xFF101018);
            if (on) g.fill(x + 4, y + 3, x + 11, y + 10, 0xFF55DD66);
            g.drawString(mc.font, Component.translatable("mob_map_icons.options." + key), x + 17, y + 3, 0xFFFFFF, false);
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
            value.set(!value.get());
            changed();
            commit();
        }

        @Override
        void wheel(int x, int w, double mx, int dir) {
            boolean want = dir > 0;
            if (value.get() != want) {
                value.set(want);
                changed();
                commit();
            }
        }
    }

    /**
     * The icon size of one view of one map mod. Click: next preset (right click or shift: previous), wheel: one
     * pixel. The small button at the right switches between the minimap's size and the full-screen map's.
     * 0 on a map mod's tab means "the global size" and is shown as that size marked (g).
     */
    private static final class SizeRow extends Row {
        private static final int TOGGLE_W = 38;
        final MapMod mod;

        SizeRow(MapMod mod) {
            this.mod = mod;
        }

        private boolean hasToggle() {
            return mod != MapMod.XAERO;
        }

        private MapView view() {
            return hasToggle() ? sizeView : MapView.MINIMAP;
        }

        private ModConfigSpec.IntValue value() {
            return ClientConfig.sizeValue(mod, view());
        }

        private int min() {
            return mod == MapMod.GLOBAL ? 8 : 0;
        }

        private String shown() {
            int v = value().get();
            return v == 0 ? ClientConfig.iconSize(view()) + " (g)" : Integer.toString(v);
        }

        @Override
        String label() {
            return "size." + view().name().toLowerCase(Locale.ROOT) + "=" + value().get();
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            int lw = hasToggle() ? w - TOGGLE_W - GAP : w;
            box(g, x, y, lw, hover(x, y, lw, mx, my));
            String key = hasToggle() ? "mob_map_icons.options.iconSize." + view().name().toLowerCase(Locale.ROOT) : "mob_map_icons.options.iconSize.xaero";
            text(g, mc, Component.translatable(key, shown()), x, y, lw);
            if (hasToggle()) {
                int bx = x + lw + GAP;
                g.fill(bx, y, bx + TOGGLE_W, y + ROW, hover(bx, y, TOGGLE_W, mx, my) ? 0xFF6A8FD0 : 0xFF4A6FB0);
                text(g, mc, Component.translatable("mob_map_icons.options.view." + view().name().toLowerCase(Locale.ROOT)), bx, y, TOGGLE_W);
            }
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
            if (hasToggle() && mx >= x + w - TOGGLE_W) {
                sizeView = sizeView == MapView.MINIMAP ? MapView.FULLSCREEN : MapView.MINIMAP;
                return;
            }
            int cur = value().get();
            // Off "inherit" (0), step from the size the map really shows, so the first click always changes the look.
            int basis = cur == 0 ? ClientConfig.iconSize(view()) : cur;
            List<Integer> presets = new ArrayList<>();
            if (min() == 0) presets.add(0);
            for (int p : SIZE_PRESETS) presets.add(p);
            int next;
            if (dir > 0) {
                next = presets.get(0);
                for (int p : presets) if (p > basis) { next = p; break; }
            } else {
                next = presets.get(presets.size() - 1);
                for (int i = presets.size() - 1; i >= 0; i--) if (presets.get(i) < basis && (cur != 0 || presets.get(i) != 0)) { next = presets.get(i); break; }
            }
            set(next);
        }

        @Override
        void wheel(int x, int w, double mx, int dir) {
            int cur = value().get();
            int next = cur + dir;
            if (cur == 0 && dir > 0) next = 8;
            else if (cur <= 8 && dir < 0) next = min();
            set(Math.max(min(), Math.min(64, next)));
        }

        private void set(int v) {
            if (v == value().get()) return;
            value().set(v);
            changed();
            commit();
        }

        @Override
        void describe(List<String> out, int idx, int x, int y, int w, double scale) {
            int lw = hasToggle() ? w - TOGGLE_W - GAP : w;
            cell(out, idx, 0, x, y, lw, scale, this);
            if (hasToggle()) out.add("row " + idx + " c1 px=" + (int) ((x + lw + GAP + TOGGLE_W / 2.0) * scale)
                    + " py=" + (int) ((y + ROW / 2.0) * scale) + " ViewToggle " + view());
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

        void stepBy(int dir) {
            apply(get() + dir * step);
        }

        void drag(int x, int w, double mx) {
            apply(min + (max - min) * Math.max(0, Math.min(1, (mx - x) / w)));
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
            drag(x, w, mx);
        }

        @Override
        void wheel(int x, int w, double mx, int dir) {
            stepBy(dir);
        }

        @Override
        String label() {
            return key + "=" + show();
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
        void press(int x, int w, double mx, int dir, int button) {
            List<MapMod> t = tabs();
            int cell = w / t.size();
            ctx = t.get(Math.max(0, Math.min(t.size() - 1, (int) ((mx - x) / cell))));
            rebuild();
        }

        @Override
        void describe(List<String> out, int idx, int x, int y, int w, double scale) {
            List<MapMod> t = tabs();
            int cell = w / t.size();
            for (int i = 0; i < t.size(); i++) {
                out.add("row " + idx + " c" + i + " px=" + (int) ((x + i * cell + cell / 2.0) * scale) + " py="
                        + (int) ((y + ROW / 2.0) * scale) + " Tab " + t.get(i).id() + (t.get(i) == ctx ? " (selected)" : ""));
            }
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
            g.drawString(mc.font, Component.translatable("mob_map_icons.options." + key), x + 2, y + 3, 0xFFA0A0B8, false);
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {}
    }

    /** What a map mod's tab remembers of its overrides before "same as global" cleared them. */
    private static final Map<MapMod, int[]> SNAPSHOTS = new EnumMap<>(MapMod.class);

    private static List<ModConfigSpec.ConfigValue<?>> overrides(ClientConfig.Section s) {
        List<ModConfigSpec.ConfigValue<?>> l = new ArrayList<>();
        l.add(s.iconStyle);
        l.add(s.iconMode);
        l.add(s.iconSizeMinimap);
        if (s.iconSizeFullscreen != null) l.add(s.iconSizeFullscreen);
        if (s.rotateWithHeading != null) l.add(s.rotateWithHeading);
        if (s.headingArrow != null) l.add(s.headingArrow);
        if (s.farmMode != null) l.add(s.farmMode);
        if (s.players != null) l.add(s.players);
        return l;
    }

    private static int ordinalOf(Object v) {
        return v instanceof Enum<?> e ? e.ordinal() : v instanceof Integer i ? i : 0;
    }

    /**
     * "Same as global": ticked when none of this map mod's style, mode, sizes or heading, arrow, farm and
     * player switches is overridden. Click while unticked: every override goes back to inherit (and is
     * remembered); click again: they come back.
     */
    private static final class SameAsGlobal extends Row {
        private boolean all() {
            for (ModConfigSpec.ConfigValue<?> v : overrides(ClientConfig.sectionOf(ctx))) {
                if (ordinalOf(v.get()) != 0) return false;
            }
            return true;
        }

        @Override
        String label() {
            return "sameAsGlobal=" + (all() ? "on" : "off");
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            g.fill(x + 3, y + 2, x + 12, y + 11, 0xFF101018);
            if (all()) g.fill(x + 4, y + 3, x + 11, y + 10, 0xFF55DD66);
            g.drawString(mc.font, Component.translatable("mob_map_icons.options.sameAsGlobal"), x + 17, y + 3, 0xFFFFFF, false);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override
        void press(int x, int w, double mx, int dir, int button) {
            List<ModConfigSpec.ConfigValue<?>> l = overrides(ClientConfig.sectionOf(ctx));
            if (all()) {
                int[] snap = SNAPSHOTS.get(ctx);
                if (snap == null) return;
                for (int i = 0; i < l.size() && i < snap.length; i++) {
                    Object cur = l.get(i).get();
                    if (cur instanceof Enum<?> e) ((ModConfigSpec.EnumValue) l.get(i)).set(e.getDeclaringClass().getEnumConstants()[snap[i]]);
                    else ((ModConfigSpec.IntValue) l.get(i)).set(snap[i]);
                }
            } else {
                int[] snap = new int[l.size()];
                for (int i = 0; i < l.size(); i++) {
                    snap[i] = ordinalOf(l.get(i).get());
                    Object cur = l.get(i).get();
                    if (cur instanceof Enum<?> e) ((ModConfigSpec.EnumValue) l.get(i)).set(e.getDeclaringClass().getEnumConstants()[0]);
                    else ((ModConfigSpec.IntValue) l.get(i)).set(0);
                }
                SNAPSHOTS.put(ctx, snap);
            }
            changed();
            commit();
        }
    }

    /** Switches between the compact page and the "More" page. */
    private static final class MoreButton extends Row {
        final boolean forward;

        MoreButton(boolean forward) {
            this.forward = forward;
        }

        @Override
        String label() {
            return forward ? "more" : "back";
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            text(g, mc, Component.translatable(forward ? "mob_map_icons.options.more" : "mob_map_icons.options.back"), x, y, w);
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
            page = forward ? 1 : 0;
            rebuild();
        }
    }

    /** One of the map mod's own options (see {@link MapModOptions}); every call is guarded. */
    private static final class Action extends Row {
        final MapModOptions.Entry entry;

        Action(MapModOptions.Entry entry) {
            this.entry = entry;
        }

        private String value() {
            try {
                return entry.value().get();
            } catch (RuntimeException e) {
                return "?";
            }
        }

        @Override
        String label() {
            return entry.key() + "=" + value();
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            box(g, x, y, w, hover(x, y, w, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options.native." + entry.key(), value()), x, y, w);
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
            try {
                entry.press().accept(dir);
            } catch (RuntimeException e) {
                MobMapIcons.LOG.debug("Quick options: native option failed", e);
            }
        }

        @Override
        void wheel(int x, int w, double mx, int dir) {
            press(x, w, mx, dir, 0);
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
        String show() { return String.format(Locale.ROOT, "%.2f", value.get()); }
    }

    /** "Full config" and "Close" side by side. */
    private static final class Buttons extends Row {
        @Override
        String label() {
            return "fullConfig|close";
        }

        @Override
        void draw(GuiGraphics g, Minecraft mc, int x, int y, int w, double mx, double my) {
            int half = (w - 4) / 2;
            box(g, x, y, half, hover(x, y, half, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options.fullConfig"), x, y, half);
            box(g, x + half + 4, y, half, hover(x + half + 4, y, half, mx, my));
            text(g, mc, Component.translatable("mob_map_icons.options.close"), x + half + 4, y, half);
        }

        @Override
        void press(int x, int w, double mx, int dir, int button) {
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

        @Override
        void describe(List<String> out, int idx, int x, int y, int w, double scale) {
            int half = (w - 4) / 2;
            cell(out, idx, 0, x, y, half, scale, this);
            out.add("row " + idx + " c1 px=" + (int) ((x + half + 4 + half / 2.0) * scale) + " py="
                    + (int) ((y + ROW / 2.0) * scale) + " CloseButton");
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
