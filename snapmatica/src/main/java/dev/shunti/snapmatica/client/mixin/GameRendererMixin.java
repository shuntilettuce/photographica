package dev.shunti.snapmatica.client.mixin;

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
        // Focal-length zoom applies while recording (any pose) OR through the viewfinder.
        if (VideoRecorder.isRecording()) {
            // recording: zoom always active
        } else {
            if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isSneaking()) return;
            if (SnapmaticaClient.lensType == 0) return;
        }
        int f = SnapmaticaClient.focalLengthMm;
        if (f <= 0) return;
        MinecraftClient mcw = MinecraftClient.getInstance();
        double aspect = (double) mcw.getWindow().getFramebufferWidth()
                / Math.max(1, mcw.getWindow().getFramebufferHeight());
        cir.setReturnValue((float) snapmatica$frameFov(f, aspect));
    }
    *///?} else {
    @Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
            at = @At("RETURN"),
            cancellable = true)
    private void snapmatica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Double> cir) {
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;

        // Focal-length zoom applies while recording (any pose) OR through the viewfinder.
        if (VideoRecorder.isRecording()) {
            // recording: zoom always active
        } else {
            if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isSneaking()) return;
            if (SnapmaticaClient.lensType == 0) return; // no lens
        }

        int f = SnapmaticaClient.focalLengthMm;
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
    //?}

    /**
     * Suppress hand rendering before renderWorld() when a photo capture is pending
     * or when the viewfinder is active (sneaking with viewfinder mode enabled).
     */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
                    shift = At.Shift.BEFORE))
    private void snapmatica$suppressHandBeforeCapture(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        //? if <1.21.11 {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderSneakEnabled
                && mc.player != null && mc.player.isSneaking();
        if (PhotoCapture.isCapturePending() || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = false;
        }
        //?}
    }

    //? if >=1.21.11 {
    /*// In 1.21.11 the hand is drawn by renderHand() inside renderWorld(), so the
    // post-composite screenshot includes it. Cancel that call while recording so the
    // held item never appears in the footage (and the live view stays clean too).
    @Inject(method = "renderHand(FZLorg/joml/Matrix4f;)V", at = @At("HEAD"), cancellable = true)
    private void snapmatica$suppressHandWhileRecording(float tickDelta, boolean blockOutline,
                                                       org.joml.Matrix4f matrix, CallbackInfo ci) {
        if (VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }
    *///?}

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
        // Apply the EVF blur (scheduled during the previous frame's HUD render) BEFORE
        // capturing, so the screenshot includes GPU bokeh on all versions.
        // Pass forCapture=true when a screenshot is about to be taken so the blur covers
        // the full photo crop area (not just the scissored viewfinder frame).
        dev.shunti.snapmatica.client.EvfBlurRenderer.applyBlur(wasCapturePending);
        PhotoCapture.captureIfPending();
        VideoRecorder.captureFrameIfRecording();
        //? if <1.21.11 {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean viewfinderActive = SnapmaticaClient.viewfinderSneakEnabled
                && mc.player != null && mc.player.isSneaking();
        if (wasCapturePending || viewfinderActive || VideoRecorder.isRecording()) {
            this.renderHand = true;
        }
        //?}
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
    private static double snapmatica$frameFov(int focalMm, double windowAspect) {
        boolean portrait = SnapmaticaClient.portraitOrientation;
        double halfH = portrait ? 18.0 : 12.0;   // 36x24 frame, half-extents in mm
        double halfW = portrait ? 12.0 : 18.0;
        double frameAspect = halfW / halfH;
        double vHalfMm = (windowAspect >= frameAspect) ? halfH : halfW / windowAspect;
        return Math.toDegrees(2.0 * Math.atan(vHalfMm / focalMm));
    }

}
