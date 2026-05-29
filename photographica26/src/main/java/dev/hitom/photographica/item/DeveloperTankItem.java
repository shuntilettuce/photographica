package dev.hitom.photographica.item;

import dev.hitom.photographica.network.DevelopFilmPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;

/**
 * Patterson-style developer tank. Hold right-click for 3 seconds in darkness
 * (light level 0) to develop the first available exposed film roll in your
 * inventory into Photo items. The tank has 32 uses before it breaks.
 *
 * Developing in light > 0 is now allowed but produces fogged (ruined) photos.
 */
public class DeveloperTankItem extends Item {

	private static final int USE_TICKS = 60; // 3 seconds

	public DeveloperTankItem(Properties settings) {
		super(settings.stacksTo(1));
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return USE_TICKS;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.NONE;
	}

	@Override
	public InteractionResult use(Level world, Player user, InteractionHand hand) {
		user.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public void onUseTick(Level world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
		if (!world.isClientSide()) return;
		if (!(user instanceof Player player)) return;
		int elapsed = USE_TICKS - remainingUseTicks;
		int bars = elapsed * 10 / USE_TICKS;
		String bar = "█".repeat(bars) + "░".repeat(10 - bars);
		player.displayClientMessage(Component.literal("§b現像中... [" + bar + "]"), true);
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level world, LivingEntity user) {
		if (world.isClientSide() && user instanceof Player) {
			sendDevelop();
		}
		return stack;
	}

	@Environment(EnvType.CLIENT)
	private static void sendDevelop() {
		ClientPlayNetworking.send(new DevelopFilmPayload());
	}
}
