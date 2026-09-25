package dev.shunti.snapmatica;

import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = "snapmatica", dist = Dist.CLIENT)
public class Snapmatica {

    public Snapmatica(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderGui);
        // 21.11 posts one event class per stage rather than one class with a Stage field.
        NeoForge.EVENT_BUS.addListener(this::onAfterEntities);
        NeoForge.EVENT_BUS.addListener(this::onAfterLevel);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        SnapmaticaClient.initialize();
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        SnapmaticaClient.registerKeyMappings(event);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        SnapmaticaClient.onClientTick();
    }

    private void onRenderGui(RenderGuiEvent.Post event) {
        SnapmaticaClient.onRenderGui(event.getGuiGraphics(), event.getPartialTick());
    }

    private void onAfterEntities(RenderLevelStageEvent.AfterEntities event) {
        SnapmaticaClient.onBeforeTranslucent();
    }

    private void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        SnapmaticaClient.onWorldRenderEnd();
    }
}
