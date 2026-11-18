package dev.watdafawx.mobmapicons.core;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import dev.watdafawx.mobmapicons.api.IconMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * Turns one entity instance into an icon image, drawn from its real renderer, model and texture,
 * so modded mobs come out right with no per-mod code.
 *
 * <p>How: the entity is drawn offscreen into a {@link TextureTarget} under an orthographic camera,
 * exactly like the inventory paper-doll (same renderer call, full-bright light, no shadow, no name
 * tag), but with our own projection so nothing depends on the GUI scale. Two passes:
 * <ol>
 *   <li>a generous view sized from the bounding box, read back to find where the model's pixels
 *       actually are (models overhang their hitbox: dragon wings, bat wings, chicken beaks); if the
 *       model touches the edge the view doubles and it retries;</li>
 *   <li>a tight square view around those pixels - or, in {@link IconMode#HEAD}, around the top band
 *       of them - so big mobs shrink and small mobs grow to fill the icon.</li>
 * </ol>
 * The second pass is drawn at 2-4x the output size and box-filtered down, which keeps the pixel-art
 * look while smoothing the silhouette edge.
 *
 * <p>Render thread only. Every GL state it touches - framebuffer, viewport, projection, model-view
 * stack, fog, shader colour, shader lights - is put back before it returns, even on failure.
 */
public final class MobIconRenderer {

    /** Turn the mob a little towards the viewer's left: a three-quarter view reads better than flat-on. */
    private static final float YAW_DEGREES = -30.0F;
    /** Look down at the mob a little, so the top of the head and back are visible. */
    private static final float PITCH_DEGREES = 12.0F;
    /** Biggest offscreen buffer ever used, in pixels per edge. */
    private static final int MAX_RENDER_SIZE = 256;
    /** Share of the model's visible height that HEAD mode keeps, from the top down. */
    private static final float HEAD_BAND = 0.35F;
    /** Empty margin around the model in the final icon, as a share of its size. */
    private static final float PADDING = 0.04F;
    /** Alpha at or below which a pixel counts as empty when measuring the model. */
    private static final int ALPHA_EMPTY = 8;

    /** View-space light directions (camera looks down -Z, +Y up): key light top-front-left, fill front-right. */
    private static final Vector3f LIGHT_0 = new Vector3f(-0.35F, 1.0F, 0.9F).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(0.5F, 0.3F, 1.0F).normalize();

    @Nullable
    private static TextureTarget target;

    private MobIconRenderer() {}

    /** Offscreen render size for an output size: an integer multiple of it, so the downscale is exact. */
    static int renderSizeFor(int outSize) {
        int factor = Math.max(1, Math.min(4, MAX_RENDER_SIZE / outSize));
        return outSize * factor;
    }

    /**
     * Draws {@code entity} as an icon. The entity is posed in place (rotation and position reset), so
     * pass a throwaway instance that is not in any level's entity list.
     *
     * @param outSize edge length of the returned image in pixels
     * @return a new image owned by the caller, or {@code null} when the entity draws nothing visible
     * @throws RuntimeException from the entity's renderer; the caller decides what a failure means
     */
    @Nullable
    public static NativeImage render(Entity entity, IconMode mode, int outSize) {
        RenderSystem.assertOnRenderThread();
        int size = renderSizeFor(outSize);
        pose(entity);

        float bbWidth = Math.max(entity.getBbWidth(), 0.1F);
        float bbHeight = Math.max(entity.getBbHeight(), 0.1F);
        float half = Math.max(bbWidth, bbHeight) * 0.9F + 0.5F;
        float centerX = 0.0F;
        float centerY = bbHeight / 2.0F;

        // Pass 1: find the model's real extent.
        float left = 0, right = 0, bottom = 0, top = 0;
        PixelBounds px = null;
        NativeImage probe = null;
        try {
            for (int attempt = 0; ; attempt++) {
                left = centerX - half;
                right = centerX + half;
                bottom = centerY - half;
                top = centerY + half;
                if (probe != null) probe.close();
                probe = draw(entity, size, left, right, bottom, top);
                px = PixelBounds.of(probe, 0, size - 1);
                if (px == null) return null;
                if (!px.touchesEdge(size) || attempt == 3) break;
                half *= 2.0F;
            }

            if (mode == IconMode.HEAD) {
                int bandRows = Math.max(1, Math.round((px.maxY - px.minY + 1) * HEAD_BAND));
                PixelBounds head = PixelBounds.of(probe, px.minY, px.minY + bandRows - 1);
                if (head != null) px = head;
            }
        } finally {
            if (probe != null) probe.close();
        }

        // Pixel bounds -> view units. Row 0 is the top of the image.
        float unit = (right - left) / size;
        float x0 = left + px.minX * unit;
        float x1 = left + (px.maxX + 1) * unit;
        float y1 = top - px.minY * unit;
        float y0 = top - (px.maxY + 1) * unit;
        float side = Math.max(x1 - x0, y1 - y0) * (1.0F + 2.0F * PADDING) + 2.0F * unit;
        float cx = (x0 + x1) / 2.0F;
        float cy = (y0 + y1) / 2.0F;

        // Pass 2: tight fit, then box-filter down to the output size.
        try (NativeImage full = draw(entity, size, cx - side / 2, cx + side / 2, cy - side / 2, cy + side / 2)) {
            return downscale(full, outSize);
        }
    }

    /** Frees the offscreen buffer; the next render makes a new one. */
    public static void close() {
        RenderSystem.assertOnRenderThread();
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    /**
     * Neutral pose: facing the camera, head straight, out of name-tag range. The position is far below
     * the world, so the dispatcher's camera distance check never shows a name tag and nothing in the
     * level is lit or shadowed by it. The light level is passed explicitly, so position does not
     * affect brightness.
     */
    private static void pose(Entity entity) {
        double y = entity.level().getMinBuildHeight() - 1024.0;
        entity.moveTo(0.0, y, 0.0, 0.0F, 0.0F);
        entity.setYHeadRot(0.0F);
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = 0.0F;
            living.yBodyRotO = 0.0F;
            living.yHeadRot = 0.0F;
            living.yHeadRotO = 0.0F;
        }
    }

    /**
     * Extra yaw for renderers whose model faces backwards at yaw 0. The ender dragon (and subclasses,
     * which modded dragons often are) is drawn with its head towards -Z, opposite every other mob.
     */
    private static float facingCorrection(Entity entity) {
        return entity instanceof EnderDragon ? 180.0F : 0.0F;
    }

    /** One orthographic draw of the entity into the offscreen buffer, read back as an image. */
    private static NativeImage draw(Entity entity, int size, float left, float right, float bottom, float top) {
        Minecraft mc = Minecraft.getInstance();
        TextureTarget fb = target(size);
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] savedFogColor = RenderSystem.getShaderFogColor().clone();
        FogShape savedFogShape = RenderSystem.getShaderFogShape();
        float[] savedColor = RenderSystem.getShaderColor().clone();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            fb.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            fb.clear(Minecraft.ON_OSX);
            fb.bindWrite(true);

            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(left, right, bottom, top, -1000.0F, 1000.0F),
                    VertexSorting.ORTHOGRAPHIC_Z);
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            FogRenderer.setupNoFog();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShaderLights(LIGHT_0, LIGHT_1);

            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(Axis.XP.rotationDegrees(PITCH_DEGREES));
            poseStack.mulPose(Axis.YP.rotationDegrees(YAW_DEGREES + facingCorrection(entity)));
            renderEntity(dispatcher, entity, poseStack, buffers);
            buffers.endBatch();

            NativeImage image = new NativeImage(size, size, false);
            RenderSystem.bindTexture(fb.getColorTextureId());
            image.downloadTexture(0, false);
            image.flipY();
            return image;
        } finally {
            try {
                buffers.endBatch();
            } catch (Throwable ignored) {
                // A renderer that threw mid-draw can leave a half-built batch; nothing useful to do.
            }
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            RenderSystem.setShaderFogStart(savedFogStart);
            RenderSystem.setShaderFogEnd(savedFogEnd);
            RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);
            RenderSystem.setShaderFogShape(savedFogShape);
            RenderSystem.setShaderColor(savedColor[0], savedColor[1], savedColor[2], savedColor[3]);
            Lighting.setupFor3DItems();
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /**
     * The renderer call itself, minus the dispatcher's extras (shadow, hitbox, fire): the same
     * {@link EntityRenderer#render} the inventory paper-doll ends up in, at partial tick 1.
     */
    private static <E extends Entity> void renderEntity(EntityRenderDispatcher dispatcher, E entity,
                                                        PoseStack poseStack, MultiBufferSource buffers) {
        EntityRenderer<? super E> renderer = dispatcher.getRenderer(entity);
        Vec3 offset = renderer.getRenderOffset(entity, 1.0F);
        poseStack.pushPose();
        poseStack.translate(offset.x, offset.y, offset.z);
        RenderSystem.runAsFancy(() -> renderer.render(entity, 0.0F, 1.0F, poseStack, buffers, LightTexture.FULL_BRIGHT));
        poseStack.popPose();
    }

    private static TextureTarget target(int size) {
        if (target == null) {
            target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
        } else if (target.width != size || target.height != size) {
            target.resize(size, size, Minecraft.ON_OSX);
        }
        return target;
    }

    /**
     * Box filter by an integer factor. The framebuffer holds premultiplied colour (opaque and cutout
     * pixels have alpha 255, translucent layers were blended over transparent black), so colour and
     * alpha are averaged as-is and colour is divided by alpha at the end.
     */
    private static NativeImage downscale(NativeImage src, int outSize) {
        int factor = src.getWidth() / outSize;
        NativeImage out = new NativeImage(outSize, outSize, true);
        int n = factor * factor;
        for (int oy = 0; oy < outSize; oy++) {
            for (int ox = 0; ox < outSize; ox++) {
                int r = 0, g = 0, b = 0, a = 0;
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        int c = src.getPixelRGBA(ox * factor + dx, oy * factor + dy); // ABGR
                        a += c >>> 24;
                        b += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        r += c & 0xFF;
                    }
                }
                int pixel = 0;
                if (a > 0) {
                    int outA = Math.round((float) a / n);
                    int outR = Math.min(255, Math.round(r * 255.0F / a));
                    int outG = Math.min(255, Math.round(g * 255.0F / a));
                    int outB = Math.min(255, Math.round(b * 255.0F / a));
                    pixel = outA << 24 | outB << 16 | outG << 8 | outR;
                }
                out.setPixelRGBA(ox, oy, pixel);
            }
        }
        return out;
    }

    /** Inclusive pixel rectangle of non-empty pixels. */
    private record PixelBounds(int minX, int minY, int maxX, int maxY) {

        /** Bounds of non-empty pixels within rows {@code [fromRow, toRow]}, or {@code null} if all empty. */
        @Nullable
        static PixelBounds of(NativeImage image, int fromRow, int toRow) {
            int w = image.getWidth();
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int y = fromRow; y <= toRow; y++) {
                for (int x = 0; x < w; x++) {
                    if ((image.getPixelRGBA(x, y) >>> 24) > ALPHA_EMPTY) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        maxY = y;
                    }
                }
            }
            return maxX < 0 ? null : new PixelBounds(minX, minY, maxX, maxY);
        }

        boolean touchesEdge(int size) {
            return minX == 0 || minY == 0 || maxX == size - 1 || maxY == size - 1;
        }
    }
}
