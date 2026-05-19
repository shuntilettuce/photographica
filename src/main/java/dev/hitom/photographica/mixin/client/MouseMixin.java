package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.CameraScrollHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts mouse scroll events so that, while the camera viewfinder is active
 * (player sneaking with a camera in hand), the scroll wheel adjusts focal length
 * or aperture instead of switching the hotbar slot.
 */
@Mixin(Mouse.class)
public class MouseMixin {
	@Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
	private void photographica$onMouseScroll(long window, double horizontal, double vertical,
	                                         CallbackInfo ci) {
		if (CameraScrollHandler.onScroll(vertical)) {
			ci.cancel();
		}
	}

	// In Xvfb dev environments GLFW cursor capture never succeeds, so the game
	// stays in "click to play" state indefinitely. Force-report locked so that
	// keyboard bindings (chat, commands, etc.) are processed normally.
	@Inject(method = "isCursorLocked", at = @At("RETURN"), cancellable = true)
	private void photographica$forceLockedInDev(CallbackInfoReturnable<Boolean> cir) {
		if (FabricLoader.getInstance().isDevelopmentEnvironment() && !cir.getReturnValue()) {
			cir.setReturnValue(true);
		}
	}
}
