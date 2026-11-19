package dev.watdafawx.mobmapicons.compat.ftbchunks;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MapView;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * The raw drawing both of our icons share: heading bucket, the textured quad and the count badge.
 * Nothing here allocates per call beyond what the vertex builder itself does.
 */
final class IconDraw {
    /** FTB lays an icon out in an 18-unit cell under a pose scaled by {@code w / 18}. */
    static final float CELL = 18f;
    /** Hostile mobs get a red wash when {@code tintByHostility} is on. */
    private static final int[] HOSTILE_TINT = {255, 140, 140};

    /** Nanoseconds spent drawing our icons since the last perf log line; see {@link FtbPerf}. */
    static long drawNanos;
    static int drawCount;

    private IconDraw() {}

    /** FTB's minimap draws at the minimap size, its large map at the full-screen one. */
    static MapView viewOf(MapType mapType) {
        return mapType.isLargeMap() ? MapView.FULLSCREEN : MapView.MINIMAP;
    }

    /** Whether FTB's minimap turns with the player (its "locked north" and "square" options are both off). */
    static boolean minimapRotates() {
        try {
            return !(FTBChunksClientConfig.MINIMAP_LOCKED_NORTH.get() || FTBChunksClientConfig.SQUARE_MINIMAP.get());
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * Heading bucket for this entity on this map: 0 unless the icon uses a heading (it turns, or it has a
     * heading arrow, see {@code MobIconApi.usesHeading(MapMod.FTB)}). North-up maps (the
     * large map, a locked minimap) use the body yaw as is; a rotating minimap has the player's look
     * direction at the top, so the yaw is taken relative to the player.
     */
    static int bucket(Entity entity, MapType mapType) {
        if (!MobIconApi.usesHeading(MapMod.FTB)) {
            return 0;
        }
        float yaw = entity instanceof net.minecraft.client.player.LocalPlayer ? entity.getYRot()
                : entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot();
        if (mapType.isMinimap() && minimapRotates()) {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                yaw = yaw - player.getYRot() + 180f;
            }
        }
        return MobIconApi.yawBucket(yaw);
    }

    /** Hostile tint colour component, 255 when tinting is off or the entity is not hostile. */
    private static int tint(Entity entity, int channel) {
        return ClientConfig.tintByHostility() && entity instanceof Enemy ? HOSTILE_TINT[channel] : 255;
    }

    /**
     * Draws the icon quad centred in the 18-unit cell at {@code (x, y)} scaled by {@code w / 18}, then
     * restores the state FTB expects.
     */
    static void quad(GuiGraphics graphics, ResourceLocation texture, Entity tintSource, int x, int y, int w, int h, int iconAlpha, MapView view) {
        float size = MobIconApi.iconSize(MapMod.FTB, view);
        int r = tint(tintSource, 0), g = tint(tintSource, 1), b = tint(tintSource, 2);

        // Raw quad below: draw anything queued first so layer order holds. GuiGraphics.flush() always
        // leaves the depth test ON, but FTB draws minimap icons with it OFF (the round minimap mask
        // lives in the depth buffer), so a plain flush made our icon and everything FTB drew after it
        // (the player marker) fail the depth test and vanish. Put the depth test back as it was.
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        graphics.flush();
        if (!depth) RenderSystem.disableDepthTest();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate((float) x, (float) y, 0f);
        pose.scale(w / CELL, h / CELL, 1f);
        Matrix4f m = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        var buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // FTB fits a 16px head plus its 1px backing into the 18-unit cell. Our texture carries its own
        // transparent margin, so the default 16px icon fills the whole cell, scaled by iconSize/16 and
        // always centred on the mob (a fixed 1px offset pushed larger icons down-right of the mob).
        float drawn = CELL * size / ClientConfig.DEFAULT_ICON_SIZE;
        float lo = (CELL - drawn) / 2f, hi = lo + drawn;
        buf.addVertex(m, lo, lo, 0f).setUv(0f, 0f).setColor(r, g, b, iconAlpha);
        buf.addVertex(m, lo, hi, 0f).setUv(0f, 1f).setColor(r, g, b, iconAlpha);
        buf.addVertex(m, hi, hi, 0f).setUv(1f, 1f).setColor(r, g, b, iconAlpha);
        buf.addVertex(m, hi, lo, 0f).setUv(1f, 0f).setColor(r, g, b, iconAlpha);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        pose.popPose();
    }

    /**
     * A boss's name centred under its icon cell, in {@code rgb}, with a shadow. Same pose handling as
     * {@link #badge}: readable GUI size however small FTB scales the icon.
     */
    static void label(GuiGraphics graphics, net.minecraft.network.chat.Component name, int rgb, int x, int y, int w, int h, int iconAlpha, MapView view) {
        Font font = Minecraft.getInstance().font;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate((float) x, (float) y, 0f);
        pose.scale(w / CELL, h / CELL, 1f);
        float guiPerUnit = Math.abs(pose.last().pose().m00());
        if (guiPerUnit > 1e-4f) {
            float k = 7f / 9f / guiPerUnit; // cell units per font pixel: 7 GUI px text
            float drawn = CELL * MobIconApi.iconSize(MapMod.FTB, view) / ClientConfig.DEFAULT_ICON_SIZE;
            float hi = (CELL + drawn) / 2f;
            float tw = font.width(name) * k;
            pose.translate(CELL / 2f - tw / 2f, hi + 0.5f * k, 0f);
            pose.scale(k, k, 1f);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            font.drawInBatch(name.getVisualOrderText(), 0f, 0f, (Math.max(iconAlpha, 16) << 24) | (rgb & 0xFFFFFF), true,
                    pose.last().pose(), graphics.bufferSource(), Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            graphics.flush();
            if (!depth) RenderSystem.disableDepthTest();
        }
        pose.popPose();
    }

    /**
     * Count badge in the bottom-right corner of the icon cell at {@code (x, y, w, h)}: white text with a
     * shadow. The text keeps a readable screen size however small FTB scales the icon (minimap icons can
     * be only a few GUI pixels wide), so its scale comes from the pose rather than the icon.
     */
    static void badge(GuiGraphics graphics, String text, int x, int y, int w, int h, int iconAlpha, MapView view) {
        Font font = Minecraft.getInstance().font;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate((float) x, (float) y, 0f);
        pose.scale(w / CELL, h / CELL, 1f);
        // GUI pixels per cell unit under the current pose (icons are never rotated, only positioned).
        float guiPerUnit = Math.abs(pose.last().pose().m00());
        if (guiPerUnit > 1e-4f) {
            float iconGui = CELL * MobIconApi.iconSize(MapMod.FTB, view) / ClientConfig.DEFAULT_ICON_SIZE * guiPerUnit;
            // Glyph cell is 9 font px tall; aim for under half the icon, never under 5 or over 8 GUI px.
            float textGui = Math.max(5f, Math.min(8f, iconGui * 0.45f));
            float k = textGui / 9f / guiPerUnit; // cell units per font pixel
            float drawn = CELL * MobIconApi.iconSize(MapMod.FTB, view) / ClientConfig.DEFAULT_ICON_SIZE;
            float hi = (CELL + drawn) / 2f;
            float tw = font.width(text) * k;
            pose.translate(hi - tw + 1.5f * k, hi - 8f * k, 0f);
            pose.scale(k, k, 1f);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            // SEE_THROUGH has no depth test, so the minimap's depth mask cannot swallow the text.
            font.drawInBatch(text, 0f, 0f, (iconAlpha << 24) | 0xFFFFFF, true, pose.last().pose(),
                    graphics.bufferSource(), Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            graphics.flush();
            if (!depth) RenderSystem.disableDepthTest();
        }
        pose.popPose();
    }
}
