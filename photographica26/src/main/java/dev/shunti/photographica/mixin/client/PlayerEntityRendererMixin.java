package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerRenderer overrides renderNameTag, so the EntityRenderer
 * mixin never suppresses a player's name tag. Without this, photographing a
 * player (e.g. from an armor-stand camera) bakes the name tag into the photo.
 */
@Mixin(AvatarRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(
            method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void photographica$hidePlayerNametagDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending()) {
            ci.cancel();
        }
    }
}
