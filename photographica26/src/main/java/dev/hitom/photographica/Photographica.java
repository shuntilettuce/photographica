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
import dev.hitom.photographica.item.VideoCameraItem;
import dev.hitom.photographica.network.CreatePhotoFromArmorStandPayload;
import dev.hitom.photographica.network.CreatePhotoPayload;
import dev.hitom.photographica.network.EquipCameraToArmorStandPayload;
import dev.hitom.photographica.network.DeleteSdPhotoPayload;
import dev.hitom.photographica.network.DevelopFilmPayload;
import dev.hitom.photographica.network.LoadFilmPayload;
import dev.hitom.photographica.network.LoadSdCardPayload;
import dev.hitom.photographica.network.TakeFilmPhotoFromArmorStandPayload;
import dev.hitom.photographica.network.TakeFilmPhotoPayload;
import dev.hitom.photographica.network.UnequipCameraFromArmorStandPayload;
import dev.hitom.photographica.network.UnloadFilmPayload;
import dev.hitom.photographica.network.UnloadSdCardPayload;
import dev.hitom.photographica.network.UpdateArmorStandCameraPayload;
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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
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
		PayloadTypeRegistry.playC2S().register(UpdateArmorStandCameraPayload.ID,        UpdateArmorStandCameraPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(CreatePhotoFromArmorStandPayload.ID,    CreatePhotoFromArmorStandPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(TakeFilmPhotoFromArmorStandPayload.ID,  TakeFilmPhotoFromArmorStandPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(EquipCameraToArmorStandPayload.ID,      EquipCameraToArmorStandPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UnequipCameraFromArmorStandPayload.ID, UnequipCameraFromArmorStandPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(UpdateCameraSettingsPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				ItemStack stack = context.player().getItemInHand(InteractionHand.MAIN_HAND);
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
							incoming.exposureMode(), incoming.focusMode(), incoming.autoWind(), incoming.timerSeconds(),
							incoming.motionBlur());
					FilmCameraItem.setSettings(stack, safe);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(CreatePhotoPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem) && !(camera.getItem() instanceof MirrorlessCameraItem)) return;
				ServerLevel world = (ServerLevel) player.level();
				BlockPos pos = player.blockPosition();
				PhotoData photoData = new PhotoData(
						payload.id(), player.getName().getString(), world.getGameTime(),
						world.dimension().location().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings()
				);
				// If the camera has an SD card loaded, store the photo on it
				if (camera.has(ModDataComponents.SD_CARD)) {
					SdCardData sd = camera.get(ModDataComponents.SD_CARD);
					if (sd != null && !sd.isFull()) {
						camera.set(ModDataComponents.SD_CARD, sd.withPhoto(photoData));
						return;
					}
				}
				// Otherwise create a Photo item
				ItemStack photo = new ItemStack(ModItems.PHOTO);
				photo.set(ModDataComponents.PHOTO_DATA, photoData);
				if (!player.getInventory().add(photo)) {
					player.drop(photo, false);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(TakeFilmPhotoPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0 || film.isExposed() || !film.wound()) return;

				ServerLevel world = (ServerLevel) player.level();
				BlockPos pos = player.blockPosition();
				PhotoData shot = new PhotoData(
						payload.id(), player.getName().getString(), world.getGameTime(),
						world.dimension().location().toString(),
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
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0 || film.isExposed() || film.wound()) return;
				FilmCameraItem.setFilm(camera, film.withWound(true));
				player.playSound(SoundEvents.LEVER_CLICK, 0.6f, 1.6f);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(LoadFilmPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				if (FilmCameraItem.hasFilm(camera)) {
					player.displayClientMessage(Component.literal("既にフィルムが装填されています"), true);
					return;
				}
				Inventory inv = player.getInventory();
				for (int i = 0; i < inv.getContainerSize(); i++) {
					ItemStack s = inv.getItem(i);
					if (s.getItem() instanceof FilmRollItem fr && fr.filmType() == payload.filmType()) {
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
								cur.exposureMode(), cur.focusMode(), cur.autoWind(), cur.timerSeconds(),
								cur.motionBlur()));
						s.shrink(1);
						player.playSound(SoundEvents.DISPENSER_DISPENSE, 0.6f, 1.2f);
						player.displayClientMessage(Component.literal("フィルムを装填しました"), true);
						return;
					}
				}
				player.displayClientMessage(Component.literal("フィルムが見当たりません"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(UnloadFilmPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (!(camera.getItem() instanceof FilmCameraItem)) return;
				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0) {
					player.displayClientMessage(Component.literal("フィルムが装填されていません"), true);
					return;
				}
				// Opening the back in light fogs all latent frames.
				ServerLevel world = (ServerLevel) player.level();
				int light = world.getLightEmission(player.blockPosition());
				if (light > 0 && !film.isEmpty()) {
					film = film.withFoggedExposures();
					player.displayClientMessage(Component.literal("§c光が入りました — 撮影済みのフレームが感光しました"), true);
					player.playSound(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.6f, 0.6f);
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
				if (!player.getInventory().add(out)) {
					player.drop(out, false);
				}
				player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.6f, 1.0f);
				if (light == 0 || film.isEmpty()) {
					player.displayClientMessage(Component.literal("フィルムを取り出しました"), true);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(DevelopFilmPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ServerLevel world = (ServerLevel) player.level();
				BlockPos pos = player.blockPosition();
				boolean inLight = world.getLightEmission(pos) > 7;
				Inventory inv = player.getInventory();
				for (int i = 0; i < inv.getContainerSize(); i++) {
					ItemStack s = inv.getItem(i);
					if (s.getItem() != ModItems.EXPOSED_FILM) continue;
					FilmRollData film = s.get(ModDataComponents.FILM_ROLL);
					if (film == null || film.exposures().isEmpty()) continue;
					int count = film.exposures().size();
					FilmRollData processedFilm = inLight ? film.withFoggedExposures() : film;
					ItemStack developedStack = new ItemStack(ModItems.DEVELOPED_FILM);
					developedStack.set(ModDataComponents.FILM_ROLL, processedFilm);
					if (!player.getInventory().add(developedStack)) {
						player.drop(developedStack, false);
					}
					s.shrink(1);
					// Damage the developer tank in the player's hand.
					damageDeveloperTank(player);
					if (inLight) {
						player.playSound(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.6f, 0.6f);
						player.displayClientMessage(Component.literal("§c光が入りました — " + count + " 枚が被りました"), true);
					} else {
						player.playSound(SoundEvents.BREWING_STAND_BREW, 0.8f, 1.0f);
						player.displayClientMessage(Component.literal("§b現像済ネガを作成しました: " + count + " 枚"), true);
					}
					return;
				}
				player.displayClientMessage(Component.literal("現像する未現像フィルムがありません"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(LoadSdCardPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem) && !(camera.getItem() instanceof MirrorlessCameraItem)) {
					player.displayClientMessage(Component.literal("デジタルカメラを手に持ってください"), true);
					return;
				}
				if (camera.has(ModDataComponents.SD_CARD)) {
					player.displayClientMessage(Component.literal("既にSDカードが装填されています"), true);
					return;
				}
				Inventory inv = player.getInventory();
				for (int i = 0; i < inv.getContainerSize(); i++) {
					ItemStack s = inv.getItem(i);
					if (s.getItem() instanceof SdCardItem) {
						SdCardData sdData = s.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
						camera.set(ModDataComponents.SD_CARD, sdData);
						s.shrink(1);
						player.displayClientMessage(Component.literal("SDカードを装填しました"), true);
						return;
					}
				}
				player.displayClientMessage(Component.literal("SDカードが見当たりません"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(UnloadSdCardPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem) && !(camera.getItem() instanceof MirrorlessCameraItem)) {
					player.displayClientMessage(Component.literal("デジタルカメラを手に持ってください"), true);
					return;
				}
				if (!camera.has(ModDataComponents.SD_CARD)) {
					player.displayClientMessage(Component.literal("SDカードが装填されていません"), true);
					return;
				}
				SdCardData sdData = camera.get(ModDataComponents.SD_CARD);
				camera.remove(ModDataComponents.SD_CARD);
				ItemStack sdStack = new ItemStack(ModItems.SD_CARD);
				sdStack.set(ModDataComponents.SD_CARD, sdData != null ? sdData : SdCardData.EMPTY);
				if (!player.getInventory().add(sdStack)) {
					player.drop(sdStack, false);
				}
				player.displayClientMessage(Component.literal("SDカードを取り出しました"), true);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(DeleteSdPhotoPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				for (InteractionHand hand : InteractionHand.values()) {
					ItemStack s = player.getItemInHand(hand);
					if (!(s.getItem() instanceof CameraItem) && !(s.getItem() instanceof MirrorlessCameraItem)) continue;
					SdCardData sd = s.get(ModDataComponents.SD_CARD);
					if (sd == null) continue;
					s.set(ModDataComponents.SD_CARD, sd.withoutPhoto(payload.photoId()));
					return;
				}
			});
		});

		// UpdateArmorStandCameraPayload: update camera settings on an armor stand
		ServerPlayNetworking.registerGlobalReceiver(UpdateArmorStandCameraPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				net.minecraft.world.entity.Entity entity = context.player().level().getEntity(payload.entityId());
				if (!(entity instanceof ArmorStand stand)) return;
				// Try MAINHAND first, then OFFHAND
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack camera = stand.getItemBySlot(slot);
					if (camera.isEmpty()) continue;
					if (camera.getItem() instanceof CameraItem) {
						CameraItem.setSettings(camera, payload.settings());
						stand.setItemSlot(slot, camera);
						return;
					}
					if (camera.getItem() instanceof FilmCameraItem) {
						CameraSettings incoming = payload.settings();
						FilmRollData f = FilmCameraItem.getFilm(camera);
						int lockedIso = f.totalExposures() > 0 ? FilmKind.isoOf(f.filmType()) : incoming.iso();
						CameraSettings safe = new CameraSettings(
								incoming.aperture(), incoming.shutterSpeedIdx(), lockedIso,
								incoming.focusDistance(), incoming.focalLengthMm(), incoming.lensType(),
								incoming.filmType(), incoming.remainingShots(),
								incoming.exposureMode(), incoming.focusMode(), incoming.autoWind(), incoming.timerSeconds(),
								incoming.motionBlur());
						FilmCameraItem.setSettings(camera, safe);
						stand.setItemSlot(slot, camera);
						return;
					}
				}
			});
		});

		// CreatePhotoFromArmorStandPayload: create photo from armor stand's camera
		ServerPlayNetworking.registerGlobalReceiver(CreatePhotoFromArmorStandPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				net.minecraft.world.entity.Entity entity = player.level().getEntity(payload.entityId());
				if (!(entity instanceof ArmorStand stand)) return;
				ItemStack camera = null;
				EquipmentSlot cameraSlot = null;
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getItemBySlot(slot);
					if (!s.isEmpty() && (s.getItem() instanceof CameraItem || s.getItem() instanceof MirrorlessCameraItem)) {
						camera = s; cameraSlot = slot; break;
					}
				}
				if (camera == null) return;

				ServerLevel world = (ServerLevel) player.level();
				BlockPos pos = stand.blockPosition();
				PhotoData photoData = new PhotoData(
						payload.id(), player.getName().getString(), world.getGameTime(),
						world.dimension().location().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings());

				if (camera.has(ModDataComponents.SD_CARD)) {
					SdCardData sd = camera.get(ModDataComponents.SD_CARD);
					if (sd != null && !sd.isFull()) {
						camera.set(ModDataComponents.SD_CARD, sd.withPhoto(photoData));
						stand.setItemSlot(cameraSlot, camera);
						return;
					}
				}
				ItemStack photo = new ItemStack(ModItems.PHOTO);
				photo.set(ModDataComponents.PHOTO_DATA, photoData);
				if (!player.getInventory().add(photo)) player.drop(photo, false);
			});
		});

		// TakeFilmPhotoFromArmorStandPayload: expose frame on film camera on armor stand
		ServerPlayNetworking.registerGlobalReceiver(TakeFilmPhotoFromArmorStandPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				net.minecraft.world.entity.Entity entity = player.level().getEntity(payload.entityId());
				if (!(entity instanceof ArmorStand stand)) return;
				ItemStack camera = null;
				EquipmentSlot cameraSlot = null;
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getItemBySlot(slot);
					if (!s.isEmpty() && s.getItem() instanceof FilmCameraItem) {
						camera = s; cameraSlot = slot; break;
					}
				}
				if (camera == null) return;

				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0 || film.isExposed() || !film.wound()) return;

				ServerLevel world = (ServerLevel) player.level();
				BlockPos pos = stand.blockPosition();
				PhotoData shot = new PhotoData(
						payload.id(), player.getName().getString(), world.getGameTime(),
						world.dimension().location().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings());
				FilmRollData updated = film.withNewExposure(shot);
				if (payload.settings().autoWind() && !updated.isExposed()) {
					updated = updated.withWound(true);
				}
				FilmCameraItem.setFilm(camera, updated);
				stand.setItemSlot(cameraSlot, camera);
			});
		});

		// EquipCameraToArmorStandPayload: move camera from player's main hand to armor stand's main hand
		ServerPlayNetworking.registerGlobalReceiver(EquipCameraToArmorStandPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				net.minecraft.world.entity.Entity entity = player.level().getEntity(payload.entityId());
				if (!(entity instanceof ArmorStand stand)) return;

				// Safety: reject if stand already has a camera
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getItemBySlot(slot);
					if (!s.isEmpty() && (s.getItem() instanceof CameraItem
							|| s.getItem() instanceof FilmCameraItem
							|| s.getItem() instanceof MirrorlessCameraItem)) return;
				}

				ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
				if (held.isEmpty()) return;
				if (!(held.getItem() instanceof CameraItem)
						&& !(held.getItem() instanceof FilmCameraItem)
						&& !(held.getItem() instanceof MirrorlessCameraItem)) return;

				// Equip camera to stand's main hand; return what was there to player
				ItemStack existing = stand.getItemBySlot(EquipmentSlot.MAINHAND).copy();
				stand.setItemSlot(EquipmentSlot.MAINHAND, held.copy());
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

				if (!existing.isEmpty() && !player.getInventory().add(existing)) {
					player.drop(existing, false);
				}
				player.playSound(SoundEvents.BUNDLE_INSERT, 0.8f, 1.1f);
			});
		});

		// UnequipCameraFromArmorStandPayload: remove camera from armor stand → player inventory
		ServerPlayNetworking.registerGlobalReceiver(UnequipCameraFromArmorStandPayload.ID, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				net.minecraft.world.entity.Entity entity = player.level().getEntity(payload.entityId());
				if (!(entity instanceof ArmorStand stand)) return;

				// Find the camera slot
				for (EquipmentSlot slot : new EquipmentSlot[]{
						EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getItemBySlot(slot);
					if (s.isEmpty()) continue;
					if (!(s.getItem() instanceof CameraItem)
							&& !(s.getItem() instanceof FilmCameraItem)
							&& !(s.getItem() instanceof MirrorlessCameraItem)
							&& !(s.getItem() instanceof VideoCameraItem)) continue;

					// Remove from stand and give to player
					stand.setItemSlot(slot, ItemStack.EMPTY);
					if (!player.getInventory().add(s.copy())) {
						player.drop(s, false);
					}
					player.playSound(SoundEvents.BUNDLE_REMOVE_ONE, 0.8f, 1.1f);
					return;
				}
			});
		});

		LOGGER.info("Photographica initialized.");
	}

	/** Finds and damages the developer tank in the player's main or off hand by 1. */
	private static void damageDeveloperTank(ServerPlayer player) {
		ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
		if (main.getItem() instanceof dev.hitom.photographica.item.DeveloperTankItem) {
			main.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
			return;
		}
		ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
		if (off.getItem() instanceof dev.hitom.photographica.item.DeveloperTankItem) {
			off.hurtAndBreak(1, player, EquipmentSlot.OFFHAND);
		}
	}

	private static void registerDevGiveCommand() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			if (player.getInventory().getItem(0).isEmpty()) {
				player.setGameMode(GameType.CREATIVE);
				Inventory inv = player.getInventory();
				inv.setItem(0, new ItemStack(ModItems.FILM_CAMERA));
				inv.setItem(1, new ItemStack(ModItems.FILM_ROLL_COLOR));
				inv.setItem(2, new ItemStack(ModItems.FILM_ROLL_COLOR_100));
				inv.setItem(3, new ItemStack(ModItems.FILM_ROLL_COLOR_1600));
				inv.setItem(4, new ItemStack(ModItems.FILM_ROLL_BW));
				inv.setItem(5, new ItemStack(ModItems.FILM_ROLL_COLOR_24));
				ItemStack tank = new ItemStack(ModItems.DEVELOPER_TANK);
				inv.setItem(6, tank);
				inv.setItem(7, new ItemStack(ModItems.CAMERA));
				inv.setItem(8, new ItemStack(ModItems.SD_CARD));
				inv.setItem(9, new ItemStack(ModItems.PHOTO_PAPER, 36));
				inv.setItem(10, new ItemStack(ModItems.LENS_PRIME_35));
				inv.setItem(11, new ItemStack(ModItems.LENS_PRIME_85));
				player.displayClientMessage(Component.literal("§a[Dev] Photographica test items given! Game mode: Creative"), false);
				// Set daytime so the world is visible
				server.getCommands().performPrefixedCommand(
						server.createCommandSourceStack(), "time set day");
			}
		});
	}

	/** Returns the correct FilmRollItem stack for a given filmType when unloading an unused roll. */
	private static ItemStack filmRollItemForType(int filmType) {
		net.minecraft.world.item.Item rollItem = switch (filmType) {
			case dev.hitom.photographica.component.FilmKind.COLOR_100    -> ModItems.FILM_ROLL_COLOR_100;
			case dev.hitom.photographica.component.FilmKind.COLOR_1600   -> ModItems.FILM_ROLL_COLOR_1600;
			case dev.hitom.photographica.component.FilmKind.BW_400       -> ModItems.FILM_ROLL_BW;
			case dev.hitom.photographica.component.FilmKind.COLOR_400_24 -> ModItems.FILM_ROLL_COLOR_24;
			default                                                       -> ModItems.FILM_ROLL_COLOR;
		};
		return FilmRollItem.stackOf(rollItem, filmType);
	}
}
