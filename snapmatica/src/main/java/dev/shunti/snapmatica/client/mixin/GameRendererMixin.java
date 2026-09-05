package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import dev.shunti.snapmatica.client.Freecam;
import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import dev.shunti.snapmatica.client.VideoRecorder;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides FOV based on focal length (35mm full-frame sensor model).
 * Suppresses hand rendering during photo capture.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    //? if <1.21.10 {
    /*@Shadow private boolean renderHand;
    *///?}

    //? if >=1.21.2 {
    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F",
            at = @At("RETURN"),
            cancellable = true)
    private void snapmatica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Float> cir) {
        PlayerEntity player = MinecraftClient.getInstance().player;
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
            if (!SnapmaticaClient.viewfinderActive(MinecraftClient.getInstance())) return;
            if (SnapmaticaClient.lensType == 0) return;
        }
        // Freecam.currentFocalLengthMm interpolates smoothly between ticks while a camera
        // path is playing (the plain stepped value otherwise), so a path's dolly zoom glides
        // instead of visibly snapping to a whole millimetre every 50 ms.
        float f = Freecam.currentFocalLengthMm(tickDelta);
        if (f <= 0) return;
        MinecraftClient mcw = MinecraftClient.getInstance();
        double aspect = (double) mcw.getWindow().getFramebufferWidth()
                / Math.max(1, mcw.getWindow().getFramebufferHeight());
        cir.setReturnValue((float) snapmatica$frameFov(f, aspect));
    }
    //?} else {
    /*@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
            at = @At("RETURN"),
            cancellable = true)
    private void snapmatica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Double> cir) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;

        // Recording, viewfinder, or the whole of a burst — see the note on the branch above:
        // a lens does not change while the shutter is open.
        if (VideoRecorder.isRecording() || ApertureIntegration.isActive()) {
            if (SnapmaticaClient.lensType == 0) return; // no lens
        } else {
            if (!SnapmaticaClient.viewfinderActive(MinecraftClient.getInstance())) return;
            if (SnapmaticaClient.lensType == 0) return; // no lens
        }

        float f = Freecam.currentFocalLengthMm(tickDelta);
        if (f <= 0) return;

        // Vertical FOV from the full-frame sensor: the long (36mm) side is vertical in
        // portrait, the short (24mm) side in landscape, so the focal-length number stays
        // physically accurate in both orientations.
        //   landscape: 2 * atan(12 / f)   portrait: 2 * atan(18 / f)
        MinecraftClient mcw = MinecraftClient.getInstance();
        double aspect = (double) mcw.getWindow().getFramebufferWidth()
                / Math.max(1, mcw.getWindow().getFramebufferHeight());
        cir.setReturnValue(snapmatica$frameFov(f, aspect));
    }
    *///?}

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
     * {@code getProjectionMatrix(fov)}, is handed to {@code WorldRenderer.render} for frustum
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
    //? if >=1.21.10 {
    @Redirect(method = "renderWorld",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/render/RawProjectionMatrix;set(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private com.mojang.blaze3d.buffers.GpuBufferSlice snapmatica$pupilShearDrawn(
            net.minecraft.client.render.RawProjectionMatrix raw, org.joml.Matrix4f projection) {
        if (!ApertureIntegration.isActive()) return raw.set(projection);
        org.joml.Matrix4f m = new org.joml.Matrix4f(projection);
        ApertureIntegration.shear(m);
        return raw.set(m);
    }

    /**
     * The same shear again, on the matrix handed to {@code WorldRenderer.render}.
     *
     * <p>Two renderers, two routes, and a photograph has to work under both. Vanilla draws from
     * the uniform buffer filled above; Iris replaces the pipeline outright and takes its
     * projection from THIS argument instead, so shearing only the buffer moves nothing at all
     * when a shader pack is loaded — which is how a burst can complete all 64 samples, in the
     * log, and still average out to one sharp frame. Both routes are sheared now, and neither
     * cares that the other exists.
     */
    //? if >=1.21.11 {
    @Redirect(method = "renderWorld",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/memory/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void snapmatica$pupilShearWorldRender(
            net.minecraft.client.render.WorldRenderer worldRenderer,
            net.minecraft.client.util.memory.ObjectAllocator allocator,
            RenderTickCounter tickCounter, boolean renderBlockOutline,
            Camera camera, org.joml.Matrix4f positionMatrix, org.joml.Matrix4f projection,
            org.joml.Matrix4f cullingProjection,
            com.mojang.blaze3d.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor,
            boolean shouldRenderSky) {
        org.joml.Matrix4f proj = projection;
        if (ApertureIntegration.isActive()) {
            proj = new org.joml.Matrix4f(projection);
            ApertureIntegration.shear(proj);
        }
        worldRenderer.render(allocator, tickCounter, renderBlockOutline, camera, positionMatrix,
                proj, cullingProjection, fog, fogColor, shouldRenderSky);
    }
    //?} else {
    /*@Redirect(method = "renderWorld",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/util/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
    private void snapmatica$pupilShearWorldRender(
            net.minecraft.client.render.WorldRenderer worldRenderer,
            net.minecraft.client.util.ObjectAllocator allocator,
            RenderTickCounter tickCounter, boolean renderBlockOutline,
            Camera camera, org.joml.Matrix4f positionMatrix, org.joml.Matrix4f projection,
            org.joml.Matrix4f cullingProjection,
            com.mojang.blaze3d.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor,
            boolean shouldRenderSky) {
        org.joml.Matrix4f proj = projection;
        if (ApertureIntegration.isActive()) {
            proj = new org.joml.Matrix4f(projection);
            ApertureIntegration.shear(proj);
        }
        worldRenderer.render(allocator, tickCounter, renderBlockOutline, camera, positionMatrix,
                proj, cullingProjection, fog, fogColor, shouldRenderSky);
    }
    *///?}

    /** The culling frustum, sheared to agree with what is drawn. See the redirects above. */
    @Inject(method = "getProjectionMatrix(F)Lorg/joml/Matrix4f;", at = @At("RETURN"), cancellable = true)
    private void snapmatica$pupilShearFrustum(float fov, CallbackInfoReturnable<org.joml.Matrix4f> cir) {
        if (!ApertureIntegration.isActive()) return;
        org.joml.Matrix4f m = new org.joml.Matrix4f(cir.getReturnValue());
        ApertureIntegration.shear(m);
        cir.setReturnValue(m);
    }
    //?} elif >=1.21.2 {
    /*@Inject(method = "getBasicProjectionMatrix(F)Lorg/joml/Matrix4f;", at = @At("RETURN"), cancellable = true)
    private void snapmatica$pupilShearDrawn(float fov, CallbackInfoReturnable<org.joml.Matrix4f> cir) {
        if (!ApertureIntegration.isActive()) return;
        org.joml.Matrix4f m = new org.joml.Matrix4f(cir.getReturnValue());
        ApertureIntegration.shear(m);
        cir.setReturnValue(m);
    }
    *///?} else {
    /*@Inject(method = "getBasicProjectionMatrix(D)Lorg/joml/Matrix4f;", at = @At("RETURN"), cancellable = true)
    private void snapmatica$pupilShearDrawn(double fov, CallbackInfoReturnable<org.joml.Matrix4f> cir) {
        if (!ApertureIntegration.isActive()) return;
        org.joml.Matrix4f m = new org.joml.Matrix4f(cir.getReturnValue());
        ApertureIntegration.shear(m);
        cir.setReturnValue(m);
    }
    *///?}

    /**
     * Freecam already cancels {@code Camera.update()} outright — see {@link CameraMixin} — so
     * the render camera itself sits still while it's active, locked or not. View bobbing is a
     * separate matrix transform applied here, driven by the player's own walk distance rather
     * than the camera, so it swayed the picture regardless: invisible while flying (nothing
     * moves the player), but plainly visible once locked mode handed the player real WASD
     * input back and they started actually walking. Freecam owns the camera outright, so
     * nothing else gets to perturb it either.
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void snapmatica$noBobInFreecam(net.minecraft.client.util.math.MatrixStack matrices,
                                           float tickDelta, CallbackInfo ci) {
        if (Freecam.isActive()) {
            ci.cancel();
        }
    }

    /**
     * Suppress hand rendering before renderWorld() when a photo capture is pending
     * or when the viewfinder is active (sneaking with viewfinder mode enabled).
     */
    // 1.21 introduced RenderTickCounter; 1.20.1's render()/renderWorld() still take a plain
    // float tickDelta (and a MatrixStack) directly, so the whole @Inject needs its own
    // method/target descriptors below that boundary, not just the body.
    //? if >=1.21 {
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
                    shift = At.Shift.BEFORE))
    private void snapmatica$suppressHandBeforeCapture(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        // Once per frame, before Camera.update runs inside renderWorld: step to the next point
        // The FOV override above reads Freecam's own smoothed float directly, but the blur
        // pass and the video recorder both still key off this int field — sync it once per
        // frame (not once per 50 ms tick) so a path's dolly zoom stays in step with what
        // renderWorld() is about to draw, in the bokeh amount and in what gets captured.
        if (Freecam.isPathPlaying()) {
            //? if >=1.21.10 {
            SnapmaticaClient.focalLengthMm = Math.round(Freecam.currentFocalLengthMm(tickCounter.getTickProgress(true)));
            //?} else {
            /*SnapmaticaClient.focalLengthMm = Math.round(Freecam.currentFocalLengthMm(tickCounter.getTickDelta(true)));
            *///?}
        }
        //? if <1.21.10 {
        /*MinecraftClient mc = MinecraftClient.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderActive(mc);
        if (PhotoCapture.isCapturePending() || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = false;
        }
        *///?}
    }
    //?} else {
    /*@Inject(method = "render(FJZ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
                    shift = At.Shift.BEFORE))
    private void snapmatica$suppressHandBeforeCapture(float tickDelta, long limitTime, boolean tick, CallbackInfo ci) {
        if (Freecam.isPathPlaying()) {
            SnapmaticaClient.focalLengthMm = Math.round(Freecam.currentFocalLengthMm(tickDelta));
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderActive(mc);
        if (PhotoCapture.isCapturePending() || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = false;
        }
    }
    *///?}

    //? if >=1.21.10 {
    // From 1.21.10 the hand is drawn by renderHand() inside renderWorld(), so the
    // post-composite screenshot includes it. Cancel that call while recording so the
    // held item never appears in the footage (and the live view stays clean too).
    @Inject(method = "renderHand(FZLorg/joml/Matrix4f;)V", at = @At("HEAD"), cancellable = true)
    private void snapmatica$suppressHandWhileRecording(float tickDelta, boolean blockOutline,
                                                       org.joml.Matrix4f matrix, CallbackInfo ci) {
        // Ambient blur, before the held item exists. Paired with the shader's
        // drawnAfterDepthCopy mask rather than replacing it: this route depends on the item
        // being drawn where vanilla draws it, the mask depends on it marking the live depth
        // buffer, and the two fail under different mods. Whichever holds, the item stays sharp.
        dev.shunti.snapmatica.client.EvfBlurRenderer.applyAmbientBlurBeforeHand();

        if (VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }
    //?}

    /**
     * Capture the screenshot after renderWorld() returns (after Iris shader composite if present).
     * Restore renderHand afterwards.
     */
    //? if >=1.21 {
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterComposite(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
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
        //? if <1.21.10 {
        /*MinecraftClient mc = MinecraftClient.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderActive(mc);
        if (wasCapturePending || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = true;
        }
        *///?}
    }
    //?} else {
    /*@Inject(method = "render(FJZ)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(FJLnet/minecraft/client/util/math/MatrixStack;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterComposite(float tickDelta, long limitTime, boolean tick, CallbackInfo ci) {
        boolean wasCapturePending = PhotoCapture.isCapturePending();
        boolean wasSingleShotPending = PhotoCapture.isSingleShotCapturePending();
        // gather reads from InSampler, or the colour it hands back would be a stage ahead.
        dev.shunti.snapmatica.client.EvfBlurRenderer.applyBlur(
                wasCapturePending || VideoRecorder.isRecording(), wasSingleShotPending);
        PhotoCapture.captureIfPending();
        VideoRecorder.captureFrameIfRecording();
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderActive(mc);
        if (wasCapturePending || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = true;
        }
    }
    *///?}

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
