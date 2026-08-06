package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.EvfBlurRenderer;
import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import dev.shunti.snapmatica.client.VideoRecorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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

        // The focal length applies while recording (any pose) or through the viewfinder.
        if (!VideoRecorder.isRecording()) {
            if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isShiftKeyDown()) return;
            if (SnapmaticaClient.lensType == 0) return;
        }
        int f = SnapmaticaClient.focalLengthMm;
        if (f <= 0) return;

        CameraRenderState camState = gameRenderState.levelRenderState.cameraRenderState;
        if (camState == null || camState.projectionMatrix == null) return;

        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float fovDeg = (float) snapmatica$frameFov(f, aspect);
        camState.projectionMatrix.setPerspective(
                (float) Math.toRadians(fovDeg), aspect, 0.05f, camState.depthFar);
        camState.hudFov = fovDeg;
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterLevel(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        // Apply scheduled EVF blur BEFORE capture: forCapture=true blurs the full
        // framebuffer so the 3:2 photo crop is fully covered (viewfinder frame is only
        // 86% height, leaving ~90px unblurred on each edge of the photo otherwise).
        boolean wasCapturePending = PhotoCapture.isCapturePending();
        EvfBlurRenderer.applyBlur(wasCapturePending);
        PhotoCapture.captureIfPending();
        // Video frame capture applies its own full-frame blur internally.
        VideoRecorder.captureFrameIfRecording();
    }

    /**
     * Vertical FOV that frames the photo, not the window.
     *
     * <p>Minecraft's fov is the vertical angle of the whole window, but the photo is the
     * largest centred 3:2 (or 2:3) rectangle inside it — see PhotoCapture.frameRect. Those
     * coincide only when the window is at least as wide as the frame. On anything narrower
     * the frame is limited by WIDTH, its height falls short of the window's, and anchoring on
     * the window's vertical angle quietly narrows the picture: a 24 mm delivered about 45
     * degrees instead of 53.
     *
     * <p>So anchor on whichever edge actually constrains the frame — height when the window
     * is wide enough, width otherwise — and let the other follow from the 3:2 shape.
     */
    @Unique
    private static double snapmatica$frameFov(int focalMm, double windowAspect) {
        boolean portrait = SnapmaticaClient.portraitOrientation;
        double halfH = portrait ? 18.0 : 12.0;   // 36x24 frame, half-extents in mm
        double halfW = portrait ? 12.0 : 18.0;
        double frameAspect = halfW / halfH;
        double vHalfMm = (windowAspect >= frameAspect) ? halfH : halfW / windowAspect;
        return Math.toDegrees(2.0 * Math.atan(vHalfMm / focalMm));
    }
}
