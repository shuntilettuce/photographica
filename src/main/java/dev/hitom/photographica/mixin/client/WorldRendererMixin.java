package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

	/**
	 * Suppresses the block-selection outline during any capture or recording,
	 * so it never bleeds into photos or video frames.
	 */
	@Inject(
			method = "drawBlockOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/entity/Entity;DDDLnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void photographica$hideOutlineDuringCapture(MatrixStack matrices, VertexConsumer vertexConsumer,
	                                                    Entity entity, double cameraX, double cameraY, double cameraZ,
	                                                    BlockPos pos, BlockState state, CallbackInfo ci) {
		if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()) {
			ci.cancel();
		}
	}

	/**
	 * Vanilla WorldRenderer.render() skips drawing a {@code ClientPlayerEntity} when
	 * {@code camera.getFocusedEntity() != entity}.  This is intentional for spectating
	 * (you shouldn't see your own floating body), but it also fires when the mod
	 * redirects the camera to an armor-stand for a photo — making the player
	 * invisible in the shot.
	 *
	 * The entity-skip check (bytecode offsets 896–913) reads:
	 * <pre>
	 *   if (entity instanceof ClientPlayerEntity
	 *       && camera.getFocusedEntity() != entity) { continue; }
	 * </pre>
	 * There are five calls to {@code getFocusedEntity()} in this method; the fourth
	 * (ordinal 3, offset 905) is the one inside that check.  We redirect it to
	 * return {@code mc.player} during an armor-stand capture so the comparison
	 * {@code mc.player != mc.player} evaluates to {@code false} and the player
	 * entity is rendered normally.
	 */
	@Redirect(
			method = "render(Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			at = @At(value = "INVOKE", ordinal = 3,
					target = "Lnet/minecraft/client/render/Camera;getFocusedEntity()Lnet/minecraft/entity/Entity;")
	)
	private Entity photographica$allowPlayerRenderDuringArmorStandCapture(Camera camera) {
		if (PhotoCapture.armorStandCapturePending
				|| VideoRecorder.isTripodRecording()) {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.player != null) return mc.player;
		}
		return camera.getFocusedEntity();
	}

	/** During tripod recording, prevent the camera armor stand from being rendered
	 *  so the view from inside its model is unobstructed. */
	@Redirect(
			method = "render(Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z")
	)
	private boolean photographica$hideTripodStandFromRender(EntityRenderDispatcher dispatcher, Entity entity,
			Frustum frustum, double x, double y, double z) {
		if (VideoRecorder.isTripodRecording()
				&& entity.getId() == VideoRecorder.getRecordingArmorStandEntityId()) {
			return false;
		}
		return dispatcher.shouldRender(entity, frustum, x, y, z);
	}
}
