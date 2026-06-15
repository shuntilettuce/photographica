package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import dev.shunti.snapmatica.client.VideoRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void snapmatica$suppressHand(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (PhotoCapture.isCapturePending() ||
                VideoRecorder.isRecording() ||
                (SnapmaticaClient.viewfinderSneakEnabled && mc.player != null && mc.player.isShiftKeyDown())) {
            ci.cancel();
        }
    }
}
