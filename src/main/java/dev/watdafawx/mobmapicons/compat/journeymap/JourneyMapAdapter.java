package dev.watdafawx.mobmapicons.compat.journeymap;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MobIconApi;
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
    }

    private static void update(EntityRadarUpdateEvent event) {
        if (!ClientConfig.journeyMapEnabled() || event.getType() != EntityRadarUpdateEvent.EntityType.MOB) return;
        var wrapped = event.getWrappedEntity();
        var entity = wrapped.getEntityRef().get();
        if (!(entity instanceof Mob mob)) return;
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
        if (MobIconApi.rotateWithHeading()) {
            bucket = MobIconApi.yawBucket(mob.yBodyRot + (float) minimapRotation(ui));
        }
        ResourceLocation icon = paddedIcon(mob, bucket);
        // JourneyMap clears this override each radar update. Never hand it a pending resource.
        if (icon != null) wrapped.setEntityIconLocation(icon);
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

    /** The mob's icon fitted into JourneyMap's marker, served by {@link PaddedIconPack}; the unpadded one if that fails. */
    private static ResourceLocation paddedIcon(LivingEntity mob, int bucket) {
        ResourceLocation plain = MobIconApi.getResourceIcon(mob, bucket); // also queues the render
        byte[] png = plain == null ? null : MobIconApi.getPng(mob, bucket);
        if (png == null) return null;
        CRC32 crc = new CRC32();
        crc.update(png);
        String path = PaddedIconPack.PREFIX + Long.toHexString(crc.getValue()) + "_" + png.length + ".png";
        if (!PaddedIconPack.has(path)) {
            byte[] padded = IconPadding.fit(png, INSET, true);
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

    private static Object fieldValue(Object props, String name) throws ReflectiveOperationException {
        Object field = props.getClass().getField(name).get(props);
        return field.getClass().getMethod("get").invoke(field);
    }
}
