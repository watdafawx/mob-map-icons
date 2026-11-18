package dev.watdafawx.mobmapicons.compat.journeymap;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hides JourneyMap's marker (the filled teardrop behind a mob icon) and the black box it draws around a
 * player's face.
 *
 * <p>The API can drop the marker's border per entity ({@code WrappedEntity.setDrawOutline(false)}), but
 * not its coloured fill. That fill is a stock texture, {@code journeymap:ui/img/marker-icon-bg-160.png}
 * (and the {@code -arrow-} twin used with the heading tail), which JourneyMap loads into the client
 * texture manager as a {@link DynamicTexture} under its own id and fetches from there every frame. So
 * "hide" registers a fully transparent texture of the same size under those ids, through the ordinary
 * {@code TextureManager} API (no JourneyMap code involved), and "show" releases it so JourneyMap loads
 * its own again. Only the entity radar reads these ids.
 *
 * <p>The player box is {@code DrawEntityStep.playerOutlineTexture}, a static field set once. Hiding swaps
 * in a transparent texture by reflection, showing puts JourneyMap's own object back. Any failure logs one
 * warning and leaves JourneyMap untouched.
 *
 * <p>All state changes run on the render thread; callers may be on any thread.
 */
final class FrameHider {
    private static final ResourceLocation[] MARKER_FILLS = {
            ResourceLocation.fromNamespaceAndPath("journeymap", "ui/img/marker-icon-bg-160.png"),
            ResourceLocation.fromNamespaceAndPath("journeymap", "ui/img/marker-icon-arrow-bg-160.png"),
    };
    private static final int FALLBACK_EDGE = 160;
    /** JourneyMap may drop its textures on its own reload; look again at most this often. */
    private static final long RECHECK_MS = 1000;

    private FrameHider() {}

    // ---------------------------------------------------------------------------------------------
    // Marker fill

    /** What we registered per id (render thread). */
    private static final Map<ResourceLocation, DynamicTexture> OURS = new LinkedHashMap<>();
    private static volatile boolean wantFillHidden;
    private static volatile boolean fillFailed;
    private static volatile long lastCheck;

    /** {@code false} after a failure: the marker cannot be hidden, so the rule keeps it. */
    static boolean canHideFill() {
        return !fillFailed;
    }

    /** Hide or show the marker fill. Cheap to call every frame. */
    static void setFillHidden(boolean hide) {
        long now = System.currentTimeMillis();
        boolean changed = hide != wantFillHidden;
        if (!changed && now - lastCheck < RECHECK_MS) return;
        lastCheck = now;
        wantFillHidden = hide;
        if (!changed && !hide) return;
        onRenderThread(FrameHider::applyFill);
    }

    private static void applyFill() {
        if (fillFailed) return;
        try {
            var textures = Minecraft.getInstance().getTextureManager();
            for (ResourceLocation id : MARKER_FILLS) {
                DynamicTexture ours = OURS.get(id);
                AbstractTexture current = textures.getTexture(id, null);
                if (wantFillHidden) {
                    if (ours != null && current == ours) continue;
                    int[] size = sizeOf(current, id);
                    DynamicTexture blank = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, size[0], size[1], true));
                    textures.register(id, blank); // closes JourneyMap's, which it simply loads again later
                    OURS.put(id, blank);
                } else if (ours != null) {
                    OURS.remove(id);
                    if (current == ours) textures.release(id); // closes ours; JourneyMap reloads its own on next use
                    else ours.close();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            fillFailed = true;
            MobMapIcons.LOG.warn("Could not hide JourneyMap's mob marker; it stays visible", e);
            OURS.keySet().forEach(id -> Minecraft.getInstance().getTextureManager().release(id));
            OURS.clear();
        }
    }

    private static int[] sizeOf(AbstractTexture current, ResourceLocation id) {
        if (current instanceof DynamicTexture dyn && dyn.getPixels() != null) {
            return new int[]{dyn.getPixels().getWidth(), dyn.getPixels().getHeight()};
        }
        try (var in = Minecraft.getInstance().getResourceManager().open(id); NativeImage img = NativeImage.read(in)) {
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (java.io.IOException | RuntimeException e) {
            return new int[]{FALLBACK_EDGE, FALLBACK_EDGE};
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Player box

    private static Field boxField;
    private static volatile boolean boxFailed;
    private static volatile boolean wantBoxHidden;
    private static volatile boolean boxIsHidden;
    private static DynamicTexture boxOriginal, boxBlank;

    /** Hide or show the box around player icons. Cheap to call every frame; retries until JourneyMap has made its first marker. */
    static void setPlayerBoxHidden(boolean hide) {
        if (boxFailed) return;
        wantBoxHidden = hide;
        if (hide == boxIsHidden) return;
        onRenderThread(FrameHider::applyBox);
    }

    private static void applyBox() {
        if (boxFailed) return;
        try {
            if (boxField == null) {
                boxField = Class.forName("journeymap.client.render.draw.DrawEntityStep").getDeclaredField("playerOutlineTexture");
                boxField.setAccessible(true);
            }
            Object current = boxField.get(null);
            if (wantBoxHidden) {
                // Null until JourneyMap builds its first marker; the next call tries again. Only ever keep a
                // texture that is JourneyMap's, so showing restores exactly what it had.
                if (boxBlank != null || !(current instanceof DynamicTexture original)) return;
                int w = original.getPixels() == null ? 26 : original.getPixels().getWidth();
                int h = original.getPixels() == null ? 26 : original.getPixels().getHeight();
                boxBlank = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, w, h, true));
                boxOriginal = original;
                boxField.set(null, boxBlank);
                boxIsHidden = true;
            } else if (boxBlank != null) {
                if (current == boxBlank) boxField.set(null, boxOriginal);
                boxBlank.close();
                boxBlank = null;
                boxOriginal = null;
                boxIsHidden = false;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            boxFailed = true;
            MobMapIcons.LOG.warn("JourneyMap's player box is not where expected; it stays visible", e);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Back to JourneyMap's own look everywhere (JourneyMap support switched off). */
    static void releaseAll() {
        if (!wantFillHidden && !boxIsHidden) return;
        wantFillHidden = false;
        wantBoxHidden = false;
        onRenderThread(() -> {
            applyFill();
            applyBox();
        });
    }

    private static void onRenderThread(Runnable task) {
        if (RenderSystem.isOnRenderThread()) task.run();
        else Minecraft.getInstance().execute(task);
    }
}
