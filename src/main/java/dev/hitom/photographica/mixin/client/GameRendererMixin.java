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

	//? if <1.21.11 {
	@Shadow private boolean renderHand;
	//?}

	/** True when the hand was hidden for an in-progress video frame capture. */
	@Unique private boolean photographica$videoHandSuppressed = false;

	//? if >=1.21.11 {
	/*@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)F",
			at = @At("RETURN"),
			cancellable = true)
	private void photographica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
	                                            CallbackInfoReturnable<Float> cir) {
		if (PhotoCapture.armorStandCapturePending && PhotoCapture.armorStandFocalLength > 0) {
			int f = PhotoCapture.armorStandFocalLength;
			cir.setReturnValue((float) Math.toDegrees(2.0 * Math.atan(12.0 / f)));
			return;
		}
		PlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) return;
		ItemStack vs = player.getMainHandStack();
		if (!(vs.getItem() instanceof VideoCameraItem)) vs = player.getOffHandStack();
		if (vs.getItem() instanceof VideoCameraItem) {
			cir.setReturnValue(VideoRecorder.videoFov);
			return;
		}
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
		cir.setReturnValue((float) Math.toDegrees(2.0 * Math.atan(12.0 / f)));
	}*/
	//?} else {
	@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
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
	//?}

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
	 */
	@Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V",
					shift = At.Shift.BEFORE))
	private void photographica$suppressHandBeforeAccumSample(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
		// Suppress hand during long-exposure accumulation AND armor stand capture
		//? if <1.21.11 {
		if (PhotoCapture.isAccumulating() || PhotoCapture.armorStandCapturePending) {
			this.renderHand = false;
		}
		//?}
		// Suppress hand for the entire duration of recording so it never
		// appears in any captured frame (user confirmed complete hide is fine).
		if (VideoRecorder.isRecording()) {
			//? if <1.21.11 {
			this.renderHand = false;
			//?}
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
		//? if <1.21.11 {
		if (wasAccumulating || wasArmorStand || photographica$videoHandSuppressed) {
			this.renderHand = true;
		}
		//?}
		photographica$videoHandSuppressed = false;
	}

	private static boolean isCamera(ItemStack stack) {
		return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
	}
}
