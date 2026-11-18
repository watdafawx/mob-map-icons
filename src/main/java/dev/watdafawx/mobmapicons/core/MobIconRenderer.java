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
import dev.watdafawx.mobmapicons.api.MobIconApi;
import dev.watdafawx.mobmapicons.MobMapIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *   <li>a square view around those pixels - tight in FULL_BODY, the shared {@link Frame} size when
 *       headings are on, or a head-sized square around the eyes in {@link IconMode#HEAD} - so big
 *       mobs shrink and small mobs grow to fill the icon.</li>
 * </ol>
 * The second pass is drawn at 2-4x the output size and box-filtered down, which keeps the pixel-art
 * look while smoothing the silhouette edge.
 *
 * <p><b>Heading.</b> Bucket {@code b} turns the model {@code b * 45} degrees clockwise as seen from
 * above (see {@link MobIconApi}), applied in the pose stack around the entity's vertical axis, so it
 * works for every renderer, including ones that ignore the entity's own yaw (the ender dragon).
 *
 * <p><b>Where it is drawn.</b> The entity stays wherever the caller put it (the real mob's position,
 * or the player's for a per-type icon), so renderers that read the world around them see real
 * blocks and biome, while light is passed in as full-bright. It is never added to the level.
 * {@link #isRendering()} is true during the draw, which the core uses to veto name tags.
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
    /** Empty margin around the model in the final icon, as a share of its size. */
    private static final float PADDING = 0.04F;
    /** Alpha at or below which a pixel counts as empty when measuring the model. */
    private static final int ALPHA_EMPTY = 8;
    /** HEAD mode falls back to the full body when the bounding box is at least this much wider than tall. */
    private static final float HEAD_WIDE_RATIO = 1.6F;
    /** HEAD crop edge as a multiple of the estimated head size, so ears, horns and snouts fit. */
    private static final float HEAD_MARGIN = 1.4F;
    /** HEAD crop edge as a multiple of the found head's drawn height... */
    private static final float HEAD_HEIGHT_MARGIN = 1.55F;
    /** Share of the HEAD crop edge the crop moves down from the head's centre, to show the shoulders. */
    private static final float HEAD_SHOULDER_SHIFT = 0.10F;
    /** ...or of its drawn width, whichever is bigger. */
    private static final float HEAD_WIDTH_MARGIN = 1.3F;
    /** Channel difference above which a pixel counts as changed when diffing head-hidden draws. */
    private static final int DIFF_THRESHOLD = 12;

    /** View-space light directions (camera looks down -Z, +Y up): key light top-front-left, fill front-right. */
    private static final Vector3f LIGHT_0 = new Vector3f(-0.35F, 1.0F, 0.9F).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(0.5F, 0.3F, 1.0F).normalize();

    @Nullable
    private static TextureTarget target;
    private static boolean rendering;

    private MobIconRenderer() {}

    /**
     * Rotation-independent extent of a model, measured once per variant so every heading bucket can
     * use the same scale: {@code diameter} is the model's widest extent seen from above (the most it
     * can span sideways at any yaw), {@code yMin..yMax} its height range seen from the side (pitch 0),
     * in blocks. Each bucket is still centred on its own pixels, so only the extent matters, not
     * where the model sits relative to its axis.
     */
    public record Frame(float diameter, float yMin, float yMax) {
        /** Edge of a square that holds the model at any yaw, under the icon camera's pitch. */
        float side() {
            float pitch = PITCH_DEGREES * Mth.DEG_TO_RAD;
            return Math.max(diameter, (yMax - yMin) * Mth.cos(pitch) + diameter * Mth.sin(pitch));
        }
    }

    /** {@code true} while an icon is being drawn (render thread). */
    public static boolean isRendering() {
        return rendering;
    }

    /** Offscreen render size for an output size: an integer multiple of it, so the downscale is exact. */
    static int renderSizeFor(int outSize) {
        int factor = Math.max(1, Math.min(4, MAX_RENDER_SIZE / outSize));
        return outSize * factor;
    }

    /** Whether HEAD mode makes sense for this entity; flyers and wide mobs get the full body instead. */
    public static boolean headCropApplies(Entity entity) {
        if (entity instanceof FlyingMob || entity instanceof Bat || entity instanceof EnderDragon) return false;
        float w = Math.max(entity.getBbWidth(), 0.01F);
        float h = Math.max(entity.getBbHeight(), 0.01F);
        return w / h < HEAD_WIDE_RATIO;
    }

    /**
     * Draws {@code entity} as an icon. The entity's rotation is reset (its position is kept), so pass
     * a throwaway instance that is not in any level's entity list.
     *
     * @param outSize   edge length of the returned image in pixels
     * @param yawBucket heading bucket, {@code 0..}{@link MobIconApi#YAW_BUCKETS}{@code -1}
     * @param frame     shared scale for all buckets (FULL_BODY only), or {@code null} for a tight fit
     * @return a new image owned by the caller, or {@code null} when the entity draws nothing visible
     * @throws RuntimeException from the entity's renderer; the caller decides what a failure means
     */
    @Nullable
    public static NativeImage render(Entity entity, IconMode mode, int outSize, int yawBucket, @Nullable Frame frame) {
        return render(entity, mode, outSize, yawBucket, frame, false);
    }

    static NativeImage render(Entity entity, IconMode mode, int outSize, int yawBucket, @Nullable Frame frame, boolean top) {
        float pitch = top ? 90 : PITCH_DEGREES;
        RenderSystem.assertOnRenderThread();
        int size = renderSizeFor(outSize);
        pose(entity);
        float yaw = (top ? 180 : YAW_DEGREES) + facingCorrection(entity) - Math.floorMod(yawBucket, MobIconApi.YAW_BUCKETS) * 45.0F;

        float bbHeight = Math.max(entity.getBbHeight(), 0.1F);
        View probeView = probe(entity, size, pitch, yaw, 0.0F, bbHeight / 2.0F);
        if (probeView == null) return null;
        try {
            PixelBounds px = probeView.bounds;
            float unit = probeView.unit();
            float x0 = probeView.left + px.minX * unit;
            float x1 = probeView.left + (px.maxX + 1) * unit;
            float y1 = probeView.top - px.minY * unit;
            float y0 = probeView.top - (px.maxY + 1) * unit;
            float cx = (x0 + x1) / 2.0F;
            float cy = (y0 + y1) / 2.0F;
            float side;

            float[] headRect = mode == IconMode.HEAD && headCropApplies(entity)
                    ? findHead(entity, probeView, pitch, yaw) : null;
            if (headRect != null) {
                // The head as drawn: where the model's head part is (see findHead). The edge follows
                // the head's height more than its width, because the height barely changes with the
                // heading while the width does; that keeps heads one size across buckets.
                cx = (headRect[0] + headRect[2]) / 2.0F;
                cy = (headRect[1] + headRect[3]) / 2.0F;
                float w = headRect[2] - headRect[0];
                float h = headRect[3] - headRect[1];
                side = Math.max(h * HEAD_HEIGHT_MARGIN, w * HEAD_WIDTH_MARGIN);
                // Head and shoulders: sit the square a little below the head's centre so the top of the
                // body comes into frame, like the "head" column of the style mocks.
                cy -= side * HEAD_SHOULDER_SHIFT;
            } else if (mode == IconMode.HEAD && headCropApplies(entity)) {
                // No head part to find: a head-sized square around the eyes. The eye height is on the
                // entity's vertical axis, but heads sit forward of it, so the horizontal centre comes
                // from the pixels above the eyes, and the square moves up if the head would be cut off.
                float head = headSize(entity) * HEAD_MARGIN;
                float eyeY = entity.getEyeHeight() * Mth.cos(pitch * Mth.DEG_TO_RAD);
                int eyeRow = Mth.clamp(Math.round((probeView.top - eyeY) / unit), px.minY, px.maxY);
                PixelBounds above = PixelBounds.of(probeView.image, px.minY, eyeRow);
                if (above == null) above = px;
                cx = probeView.left + (above.minX + above.maxX + 1) * 0.5F * unit;
                float headTop = probeView.top - above.minY * unit;
                cy = Math.max(eyeY, headTop - head * (0.5F - PADDING));
                side = head;
            } else if (frame != null) {
                side = frame.side() * (1.0F + 2.0F * PADDING) + 2.0F * unit;
            } else {
                side = Math.max(x1 - x0, y1 - y0) * (1.0F + 2.0F * PADDING) + 2.0F * unit;
            }

            // Pass 2: the final square, then box-filter down to the output size.
            try (NativeImage full = draw(entity, size, pitch, yaw,
                    cx - side / 2, cx + side / 2, cy - side / 2, cy + side / 2)) {
                return downscale(full, outSize);
            }
        } finally {
            probeView.image.close();
        }
    }

    /**
     * Measures {@code entity}'s rotation-independent {@link Frame}: one probe from straight above
     * and one from the side. Render thread.
     *
     * @return the frame, or {@code null} when the entity draws nothing
     */
    @Nullable
    public static Frame measureFrame(Entity entity, int outSize) {
        RenderSystem.assertOnRenderThread();
        int size = renderSizeFor(outSize);
        pose(entity);
        float correction = facingCorrection(entity);
        float bbHeight = Math.max(entity.getBbHeight(), 0.1F);

        // From above (pitch 90): the footprint. Its widest extent over all directions is the most the
        // model can span sideways at any yaw. Each row's outermost pixel corners are enough for that.
        View top = probe(entity, size, 90.0F, correction, 0.0F, 0.0F);
        if (top == null) return null;
        float diameter = 0.0F;
        try {
            PixelBounds b = top.bounds;
            List<float[]> edge = new ArrayList<>();
            for (int y = b.minY; y <= b.maxY; y++) {
                int first = -1, last = -1;
                for (int x = b.minX; x <= b.maxX; x++) {
                    if ((top.image.getPixelRGBA(x, y) >>> 24) <= ALPHA_EMPTY) continue;
                    if (first < 0) first = x;
                    last = x;
                }
                if (first < 0) continue;
                edge.add(new float[]{first, y});
                edge.add(new float[]{first, y + 1});
                edge.add(new float[]{last + 1, y});
                edge.add(new float[]{last + 1, y + 1});
            }
            for (int deg = 0; deg < 180; deg += 3) {
                float cos = Mth.cos(deg * Mth.DEG_TO_RAD), sin = Mth.sin(deg * Mth.DEG_TO_RAD);
                float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
                for (float[] p : edge) {
                    float d = p[0] * cos + p[1] * sin;
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
                diameter = Math.max(diameter, max - min);
            }
            diameter *= top.unit();
        } finally {
            top.image.close();
        }

        View sideView = probe(entity, size, 0.0F, correction + 90.0F, 0.0F, bbHeight / 2.0F);
        if (sideView == null) return null;
        try {
            float unit = sideView.unit();
            float yMax = sideView.top - sideView.bounds.minY * unit;
            float yMin = sideView.top - (sideView.bounds.maxY + 1) * unit;
            return new Frame(diameter, yMin, yMax);
        } finally {
            sideView.image.close();
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
     * Where the head is in {@code probe}, in view units ({@code [x0, y0, x1, y1]}), or {@code null}
     * when the model has no head part we can find or hiding it changes nothing. Draws the probe view
     * again with the model's head part(s) hidden: the pixels that change are the head.
     */
    @Nullable
    private static float[] findHead(Entity entity, View probe, float pitch, float yaw) {
        List<ModelPart> parts = headParts(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity));
        if (parts.isEmpty()) return null;
        boolean[] wasVisible = new boolean[parts.size()];
        NativeImage hidden;
        try {
            for (int i = 0; i < parts.size(); i++) {
                wasVisible[i] = parts.get(i).visible;
                parts.get(i).visible = false;
            }
            hidden = draw(entity, probe.size, pitch, yaw, probe.left, probe.right, probe.bottom, probe.top);
        } finally {
            for (int i = 0; i < parts.size(); i++) parts.get(i).visible = wasVisible[i];
        }
        try (hidden) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            PixelBounds b = probe.bounds;
            for (int y = b.minY; y <= b.maxY; y++) {
                for (int x = b.minX; x <= b.maxX; x++) {
                    if (differs(probe.image.getPixelRGBA(x, y), hidden.getPixelRGBA(x, y))) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            if (maxX < 0) return null;
            float unit = probe.unit();
            return new float[]{probe.left + minX * unit, probe.top - (maxY + 1) * unit,
                    probe.left + (maxX + 1) * unit, probe.top - minY * unit};
        }
    }

    private static boolean differs(int a, int b) {
        for (int shift = 0; shift < 32; shift += 8) {
            if (Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)) > DIFF_THRESHOLD) return true;
        }
        return false;
    }

    /**
     * The model parts that make up the head of {@code renderer}'s model: {@link HeadedModel#getHead()}
     * (humanoids, villagers, illagers), {@code AgeableListModel.headParts()} (most quadrupeds,
     * chickens, wolves), a descendant called {@code head} of a {@link HierarchicalModel}'s root, or
     * a field called {@code head}. Empty when there is none (non-living renderers, odd models).
     */
    private static List<ModelPart> headParts(EntityRenderer<?> renderer) {
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) return List.of();
        EntityModel<?> model = living.getModel();
        List<ModelPart> parts = new ArrayList<>();
        try {
            if (model instanceof HeadedModel headed) {
                parts.add(headed.getHead());
            } else if (model instanceof AgeableListModel<?>) {
                Method m = AgeableListModel.class.getDeclaredMethod("headParts");
                m.setAccessible(true);
                for (Object part : (Iterable<?>) m.invoke(model)) {
                    if (part instanceof ModelPart mp) parts.add(mp);
                }
            } else if (model instanceof HierarchicalModel<?> hierarchical) {
                ModelPart head = findChild(hierarchical.root(), "head", 4);
                if (head != null) parts.add(head);
            }
            if (parts.isEmpty()) {
                for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType() == ModelPart.class && f.getName().equals("head")
                                && !Modifier.isStatic(f.getModifiers())) {
                            f.setAccessible(true);
                            if (f.get(model) instanceof ModelPart mp) parts.add(mp);
                            return parts;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            MobMapIcons.LOG.debug("Could not find the head of {}: {}", model.getClass().getName(), t.toString());
        }
        return parts;
    }

    /** Depth-first search for a part called {@code name} under {@code root}, at most {@code depth} levels down. */
    @Nullable
    @SuppressWarnings("unchecked")
    private static ModelPart findChild(ModelPart root, String name, int depth) throws ReflectiveOperationException {
        if (root.hasChild(name)) return root.getChild(name);
        if (depth <= 0) return null;
        if (childrenField == null) {
            childrenField = ModelPart.class.getDeclaredField("children");
            childrenField.setAccessible(true);
        }
        for (ModelPart child : ((Map<String, ModelPart>) childrenField.get(root)).values()) {
            ModelPart found = findChild(child, name, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    @Nullable
    private static Field childrenField;

    /**
     * Estimated head edge in blocks: most vanilla heads are about half a block, smaller on small
     * mobs (chickens, cats, babies), bigger on very tall ones.
     */
    private static float headSize(Entity entity) {
        float w = Math.max(entity.getBbWidth(), 0.1F);
        float h = Math.max(entity.getBbHeight(), 0.1F);
        return Math.min(w * 0.85F, Math.max(0.55F, h * 0.22F));
    }

    /** A probe image, its view rectangle in view units and the model's pixel bounds in it. */
    private record View(NativeImage image, int size, float left, float right, float bottom, float top, PixelBounds bounds) {
        float unit() {
            return (right - left) / size;
        }
    }

    /**
     * Pass 1: draws the entity in a square view centred on {@code (centerX, centerY)}, doubling the
     * view until the model no longer touches its edge.
     *
     * @return the probe (caller closes its image), or {@code null} when nothing is drawn
     */
    @Nullable
    private static View probe(Entity entity, int size, float pitch, float yaw, float centerX, float centerY) {
        float bbWidth = Math.max(entity.getBbWidth(), 0.1F);
        float bbHeight = Math.max(entity.getBbHeight(), 0.1F);
        float half = Math.max(bbWidth, bbHeight) * 0.9F + 0.5F;
        NativeImage image = null;
        for (int attempt = 0; ; attempt++) {
            if (image != null) image.close();
            float left = centerX - half, right = centerX + half, bottom = centerY - half, top = centerY + half;
            image = draw(entity, size, pitch, yaw, left, right, bottom, top);
            PixelBounds px = PixelBounds.of(image, 0, size - 1);
            if (px == null) {
                image.close();
                return null;
            }
            if (!px.touchesEdge(size) || attempt == 3) return new View(image, size, left, right, bottom, top, px);
            half *= 2.0F;
        }
    }

    /** Neutral pose: rotation reset, head straight. The position is left where the caller put it. */
    private static void pose(Entity entity) {
        entity.moveTo(entity.getX(), entity.getY(), entity.getZ(), 0.0F, 0.0F);
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
    private static NativeImage draw(Entity entity, int size, float pitch, float yaw,
                                    float left, float right, float bottom, float top) {
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
        rendering = true;
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
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
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
            rendering = false;
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
