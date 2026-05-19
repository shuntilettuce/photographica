package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.CameraScrollHandler;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
