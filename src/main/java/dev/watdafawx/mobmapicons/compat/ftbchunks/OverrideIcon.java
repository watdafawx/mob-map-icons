package dev.watdafawx.mobmapicons.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.core.ApothBoss;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Draws our mob icon where FTB's {@code EntityMapIcon} would have drawn its head. Everything except
 * {@link #draw} delegates to that original, so position, visibility, scale, priority, tooltip (the
 * entity's display name) and the hover hotkeys behave exactly as in FTB.
 *
 * <p><b>Upright:</b> FTB rotates only an icon's <i>position</i> with the minimap, never its quad, and
 * this draws an axis-aligned quad, so icons stay upright whatever the map rotation. The mob's heading
 * is instead baked into which of the 8 heading icons is drawn, see {@link IconDraw#bucket}.
 *
 * <p><b>Coordinates:</b> the minimap passes a 1x1 unit square under a scaled pose, the large map passes a
 * pixel rectangle. Like FTB, the quad is laid out in an 18-unit cell and the pose is scaled by
 * {@code w/18}, which makes both surfaces the same code.
 */
final class OverrideIcon implements MapIcon {
    private final MapIcon original;
    private final Entity entity;
    /** Whether the heading arrow is drawn into the icon (off where FTB keeps its own pointer next to it). */
    private final boolean arrow;

    OverrideIcon(MapIcon original, Entity entity) {
        this(original, entity, true);
    }

    OverrideIcon(MapIcon original, Entity entity, boolean arrow) {
        this.original = original;
        this.entity = entity;
        this.arrow = arrow;
    }

    @Override
    public void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h, boolean outsideVisibleArea, int iconAlpha) {
        // Look the texture up again on every draw: FTB keeps this icon for up to 500 ms (minimap) or until
        // the large map rebuilds, and a resource reload or icon config change frees the texture we were
        // built with. Binding a freed id makes the texture manager try to load it from disk (a WARN per
        // mob and a missing-texture frame), so fall back to FTB's own icon until ours is rendered again.
        long t0 = System.nanoTime();
        ResourceLocation texture = MobIconApi.getIcon(entity, IconDraw.bucket(entity, mapType), MapMod.FTB, IconDraw.viewOf(mapType), arrow);
        if (texture == null) {
            original.draw(mapType, graphics, x, y, w, h, outsideVisibleArea, iconAlpha);
        } else {
            IconDraw.quad(graphics, texture, entity, x, y, w, h, iconAlpha, IconDraw.viewOf(mapType));
        }
        if (ClientConfig.apotheosisBossNames()) {
            var name = ApothBoss.name(entity);
            if (name != null) IconDraw.label(graphics, name, ApothBoss.color(entity), x, y, w, h, iconAlpha, IconDraw.viewOf(mapType));
        }
        IconDraw.drawNanos += System.nanoTime() - t0;
        IconDraw.drawCount++;
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
