package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides FOV based on focal length (35mm full-frame sensor model).
 * Suppresses hand rendering during photo capture.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    //? if <1.21.11 {
    @Shadow private boolean renderHand;
    //?}

    //? if >=1.21.4 {
    /*@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F",
            at = @At("RETURN"),
            cancellable = true)
    private void snapmatica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Float> cir) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;
        if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isSneaking()) return;
        if (SnapmaticaClient.lensType == 0) return;
        int f = SnapmaticaClient.focalLengthMm;
        if (f <= 0) return;
        cir.setReturnValue((float) Math.toDegrees(2.0 * Math.atan(12.0 / f)));
    }*/
    //?} else {
    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
            at = @At("RETURN"),
            cancellable = true)
    private void snapmatica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Double> cir) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;

        // Only apply FOV override while viewfinder is active (sneaking + mode enabled)
        if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isSneaking()) return;
        if (SnapmaticaClient.lensType == 0) return; // no lens

        int f = SnapmaticaClient.focalLengthMm;
        if (f <= 0) return;

        // Vertical FOV = 2 * atan(12 / focalLengthMm)
        double vFovDegrees = Math.toDegrees(2.0 * Math.atan(12.0 / f));
        cir.setReturnValue(vFovDegrees);
    }
    //?}

    /**
     * Suppress hand rendering before renderWorld() when a photo capture is pending.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
                    shift = At.Shift.BEFORE))
    private void snapmatica$suppressHandBeforeCapture(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        //? if <1.21.11 {
        if (PhotoCapture.isCapturePending()) {
            this.renderHand = false;
        }
        //?}
    }

    /**
     * Capture the screenshot after renderWorld() returns (after Iris shader composite if present).
     * Restore renderHand afterwards.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterComposite(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        boolean wasCapturePending = PhotoCapture.isCapturePending();
        PhotoCapture.captureIfPending();
        //? if <1.21.11 {
        if (wasCapturePending) {
            this.renderHand = true;
        }
        //?}
    }
}
