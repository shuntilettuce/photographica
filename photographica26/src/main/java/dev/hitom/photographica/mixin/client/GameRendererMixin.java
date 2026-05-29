package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.VideoCameraItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.Camera;
import net.minecraft.util.profiling.jfr.callback.ProfiledDuration;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides {@link GameRenderer#getFov} with a vertical FOV derived from the
 * camera's focal length, treating the photo crop as a 35mm full-frame sensor
 * (36x24mm). FOV is independent of the user's vanilla FOV setting — a 50mm
 * lens reads 50mm whether the player slider is on 30 or 110.
 *
 *   vFov(rad) = 2 * atan(12 / focalLengthMm)
 *
 *   24mm -> 53.1°    35mm -> 37.8°    50mm -> 27.0°    70mm -> 19.5°
 *
 * If no lens is attached (or the held item isn't a camera) the vanilla return
 * value is left untouched.
 *
 * Also suppresses the player's hand model during video frames that will be
 * captured, so the hand does not appear in recorded footage.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    /** True when the hand was hidden for an in-progress video frame capture. */
    @Unique private boolean photographica$videoHandSuppressed = false;

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)D",
            at = @At("RETURN"),
            cancellable = true)
    private void photographica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                                CallbackInfoReturnable<Double> cir) {
        // Armor stand capture mode: use the armor stand camera's focal length
        if (PhotoCapture.armorStandCapturePending && PhotoCapture.armorStandFocalLength > 0) {
            int f = PhotoCapture.armorStandFocalLength;
            double vFovDegrees = Math.toDegrees(2.0 * Math.atan(12.0 / f));
            cir.setReturnValue(vFovDegrees);
            return;
        }

        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        // Video camera zoom — always applies while holding a video camera so that
        // the vanilla FOV setting never bleeds through at the max-zoom-out position.
        ItemStack vs = player.getMainHandItem();
        if (!(vs.getItem() instanceof VideoCameraItem)) vs = player.getOffhandItem();
        if (vs.getItem() instanceof VideoCameraItem) {
            cir.setReturnValue((double) VideoRecorder.videoFov);
            return;
        }

        // FOV change (focal length) only applies while the viewfinder is active
        if (!player.isShiftKeyDown()) return;

        ItemStack stack = player.getMainHandItem();
        if (!isCamera(stack)) {
            stack = player.getOffhandItem();
            if (!isCamera(stack)) return;
        }

        CameraSettings settings = stack.getItem() instanceof FilmCameraItem
                ? FilmCameraItem.getSettings(stack)
                : CameraItem.getSettings(stack);
        if (!LensKind.hasLens(settings.lensType())) return;

        int f = settings.focalLengthMm();
        if (f <= 0) return;

        double vFovDegrees = Math.toDegrees(2.0 * Math.atan(12.0 / f));
        cir.setReturnValue(vFovDegrees);
    }

    /**
     * Fired just before renderLevel() during long-exposure accumulation OR
     * when a video frame is about to be captured.
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.BEFORE))
    private void photographica$suppressHandBeforeAccumSample(net.minecraft.client.DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        // Suppress hand for the entire duration of recording so it never
        // appears in any captured frame.
        photographica$videoHandSuppressed = VideoRecorder.isRecording();
    }

    /**
     * Fires after renderLevel() returns inside render(), at which point Iris (if present)
     * has already composited its pipeline output into mc.getMainRenderTarget(). This is
     * the correct time to take a screenshot that includes shader post-processing.
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void photographica$captureAfterComposite(net.minecraft.client.DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        PhotoCapture.captureIfPending();
        VideoRecorder.captureFrameIfRecording();
        photographica$videoHandSuppressed = false;
    }

    private static boolean isCamera(ItemStack stack) {
        return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
    }
}
