package dev.watdafawx.mobmapicons.compat.journeymap;

import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.IconMode;
import dev.watdafawx.mobmapicons.api.IconProfile;
import dev.watdafawx.mobmapicons.api.IconStyle;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Your own marker on JourneyMap (ownPlayer). JourneyMap draws the local player as two textures,
 * {@code TextureCache.PlayerArrow} (tinted by its "self" colour) over {@code PlayerArrowBG}, on the minimap
 * and the fullscreen map alike, turning them with the camera. Both are DynamicTextures it hands out from
 * {@code TextureCache.getTexture}, and the minimap keeps the very same objects in private fields, so this
 * rewrites their <i>pixels</i> in place (our icon into the arrow, nothing into the background) and uploads
 * them. No private field is touched; position, size slider, z-order and "show self" stay JourneyMap's.
 * The original pixels are kept and put back when the option goes off.
 *
 * <p>The marker turns continuously, so the icon is a TOP_DOWN render (the model seen from above) rather
 * than the configured style. Anything unexpected (class or method missing, texture not loaded, odd size)
 * stops the feature and leaves JourneyMap's own marker as it was.
 */
final class OwnArrow {
    private static final IconProfile PROFILE_BASE = new IconProfile(IconStyle.TOP_DOWN, IconMode.FULL_BODY,
            ClientConfig.DEFAULT_ICON_SIZE * 2, false, false);

    private static boolean failed, hooked;
    private static Method getTexture;
    private static ResourceLocation arrowId, arrowBgId;

    /** What we changed: the live textures, JourneyMap's pixels as they were, and what we last wrote. */
    private static DynamicTexture fg, bg;
    private static NativeImage origFg, origBg;
    private static int writtenHash;
    private static int ticks;

    private OwnArrow() {}

