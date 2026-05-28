package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerEntityRenderer overrides renderLabelIfPresent, so the EntityRenderer
 * mixin never suppresses a player's name tag. Without this, photographing a
 * player (e.g. from an armor-stand camera) bakes the name tag into the photo.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

	@Inject(
			method = "renderLabelIfPresent",
			at = @At("HEAD"),
			cancellable = true,
			require = 0
	)
	private void photographica$hidePlayerNametagDuringCapture(CallbackInfo ci) {
		if (PhotoCapture.isCapturePending()) {
			ci.cancel();
		}
	}
}
