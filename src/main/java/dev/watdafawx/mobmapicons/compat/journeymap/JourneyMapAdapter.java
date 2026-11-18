package dev.watdafawx.mobmapicons.compat.journeymap;

import dev.watdafawx.mobmapicons.ClientConfig;
import dev.watdafawx.mobmapicons.MobMapIcons;
import dev.watdafawx.mobmapicons.api.MobIconApi;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.event.EntityRadarUpdateEvent;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapAdapter implements IClientPlugin {
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
        var entity = event.getWrappedEntity().getEntityRef().get();
        if (!(entity instanceof Mob)) return;
        var type = entity.getType();
        if (ClientConfig.isBlacklisted(BuiltInRegistries.ENTITY_TYPE.getKey(type))) return;
        var icon = MobIconApi.getResourceIcon(type);
        // JourneyMap clears this override each radar update. Never hand it a pending resource.
        if (icon != null) event.getWrappedEntity().setEntityIconLocation(icon);
    }
}
