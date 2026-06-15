package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow private GameRenderState gameRenderState;

    /**
     * Override the perspective FOV with focal-length-derived FOV when the player
     * is looking through the viewfinder. In MC 26.1.2 getFov() no longer exists;
     * extractCamera() builds CameraRenderState.projectionMatrix directly, so we
     * overwrite both projectionMatrix and hudFov at its RETURN.
     */
    @Inject(method = "extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V",
            at = @At("RETURN"))
    private void snapmatica$applyFocalLength(DeltaTracker dt, float f1, float f2,
                                             CallbackInfo ci) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isShiftKeyDown()) return;
        if (SnapmaticaClient.lensType == 0) return;
        int f = SnapmaticaClient.focalLengthMm;
        if (f <= 0) return;

        double halfSensorMm = SnapmaticaClient.portraitOrientation ? 18.0 : 12.0;
        float fovDeg = (float) Math.toDegrees(2.0 * Math.atan(halfSensorMm / f));

        CameraRenderState camState = gameRenderState.levelRenderState.cameraRenderState;
        if (camState == null || camState.projectionMatrix == null) return;

        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        camState.projectionMatrix.setPerspective(
                (float) Math.toRadians(fovDeg), aspect, 0.05f, camState.depthFar);
        camState.hudFov = fovDeg;
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterLevel(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        PhotoCapture.captureIfPending();
    }
}
