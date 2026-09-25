package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import dev.shunti.snapmatica.client.Freecam;
import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import dev.shunti.snapmatica.client.VideoRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides FOV based on focal length (35mm full-frame sensor model).
 * Suppresses hand rendering during photo capture.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow private boolean renderHand;

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F",
            at = @At("RETURN"),
            cancellable = true)
    private void snapmatica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Float> cir) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        // Focal-length zoom applies while recording (any pose), through the viewfinder, OR for
        // the whole of an aperture burst.
        //
        // The burst is the reason for that third case. It takes over the camera so the shutter
        // can be pressed and forgotten — see CameraMixin — but the FIELD OF VIEW was still tied
        // to the viewfinder being up, which means sneak being held. Let go, and the lens
        // silently reverted to Minecraft's own wide angle partway through the exposure: the
        // sub-frames stop agreeing about what focal length they were taken at, and the sum is
        // of two different lenses. A lens does not change while the shutter is open, so nothing
        // about it may depend on a key still being held.
        if (VideoRecorder.isRecording() || ApertureIntegration.isActive()) {
            // recording or exposing: zoom always active
            if (SnapmaticaClient.lensType == 0) return;
        } else {
            if (!SnapmaticaClient.viewfinderActive(Minecraft.getInstance())) return;
            if (SnapmaticaClient.lensType == 0) return;
        }
        // Freecam.currentFocalLengthMm interpolates smoothly between ticks while a camera
        // path is playing (the plain stepped value otherwise), so a path's dolly zoom glides
        // instead of visibly snapping to a whole millimetre every 50 ms.
        float f = Freecam.currentFocalLengthMm(tickDelta);
        if (f <= 0) return;
        Minecraft mcw = Minecraft.getInstance();
        double aspect = (double) mcw.getWindow().getWidth()
                / Math.max(1, mcw.getWindow().getHeight());
        cir.setReturnValue((float) snapmatica$frameFov(f, aspect));
    }

    /**
     * Shift the viewpoint onto one point of the entrance pupil, for a photograph being taken by
     * integrating the aperture rather than by blurring one frame.
     *
     * <p>This is the whole of what makes {@link ApertureIntegration} physical: the world is
     * genuinely rasterised from that point, so it occludes from that point, and the sum over
     * the pupil is a real lens's image rather than an estimate of one.
     *
     * <p><b>It has to be this call.</b> {@code renderWorld} handles two projection matrices and
     * only one of them draws anything. The one that reaches the GPU goes through
     * {@code RawProjectionMatrix.set} into {@code RenderSystem.setProjectionMatrix}, and that
     * is the transform every vertex actually gets. The other, from
     * {@code getProjectionMatrix(fov)}, is handed to {@code LevelRenderer.renderLevel} for frustum
     * culling and for the pipeline's own bookkeeping. Shearing that second one — which was the
     * first attempt here — moves the frustum and changes not a single pixel: the burst ran, the
     * sub-frames came back identical, and the average of sixty-four identical frames is one
     * sharp frame. Both are sheared now, so the geometry that the shear brings into view is not
     * culled before it can be drawn.
     *
     * <p>The shear that keeps the focal plane registered lives in
     * {@code ApertureIntegration.shear} along with its derivation. It leaves the depth range and
     * the vertical scale alone, so everything else reading these matrices — the blur pass's own
     * depth linearisation included — is unaffected whether a burst is running or not. A copy is
     * sheared rather than the caller's own matrix, which is still needed unsheared elsewhere in
     * the same method.
     */
    @Inject(method = "getProjectionMatrix(F)Lorg/joml/Matrix4f;", at = @At("RETURN"), cancellable = true)
    private void snapmatica$pupilShearDrawn(float fov, CallbackInfoReturnable<org.joml.Matrix4f> cir) {
        if (!ApertureIntegration.isActive()) return;
        org.joml.Matrix4f m = new org.joml.Matrix4f(cir.getReturnValue());
        ApertureIntegration.shear(m);
        cir.setReturnValue(m);
    }

    /**
     * Freecam already cancels {@code Camera.setup()} outright — see {@link CameraMixin} — so
     * the render camera itself sits still while it's active, locked or not. View bobbing is a
     * separate matrix transform applied here, driven by the player's own walk distance rather
     * than the camera, so it swayed the picture regardless: invisible while flying (nothing
     * moves the player), but plainly visible once locked mode handed the player real WASD
     * input back and they started actually walking. Freecam owns the camera outright, so
     * nothing else gets to perturb it either.
     */
    /**
     * The roll, on 1.20.1. That version builds the view in {@code renderWorld} from yaw and pitch
     * -- {@code multiply(POSITIVE_X(pitch))} then {@code multiply(POSITIVE_Y(yaw + 180))} -- and
     * never reads the camera's quaternion, so turning the quaternion (what CameraMixin does on
     * 1.21+, where the view IS that quaternion) would move nothing on screen. The turn goes into
     * the same stack instead, just before the pitch: view = Rz(roll) * Rx(pitch) * Ry(yaw), which
     * is exactly the 1.21 view with the same roll. The frustum is built from this stack a few
     * calls later, so culling follows the turned frame for free.
     */

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void snapmatica$noBobInFreecam(com.mojang.blaze3d.vertex.PoseStack matrices,
                                           float tickDelta, CallbackInfo ci) {
        if (Freecam.isActive()) {
            ci.cancel();
        }
    }

    /**
     * Suppress hand rendering before renderWorld() when a photo capture is pending
     * or when the viewfinder is active (sneaking with viewfinder mode enabled).
     */
    // 1.21 introduced DeltaTracker; 1.20.1's render()/renderWorld() still take a plain
    // float tickDelta (and a PoseStack) directly, so the whole @Inject needs its own
    // method/target descriptors below that boundary, not just the body.
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.BEFORE))
    private void snapmatica$suppressHandBeforeCapture(DeltaTracker tickCounter, boolean tick, CallbackInfo ci) {
        // Once per frame, before Camera.setup runs inside renderWorld: step to the next point
        // The FOV override above reads Freecam's own smoothed float directly, but the blur
        // pass and the video recorder both still key off this int field — sync it once per
        // frame (not once per 50 ms tick) so a path's dolly zoom stays in step with what
        // renderWorld() is about to draw, in the bokeh amount and in what gets captured.
        if (Freecam.isPathPlaying()) {
            SnapmaticaClient.focalLengthMm = Math.round(Freecam.currentFocalLengthMm(tickCounter.getGameTimeDeltaPartialTick(true)));
        }
        Minecraft mc = Minecraft.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderActive(mc);
        if (PhotoCapture.isCapturePending() || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = false;
        }
    }


    /**
     * Capture the screenshot after renderWorld() returns (after Iris shader composite if present).
     * Restore renderHand afterwards.
     */
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterComposite(DeltaTracker tickCounter, boolean tick, CallbackInfo ci) {
        boolean wasCapturePending = PhotoCapture.isCapturePending();
        // Read before captureIfPending() below consumes it, same as wasCapturePending — this is
        // true only for a fast-shutter single frame, never for one of a long exposure's samples.
        boolean wasSingleShotPending = PhotoCapture.isSingleShotCapturePending();
        // Apply the EVF blur (scheduled during the previous frame's HUD render) BEFORE
        // capturing, so the screenshot includes GPU bokeh on all versions.
        // Pass forCapture=true when a screenshot is about to be taken so the blur covers
        // the full photo crop area (not just the scissored viewfinder frame), and — just as
        // importantly — so focus peaking stays off (see EvfBlurRenderer.applyBlur's
        // showPeaking). VideoRecorder.captureFrameIfRecording() below re-applies its own
        // capture-safe blur afterward, but that second pass just re-blurs whatever is
        // already in the framebuffer — it does not erase peaking's highlight overlay this
        // call would otherwise have baked in first, treating a recording frame as a plain
        // live preview. captureHQ is narrower still: only the fast-shutter frame gets the
        // expensive extra sample budget.
        // gather reads from InSampler, or the colour it hands back would be a stage ahead.
        dev.shunti.snapmatica.client.EvfBlurRenderer.applyBlur(
                wasCapturePending || VideoRecorder.isRecording(), wasSingleShotPending);
        PhotoCapture.captureIfPending();
        VideoRecorder.captureFrameIfRecording();
        Minecraft mc = Minecraft.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderActive(mc);
        if (wasCapturePending || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = true;
        }
    }

    /**
     * Vertical FOV that makes the PHOTO FRAME span the focal length's true field.
     *
     * <p>Minecraft's fov is the vertical angle of the whole window, but the photo is the largest
     * centred 3:2 (or 2:3) rectangle inside it — see PhotoCapture.frameRect. Those coincide only
     * when the window is at least as wide as the frame. On anything narrower the frame is limited
     * by WIDTH, its height falls short of the window's, and anchoring on the window's vertical
     * angle quietly narrows the picture: a 24 mm delivered about 45 degrees instead of 53.
     *
     * <p>So anchor on whichever edge actually constrains the frame — height when the window is
     * wide enough, width otherwise — and let the other follow from the 3:2 shape.
     */
    @Unique
    private static double snapmatica$frameFov(double focalMm, double windowAspect) {
        boolean portrait = SnapmaticaClient.portraitOrientation;
        // Half-extents of the frame in mm. 36x24 at full frame; a cropped sensor is the same
        // shape, smaller — which is the whole of what a crop factor means, and why the same
        // lens frames tighter on one. See SnapmaticaClient.sensorCropFactor.
        double crop  = Math.max(0.2, SnapmaticaClient.sensorCropFactor);
        double halfH = (portrait ? 18.0 : 12.0) / crop;
        double halfW = (portrait ? 12.0 : 18.0) / crop;
        double frameAspect = halfW / halfH;
        double vHalfMm = (windowAspect >= frameAspect) ? halfH : halfW / windowAspect;
        return Math.toDegrees(2.0 * Math.atan(vHalfMm / SnapmaticaClient.imageDistanceMm(focalMm)));
    }

}
