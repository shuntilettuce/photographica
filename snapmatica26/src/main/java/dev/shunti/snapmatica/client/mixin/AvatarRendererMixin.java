package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AvatarRenderer overrides submitNameTag, bypassing the EntityRenderer mixin.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(
            method = "submitNameTag",
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
