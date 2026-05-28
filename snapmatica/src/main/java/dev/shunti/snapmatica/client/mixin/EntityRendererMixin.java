package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import net.minecraft.client.render.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides entity name tags during photo capture so they don't appear in photos.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(
            method = "renderLabelIfPresent",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void snapmatica$hideLabelDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending()) {
            ci.cancel();
        }
    }
}
