package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import net.minecraft.client.render.entity.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides entity name tags during photo/video capture so they don't appear baked into the
 * result — most noticeable on a tripod (armor-stand) shot, where the player themselves is
 * standing in frame with their own name tag floating overhead.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

	@Inject(
			method = "renderLabelIfPresent",
			at = @At("HEAD"),
			cancellable = true,
			require = 0
	)
	private void photographica$hideLabelDuringCapture(CallbackInfo ci) {
		if (PhotoCapture.isCapturePending()) {
			ci.cancel();
		}
	}
}
