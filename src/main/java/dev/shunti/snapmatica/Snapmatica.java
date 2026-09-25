package dev.shunti.snapmatica;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import dev.shunti.snapmatica.client.SnapmaticaClient;

@Mod(value = "snapmatica", dist = Dist.CLIENT)
public class Snapmatica {

    public Snapmatica(IEventBus modEventBus) {
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderGui);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
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
        SnapmaticaClient.onRenderGui(event.getGuiGraphics(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            SnapmaticaClient.onWorldRenderEnd(event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            // The last stage before the translucent pass -- where Fabric's
            // BEFORE_TRANSLUCENT / BEFORE_DEBUG_RENDER fire. Depth is captured here so glass
            // does not stamp its own distance over the view through it.
            //
            // Not AFTER_TRIPWIRE_BLOCKS, which the earlier NeoForge and Forge ports used on
            // the belief that tripwire draws before translucent. It is the other way round:
            // LevelRenderer renders the translucent layer and THEN tripwire, and that stage
            // is keyed to RenderType.tripwire(), so it fired after the glass had already
            // written its depth.
            SnapmaticaClient.onBeforeTranslucent(event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
    }
}
