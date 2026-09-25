package dev.shunti.snapmatica;

import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("snapmatica")
public class Snapmatica {

    public Snapmatica() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderGuiOverlay);
        MinecraftForge.EVENT_BUS.addListener(this::onRenderLevelStage);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        SnapmaticaClient.initialize();
    }

    private void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        SnapmaticaClient.registerKeyMappings(event);
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) SnapmaticaClient.onClientTick();
    }

    private void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        SnapmaticaClient.onRenderGui(event.getGuiGraphics(), event.getPartialTick());
    }

    private void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            SnapmaticaClient.onWorldRenderEnd();
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            // The last stage before the translucent pass -- where Fabric's BEFORE_TRANSLUCENT /
            // BEFORE_DEBUG_RENDER fire. Depth is captured here so glass and water do not stamp
            // their own distance over the view through them.
            //
            // Not AFTER_TRIPWIRE_BLOCKS, which this used until 1.3.4 on the belief that tripwire
            // draws first. It is the other way round: LevelRenderer renders the translucent
            // layer and then tripwire, and that stage is keyed to RenderType.tripwire(), so it
            // fired after the glass had already written its depth.
            SnapmaticaClient.onBeforeTranslucent();
        }
    }
}
