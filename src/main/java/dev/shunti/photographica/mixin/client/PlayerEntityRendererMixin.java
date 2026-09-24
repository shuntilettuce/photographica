package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerEntityRenderer overrides renderLabelIfPresent, so the EntityRendererMixin above never
 * suppresses a player's name tag. Without this, a tripod photo with the player in frame bakes
 * their name tag into the shot.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

	@Inject(
			method = "renderLabelIfPresent",
			at = @At("HEAD"),
			cancellable = true,
			require = 0
	)
	private void photographica$hidePlayerLabelDuringCapture(CallbackInfo ci) {
		if (PhotoCapture.isCapturePending()) {
			ci.cancel();
		}
	}
}
