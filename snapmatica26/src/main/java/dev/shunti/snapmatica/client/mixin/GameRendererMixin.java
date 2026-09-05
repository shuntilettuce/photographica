package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.EvfBlurRenderer;
import dev.shunti.snapmatica.client.Freecam;
import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import dev.shunti.snapmatica.client.VideoRecorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.culling.Frustum;
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
     *
     * <p>The chunk-culling frustum has to be rebuilt from that same new FOV, or a wide lens
     * (say 14 mm) renders more than vanilla's own narrower FOV ever culled for — chunks at
     * the new, wider edges of the frame were never submitted for rendering in the first
     * place, so they read as void. This is the same class of bug freecam's cull frustum fix
     * was for, just triggered by zoom instead of by moving the camera: whatever built
     * {@code cameraRenderState.cullFrustum} (inside {@code Camera.update()}, before this
     * method even runs) used vanilla's own FOV, not this one.
     */
    @Inject(method = "extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V",
            at = @At("RETURN"))
    private void snapmatica$applyFocalLength(DeltaTracker dt, float f1, float f2,
                                             CallbackInfo ci) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        // The focal length applies while recording (any pose) or through the viewfinder.
        if (!VideoRecorder.isRecording()) {
            if (!SnapmaticaClient.viewfinderActive(Minecraft.getInstance())) return;
            if (SnapmaticaClient.lensType == 0) return;
        }
        // Freecam.currentFocalLengthMm interpolates smoothly between ticks while a camera
        // path is playing (the plain stepped value otherwise), so a path's dolly zoom glides
        // instead of visibly snapping to a whole millimetre every 50 ms.
        float f = Freecam.currentFocalLengthMm(dt.getGameTimeDeltaPartialTick(true));
        if (f <= 0) return;

        CameraRenderState camState = gameRenderState.levelRenderState.cameraRenderState;
        if (camState == null || camState.projectionMatrix == null) return;

        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float fovDeg = (float) snapmatica$frameFov(f, aspect);
        camState.projectionMatrix.setPerspective(
                (float) Math.toRadians(fovDeg), aspect, 0.05f, camState.depthFar);
        camState.hudFov = fovDeg;

        // Rebuild the cull frustum against the new projection — see the class doc above.
        // viewRotationMatrix is unaffected by FOV (it's a pure rotation), so it's already
        // correct; only the projection half needs to change.
        Frustum newFrustum = new Frustum(camState.viewRotationMatrix, camState.projectionMatrix);
        newFrustum.prepare(camState.pos.x, camState.pos.y, camState.pos.z);
        camState.cullFrustum.set(newFrustum);
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterLevel(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        // The FOV override above reads Freecam's own smoothed float directly, but the blur
        // pass and the video recorder both still key off this int field — sync it once per
        // frame (not once per 50 ms tick) so a path's dolly zoom stays in step with what
        // renderLevel() is about to draw, in the bokeh amount and in what gets captured.
        if (Freecam.isPathPlaying()) {
            SnapmaticaClient.focalLengthMm = Math.round(
                    Freecam.currentFocalLengthMm(deltaTracker.getGameTimeDeltaPartialTick(true)));
        }
        // Apply scheduled EVF blur BEFORE capture: forCapture=true blurs the full
        // framebuffer so the 3:2 photo crop is fully covered (viewfinder frame is only
        // 86% height, leaving ~90px unblurred on each edge of the photo otherwise).
        boolean wasCapturePending = PhotoCapture.isCapturePending();
        boolean wasSingleShotPending = PhotoCapture.isSingleShotCapturePending();
        EvfBlurRenderer.applyBlur(wasCapturePending, wasSingleShotPending);
        PhotoCapture.captureIfPending();
        // Video frame capture applies its own full-frame blur internally.
        VideoRecorder.captureFrameIfRecording();
    }

    /**
     * Freecam already cancels {@code Camera.update()} outright — see {@link CameraMixin} — so
     * the render camera itself sits still while it's active, locked or not. View bobbing is a
     * separate transform applied here, driven by the player's own walk distance rather than
     * the camera, so it swayed the picture regardless: invisible while flying (nothing moves
     * the player), but plainly visible once locked mode handed the player real WASD input back
     * and they started actually walking. Freecam owns the camera outright, so nothing else
     * gets to perturb it either.
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void snapmatica$noBobInFreecam(CameraRenderState camState,
                                           com.mojang.blaze3d.vertex.PoseStack poseStack, CallbackInfo ci) {
        if (Freecam.isActive()) {
            ci.cancel();
        }
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
    private static double snapmatica$frameFov(double focalMm, double windowAspect) {
        boolean portrait = SnapmaticaClient.portraitOrientation;
        double halfH = portrait ? 18.0 : 12.0;   // 36x24 frame, half-extents in mm
        double halfW = portrait ? 12.0 : 18.0;
        double frameAspect = halfW / halfH;
        double vHalfMm = (windowAspect >= frameAspect) ? halfH : halfW / windowAspect;
        return Math.toDegrees(2.0 * Math.atan(vHalfMm / focalMm));
    }
}
