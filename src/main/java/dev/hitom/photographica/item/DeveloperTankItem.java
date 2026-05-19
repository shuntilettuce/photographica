package dev.hitom.photographica.item;

import dev.hitom.photographica.network.DevelopFilmPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.UseAction;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * Patterson-style developer tank. Hold right-click for 3 seconds in darkness
 * (light level 0) to develop the first available exposed film roll in your
 * inventory into Photo items. The tank has 32 uses before it breaks.
 *
 * Developing in light > 0 is now allowed but produces fogged (ruined) photos.
 */
public class DeveloperTankItem extends Item {

	private static final int USE_TICKS = 60; // 3 seconds

	public DeveloperTankItem(Settings settings) {
		super(settings.maxCount(1));
	}

	@Override
	public int getMaxUseTime(ItemStack stack, LivingEntity entity) {
		return USE_TICKS;
	}

	@Override
	public UseAction getUseAction(ItemStack stack) {
		return UseAction.NONE;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		user.setCurrentHand(hand);
		return TypedActionResult.consume(user.getStackInHand(hand));
	}

	@Override
	public void usageTick(World world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
		if (!world.isClient) return;
		if (!(user instanceof PlayerEntity player)) return;
		int elapsed = USE_TICKS - remainingUseTicks;
		int bars = elapsed * 10 / USE_TICKS;
		String bar = "█".repeat(bars) + "░".repeat(10 - bars);
		player.sendMessage(Text.literal("§b現像中... [" + bar + "]"), true);
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		if (world.isClient && user instanceof PlayerEntity) {
			sendDevelop();
		}
		return stack;
	}

	@Environment(EnvType.CLIENT)
	private static void sendDevelop() {
		ClientPlayNetworking.send(new DevelopFilmPayload());
	}
}
