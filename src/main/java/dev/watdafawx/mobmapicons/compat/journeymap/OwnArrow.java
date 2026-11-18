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

    private static boolean failed;
    private static Method getTexture;
    private static ResourceLocation arrowId, arrowBgId;

    /** What we changed: the live textures, JourneyMap's pixels as they were, and what we last wrote. */
    private static DynamicTexture fg, bg;
    private static NativeImage origFg, origBg;
    private static int writtenHash;
    private static int ticks;

    private OwnArrow() {}

    static void tick() {
        if (failed || ++ticks % 5 != 0) return;
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
        if (arrow == fg && back == bg && hash == writtenHash) return; // nothing changed
        if (fg != null && (arrow != fg || back != bg)) forget(); // JourneyMap made new textures: ours are gone with the old ones
        if (origFg == null) {
            origFg = copy(arrow.getPixels());
            origBg = copy(back.getPixels());
        }
        NativeImage pixels = arrow.getPixels();
        try (NativeImage icon = NativeImage.read(new ByteArrayInputStream(png))) {
            pixels.fillRect(0, 0, pixels.getWidth(), pixels.getHeight(), 0);
            // Keep the icon's aspect ratio, centred.
            int side = Math.min(pixels.getWidth(), pixels.getHeight());
            int x = (pixels.getWidth() - side) / 2, y = (pixels.getHeight() - side) / 2;
            try (NativeImage scratch = new NativeImage(NativeImage.Format.RGBA, side, side, true)) {
                icon.resizeSubRectTo(0, 0, icon.getWidth(), icon.getHeight(), scratch);
                scratch.copyRect(pixels, 0, 0, x, y, side, side, false, false);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        arrow.upload();
        NativeImage bgPixels = back.getPixels();
        bgPixels.fillRect(0, 0, bgPixels.getWidth(), bgPixels.getHeight(), 0);
        back.upload();
        whiten();
        fg = arrow;
        bg = back;
        writtenHash = hash;
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
