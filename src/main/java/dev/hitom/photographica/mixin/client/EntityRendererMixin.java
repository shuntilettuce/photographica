package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
//? if >=1.21.4 {
/*import net.minecraft.client.render.entity.state.EntityRenderState;*/
//?} else {
import net.minecraft.entity.Entity;
//?}
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses all entity name-tag rendering during photo/video capture.
 */
//? if >=1.21.4 {
/*@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<S extends EntityRenderState> {

	@Inject(
			method = "renderLabelIfPresent",
			at = @At("HEAD"),
			cancellable = true
	)
	private void photographica$hideNametagDuringCapture(
			S state,
			Text text,
			MatrixStack matrices,
			VertexConsumerProvider vertexConsumers,
			int light,
			CallbackInfo ci) {
		if (PhotoCapture.isCapturePending()) {
			ci.cancel();
		}
	}
}*/
//?} else {
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
//?}
