package dev.hitom.photographica.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import java.util.function.Consumer;

/**
 * Mirrorless camera. Shares the same CameraSettings component and lens system as the DSLR,
 * but uses an Electronic Viewfinder (EVF) that shows a live preview of exposure and
 * vignette effects directly in the viewfinder overlay.
 *
 * Differences from the DSLR:
 *  - No mirror blackout on shutter press (electronic shutter)
 *  - Quieter shutter sound
 *  - Live EVF preview: exposure tint + vignette simulation in the viewfinder
 */
public class MirrorlessCameraItem extends CameraItem {
	/** Wired by client init. Opens the settings GUI on shift+right-click. */
	public static Consumer<ItemStack> clientOpenScreen = stack -> {};
	/** Wired by client init. Captures screenshot on right-click. */
	public static Consumer<ItemStack> clientTakePhoto = stack -> {};

	public MirrorlessCameraItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) clientTakePhoto.accept(stack);
		return TypedActionResult.success(stack, world.isClient);
	}
}
