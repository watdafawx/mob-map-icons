package dev.watdafawx.mobmapicons.core;

import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Client wiring for the core: frame hook, reload listener, commands, self-test.
 *
 * <p>Only ever called from behind the client-dist check in the mod constructor, so a dedicated
 * server never loads this or anything it references.
 */
public final class MobIconCore {

    private MobIconCore() {}

    public static void init(IEventBus modBus) {
        // A resource pack change can retexture any mob: drop every icon. Runs on the render thread.
        modBus.addListener(RegisterClientReloadListenersEvent.class, event ->
                event.registerReloadListener((ResourceManagerReloadListener) manager -> MobIconCache.invalidateAll()));

        // Start of the frame: the main framebuffer is bound and nothing else is mid-draw.
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Pre.class, event -> MobIconCache.onFrameStart());
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, MobIconCommands::register);
        // The icon copy stands at the real mob's position, so within name-tag range of the camera.
        // Its name is cleared, but a mod can still force a tag on in this event: veto it last.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, RenderNameTagEvent.class, event -> {
            if (MobIconRenderer.isRendering()) event.setCanRender(TriState.FALSE);
        });

        if (Boolean.getBoolean(MobIconSelfTest.PROPERTY)) MobIconSelfTest.enable();
        if (Boolean.getBoolean(MobIconScene.PROPERTY)) MobIconScene.enable();
    }
}
