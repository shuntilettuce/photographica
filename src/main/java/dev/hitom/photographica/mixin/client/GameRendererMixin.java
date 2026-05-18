package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
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
 * Overrides {@link GameRenderer#getFov} with a vertical FOV derived from the
 * camera's focal length, treating the photo crop as a 35mm full-frame sensor
 * (36x24mm). FOV is independent of the user's vanilla FOV setting — a 50mm
 * lens reads 50mm whether the player slider is on 30 or 110.
 *
 *   vFov(rad) = 2 * atan(12 / focalLengthMm)
 *
 *   24mm -> 53.1°    35mm -> 37.8°    50mm -> 27.0°    70mm -> 19.5°
 *
 * If no lens is attached (or the held item isn't a camera) the vanilla return
 * value is left untouched.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "getFov(Lnet/minecraft/client/render/Camera;FZ)D",
			at = @At("RETURN"),
			cancellable = true)
	private void photographica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
	                                            CallbackInfoReturnable<Double> cir) {
		PlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) return;

		ItemStack stack = player.getMainHandStack();
		if (!(stack.getItem() instanceof CameraItem)) {
			stack = player.getOffHandStack();
			if (!(stack.getItem() instanceof CameraItem)) return;
		}

		CameraSettings settings = CameraItem.getSettings(stack);
		if (!LensKind.hasLens(settings.lensType())) return;

		int f = settings.focalLengthMm();
		if (f <= 0) return;

		double vFovDegrees = Math.toDegrees(2.0 * Math.atan(12.0 / f));
		cir.setReturnValue(vFovDegrees);
	}
}
