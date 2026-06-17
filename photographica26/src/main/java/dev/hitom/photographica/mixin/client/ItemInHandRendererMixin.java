package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    // 26.2 renamed ItemInHandRenderer.renderHandsWithItems → submitHandsWithItems.
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void photographica$suppressHand(CallbackInfo ci) {
        // Hide the first-person hand during photo capture and during any video recording
        // (handheld or tripod) so the held camera/arm never appears in the shot or footage.
        if (PhotoCapture.isCapturePending()
                || PhotoCapture.armorStandCapturePending
                || VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }
}
