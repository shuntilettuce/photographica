package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import dev.hitom.photographica.client.hud.ViewfinderHud;
import dev.hitom.photographica.client.render.EvfBlurRenderer;
import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.VideoCameraItem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overrides the perspective projection FOV with a value derived from the
 * camera's focal length, treating the photo crop as a 35mm full-frame sensor.
 *
 *   vFov(rad) = 2 * atan(12 / focalLengthMm)
 *
 * In MC 26.1 getFov() is gone; instead we inject into extractCamera() at RETURN
 * and overwrite CameraRenderState.projectionMatrix directly via JOML.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow private GameRenderState gameRenderState;

    /** True when the hand was hidden for an in-progress video frame capture. */
    @Unique private boolean photographica$videoHandSuppressed = false;

    /**
     * While tripod-recording, focus the world-render camera on the armor stand so the
     * footage is filmed from its perspective.  We wrap only Camera.update() — which
     * runs in GameRenderer.update() BEFORE pick() and renderLevel() — temporarily
     * pointing the camera at the stand to compute its position, then immediately
     * restoring the player in a finally block so a thrown exception can never leave
     * the camera stuck on the stand.  Because getCameraEntity() == Camera.entity() in
     * 26.x, the restore means pick() (crosshair/interaction) and all input still use
     * the player — and PhotographicaClient's per-tick safety net re-asserts the player
     * as a second line of defence so movement/look/sneak can never freeze.
     */
    @Redirect(method = "update(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;update(Lnet/minecraft/client/DeltaTracker;)V"),
            require = 0)
    private void photographica$focusCameraOnTripod(Camera camera, DeltaTracker dt) {
        int standId = VideoRecorder.getRecordingArmorStandEntityId();
        Minecraft mc = Minecraft.getInstance();
        Entity stand = (standId >= 0 && mc.level != null) ? mc.level.getEntity(standId) : null;
        if (stand != null) {
            Entity prev = camera.entity();
            camera.setEntity(stand);
            try {
                camera.update(dt);
            } finally {
                camera.setEntity(prev != null ? prev : mc.player);
            }
        } else {
            camera.update(dt);
        }
    }

    @Inject(
            method = "extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V",
            at = @At("RETURN"),
            require = 0
    )
    private void photographica$applyFocalLength(DeltaTracker dt, float baseFov, float partialTick,
                                                CallbackInfo ci) {
        double vFovDeg = photographica$computeCustomVFov();
        if (vFovDeg <= 0) return;

        CameraRenderState camState = gameRenderState.levelRenderState.cameraRenderState;
        if (camState == null || camState.projectionMatrix == null) return;

        // Keep EvfBlurRenderer in sync with the actual far clip plane.
        EvfBlurRenderer.currentDepthFar = camState.depthFar;

        Minecraft mc = Minecraft.getInstance();
        float aspect = (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        float fovRad = (float) Math.toRadians(vFovDeg);
        // JOML setPerspective: (fovY radians, aspect, zNear, zFar)
        camState.projectionMatrix.setPerspective(fovRad, aspect, 0.05f, camState.depthFar);
        camState.hudFov = (float) vFovDeg;
    }

    /** Returns the desired vertical FOV in degrees, or ≤0 to leave vanilla unchanged. */
    @Unique
    private double photographica$computeCustomVFov() {
        // Tripod recording: fixed camcorder-native FOV.
        if (VideoRecorder.isTripodRecording()) {
            return VideoRecorder.TRIPOD_FOV;
        }
        // Armor stand capture mode: use the armor stand camera's focal length
        if (PhotoCapture.armorStandCapturePending && PhotoCapture.armorStandFocalLength > 0) {
            int f = PhotoCapture.armorStandFocalLength;
            return Math.toDegrees(2.0 * Math.atan(12.0 / f));
        }

        Player player = Minecraft.getInstance().player;
        if (player == null) return -1;

        // Video camera zoom — always applies while holding a video camera
        ItemStack vs = player.getMainHandItem();
        if (!(vs.getItem() instanceof VideoCameraItem)) vs = player.getOffhandItem();
        if (vs.getItem() instanceof VideoCameraItem) {
            return VideoRecorder.videoFov;
        }

        // Safety valve: if neither hand holds a camera and no capture is queued,
        // make sure any stale armor-stand state is cleared and return vanilla FOV.
        ItemStack main = player.getMainHandItem();
        ItemStack off  = player.getOffhandItem();
        boolean holdingCamera = isCamera(main) || isCamera(off)
                || main.getItem() instanceof VideoCameraItem
                || off.getItem() instanceof VideoCameraItem;
        if (!holdingCamera && PhotoCapture.getPendingCaptureFovDeg() <= 0) {
            PhotoCapture.clearArmorStandState();
            return -1;
        }

        // FOV change only applies while the viewfinder is active (Shift held).
        // If a capture is queued and the player released Shift between pressing the
        // shutter and the actual render frame, keep the lens FOV so the photo matches
        // what the viewfinder was showing.
        if (!player.isShiftKeyDown()) {
            double pendingFov = PhotoCapture.getPendingCaptureFovDeg();
            if (pendingFov > 0) return pendingFov;
            return -1;
        }

        ItemStack stack = player.getMainHandItem();
        if (!isCamera(stack)) {
            stack = player.getOffhandItem();
            if (!isCamera(stack)) return -1;
        }

        CameraSettings settings = stack.getItem() instanceof FilmCameraItem
                ? FilmCameraItem.getSettings(stack)
                : CameraItem.getSettings(stack);
        if (!LensKind.hasLens(settings.lensType())) return -1;

        int f = settings.focalLengthMm();
        if (f <= 0) return -1;

        double halfSensorMm = ViewfinderHud.portraitOrientation ? 18.0 : 12.0;
        return Math.toDegrees(2.0 * Math.atan(halfSensorMm / f));
    }

    /**
     * Fired just before renderLevel() — suppress hand for video frame captures.
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.BEFORE))
    private void photographica$suppressHandBeforeAccumSample(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        photographica$videoHandSuppressed = VideoRecorder.isRecording();
    }

    /**
     * Fired after renderLevel() — capture photo/video frame after shaders have composited.
     */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void photographica$captureAfterComposite(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        // Sync depthFar every frame so EvfBlurRenderer always has the current value
        // (applyFocalLength fires only when FOV is overridden, so this is the reliable path).
        CameraRenderState camState = gameRenderState.levelRenderState.cameraRenderState;
        if (camState != null) EvfBlurRenderer.currentDepthFar = camState.depthFar;

        PhotoCapture.captureIfPending();
        VideoRecorder.captureFrameIfRecording();
        // Apply EVF blur after capture so photos remain unblurred.
        // renderLevel() has returned → command encoder has flushed → mainFb has current content.
        PhotoCapture.applyEvfBlur();
        photographica$videoHandSuppressed = false;
    }

    private static boolean isCamera(ItemStack stack) {
        return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
    }
}
