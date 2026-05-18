package dev.hitom.photographica;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.network.CreatePhotoPayload;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import dev.hitom.photographica.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Photographica implements ModInitializer {
	public static final String MOD_ID = "photographica";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModDataComponents.register();
		ModItems.register();

		PayloadTypeRegistry.playC2S().register(UpdateCameraSettingsPayload.ID, UpdateCameraSettingsPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(CreatePhotoPayload.ID, CreatePhotoPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(UpdateCameraSettingsPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				ItemStack stack = context.player().getStackInHand(Hand.MAIN_HAND);
				if (stack.getItem() instanceof CameraItem) {
					CameraItem.setSettings(stack, payload.settings());
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(CreatePhotoPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem)) return;

				ServerWorld world = player.getServerWorld();
				BlockPos pos = player.getBlockPos();
				ItemStack photo = new ItemStack(ModItems.PHOTO);
				photo.set(ModDataComponents.PHOTO_DATA, new PhotoData(
						payload.id(),
						player.getName().getString(),
						world.getTime(),
						world.getRegistryKey().getValue().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings()
				));

				if (!player.getInventory().insertStack(photo)) {
					player.dropItem(photo, false);
				}
			});
		});

		LOGGER.info("Photographica initialized.");
	}
}
