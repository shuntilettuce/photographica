package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses all entity name-tag rendering during photo/video capture.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(
            method = "renderNameTag",
            at = @At("HEAD"),
            cancellable = true
    )
    private void photographica$hideNametagDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending()) {
            ci.cancel();
        }
    }
}
