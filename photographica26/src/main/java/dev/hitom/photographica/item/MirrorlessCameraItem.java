package dev.hitom.photographica.item;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

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

	public MirrorlessCameraItem(Properties settings) {
		super(settings);
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		ItemStack stack = user.getItemInHand(hand);
		if (world.isClientSide()) clientTakePhoto.accept(stack);
		return InteractionResult.SUCCESS;
	}
}
