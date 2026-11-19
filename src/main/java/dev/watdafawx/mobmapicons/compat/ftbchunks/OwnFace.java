package dev.watdafawx.mobmapicons.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftblibrary.icon.FaceIcon;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MapView;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * Your own marker on FTB's minimap (ownPlayer). The minimap draws the local player inline, not through
 * the icon list, from a {@link FaceIcon} whose {@code head} and {@code hat} are plain public fields. This
 * swaps {@code head} for {@link Ours} (our icon of the local player; FTB's own head when ours is not ready)
 * and blanks {@code hat}, every client tick, because FTB rewrites both when the skin resolves. Turning the
 * option off puts FTB's own icons back. FTB's pointer arrow is left alone: it is the heading marker.
 */
final class OwnFace {

    private static FaceIcon face;
    private static Icon ourHead;
    private static Icon origHead, origHat;
    private static boolean failed;

    private OwnFace() {}

    /** An FTB icon that draws our icon of the local player into the face's cell. */
    private static final class Ours extends Icon {
        final Icon fallback;

        Ours(Icon fallback) {
            this.fallback = fallback;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y, int w, int h) {
            LocalPlayer player = Minecraft.getInstance().player;
            ResourceLocation texture = player == null ? null
                    : MobIconApi.getIcon(player, IconDraw.bucket(player, MapType.MINIMAP), MapMod.FTB, MapView.MINIMAP, false);
            if (texture == null) {
                fallback.draw(graphics, x, y, w, h);
            } else {
                IconDraw.quad(graphics, texture, player, x, y, w, h, 255, MapView.MINIMAP);
            }
        }
    }

    static void tick() {
        if (failed) return;
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && ClientConfig.ftbChunksEnabled() && ClientConfig.ownPlayer(MapMod.FTB)) {
                apply(FaceIcon.getFace(player.getGameProfile()));
            } else {
                restore();
            }
        } catch (LinkageError | RuntimeException e) {
            failed = true;
            restore0();
            MobMapIcons.LOG.warn("FTB Chunks' face icon differs from the supported 2101.1.x line; the minimap keeps FTB's own marker", e);
        }
    }

    private static void apply(FaceIcon f) {
        if (f == face && f.head == ourHead) return; // already ours
        if (face != null && face != f) restore0();
        face = f;
        // FTB's skin callback replaced our icons (or this is the first time): remember what is there now.
        origHead = f.head;
        origHat = f.hat;
        ourHead = new Ours(origHead);
        f.head = ourHead;
        f.hat = Icon.empty();
    }

    private static void restore() {
        if (face != null) restore0();
    }

    private static void restore0() {
        FaceIcon f = face;
        face = null;
        if (f != null && f.head == ourHead) {
            f.head = origHead;
            f.hat = origHat;
        }
        ourHead = origHead = origHat = null;
    }
}
