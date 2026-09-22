package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerRenderer overrides renderLabelIfPresent, so the EntityRenderer
 * mixin never suppresses a player's name tag. Without this, photographing a
 * player bakes the name tag into the photo.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(
            method = "renderNameTag",
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
