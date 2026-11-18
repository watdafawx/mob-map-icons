package dev.watdafawx.mobmapicons.compat.ftbchunks;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws our mob icon where FTB's {@code EntityMapIcon} would have drawn its head. Everything except
 * {@link #draw} delegates to that original, so position, visibility, scale, priority, tooltip (the
 * entity's display name) and the hover hotkeys behave exactly as in FTB.
 *
 * <p><b>Upright:</b> FTB rotates only an icon's <i>position</i> with the minimap, never its quad, and
 * this draws an axis-aligned quad, so icons stay upright whatever the map rotation.
 *
 * <p><b>Coordinates:</b> the minimap passes a 1x1 unit square under a scaled pose, the large map passes a
 * pixel rectangle. Like FTB, the quad is laid out in an 18-unit cell and the pose is scaled by
 * {@code w/18}, which makes both surfaces the same code.
 */
final class OverrideIcon implements MapIcon {
    private static final float CELL = 18f;
    /** Hostile mobs get a red wash when {@code tintByHostility} is on. */
    private static final int[] HOSTILE_TINT = {255, 140, 140};

    private final MapIcon original;
    private final Entity entity;
    OverrideIcon(MapIcon original, Entity entity) {
        this.original = original;
        this.entity = entity;
    }

    @Override
    public void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h, boolean outsideVisibleArea, int iconAlpha) {
        // Look the texture up again on every draw: FTB keeps this icon for up to 500 ms (minimap) or until
        // the large map rebuilds, and a resource reload or icon config change frees the texture we were
        // built with. Binding a freed id makes the texture manager try to load it from disk (a WARN per
        // mob and a missing-texture frame), so fall back to FTB's own icon until ours is rendered again.
        ResourceLocation texture = MobIconApi.getIcon(entity.getType());
        if (texture == null) {
            original.draw(mapType, graphics, x, y, w, h, outsideVisibleArea, iconAlpha);
            return;
        }
        float size = MobIconApi.iconSize();
        int r = 255, g = 255, b = 255;
        if (ClientConfig.tintByHostility() && entity instanceof Enemy) {
            r = HOSTILE_TINT[0];
            g = HOSTILE_TINT[1];
            b = HOSTILE_TINT[2];
        }

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

    @Override
    public Vec3 getPos(float partialTick) {
        return original.getPos(partialTick);
    }

    @Override
    public boolean isVisible(MapType mapType, double distanceToPlayer, boolean outsideVisibleArea) {
        return original.isVisible(mapType, distanceToPlayer, outsideVisibleArea);
    }

    @Override
    public double getIconScale(MapType mapType) {
        return original.getIconScale(mapType);
    }

    @Override
    public boolean isIconOnEdge(MapType mapType, boolean outsideVisibleArea) {
        return original.isIconOnEdge(mapType, outsideVisibleArea);
    }

    @Override
    public boolean isZoomDependant(MapType mapType) {
        return original.isZoomDependant(mapType);
    }

    @Override
    public int getPriority() {
        return original.getPriority();
    }

    @Override
    public void addTooltip(TooltipList list) {
        original.addTooltip(list);
    }

    @Override
    public boolean onMousePressed(BaseScreen screen, MouseButton button) {
        return original.onMousePressed(screen, button);
    }

    @Override
    public boolean onKeyPressed(BaseScreen screen, Key key) {
        return original.onKeyPressed(screen, key);
    }
}
