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
    private final ResourceLocation texture;

    OverrideIcon(MapIcon original, Entity entity, ResourceLocation texture) {
        this.original = original;
        this.entity = entity;
        this.texture = texture;
    }

    @Override
    public void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h, boolean outsideVisibleArea, int iconAlpha) {
        float size = MobIconApi.iconSize();
        int r = 255, g = 255, b = 255;
        if (ClientConfig.tintByHostility() && entity instanceof Enemy) {
            r = HOSTILE_TINT[0];
            g = HOSTILE_TINT[1];
            b = HOSTILE_TINT[2];
        }

        graphics.flush(); // raw quad below: draw anything queued first so layer order holds
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
        // 1px margin inside the 18-cell, same as FTB's 16px head; UV 0..1 so texture resolution is irrelevant.
        buf.addVertex(m, 1f, 1f, 0f).setUv(0f, 0f).setColor(r, g, b, iconAlpha);
        buf.addVertex(m, 1f, 1f + size, 0f).setUv(0f, 1f).setColor(r, g, b, iconAlpha);
        buf.addVertex(m, 1f + size, 1f + size, 0f).setUv(1f, 1f).setColor(r, g, b, iconAlpha);
        buf.addVertex(m, 1f + size, 1f, 0f).setUv(1f, 0f).setColor(r, g, b, iconAlpha);
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
