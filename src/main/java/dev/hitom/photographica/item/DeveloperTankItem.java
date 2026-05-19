package dev.hitom.photographica.item;

import dev.hitom.photographica.network.DevelopFilmPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * Patterson-style developer tank. Right-click while holding it in the main
 * hand and carrying at least one exposed film in your inventory; the server
 * verifies that the light level at the player's position is zero before
 * developing the first available exposed roll into a set of Photo items.
 *
 * The tank is reusable; it is not consumed.
 */
public class DeveloperTankItem extends Item {
	public DeveloperTankItem(Settings settings) {
		super(settings.maxCount(1));
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (world.isClient) {
			sendDevelop();
		}
		return TypedActionResult.success(stack, world.isClient);
	}

	@Environment(EnvType.CLIENT)
	private static void sendDevelop() {
		ClientPlayNetworking.send(new DevelopFilmPayload());
	}
}