    static void tick() {
        if (failed) return;
        if (!hooked) {
            hooked = true;
            // A save at shutdown must not see the white we put in; leave the user's value behind.
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.event.GameShuttingDownEvent e) -> unwhiten());
        }
        // JourneyMap writes its whole core config (colorSelf included) when one of its option screens is
        // closed, so the user's colour must be back in place before any of them can be used. Checked every tick.
        if (origTint != null && jmOptionsOpen()) unwhiten();
        if (++ticks % 5 != 0) return;
        try {
            var player = Minecraft.getInstance().player;
            if (player != null && ClientConfig.journeyMapEnabled() && ClientConfig.ownPlayer(MapMod.JOURNEYMAP)) {
                apply(player);
            } else {
                restore();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            try {
                restore();
            } catch (RuntimeException ignored) {
                // nothing more to do
            }
            MobMapIcons.LOG.warn("JourneyMap's player marker textures differ from the supported 6.0.x line; JourneyMap keeps its own marker", e);
        }
    }

    private static void resolve() throws ReflectiveOperationException {
        if (getTexture != null) return;
        Class<?> cache = Class.forName("journeymap.client.texture.TextureCache");
        arrowId = (ResourceLocation) cache.getField("PlayerArrow").get(null);
        arrowBgId = (ResourceLocation) cache.getField("PlayerArrowBG").get(null);
        getTexture = cache.getMethod("getTexture", ResourceLocation.class);
    }

    private static DynamicTexture texture(ResourceLocation id) throws ReflectiveOperationException {
        return (DynamicTexture) getTexture.invoke(null, id);
    }

    private static void apply(net.minecraft.client.player.LocalPlayer player) throws ReflectiveOperationException {
        resolve();
        DynamicTexture arrow = texture(arrowId), back = texture(arrowBgId);
        if (arrow == null || back == null || arrow.getPixels() == null || back.getPixels() == null) return; // not loaded yet
        // Ask for the icon (queues the render); getPng returns it once it is ready.
        IconProfile profile = PROFILE_BASE;
        MobIconApi.getResourceIcon(player, 0, profile, false);
        byte[] png = MobIconApi.getPng(player, 0, profile, false);
        if (png == null) return;
        int hash = Arrays.hashCode(png);
        if (arrow == fg && back == bg && hash == writtenHash) { // nothing changed
            if (origTint == null && !jmOptionsOpen()) whiten(); // back from an options screen
            return;
        }
        if (fg != null && (arrow != fg || back != bg)) forget(); // JourneyMap made new textures: ours are gone with the old ones
        if (origFg == null) {
            origFg = copy(arrow.getPixels());
            origBg = copy(back.getPixels());
        }
        NativeImage pixels = arrow.getPixels();
        try (NativeImage icon = NativeImage.read(new ByteArrayInputStream(png))) {
            pixels.fillRect(0, 0, pixels.getWidth(), pixels.getHeight(), 0);
            compose(icon, pixels);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        arrow.upload();
        NativeImage bgPixels = back.getPixels();
        bgPixels.fillRect(0, 0, bgPixels.getWidth(), bgPixels.getHeight(), 0);
        back.upload();
        if (!jmOptionsOpen()) whiten();
        fg = arrow;
        bg = back;
        writtenHash = hash;
    }


    /** True while a JourneyMap screen other than the fullscreen map (its options and dialogs) is open. */
    private static boolean jmOptionsOpen() {
        var screen = Minecraft.getInstance().screen;
        if (screen == null) return false;
        String name = screen.getClass().getName();
        return name.startsWith("journeymap.") && !name.contains(".fullscreen.");
    }

    /**
     * Draws the top-down figure big and crisp into {@code dst}: the opaque part of the icon is cropped out and
     * scaled to fill the square, a white front marker is added above it (the figure alone is nearly symmetric,
     * so the heading would not be readable) and everything gets a dark outline so it stands out on any map.
     */
    private static void compose(NativeImage icon, NativeImage dst) {
        int side = Math.min(dst.getWidth(), dst.getHeight());
        int offX = (dst.getWidth() - side) / 2, offY = (dst.getHeight() - side) / 2;
        int x0 = icon.getWidth(), y0 = icon.getHeight(), x1 = -1, y1 = -1;
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                if ((icon.getPixelRGBA(x, y) >>> 24) > 24) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < 0) return;
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        int m = Math.max(1, side / 24);
        int chev = Math.max(3, Math.round(side * 0.22F));
        float availW = side - 2 * m, availH = side - 2 * m - chev;
        float sc = Math.min(availW / bw, availH / bh);
        int dw = Math.max(1, Math.round(bw * sc)), dh = Math.max(1, Math.round(bh * sc));
        int fx = (side - dw) / 2, fy = m + chev + Math.round((availH - dh) / 2F);
        int[] buf = new int[side * side];
        for (int j = 0; j < dh; j++) {
            for (int i = 0; i < dw; i++) {
                // Box average over the source pixels this one covers, weighted by alpha.
                int sx0 = x0 + (int) Math.floor(i / sc), sx1 = Math.min(x1, x0 + Math.max((int) Math.floor(i / sc), (int) Math.ceil((i + 1) / sc) - 1));
                int sy0 = y0 + (int) Math.floor(j / sc), sy1 = Math.min(y1, y0 + Math.max((int) Math.floor(j / sc), (int) Math.ceil((j + 1) / sc) - 1));
                long sa = 0, sr = 0, sg = 0, sb = 0;
                int n = 0;
                for (int yy = sy0; yy <= sy1; yy++) {
                    for (int xx = sx0; xx <= sx1; xx++) {
                        int p = icon.getPixelRGBA(Math.min(xx, x1), Math.min(yy, y1));
                        int a = p >>> 24;
                        sa += a;
                        sr += (long) (p & 255) * a;
                        sg += (long) ((p >> 8) & 255) * a;
                        sb += (long) ((p >> 16) & 255) * a;
                        n++;
                    }
                }
                if (n == 0 || sa == 0) continue;
                int a = (int) (sa / n);
                buf[(fy + j) * side + fx + i] = a < 96 ? 0
                        : 0xFF000000 | ((int) (sb / sa) << 16) | ((int) (sg / sa) << 8) | (int) (sr / sa);
            }
        }
        // Front marker: a white triangle pointing up, centred above the figure.
        float half = chev * 0.95F;
        for (int r = 0; r < chev; r++) {
            int w = Math.max(0, Math.round(half * (r + 0.5F) / chev));
            for (int x = side / 2 - w; x <= side / 2 + w - (w == 0 ? 1 : 0); x++) {
                if (x >= 0 && x < side) buf[(m + r) * side + x] = 0xFFFFFFFF;
            }
        }
        // Dark outline round everything that is drawn.
        int rad = Math.max(1, side / 32);
        int[] out = buf.clone();
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                if (buf[y * side + x] != 0) continue;
                boolean edge = false;
                for (int dy = -rad; dy <= rad && !edge; dy++) {
                    for (int dx = -rad; dx <= rad && !edge; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < side && yy < side && buf[yy * side + xx] != 0) edge = true;
                    }
                }
                if (edge) out[y * side + x] = 0xFF161616;
            }
        }
        for (int y = 0; y < side; y++) {
            // JourneyMap's arrow texture has its front at the bottom (it turns it by yaw + 180), so flip the layout.
            for (int x = 0; x < side; x++) dst.setPixelRGBA(offX + x, offY + side - 1 - y, out[y * side + x]);
        }
    }

    // ---- JourneyMap multiplies the marker by its "self" colour; white leaves our icon's own colours ----

    private static String origTint;

    private static Object selfColourField() throws ReflectiveOperationException {
        Object client = Class.forName("journeymap.client.JourneymapClient").getMethod("getInstance").invoke(null);
        Object core = client.getClass().getMethod("getCoreProperties").invoke(client);
        return core.getClass().getField("colorSelf").get(core);
    }

    /** Sets the self colour to white in memory only (never saved by us); remembers the user's value. */
    private static void whiten() throws ReflectiveOperationException {
        if (origTint != null) return;
        Object field = selfColourField();
        Object cur = field.getClass().getMethod("get").invoke(field);
        if (!(cur instanceof String s)) return;
        origTint = s;
        setTint(field, "#ffffff");
    }

    private static void unwhiten() {
        if (origTint == null) return;
        String back = origTint;
        origTint = null;
        try {
            setTint(selfColourField(), back);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobMapIcons.LOG.debug("JourneyMap self colour not restored", e);
        }
    }

    private static void setTint(Object field, String value) throws ReflectiveOperationException {
        for (Method m : field.getClass().getMethods()) {
            if (m.getName().equals("set") && m.getParameterCount() == 1 && !m.isBridge()) {
                m.invoke(field, value);
                break;
            }
        }
        try {
            // The minimap reads the colour once in reset(); the fullscreen map reads it every frame.
            Object ui = Class.forName("journeymap.client.ui.UIManager").getField("INSTANCE").get(null);
            Object mini = ui.getClass().getMethod("getMinimap").invoke(ui);
            if (mini != null) mini.getClass().getMethod("reset").invoke(mini);
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobMapIcons.LOG.debug("JourneyMap minimap not reset", e);
        }
    }

    private static NativeImage copy(NativeImage src) {
        NativeImage out = new NativeImage(src.format(), src.getWidth(), src.getHeight(), false);
        out.copyFrom(src);
        return out;
    }

    private static void restore() {
        unwhiten();
        if (fg == null || origFg == null) {
            forget();
            return;
        }
        NativeImage a = fg.getPixels(), b = bg.getPixels();
        if (a != null && b != null && a.getWidth() == origFg.getWidth() && b.getWidth() == origBg.getWidth()) {
            a.copyFrom(origFg);
            b.copyFrom(origBg);
            fg.upload();
            bg.upload();
        }
        forget();
    }

    private static void forget() {
        if (origFg != null) origFg.close();
        if (origBg != null) origBg.close();
        origFg = origBg = null;
        fg = bg = null;
        writtenHash = 0;
    }
}
