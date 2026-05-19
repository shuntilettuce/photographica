package dev.hitom.photographica.item;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.ModDataComponents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.function.Consumer;

public class CameraItem extends Item {
	/** Wired by client init. Opens the dial GUI on shift+right-click. */
	public static Consumer<ItemStack> clientOpenScreen = stack -> {};
	/** Wired by client init. Captures screenshot to disk and notifies server on right-click. */
	public static Consumer<ItemStack> clientTakePhoto = stack -> {};

	public CameraItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static CameraSettings getSettings(ItemStack stack) {
		CameraSettings s = stack.get(ModDataComponents.CAMERA_SETTINGS);
		return s != null ? s : CameraSettings.DEFAULT;
	}

	public static void setSettings(ItemStack stack, CameraSettings settings) {
		stack.set(ModDataComponents.CAMERA_SETTINGS, settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) clientTakePhoto.accept(stack);
		return TypedActionResult.success(stack, world.isClient);
	}
}
