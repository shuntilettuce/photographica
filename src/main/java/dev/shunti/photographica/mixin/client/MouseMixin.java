package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.CameraScrollHandler;
import dev.hitom.photographica.client.DronePilot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts mouse scroll events so that, while the camera viewfinder is active
 * (player sneaking with a camera in hand), the scroll wheel adjusts focal length
 * or aperture instead of switching the hotbar slot. Also steers the drone
 * ({@link DronePilot}) while it is active, in place of the vanilla look/click handling.
 */
@Mixin(Mouse.class)
public class MouseMixin {
	// Tracks whether lockCursor() was called more recently than unlockCursor(),
	// so the isCursorLocked override only applies when the game actually wants
	// the cursor captured (in-game) rather than free (main menu / GUI screens).
	private static boolean photographica$wantsLock = false;

	@Shadow
	private double cursorDeltaX;

	@Shadow
	private double cursorDeltaY;

	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void photographica$onMouseScroll(long window, double horizontal, double vertical,
	                                         CallbackInfo ci) {
		if (CameraScrollHandler.onScroll(vertical)) {
			ci.cancel();
		}
	}

	/**
	 * While piloting a drone, steers it instead of the player and cancels the vanilla path
	 * ({@code player.changeLookDirection}) entirely, so the player's own facing stays put.
	 * Scaling reproduces the vanilla non-smoothed path exactly: {@code sensitivity*0.6+0.2},
	 * cubed, times 8 — see {@code Mouse.updateMouse}. Photographica's minimum version (1.21.1)
	 * is already past the 1.21 signature change that added {@code tickDelta}, so only the
	 * newer {@code updateMouse(double, CallbackInfo)} shape is needed.
	 */
	@Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
	private void photographica$dronePilotLook(double tickDelta, CallbackInfo ci) {
		// Locked (tripod mode): the vanilla path runs untouched, so the player looks around
		// normally instead of steering a camera that has stopped moving.
		if (!DronePilot.isActive() || DronePilot.isLocked()) return;
		MinecraftClient mc = MinecraftClient.getInstance();
		double sensitivity = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
		double f = sensitivity * sensitivity * sensitivity;
		double scale = f * 8.0;
		DronePilot.onMouseLook(cursorDeltaX * scale, cursorDeltaY * scale);
		ci.cancel();
	}

	/**
	 * Blocks a NEW left/right/middle click while piloting a drone — the player is frozen and
	 * facing wherever it was left, so an attack or block placement would swing at (or aim
	 * from) a direction that has nothing to do with what's on screen. Guarded on no screen
	 * being open so this never eats the click that reopens the camera settings.
	 *
	 * <p>Only the PRESS is ever cancelled — never the RELEASE, even mid-flight. The click that
	 * activates piloting in the first place (right-clicking the drone) is a press that reaches
	 * vanilla normally, since {@link DronePilot#isActive()} only flips true partway through
	 * handling it; the matching release for that same physical click follows a moment later,
	 * by which point isActive() has become true. Cancelling THAT release too (as an earlier
	 * version of this did) swallowed it, leaving the mouse button's own press/release count
	 * unbalanced — the game kept thinking it was still held down, which showed up as spurious
	 * repeat interacts re-firing on whatever the crosshair drifted over next (entering and
	 * immediately re-toggling out, or worse). Always letting releases through costs nothing: a
	 * release can't start a new attack or interaction by itself.
	 *
	 * <p>{@code onMouseButton}'s middle parameter became a {@code MouseInput} record at
	 * 1.21.10, before which it was still the raw {@code (button, action, mods)} triple —
	 * photographica's minimum (1.21.1) needs the older shape.
	 */
	//? if >=1.21.10 {
	/*@Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
	private void photographica$blockClicksWhilePiloting(long window, net.minecraft.client.input.MouseInput button,
	                                                     int action, CallbackInfo ci) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && DronePilot.isActive()
				&& !DronePilot.isLocked() && mc.currentScreen == null) {
			if (button.button() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				DronePilot.releaseToPlayer(mc);
			}
			ci.cancel();
		}
	}
	*///?} else {
	@Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
	private void photographica$blockClicksWhilePiloting(long window, int button, int action, int mods,
	                                                    CallbackInfo ci) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && DronePilot.isActive()
				&& !DronePilot.isLocked() && mc.currentScreen == null) {
			// Right-click mirrors how piloting starts (right-clicking the drone/remote) — a
			// quick "land it" gesture instead of reaching for Esc and going through the pause
			// menu mid-flight. Left/middle clicks still just get swallowed with no action, same
			// as before: they'd otherwise swing/place at whatever the frozen crosshair happens
			// to be aimed at, which has nothing to do with what's on screen.
			if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				DronePilot.releaseToPlayer(mc);
			}
			ci.cancel();
		}
	}
	//?}

	@Inject(method = "lockCursor", at = @At("HEAD"))
	private void photographica$onLockCursor(CallbackInfo ci) {
		photographica$wantsLock = true;
	}

	@Inject(method = "unlockCursor", at = @At("HEAD"))
	private void photographica$onUnlockCursor(CallbackInfo ci) {
		photographica$wantsLock = false;
	}

	// In Xvfb dev environments GLFW cursor capture never succeeds, so the game
	// stays in "click to play" state indefinitely. Force-report locked only when
	// the game actually called lockCursor(), so the main menu remains interactive.
	@Inject(method = "isCursorLocked", at = @At("RETURN"), cancellable = true)
	private void photographica$forceLockedInDev(CallbackInfoReturnable<Boolean> cir) {
		if (FabricLoader.getInstance().isDevelopmentEnvironment()
				&& photographica$wantsLock && !cir.getReturnValue()) {
			cir.setReturnValue(true);
		}
	}
}
