package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.VideoCameraItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

	@Shadow private boolean renderHand;

	/** True when the hand was hidden for an in-progress video frame capture. */
	@Unique private boolean photographica$videoHandSuppressed = false;

	/**
	 * While tripod-recording, point ONLY the render camera at the armor stand by
	 * substituting the focus-entity argument of Camera.update().  This is the single
	 * place that decides where the world is rendered from, so the footage becomes the
	 * tripod's view — but mc.cameraEntity is never touched.  Movement, look, sneak,
	 * crosshair and interaction all keep using the player, and PhotographicaClient's
	 * per-tick safety net guarantees the camera entity can never get stuck on the
	 * stand.  The player can walk around inside the shot and right-click the camera
	 * (or press the stop key) to stop.  thirdPerson is forced false so the shot is a
	 * clean first-person-from-the-stand view regardless of the player's F5 state.
	 */
	@Redirect(method = "renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/Camera;update(Lnet/minecraft/world/BlockView;Lnet/minecraft/entity/Entity;ZZF)V"))
	private void photographica$focusCameraOnTripod(Camera camera, net.minecraft.world.BlockView area,
			Entity focused, boolean thirdPerson, boolean inverseView, float tickDelta) {
		int standId = VideoRecorder.getRecordingArmorStandEntityId();
		if (standId >= 0) {
			MinecraftClient mc = MinecraftClient.getInstance();
			Entity stand = mc.world != null ? mc.world.getEntityById(standId) : null;
			if (stand != null) {
				camera.update(area, stand, false, inverseView, tickDelta);
				return;
			}
		}
		camera.update(area, focused, thirdPerson, inverseView, tickDelta);
	}

	@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
			at = @At("RETURN"),
			cancellable = true)
	private void photographica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
	                                            CallbackInfoReturnable<Double> cir) {
		// Tripod recording: fixed camcorder-native FOV regardless of what the
		// player is holding or looking through.
		if (VideoRecorder.isTripodRecording()) {
			cir.setReturnValue((double) VideoRecorder.TRIPOD_FOV);
			return;
		}
		// Armor stand capture mode: use the armor stand camera's focal length
		if (PhotoCapture.armorStandCapturePending && PhotoCapture.armorStandFocalLength > 0) {
			int f = PhotoCapture.armorStandFocalLength;
			double vFovDegrees = Math.toDegrees(2.0 * Math.atan(12.0 / f));
			cir.setReturnValue(vFovDegrees);
			return;
		}

		// Self-timer capture for hand-held camera: apply the lens FOV for the capture frame
		// even when the player is NOT sneaking through the viewfinder.
		// Without this, a timer that fires while the player looks away would render the capture
		// frame with the vanilla/player FOV instead of the lens focal length.
		int pendingFocal = PhotoCapture.pendingHandheldFocalLength();
		if (pendingFocal > 0) {
			double vFovDegrees = Math.toDegrees(2.0 * Math.atan(12.0 / pendingFocal));
			cir.setReturnValue(vFovDegrees);
			return;
		}

		PlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) return;

		// Video camera zoom — always applies while holding a video camera so that
		// the vanilla FOV setting never bleeds through at the max-zoom-out position.
		ItemStack vs = player.getMainHandStack();
		if (!(vs.getItem() instanceof VideoCameraItem)) vs = player.getOffHandStack();
		if (vs.getItem() instanceof VideoCameraItem) {
			cir.setReturnValue((double) VideoRecorder.videoFov);
			return;
		}

		// FOV change (focal length) only applies while the viewfinder is active
		if (!player.isSneaking()) return;

		ItemStack stack = player.getMainHandStack();
		if (!isCamera(stack)) {
			stack = player.getOffHandStack();
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
	 * Fired just before renderWorld() during long-exposure accumulation OR
	 * when a video frame is about to be captured.
	 *
	 * With Iris shaders the hand is composited into mc.getFramebuffer() inside
	 * renderWorld(), not in the vanilla renderHand() call that follows.  Setting
	 * renderHand=false here prevents Iris from including the hand in its pipeline,
	 * so the screenshot taken in photographica$captureAfterComposite is clean.
	 * The flag is restored in that same inject, so vanilla's deferred renderHand()
	 * call still runs normally for the on-screen view.
	 *
	 * Also re-asserts the armor stand as camera entity every frame while
	 * armorStandCapturePending is true, preventing any vanilla or mod code that
	 * runs between frames from silently resetting mc.cameraEntity back to the player
	 * (which would cause the photo to show the player's view instead of the stand's).
	 */
	@Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
					shift = At.Shift.BEFORE))
	private void photographica$suppressHandBeforeAccumSample(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
		// Suppress hand during long-exposure accumulation AND armor stand capture
		if (PhotoCapture.isAccumulating() || PhotoCapture.armorStandCapturePending) {
			this.renderHand = false;
		}
		// Re-assert the armor stand as camera entity every frame to ensure renderWorld()
		// always renders from the stand's perspective.  Without this, code that runs
		// between render frames (game ticks, network handlers, etc.) could reset
		// mc.cameraEntity to mc.player, causing the captured photo to show the player's
		// view composited over/instead of the armor stand's intended framing.
		if (PhotoCapture.armorStandCapturePending) {
			int standId = PhotoCapture.armorStandCaptureEntityId;
			if (standId >= 0) {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mc.world != null) {
					net.minecraft.entity.Entity stand = mc.world.getEntityById(standId);
					if (stand != null && mc.cameraEntity != stand) {
						mc.setCameraEntity(stand);
					}
				}
			}
		}
		// Suppress hand for the entire duration of recording so it never appears in
		// any captured frame.  The world render is focused on the tripod stand (see
		// photographica$focusRenderOnTripod), so the first-person hand would be wrong
		// anyway during tripod recording.
		if (VideoRecorder.isRecording()) {
			this.renderHand = false;
			photographica$videoHandSuppressed = true;
		} else {
			photographica$videoHandSuppressed = false;
		}
	}

	/**
	 * Fires after renderWorld() returns inside render(), at which point Iris (if present)
	 * has already composited its pipeline output into mc.getFramebuffer(). This is the
	 * correct time to take a screenshot that includes shader post-processing.
	 * WorldRenderEvents.LAST fires *before* Iris composites, so it cannot be used for capture.
	 *
	 * Also restores renderHand after the accumulation-sample suppression above, so that
	 * the vanilla renderHand() call that follows still draws the hand on-screen.
	 */
	@Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
					shift = At.Shift.AFTER))
	private void photographica$captureAfterComposite(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
		// Snapshot state BEFORE captureIfPending() — both flags reset inside that call.
		boolean wasAccumulating = PhotoCapture.isAccumulating();
		boolean wasArmorStand = PhotoCapture.armorStandCapturePending;
		PhotoCapture.captureIfPending();
		VideoRecorder.captureFrameIfRecording();
		// Restore renderHand for the vanilla renderHand() call that follows
		if (wasAccumulating || wasArmorStand || photographica$videoHandSuppressed) {
			this.renderHand = true;
		}
	}

	private static boolean isCamera(ItemStack stack) {
		return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
	}
}
