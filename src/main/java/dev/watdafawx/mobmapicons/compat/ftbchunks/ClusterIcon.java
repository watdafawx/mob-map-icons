package dev.watdafawx.mobmapicons.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Farm mode: one icon standing in for a connected group of same-looking mobs, with a count badge
 * ({@code x37}, {@code 999+}) and a {@code 37 x Cow} tooltip. Everything FTB asks of an icon delegates
 * to the group's representative FTB icon (same entity type, so the same visibility and scale rules).
 *
 * <p>The position is the group's live centroid, refreshed once per game tick and interpolated between
 * ticks, so it follows a moving herd without jitter. Membership is fixed until FTB next rebuilds its
 * icon list (every 500 ms on the minimap, on any zoom or refresh on the large map).
 */
final class ClusterIcon implements MapIcon {
    private final MapIcon original;
    private final Entity[] members;
    private final int count;
    private final String badge;
    private final Component tooltip;
    private int rep;
    /** Cleared by {@link IconPass} when this cluster's label would overprint a bigger neighbour's. */
    private boolean showBadge = true;

    private long tick = Long.MIN_VALUE;
    private double prevX, prevY, prevZ, curX, curY, curZ;
    /** Fan-out nudge in blocks from {@link IconPass} when several kinds share one centroid. */
    private double offX, offZ;

    ClusterIcon(MapIcon repOriginal, Entity[] members, int repIndex, double x, double y, double z) {
        this.original = repOriginal;
        this.members = members;
        this.count = members.length;
        this.rep = repIndex;
        this.badge = count > 999 ? "999+" : "x" + count;
        this.tooltip = Component.literal(count + " x ").append(members[repIndex].getType().getDescription());
        this.curX = this.prevX = x;
        this.curY = this.prevY = y;
        this.curZ = this.prevZ = z;
    }

    String badgeText() {
        return badge;
    }

    boolean showBadge() {
        return showBadge;
    }

    void setOffset(double x, double z) {
        offX = x;
        offZ = z;
        curX += x;
        curZ += z;
        prevX += x;
        prevZ += z;
    }

    void setShowBadge(boolean show) {
        showBadge = show;
    }

    /** A member that still exists, preferring the one picked at build time. */
    private Entity rep() {
        Entity e = members[rep];
        if (!e.isRemoved()) {
            return e;
        }
        for (int i = 0; i < members.length; i++) {
            if (!members[i].isRemoved()) {
                rep = i;
                return members[i];
            }
        }
        return e;
    }

    private void update() {
        Level level = Minecraft.getInstance().level;
        long now = level == null ? 0L : level.getGameTime();
        if (now == tick) {
            return;
        }
        prevX = curX;
        prevY = curY;
        prevZ = curZ;
        if (now - tick > 1) {
            prevX = Double.NaN; // stale: snap instead of gliding from an old spot
        }
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (Entity e : members) {
            if (!e.isRemoved()) {
                sx += e.getX();
                sy += e.getY();
                sz += e.getZ();
                n++;
            }
        }
        if (n > 0) {
            curX = sx / n + offX;
            curY = sy / n;
            curZ = sz / n + offZ;
        }
        if (Double.isNaN(prevX)) {
            prevX = curX;
            prevY = curY;
            prevZ = curZ;
        }
        tick = now;
    }

    @Override
    public Vec3 getPos(float partialTick) {
        update();
        if (partialTick >= 1f) {
            return new Vec3(curX, curY, curZ);
        }
        return new Vec3(prevX + (curX - prevX) * partialTick, prevY + (curY - prevY) * partialTick,
                prevZ + (curZ - prevZ) * partialTick);
    }

    @Override
    public void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h, boolean outsideVisibleArea, int iconAlpha) {
        long t0 = System.nanoTime();
        Entity e = rep();
        ResourceLocation texture = MobIconApi.getIcon(e);
        if (texture == null) {
            original.draw(mapType, graphics, x, y, w, h, outsideVisibleArea, iconAlpha);
        } else {
            IconDraw.quad(graphics, texture, e, x, y, w, h, iconAlpha);
        }
        if (showBadge) {
            IconDraw.badge(graphics, badge, x, y, w, h, iconAlpha);
        }
        IconDraw.drawNanos += System.nanoTime() - t0;
        IconDraw.drawCount++;
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
        list.add(tooltip);
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
