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

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void photographica$suppressHand(CallbackInfo ci) {
        // Hide the first-person hand only for photo capture and tripod (armor-stand)
        // recording — where the held camera should never appear in the shot/footage.
        // Handheld video recording keeps the hand visible (like the viewfinder), so the
        // player isn't left looking "invisible" while filming.
        if (PhotoCapture.isCapturePending()
                || PhotoCapture.armorStandCapturePending
                || VideoRecorder.isTripodRecording()) {
            ci.cancel();
        }
    }
}
