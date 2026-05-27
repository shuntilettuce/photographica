package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses all entity name-tag rendering during photo/video capture.
 *
 * <p>Vanilla renders name-tags (player names, mob names, renamed entities) via
 * {@link EntityRenderer#renderLabelIfPresent} on every frame. When a screenshot
 * is taken the framebuffer at that moment already contains the rendered world,
 * so any name-tags that were drawn end up baked into the saved image.</p>
 *
 * <p>By cancelling {@code renderLabelIfPresent} while
 * {@link PhotoCapture#isCapturePending()} is true we ensure that no floating
 * text appears above players or mobs in the final photo.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

	@Inject(
			method = "renderLabelIfPresent",
			at = @At("HEAD"),
			cancellable = true
	)
	private void photographica$hideNametagDuringCapture(
			T entity,
			Text text,
			MatrixStack matrices,
			VertexConsumerProvider vertexConsumers,
			int light,
			float tickDelta,
			CallbackInfo ci) {
		if (PhotoCapture.isCapturePending()) {
			ci.cancel();
		}
	}
}
