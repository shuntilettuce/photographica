package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.item.CameraItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Narrows {@link GameRenderer#getFov} when the player holds a camera with a zoom lens.
 * FOV multiplier = 1 / zoom. Prime lens (zoom locked to 1.0) leaves FOV untouched here;
 * a future change can give prime lenses their own focal-length-based narrowing.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
			at = @At("RETURN"),
			cancellable = true)
	private void photographica$applyCameraZoom(Camera camera, float tickDelta, boolean changingFov,
	                                           CallbackInfoReturnable<Double> cir) {
		PlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) return;

		ItemStack stack = player.getMainHandStack();
		if (!(stack.getItem() instanceof CameraItem)) {
			stack = player.getOffHandStack();
			if (!(stack.getItem() instanceof CameraItem)) return;
		}

		CameraSettings settings = CameraItem.getSettings(stack);

		double baseFov = cir.getReturnValueD();
		double zoom = Math.max(1.0, settings.zoom());
		double newFov = Math.max(1.0, baseFov / zoom);
		cir.setReturnValue(newFov);
	}
}
