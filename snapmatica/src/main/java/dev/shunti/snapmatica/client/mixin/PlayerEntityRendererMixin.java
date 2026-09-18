package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
//? if >=26 {
/*import net.minecraft.client.renderer.entity.player.AvatarRenderer;*/
//?} else {
import net.minecraft.client.render.entity.PlayerEntityRenderer;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player renderer overrides the name-tag method, so the EntityRenderer mixin
 * never suppresses a player's name tag. Without this, photographing a player
 * bakes the name tag into the photo.
 *
 * The target class is PlayerEntityRenderer up to 1.21.11 and AvatarRenderer from 26.
 */
//? if >=26 {
/*@Mixin(AvatarRenderer.class)*/
//?} else {
@Mixin(PlayerEntityRenderer.class)
//?}
public abstract class PlayerEntityRendererMixin {

    @Inject(
            //? if >=26 {
            /*method = "submitNameTag",*/
            //?} else {
            method = "renderLabelIfPresent",
            //?}
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
