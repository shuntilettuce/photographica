package dev.hitom.photographica;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.component.SdCardData;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.FilmRollItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.item.SdCardItem;
import dev.hitom.photographica.network.CreatePhotoPayload;
import dev.hitom.photographica.network.DeleteSdPhotoPayload;
import dev.hitom.photographica.network.DevelopFilmPayload;
import dev.hitom.photographica.network.LoadFilmPayload;
import dev.hitom.photographica.network.LoadSdCardPayload;
import dev.hitom.photographica.network.TakeFilmPhotoPayload;
import dev.hitom.photographica.network.UnloadFilmPayload;
import dev.hitom.photographica.network.UnloadSdCardPayload;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import dev.hitom.photographica.network.WindFilmPayload;
import dev.hitom.photographica.registry.ModBlockEntities;
import dev.hitom.photographica.registry.ModBlocks;
import dev.hitom.photographica.registry.ModItems;
import dev.hitom.photographica.registry.ModScreenHandlers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Photographica implements ModInitializer {
	public static final String MOD_ID = "photographica";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModDataComponents.register();
		ModItems.register();
		ModBlocks.register();
		ModBlockEntities.register();
		ModScreenHandlers.register();

		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			registerDevGiveCommand();
		}

		PayloadTypeRegistry.playC2S().register(UpdateCameraSettingsPayload.ID, UpdateCameraSettingsPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(CreatePhotoPayload.ID,         CreatePhotoPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TakeFilmPhotoPayload.ID,        TakeFilmPhotoPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(WindFilmPayload.ID,             WindFilmPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(LoadFilmPayload.ID,             LoadFilmPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UnloadFilmPayload.ID,           UnloadFilmPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DevelopFilmPayload.ID,          DevelopFilmPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(LoadSdCardPayload.ID,          LoadSdCardPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UnloadSdCardPayload.ID,        UnloadSdCardPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DeleteSdPhotoPayload.ID,       DeleteSdPhotoPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(UpdateCameraSettingsPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				ItemStack stack = context.player().getStackInHand(Hand.MAIN_HAND);
				if (stack.getItem() instanceof CameraItem) {
					CameraItem.setSettings(stack, payload.settings());
				} else if (stack.getItem() instanceof FilmCameraItem) {
					// Film cameras keep ISO locked to the loaded film, regardless of what the client sent.
					CameraSettings incoming = payload.settings();
					FilmRollData f = FilmCameraItem.getFilm(stack);
					int lockedIso = f.totalExposures() > 0 ? FilmKind.isoOf(f.filmType()) : incoming.iso();
					CameraSettings safe = new CameraSettings(
							incoming.aperture(), incoming.shutterSpeedIdx(), lockedIso,
							incoming.focusDistance(), incoming.focalLengthMm(), incoming.lensType(),
							incoming.filmType(), incoming.remainingShots(),
							incoming.exposureMode(), incoming.focusMode(), incoming.autoWind());
					FilmCameraItem.setSettings(stack, safe);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(CreatePhotoPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem) && !(camera.getItem() instanceof MirrorlessCameraItem)) return;
				ServerWorld world = player.getServerWorld();
				BlockPos pos = player.getBlockPos();
				PhotoData photoData = new PhotoData(
						payload.id(), player.getName().getString(), world.getTime(),
						world.getRegistryKey().getValue().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings()
				);
				// If the camera has an SD card loaded, store the photo on it
				if (camera.contains(ModDataComponents.SD_CARD)) {
					SdCardData sd = camera.get(ModDataComponents.SD_CARD);
					if (sd != null && !sd.isFull()) {
						camera.set(ModDataComponents.SD_CARD, sd.withPhoto(photoData));
						return;
					}
				}
				// Otherwise create a Photo item
				ItemStack photo = new ItemStack(ModItems.PHOTO);
				photo.set(ModDataComponents.PHOTO_DATA, photoData);
				if (!player.getInventory().insertStack(photo)) {
					player.dropItem(photo, false);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(TakeFilmPhotoPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0 || film.isExposed() || !film.wound()) return;

				ServerWorld world = player.getServerWorld();
				BlockPos pos = player.getBlockPos();
				PhotoData shot = new PhotoData(
						payload.id(), player.getName().getString(), world.getTime(),
						world.getRegistryKey().getValue().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings());
				FilmRollData updated = film.withNewExposure(shot);
				if (payload.settings().autoWind() && !updated.isExposed()) {
					updated = updated.withWound(true);
				}
				FilmCameraItem.setFilm(camera, updated);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(WindFilmPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0 || film.isExposed() || film.wound()) return;
				FilmCameraItem.setFilm(camera, film.withWound(true));
				player.playSound(SoundEvents.BLOCK_LEVER_CLICK, 0.6f, 1.6f);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(LoadFilmPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				if (FilmCameraItem.hasFilm(camera)) {
					player.sendMessage(Text.literal("既にフィルムが装填されています"), true);
					return;
				}
				PlayerInventory inv = player.getInventory();
				for (int i = 0; i < inv.size(); i++) {
					ItemStack s = inv.getStack(i);
					if (s.getItem() instanceof FilmRollItem fr) {
						FilmRollData fresh = s.getOrDefault(ModDataComponents.FILM_ROLL,
								FilmRollData.freshRoll(fr.filmType()));
						// Loaded films start wound (ready to shoot).
						FilmCameraItem.setFilm(camera, fresh.withWound(true));
						// Lock the camera's ISO to whatever the film provides.
						CameraSettings cur = FilmCameraItem.getSettings(camera);
						FilmCameraItem.setSettings(camera, new CameraSettings(
								cur.aperture(), cur.shutterSpeedIdx(), FilmKind.isoOf(fresh.filmType()),
								cur.focusDistance(), cur.focalLengthMm(), cur.lensType(),
								fresh.filmType(), fresh.totalExposures(),
								cur.exposureMode(), cur.focusMode(), cur.autoWind()));
						s.decrement(1);
						player.playSound(SoundEvents.BLOCK_DISPENSER_DISPENSE, 0.6f, 1.2f);
						player.sendMessage(Text.literal("フィルムを装填しました"), true);
						return;
					}
				}
				player.sendMessage(Text.literal("フィルムが見当たりません"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(UnloadFilmPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0) {
					player.sendMessage(Text.literal("フィルムが装填されていません"), true);
					return;
				}
				// Opening the back in light fogs all latent frames.
				ServerWorld world = player.getServerWorld();
				int light = world.getLightLevel(player.getBlockPos());
				if (light > 0 && !film.isEmpty()) {
					film = film.withFoggedExposures();
					player.sendMessage(Text.literal("§c光が入りました — 撮影済みのフレームが感光しました"), true);
					player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.6f, 0.6f);
				}
				ItemStack out;
				if (film.isEmpty()) {
					// Unused roll → return as fresh FilmRoll
					out = filmRollItemForType(film.filmType());
				} else {
					// Has exposures → return as ExposedFilm
					out = new ItemStack(ModItems.EXPOSED_FILM);
					out.set(ModDataComponents.FILM_ROLL, film);
				}
				camera.remove(ModDataComponents.FILM_ROLL);
				if (!player.getInventory().insertStack(out)) {
					player.dropItem(out, false);
				}
				player.playSound(SoundEvents.ITEM_BUNDLE_REMOVE_ONE, 0.6f, 1.0f);
				if (light == 0 || film.isEmpty()) {
					player.sendMessage(Text.literal("フィルムを取り出しました"), true);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(DevelopFilmPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ServerWorld world = player.getServerWorld();
				BlockPos pos = player.getBlockPos();
				boolean inLight = world.getLightLevel(pos) > 7;
				PlayerInventory inv = player.getInventory();
				for (int i = 0; i < inv.size(); i++) {
					ItemStack s = inv.getStack(i);
					if (s.getItem() != ModItems.EXPOSED_FILM) continue;
					FilmRollData film = s.get(ModDataComponents.FILM_ROLL);
					if (film == null || film.exposures().isEmpty()) continue;
					int count = film.exposures().size();
					FilmRollData processedFilm = inLight ? film.withFoggedExposures() : film;
					ItemStack developedStack = new ItemStack(ModItems.DEVELOPED_FILM);
					developedStack.set(ModDataComponents.FILM_ROLL, processedFilm);
					if (!player.getInventory().insertStack(developedStack)) {
						player.dropItem(developedStack, false);
					}
					s.decrement(1);
					// Damage the developer tank in the player's hand.
					damageDeveloperTank(player);
					if (inLight) {
						player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.6f, 0.6f);
						player.sendMessage(Text.literal("§c光が入りました — " + count + " 枚が被りました"), true);
					} else {
						player.playSound(SoundEvents.BLOCK_BREWING_STAND_BREW, 0.8f, 1.0f);
						player.sendMessage(Text.literal("§b現像済ネガを作成しました: " + count + " 枚"), true);
					}
					return;
				}
				player.sendMessage(Text.literal("現像する未現像フィルムがありません"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(LoadSdCardPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem) && !(camera.getItem() instanceof MirrorlessCameraItem)) {
					player.sendMessage(Text.literal("デジタルカメラを手に持ってください"), true);
					return;
				}
				if (camera.contains(ModDataComponents.SD_CARD)) {
					player.sendMessage(Text.literal("既にSDカードが装填されています"), true);
					return;
				}
				PlayerInventory inv = player.getInventory();
				for (int i = 0; i < inv.size(); i++) {
					ItemStack s = inv.getStack(i);
					if (s.getItem() instanceof SdCardItem) {
						SdCardData sdData = s.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
						camera.set(ModDataComponents.SD_CARD, sdData);
						s.decrement(1);
						player.sendMessage(Text.literal("SDカードを装填しました"), true);
						return;
					}
				}
				player.sendMessage(Text.literal("SDカードが見当たりません"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(UnloadSdCardPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem) && !(camera.getItem() instanceof MirrorlessCameraItem)) {
					player.sendMessage(Text.literal("デジタルカメラを手に持ってください"), true);
					return;
				}
				if (!camera.contains(ModDataComponents.SD_CARD)) {
					player.sendMessage(Text.literal("SDカードが装填されていません"), true);
					return;
				}
				SdCardData sdData = camera.get(ModDataComponents.SD_CARD);
				camera.remove(ModDataComponents.SD_CARD);
				ItemStack sdStack = new ItemStack(ModItems.SD_CARD);
				sdStack.set(ModDataComponents.SD_CARD, sdData != null ? sdData : SdCardData.EMPTY);
				if (!player.getInventory().insertStack(sdStack)) {
					player.dropItem(sdStack, false);
				}
				player.sendMessage(Text.literal("SDカードを取り出しました"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(DeleteSdPhotoPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				for (Hand hand : Hand.values()) {
					ItemStack s = player.getStackInHand(hand);
					if (!(s.getItem() instanceof CameraItem) && !(s.getItem() instanceof MirrorlessCameraItem)) continue;
					SdCardData sd = s.get(ModDataComponents.SD_CARD);
					if (sd == null) continue;
					s.set(ModDataComponents.SD_CARD, sd.withoutPhoto(payload.photoId()));
					return;
				}
			});
		});

		LOGGER.info("Photographica initialized.");
	}

	/** Finds and damages the developer tank in the player's main or off hand by 1. */
	private static void damageDeveloperTank(ServerPlayerEntity player) {
		ItemStack main = player.getStackInHand(Hand.MAIN_HAND);
		if (main.getItem() instanceof dev.hitom.photographica.item.DeveloperTankItem) {
			main.damage(1, player, EquipmentSlot.MAINHAND);
			return;
		}
		ItemStack off = player.getStackInHand(Hand.OFF_HAND);
		if (off.getItem() instanceof dev.hitom.photographica.item.DeveloperTankItem) {
			off.damage(1, player, EquipmentSlot.OFFHAND);
		}
	}

	private static void registerDevGiveCommand() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.player;
			if (player.getInventory().getStack(0).isEmpty()) {
				player.changeGameMode(GameMode.CREATIVE);
				PlayerInventory inv = player.getInventory();
				inv.setStack(0, new ItemStack(ModItems.FILM_CAMERA));
				inv.setStack(1, new ItemStack(ModItems.FILM_ROLL_COLOR));
				inv.setStack(2, new ItemStack(ModItems.FILM_ROLL_COLOR_100));
				inv.setStack(3, new ItemStack(ModItems.FILM_ROLL_COLOR_1600));
				inv.setStack(4, new ItemStack(ModItems.FILM_ROLL_BW));
				inv.setStack(5, new ItemStack(ModItems.FILM_ROLL_COLOR_24));
				ItemStack tank = new ItemStack(ModItems.DEVELOPER_TANK);
				inv.setStack(6, tank);
				inv.setStack(7, new ItemStack(ModItems.CAMERA));
				inv.setStack(8, new ItemStack(ModItems.SD_CARD));
				inv.setStack(9, new ItemStack(ModItems.PHOTO_PAPER, 36));
				inv.setStack(10, new ItemStack(ModItems.LENS_PRIME_35));
				inv.setStack(11, new ItemStack(ModItems.LENS_PRIME_85));
				player.sendMessage(Text.literal("§a[Dev] Photographica test items given! Game mode: Creative"), false);
				// Set daytime so the world is visible
				server.getCommandManager().executeWithPrefix(
						server.getCommandSource(), "time set day");
			}
		});
	}

	/** Returns the correct FilmRollItem stack for a given filmType when unloading an unused roll. */
	private static ItemStack filmRollItemForType(int filmType) {
		net.minecraft.item.Item rollItem = switch (filmType) {
			case dev.hitom.photographica.component.FilmKind.COLOR_100    -> ModItems.FILM_ROLL_COLOR_100;
			case dev.hitom.photographica.component.FilmKind.COLOR_1600   -> ModItems.FILM_ROLL_COLOR_1600;
			case dev.hitom.photographica.component.FilmKind.BW_400       -> ModItems.FILM_ROLL_BW;
			case dev.hitom.photographica.component.FilmKind.COLOR_400_24 -> ModItems.FILM_ROLL_COLOR_24;
			default                                                       -> ModItems.FILM_ROLL_COLOR;
		};
		return FilmRollItem.stackOf(rollItem, filmType);
	}
}
