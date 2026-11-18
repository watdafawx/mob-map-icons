package dev.watdafawx.mobmapicons.compat.journeymap;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MapMod;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import dev.watdafawx.mobmapicons.core.HeadingArrow;
import dev.watdafawx.mobmapicons.core.IconPadding;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.event.EntityRadarUpdateEvent;
import journeymap.api.v2.client.util.UIState;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.lang.reflect.Method;
import java.util.zip.CRC32;

/**
 * JourneyMap: per mob, on the radar update event, picks the icon for the mob's variant and heading,
 * fitted into JourneyMap's round marker, and applies farm mode / the icon cap.
 *
 * <p>Heading: JourneyMap never turns a mob icon by the mob's heading. Its {@code DrawEntityStep}
 * draws the icon upright (only counter-rotated by the minimap's own rotation) and turns just the
 * separate locator arrow by {@code getYRot()}. So the per-heading icons are used as is, with the
 * bucket taken from the mob's body yaw plus the minimap's rotation. See docs/research/journeymap.md.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapAdapter implements IClientPlugin {
    /** Margin kept free around the icon inside the round marker, per side, as a fraction of the icon edge. */
    private static final double INSET = 0.10;
    /** Wider margin when the heading arrow is drawn over the rim (see HeadingArrow). */
    private static final double ARROW_INSET = 0.22;

    private static final FarmClusters CLUSTERS = new FarmClusters();

    // JourneyMap discovers and initializes the plugin; reflective loading must not subscribe twice.
    public static void init() {}

    @Override
    public String getModId() {
        return MobMapIcons.MODID;
    }

    @Override
    public void initialize(IClientAPI api) {
        ClientEventRegistry.ENTITY_RADAR_UPDATE_EVENT.subscribe(getModId(), JourneyMapAdapter::update);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> OwnArrow.tick());
    }

    private static void update(EntityRadarUpdateEvent event) {
        if (!ClientConfig.journeyMapEnabled()) {
            FrameHider.releaseAll();
            return;
        }
        var wrapped = event.getWrappedEntity();
        var entity = wrapped.getEntityRef().get();
        if (event.getType() == EntityRadarUpdateEvent.EntityType.PLAYER) {
            if (entity instanceof net.minecraft.client.player.RemotePlayer other) {
                if (ClientConfig.players(MapMod.JOURNEYMAP)) updatePlayer(event, other);
                else restoreFace(other);
            }
            return;
        }
        if (event.getType() != EntityRadarUpdateEvent.EntityType.MOB || !(entity instanceof Mob mob)) return;
        var type = entity.getType();
        if (ClientConfig.isBlacklisted(BuiltInRegistries.ENTITY_TYPE.getKey(type))) return;

        UIState ui = event.getActiveUiState();
        var verdict = CLUSTERS.verdict(mob, ui == null ? 0 : ui.blockSize);
        debugLog(ui, mob, verdict);
        if (verdict.hidden()) {
            if (event.isCancellable()) event.cancel(); else wrapped.setDisable(true);
            return;
        }
        if (verdict.count() > 1) {
            wrapped.setCustomName(Component.literal(verdict.count() + " x ").append(type.getDescription()));
        }

        int bucket = 0;
        if (MobIconApi.usesHeading(MapMod.JOURNEYMAP)) {
            bucket = MobIconApi.yawBucket(mob.yBodyRot + (float) minimapRotation(ui));
        }
        // JourneyMap's own heading arrow (its locator, turned by the mob's yaw) makes ours a double: skip ours then.
        boolean ourArrow = MobIconApi.headingArrow(MapMod.JOURNEYMAP) && !journeyMapShowsMobHeading(ui);
        FrameRule rule = FrameRule.of(ClientConfig.style(MapMod.JOURNEYMAP), ClientConfig.journeyMapFrame(),
                MobIconApi.rotateWithHeading(MapMod.JOURNEYMAP) || ourArrow, FrameHider.canHideFill());
        ResourceLocation icon = paddedIcon(mob, bucket, rule, ourArrow);
        // JourneyMap clears this override each radar update. Never hand it a pending resource.
        if (icon != null) {
            wrapped.setEntityIconLocation(icon);
            // The marker's black border is JourneyMap's per-entity flag (reset to true on every update); its
            // coloured fill can only be hidden for all mobs at once, see FrameHider.
            wrapped.setDrawOutline(!rule.hideFrame());
        }
        FrameHider.setFillHidden(rule.hideFrame());
    }

    // ---------------------------------------------------------------------------------------------
    // Other players. JourneyMap never asks the API for a player's icon: DrawEntityStep draws the skin's
    // face from IgnSkin.faceImageCache (a private static map of uuid -> DynamicTexture, filled from the
    // skin and handed out by IgnSkin.getFace), so setEntityIconLocation has no effect on a player. The only
    // way to restyle one is to put our icon in that map. That is JourneyMap internals, reached by
    // reflection and guarded: when anything differs, JourneyMap keeps drawing its faces.

    private static java.lang.reflect.Field faceCache;
    private static boolean faceFailed;
    private record Face(long hash, net.minecraft.client.renderer.texture.DynamicTexture texture) {}

    /** uuid -> the icon we last put in JourneyMap's face cache for that player (render thread only). */
    private static final java.util.Map<java.util.UUID, Face> FACES = new java.util.HashMap<>();

    @SuppressWarnings("unchecked")
    private static java.util.Map<java.util.UUID, net.minecraft.client.renderer.texture.DynamicTexture> faces() {
        if (faceFailed) return null;
        try {
            if (faceCache == null) {
                faceCache = Class.forName("journeymap.client.texture.IgnSkin").getDeclaredField("faceImageCache");
                faceCache.setAccessible(true);
            }
            return (java.util.Map<java.util.UUID, net.minecraft.client.renderer.texture.DynamicTexture>) faceCache.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            faceFailed = true;
            MobMapIcons.LOG.warn("JourneyMap's player face cache is not where expected; players keep JourneyMap's own faces", e);
            return null;
        }
    }

    private static void updatePlayer(EntityRadarUpdateEvent event, net.minecraft.client.player.RemotePlayer player) {
        int bucket = 0;
        if (MobIconApi.rotateWithHeading(MapMod.JOURNEYMAP)) {
            bucket = MobIconApi.yawBucket(player.yBodyRot + (float) minimapRotation(event.getActiveUiState()));
        }
        // No baked arrow on a player's face: JourneyMap draws its own heading marker for players.
        ResourceLocation plain = MobIconApi.getResourceIcon(player, bucket, ClientConfig.profile(MapMod.JOURNEYMAP), false); // also queues the render
        byte[] png = plain == null ? null : MobIconApi.getPng(player, bucket, ClientConfig.profile(MapMod.JOURNEYMAP), false);
        var map = png == null ? null : faces();
        if (map == null) return;
        // The square box JourneyMap draws round a face is sized for its 16 px face, not for our skin model.
        FrameHider.setPlayerBoxHidden(true);
        CRC32 crc = new CRC32();
        crc.update(png);
        long hash = crc.getValue() << 20 ^ png.length;
        java.util.UUID id = player.getUUID();
        Minecraft.getInstance().execute(() -> {
            Face done = FACES.get(id);
            if (done != null && done.hash == hash && map.get(id) == done.texture) return;
            try {
                var texture = new net.minecraft.client.renderer.texture.DynamicTexture(
                        com.mojang.blaze3d.platform.NativeImage.read(new java.io.ByteArrayInputStream(png)));
                map.put(id, texture);
                FACES.put(id, new Face(hash, texture));
                if (done != null) done.texture.close(); // ours, never JourneyMap's own
            } catch (java.io.IOException | RuntimeException e) {
                MobMapIcons.LOG.debug("Could not hand a player icon to JourneyMap: {}", e.toString());
            }
        });
    }

    /** {@code players} was turned off: drop our icon so JourneyMap fetches the face again. */
    private static void restoreFace(net.minecraft.client.player.RemotePlayer player) {
        FrameHider.setPlayerBoxHidden(false);
        var map = faces();
        if (map == null) return;
        Minecraft.getInstance().execute(() -> {
            Face ours = FACES.remove(player.getUUID());
            if (ours == null) return;
            if (map.get(player.getUUID()) == ours.texture) map.remove(player.getUUID());
            ours.texture.close();
        });
    }

    private static long lastDebug;

    /** Dev aid: once a second, which UI asked, at what zoom, and what farm mode decided. */
    private static void debugLog(UIState ui, Mob mob, FarmClusters.Verdict verdict) {
        long now = System.currentTimeMillis();
        if (now - lastDebug < 1000) return;
        lastDebug = now;
        MobMapIcons.LOG.debug("journeymap radar: ui={} zoom={} blockSize={} rot={} {} -> {}", ui == null ? null : ui.ui,
                ui == null ? 0 : ui.zoom, ui == null ? 0 : ui.blockSize, minimapRotation(ui), mob.getType(), verdict);
    }

    /**
     * The mob's icon in the style {@code rule} picks, served by {@link PaddedIconPack}: fitted into
     * JourneyMap's round marker when the marker stays, as is when it is hidden. The unpadded one if
     * fitting fails.
     */
    private static ResourceLocation paddedIcon(LivingEntity mob, int bucket, FrameRule rule, boolean arrow) {
        // Marker kept: the arrow is not baked in (that shrinks the icon twice, once for the arrow's room and once
        // fitting the marker, and leaves it small and under the marker's border); it is drawn over the fitted icon.
        boolean overlay = arrow && !rule.hideFrame();
        boolean baked = arrow && !overlay;
        ResourceLocation plain = MobIconApi.getResourceIcon(mob, bucket, ClientConfig.profile(MapMod.JOURNEYMAP).withStyle(rule.body()), baked); // also queues the render
        byte[] png = plain == null ? null : MobIconApi.getPng(mob, bucket, ClientConfig.profile(MapMod.JOURNEYMAP).withStyle(rule.body()), baked);
        if (png == null) return null;
        CRC32 crc = new CRC32();
        crc.update(png);
        // Fitted and as-is copies of one icon never share a path: the bytes behind a path must not change.
        String path = PaddedIconPack.PREFIX + Long.toHexString(crc.getValue()) + "_" + png.length
                + (rule.hideFrame() ? "_free" : "_fit") + (overlay ? "_h" + Math.floorMod(bucket, 8) : "") + ".png";
        if (!PaddedIconPack.has(path)) {
            byte[] padded = rule.hideFrame() ? png : IconPadding.fit(png, overlay ? ARROW_INSET : INSET, true);
            if (overlay && padded != null) padded = HeadingArrow.overlay(padded, bucket);
            if (padded == null) return plain;
            PaddedIconPack.put(path, padded);
        }
        return ResourceLocation.fromNamespaceAndPath(MobMapIcons.MODID, path);
    }

    // ---------------------------------------------------------------------------------------------
    // Minimap rotation. JourneyMap turns the whole minimap clockwise by this many degrees (0 when north
    // is up) and counter-rotates every icon to stay upright, so a mob's apparent heading on screen is its
    // yaw plus this. Not in the API: read from JourneyMap's own minimap properties by reflection, and
    // fall back to north-up (0) if any of it is not there. The fullscreen map never rotates.

    private static boolean reflectionFailed;
    private static Method getInstance, getActiveProps;

    private static double minimapRotation(UIState ui) {
        if (ui == null || ui.ui != Context.UI.Minimap || reflectionFailed) return 0;
        try {
            if (getInstance == null) {
                Class<?> client = Class.forName("journeymap.client.JourneymapClient");
                getInstance = client.getMethod("getInstance");
                getActiveProps = client.getMethod("getActiveMiniMapProperties");
            }
            Object props = getActiveProps.invoke(getInstance.invoke(null));
            String orientation = String.valueOf(fieldValue(props, "orientation"));
            return switch (orientation) {
                case "OldNorth" -> 90.0;
                case "PlayerHeading" -> "Circle".equals(String.valueOf(fieldValue(props, "shape")))
                        ? 180.0 - Minecraft.getInstance().gameRenderer.getMainCamera().getYRot() : 0.0;
                default -> 0.0;
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            reflectionFailed = true;
            MobMapIcons.LOG.warn("JourneyMap minimap rotation not readable; icons assume a north-up minimap", e);
            return 0;
        }
    }

    private static boolean headingReflectionFailed;
    private static Method getFullProps;

    /**
     * Whether JourneyMap draws its own heading arrow for mobs on this map ("show mob heading", on by
     * default; the minimap and the fullscreen map have one setting each). Read from JourneyMap's map
     * properties by reflection; when it cannot be read, assume JourneyMap's default (on), so a failure
     * never produces two arrows.
     */
    private static boolean journeyMapShowsMobHeading(UIState ui) {
        if (headingReflectionFailed) return true;
        try {
            if (getInstance == null) {
                Class<?> client = Class.forName("journeymap.client.JourneymapClient");
                getInstance = client.getMethod("getInstance");
                getActiveProps = client.getMethod("getActiveMiniMapProperties");
            }
            Object client = getInstance.invoke(null);
            Object props;
            if (ui != null && ui.ui == Context.UI.Fullscreen) {
                if (getFullProps == null) getFullProps = client.getClass().getMethod("getFullMapProperties");
                props = getFullProps.invoke(client);
            } else {
                props = getActiveProps.invoke(client);
            }
            return Boolean.TRUE.equals(fieldValue(props, "showMobHeading"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            headingReflectionFailed = true;
            MobMapIcons.LOG.warn("JourneyMap's mob heading setting is not readable; assuming it is on and drawing no heading arrow of ours there", e);
            return true;
        }
    }

    private static Object fieldValue(Object props, String name) throws ReflectiveOperationException {
        Object field = props.getClass().getField(name).get(props);
        return field.getClass().getMethod("get").invoke(field);
    }
}
