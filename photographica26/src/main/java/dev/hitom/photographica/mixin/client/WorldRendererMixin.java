package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin {

    /**
     * Suppresses the block-selection outline during any capture or recording,
     * so it never bleeds into photos or video frames.
     *
     * 26.2 renamed LevelRenderer.renderHitOutline → submitHitOutline.
     */
    @Inject(
            method = "submitHitOutline",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void photographica$hideOutlineDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }
}
