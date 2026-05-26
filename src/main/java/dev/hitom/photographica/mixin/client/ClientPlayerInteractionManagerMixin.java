package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.FilmCameraScreen;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts right-click on an armor stand that has a camera equipped.
 * Instead of the vanilla armor stand interaction (equip/take), opens the
 * camera settings screen with an "armor stand mode" flag.
 *
 * Only fires when the player has an empty main hand and is NOT sneaking
 * (sneaking keeps vanilla slot-cycle behavior).
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

	@Inject(method = "interactEntity",
			at = @At("HEAD"),
			cancellable = true)
	private void photographica$openArmorStandCameraScreen(
			PlayerEntity player, Entity entity, Hand hand,
			CallbackInfoReturnable<ActionResult> cir) {
		if (hand != Hand.MAIN_HAND) return;
		if (!(entity instanceof ArmorStandEntity stand)) return;
		if (player.isSneaking()) return;
		if (!player.getMainHandStack().isEmpty()) return;

		// Check main hand first, then off hand
		ItemStack camera = stand.getEquippedStack(EquipmentSlot.MAINHAND);
		if (!isCameraItem(camera)) {
			camera = stand.getEquippedStack(EquipmentSlot.OFFHAND);
			if (!isCameraItem(camera)) return;
		}

		final ItemStack cameraStack = camera;
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.setScreen(cameraStack.getItem() instanceof FilmCameraItem
				? new FilmCameraScreen(cameraStack, stand.getId())
				: new CameraScreen(cameraStack, stand.getId()));
		cir.setReturnValue(ActionResult.SUCCESS);
	}

	private static boolean isCameraItem(ItemStack stack) {
		return !stack.isEmpty() && (
				stack.getItem() instanceof CameraItem ||
				stack.getItem() instanceof FilmCameraItem ||
				stack.getItem() instanceof MirrorlessCameraItem
		);
	}
}
