package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerEntityRenderer overrides renderLabelIfPresent, so the EntityRenderer
 * mixin never suppresses a player's name tag. Without this, photographing a
 * player bakes the name tag into the photo.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(
            method = "renderLabelIfPresent",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void snapmatica$hidePlayerLabelDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending()) {
            ci.cancel();
        }
    }
}
