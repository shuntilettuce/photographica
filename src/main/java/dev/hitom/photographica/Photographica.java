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
import dev.hitom.photographica.network.DownloadPhotoChunkPayload;
import dev.hitom.photographica.network.EquipCameraToArmorStandPayload;
import dev.hitom.photographica.network.DeleteSdPhotoPayload;
import dev.hitom.photographica.network.DevelopFilmPayload;
import dev.hitom.photographica.network.LoadFilmPayload;
import dev.hitom.photographica.network.LoadSdCardPayload;
import dev.hitom.photographica.network.PhotoChunkAssembler;
import dev.hitom.photographica.network.PhotoNotFoundPayload;
import dev.hitom.photographica.network.RequestPhotoPayload;
import dev.hitom.photographica.network.TakeFilmPhotoFromArmorStandPayload;
import dev.hitom.photographica.network.TakeFilmPhotoPayload;
import dev.hitom.photographica.network.UnequipCameraFromArmorStandPayload;
import dev.hitom.photographica.network.UnloadFilmPayload;
import dev.hitom.photographica.network.UnloadSdCardPayload;
import dev.hitom.photographica.network.UpdateArmorStandCameraPayload;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import dev.hitom.photographica.network.UploadPhotoChunkPayload;
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
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class Photographica implements ModInitializer {
	public static final String MOD_ID = "photographica";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Reassembles chunked photo uploads (see {@link UploadPhotoChunkPayload}) — one entry per
	 *  in-progress upload, keyed by photo UUID, cleared as soon as the last chunk lands. */
	private static final PhotoChunkAssembler uploadAssembler = new PhotoChunkAssembler();

	/** The world-save-relative directory canonical photo copies live in server-side — distinct
	 *  from each client's own {@code <runDirectory>/photographica/photos/}, and the reason a
	 *  photo survives for other players even after the photographer disconnects. */
	private static File photosDir(MinecraftServer server) {
		Path root = server.getSavePath(WorldSavePath.ROOT);
		return root.resolve("photographica").resolve("photos").toFile();
	}

	@Override
	public void onInitialize() {
		ModDataComponents.register();
		ModItems.register();
		ModBlocks.register();
		ModBlockEntities.register();
		dev.hitom.photographica.registry.ModEntities.register();
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
		PayloadTypeRegistry.playC2S().register(dev.hitom.photographica.network.CreatePhotoFromDronePayload.ID,
				dev.hitom.photographica.network.CreatePhotoFromDronePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.hitom.photographica.network.UpdateDronePositionPayload.ID,
				dev.hitom.photographica.network.UpdateDronePositionPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(EquipCameraToArmorStandPayload.ID,      EquipCameraToArmorStandPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UnequipCameraFromArmorStandPayload.ID, UnequipCameraFromArmorStandPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(UploadPhotoChunkPayload.ID,            UploadPhotoChunkPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RequestPhotoPayload.ID,                RequestPhotoPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DownloadPhotoChunkPayload.ID,          DownloadPhotoChunkPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PhotoNotFoundPayload.ID,               PhotoNotFoundPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.hitom.photographica.network.SendFaxPayload.ID,
				dev.hitom.photographica.network.SendFaxPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(dev.hitom.photographica.network.DroneSignalLostPayload.ID,
				dev.hitom.photographica.network.DroneSignalLostPayload.CODEC);

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
							incoming.exposureMode(), incoming.focusMode(), incoming.autoWind(), incoming.timerSeconds(),
							incoming.motionBlur(), incoming.focusPeaking());
					FilmCameraItem.setSettings(stack, safe);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(CreatePhotoPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ItemStack camera = player.getStackInHand(Hand.MAIN_HAND);
				if (!(camera.getItem() instanceof CameraItem) && !(camera.getItem() instanceof MirrorlessCameraItem)) return;
				// Charge is spent HERE, not on the client that asked for the photo. The client
				// runs the same check first (see PhotoCapture#take) so the shutter refuses
				// visibly rather than silently producing nothing — but this is the copy that
				// actually decides, and it's also what stops a flat battery from being ignored.
				if (!dev.hitom.photographica.component.CameraPower.consumeForShot(camera)) return;
				//? if >=1.21.11 {
				/*ServerWorld world = (ServerWorld) player.getEntityWorld();*/
				//?} else {
				ServerWorld world = player.getServerWorld();
				//?}
				BlockPos pos = player.getBlockPos();
				PhotoData photoData = new PhotoData(
						payload.id(), player.getName().getString(), world.getTime(),
						world.getRegistryKey().getValue().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings()
				);
				// Onto the fitted card if there's room. storePhoto writes the card ITEM in the
				// gear slot, not just the camera's mirror of it, so pulling the card back out
				// takes the photos with it.
				if (dev.hitom.photographica.component.CameraGear.storePhoto(camera, photoData)) return;
				// Otherwise create a Photo item
				ItemStack photo = new ItemStack(ModItems.PHOTO);
				photo.set(ModDataComponents.PHOTO_DATA, photoData);
				if (!player.getInventory().insertStack(photo)) {
					player.dropItem(photo, false);
				}
			});
		});

		// UploadPhotoChunkPayload: reassemble a client's just-captured photo and persist the
		// canonical copy under the world save, independent of (and not blocking) the
		// CreatePhotoPayload/TakeFilmPhotoPayload metadata flow above — the item/SD-card entry
		// is created immediately either way, the pixel data simply catches up.
		ServerPlayNetworking.registerGlobalReceiver(UploadPhotoChunkPayload.ID, (payload, context) -> {
			byte[] full = uploadAssembler.receive(payload.id(), payload.chunkIndex(), payload.totalChunks(), payload.data());
			if (full == null) return;
			context.server().execute(() -> {
				try {
					File dir = photosDir(context.server());
					if (!dir.exists()) dir.mkdirs();
					Files.write(new File(dir, payload.id() + ".jpg").toPath(), full);
				} catch (IOException e) {
					LOGGER.error("Failed to persist uploaded photo {}", payload.id(), e);
				}
			});
		});

		// RequestPhotoPayload: another player's client is missing this photo locally (they
		// weren't the photographer, or it's a fresh install) — serve the canonical copy back
		// chunked, or say so if the server doesn't have it either.
		ServerPlayNetworking.registerGlobalReceiver(RequestPhotoPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				File file = new File(photosDir(context.server()), payload.id() + ".jpg");
				if (!file.exists()) {
					ServerPlayNetworking.send(player, new PhotoNotFoundPayload(payload.id()));
					return;
				}
				try {
					byte[] data = Files.readAllBytes(file.toPath());
					boolean sent = PhotoChunkAssembler.split(data, (chunkIndex, totalChunks, chunk) ->
							ServerPlayNetworking.send(player,
									new DownloadPhotoChunkPayload(payload.id(), chunkIndex, totalChunks, chunk)));
					if (!sent) {
						LOGGER.warn("Photo {} is {} bytes — too large to serve", payload.id(), data.length);
						ServerPlayNetworking.send(player, new PhotoNotFoundPayload(payload.id()));
					}
				} catch (IOException e) {
					LOGGER.error("Failed to read photo {} for {}", payload.id(), player.getName().getString(), e);
					ServerPlayNetworking.send(player, new PhotoNotFoundPayload(payload.id()));
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

				//? if >=1.21.11 {
				/*ServerWorld world = (ServerWorld) player.getEntityWorld();*/
				//?} else {
				ServerWorld world = player.getServerWorld();
				//?}
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
								cur.motionBlur(), cur.focusPeaking()));
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
				//? if >=1.21.11 {
				/*ServerWorld world = (ServerWorld) player.getEntityWorld();*/
				//?} else {
				ServerWorld world = player.getServerWorld();
				//?}
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
				//? if >=1.21.11 {
				/*ServerWorld world = (ServerWorld) player.getEntityWorld();*/
				//?} else {
				ServerWorld world = player.getServerWorld();
				//?}
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

		// UpdateArmorStandCameraPayload: update camera settings on an armor stand
		ServerPlayNetworking.registerGlobalReceiver(UpdateArmorStandCameraPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) context.player().getEntityWorld()).getEntityById(payload.entityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = context.player().getServerWorld().getEntityById(payload.entityId());
				//?}
				// Drone-mounted camera: same payload/settings shape as the armor-stand case, just
				// a different equipment model (one TrackedData<ItemStack> slot, not vanilla gear
				// slots) — see DroneEntity#getEquippedCamera/#setEquippedCamera. Re-clamps focal
				// length to the drone's 24-200mm zoom range and re-forces f/2.8 server-side
				// regardless of what the payload claims, since DroneEntity#applyDroneCameraProfile
				// is the only legitimate source of truth for "this camera is mounted on a drone"
				// and a client could in principle send anything.
				if (entity instanceof dev.hitom.photographica.entity.DroneEntity drone) {
					// A COPY, not the tracked stack itself — DataTracker.set() only actually
					// syncs to clients when the new value differs from the stored one, and
					// mutating the already-stored ItemStack in place (as an earlier version of
					// this did) meant comparing that object against itself: trivially "equal"
					// every time, so the change silently never left the server. Scroll-to-zoom
					// (and the continuous AF sync) both route through this same receiver, so
					// this one bug was why neither one ever visibly updated on any client.
					ItemStack camera = drone.getEquippedCamera().copy();
					if (camera.isEmpty()) return;
					CameraSettings incoming = payload.settings();
					int focal = Math.max(dev.hitom.photographica.component.LensKind.DRONE_FOCAL_MIN,
							Math.min(dev.hitom.photographica.component.LensKind.DRONE_FOCAL_MAX, incoming.focalLengthMm()));
					CameraSettings forced = new CameraSettings(
							2.8f, incoming.shutterSpeedIdx(), incoming.iso(), incoming.focusDistance(),
							focal, dev.hitom.photographica.component.LensKind.DRONE_ZOOM,
							incoming.filmType(), incoming.remainingShots(), incoming.exposureMode(),
							incoming.focusMode(), incoming.autoWind(), incoming.timerSeconds(),
							incoming.motionBlur(), incoming.focusPeaking());
					if (camera.getItem() instanceof FilmCameraItem) {
						FilmCameraItem.setSettings(camera, forced);
					} else if (camera.getItem() instanceof CameraItem) {
						CameraItem.setSettings(camera, forced);
					} else {
						return;
					}
					drone.setEquippedCamera(camera);
					return;
				}

				if (!(entity instanceof ArmorStandEntity stand)) return;
				// Try MAINHAND first, then OFFHAND
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack camera = stand.getEquippedStack(slot);
					if (camera.isEmpty()) continue;
					if (camera.getItem() instanceof CameraItem) {
						CameraItem.setSettings(camera, payload.settings());
						stand.equipStack(slot, camera);
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
								incoming.motionBlur(), incoming.focusPeaking());
						FilmCameraItem.setSettings(camera, safe);
						stand.equipStack(slot, camera);
						return;
					}
				}
			});
		});

		// CreatePhotoFromArmorStandPayload: create photo from armor stand's camera
		ServerPlayNetworking.registerGlobalReceiver(CreatePhotoFromArmorStandPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) player.getEntityWorld()).getEntityById(payload.entityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = player.getServerWorld().getEntityById(payload.entityId());
				//?}
				if (!(entity instanceof ArmorStandEntity stand)) return;
				ItemStack camera = null;
				EquipmentSlot cameraSlot = null;
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getEquippedStack(slot);
					if (!s.isEmpty() && (s.getItem() instanceof CameraItem || s.getItem() instanceof MirrorlessCameraItem)) {
						camera = s; cameraSlot = slot; break;
					}
				}
				if (camera == null) return;

				//? if >=1.21.11 {
				/*ServerWorld world = (ServerWorld) player.getEntityWorld();*/
				//?} else {
				ServerWorld world = player.getServerWorld();
				//?}
				BlockPos pos = stand.getBlockPos();
				PhotoData photoData = new PhotoData(
						payload.id(), player.getName().getString(), world.getTime(),
						world.getRegistryKey().getValue().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings());

				if (camera.contains(ModDataComponents.SD_CARD)) {
					SdCardData sd = camera.get(ModDataComponents.SD_CARD);
					if (sd != null && !sd.isFull()) {
						camera.set(ModDataComponents.SD_CARD, sd.withPhoto(photoData));
						stand.equipStack(cameraSlot, camera);
						return;
					}
				}
				ItemStack photo = new ItemStack(ModItems.PHOTO);
				photo.set(ModDataComponents.PHOTO_DATA, photoData);
				if (!player.getInventory().insertStack(photo)) player.dropItem(photo, false);
			});
		});

		// CreatePhotoFromDronePayload: same idea as the armor-stand version above, but the
		// camera lives in the drone's own TrackedData field rather than a vanilla equipment slot.
		ServerPlayNetworking.registerGlobalReceiver(dev.hitom.photographica.network.CreatePhotoFromDronePayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) player.getEntityWorld()).getEntityById(payload.droneEntityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = player.getServerWorld().getEntityById(payload.droneEntityId());
				//?}
				if (!(entity instanceof dev.hitom.photographica.entity.DroneEntity drone)) return;
				// Copy before mutating — see the UpdateArmorStandCameraPayload receiver above
				// for why mutating the tracked stack in place silently breaks sync.
				ItemStack camera = drone.getEquippedCamera().copy();
				if (camera.isEmpty()) return;

				//? if >=1.21.11 {
				/*ServerWorld world = (ServerWorld) player.getEntityWorld();*/
				//?} else {
				ServerWorld world = player.getServerWorld();
				//?}
				BlockPos pos = drone.getBlockPos();
				PhotoData photoData = new PhotoData(
						payload.id(), player.getName().getString(), world.getTime(),
						world.getRegistryKey().getValue().toString(),
						pos.getX(), pos.getY(), pos.getZ(),
						payload.settings());

				if (camera.contains(ModDataComponents.SD_CARD)) {
					SdCardData sd = camera.get(ModDataComponents.SD_CARD);
					if (sd != null && !sd.isFull()) {
						camera.set(ModDataComponents.SD_CARD, sd.withPhoto(photoData));
						drone.setEquippedCamera(camera);
						return;
					}
				}
				ItemStack photo = new ItemStack(ModItems.PHOTO);
				photo.set(ModDataComponents.PHOTO_DATA, photoData);
				if (!player.getInventory().insertStack(photo)) player.dropItem(photo, false);
			});
		});

		// UpdateDronePositionPayload: move the drone to match wherever its pilot's view went
		// this tick — see DronePilot. No ownership check for v1 (anyone could in principle
		// spoof another player's drone's position with a crafted packet); acceptable for now,
		// same trust level as the rest of this mod's client-authoritative state.
		ServerPlayNetworking.registerGlobalReceiver(dev.hitom.photographica.network.UpdateDronePositionPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) context.player().getEntityWorld()).getEntityById(payload.droneEntityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = context.player().getServerWorld().getEntityById(payload.droneEntityId());
				//?}
				if (!(entity instanceof dev.hitom.photographica.entity.DroneEntity drone)) return;
				// A pilot actively steering it again (this packet only ever comes from
				// DronePilot.tick()'s normal flight branch) means any in-progress signal-loss
				// fall is over — cancel it before applying the packet's own position so the two
				// don't fight over whether physics is on this tick.
				drone.cancelFalling();
				drone.markFlying();
				// Deliberately NOT clamped to MAX_REMOTE_RANGE. An earlier version pinned the
				// position onto a boundary sphere, which made the drone stop dead against an
				// invisible wall — wrong for something that flies on momentum. Range is enforced
				// by consequence instead: the pilot loses signal near the limit and the airframe
				// coasts on out of control (see DroneSignalLostPayload / startFalling), so it can
				// still END UP past the limit, it just can't be FLOWN there.
				drone.updatePosition(payload.x(), payload.y(), payload.z());
				drone.setYaw(payload.yaw());
				drone.setPitch(payload.pitch());
				drone.setBank(payload.bank());
			});
		});

		// DroneSignalLostPayload: the pilot's own client already determined signal reached
		// zero (see DronePilot.tick()) — this just tells the server to actually start the
		// crash-fall. No ownership check for v1, same trust level as the position payload
		// above; the worst a spoofed packet could do is crash someone's drone early.
		ServerPlayNetworking.registerGlobalReceiver(dev.hitom.photographica.network.DroneSignalLostPayload.ID, (payload, context) -> {
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) context.player().getEntityWorld()).getEntityById(payload.droneEntityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = context.player().getServerWorld().getEntityById(payload.droneEntityId());
				//?}
				if (!(entity instanceof dev.hitom.photographica.entity.DroneEntity drone)) return;
				drone.startFalling(new net.minecraft.util.math.Vec3d(payload.vx(), payload.vy(), payload.vz()));
			});
		});

		// SendFaxPayload: pull whatever's in the sending machine's own out-tray (never trust
		// a stack sent over the wire) and deliver it to the target machine's in-tray — see
		// FaxMachineBlockEntity.find(). Never trusts pos to actually be a fax machine, or the
		// target number to resolve to anything: both are re-validated server-side.
		ServerPlayNetworking.registerGlobalReceiver(dev.hitom.photographica.network.SendFaxPayload.ID, (payload, context) -> {
			net.minecraft.server.network.ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.block.entity.BlockEntity be = ((ServerWorld) player.getEntityWorld()).getBlockEntity(payload.pos());*/
				//?} else {
				net.minecraft.block.entity.BlockEntity be = player.getServerWorld().getBlockEntity(payload.pos());
				//?}
				if (!(be instanceof dev.hitom.photographica.block.entity.FaxMachineBlockEntity sender)) return;

				net.minecraft.item.ItemStack out = sender.getStack(dev.hitom.photographica.block.entity.FaxMachineBlockEntity.SLOT_OUT);
				if (out.isEmpty() || !(out.getItem() instanceof dev.hitom.photographica.item.PhotoItem)) {
					player.sendMessage(net.minecraft.text.Text.literal("送信する写真がありません"), true);
					return;
				}

				dev.hitom.photographica.block.entity.FaxMachineBlockEntity target =
						dev.hitom.photographica.block.entity.FaxMachineBlockEntity.find(context.server(), payload.targetNumber());
				if (target == null) {
					player.sendMessage(net.minecraft.text.Text.literal("その番号のFAX機は見つかりませんでした"), true);
					return;
				}
				if (target == sender) {
					player.sendMessage(net.minecraft.text.Text.literal("自分自身には送信できません"), true);
					return;
				}

				net.minecraft.item.ItemStack incoming = out.copyWithCount(1);
				// v1 limitation: the in-tray is a single slot — a fax that arrives before the
				// last one was collected overwrites it, same as a real machine's paper jamming
				// if nobody empties the tray.
				target.setStack(dev.hitom.photographica.block.entity.FaxMachineBlockEntity.SLOT_IN, incoming);
				out.decrement(1);
				sender.markDirty();
				player.sendMessage(net.minecraft.text.Text.literal("送信しました (#" + payload.targetNumber() + ")"), true);
			});
		});

		// TakeFilmPhotoFromArmorStandPayload: expose frame on film camera on armor stand
		ServerPlayNetworking.registerGlobalReceiver(TakeFilmPhotoFromArmorStandPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) player.getEntityWorld()).getEntityById(payload.entityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = player.getServerWorld().getEntityById(payload.entityId());
				//?}
				if (!(entity instanceof ArmorStandEntity stand)) return;
				ItemStack camera = null;
				EquipmentSlot cameraSlot = null;
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getEquippedStack(slot);
					if (!s.isEmpty() && s.getItem() instanceof FilmCameraItem) {
						camera = s; cameraSlot = slot; break;
					}
				}
				if (camera == null) return;

				FilmRollData film = FilmCameraItem.getFilm(camera);
				if (film.totalExposures() == 0 || film.isExposed() || !film.wound()) return;

				//? if >=1.21.11 {
				/*ServerWorld world = (ServerWorld) player.getEntityWorld();*/
				//?} else {
				ServerWorld world = player.getServerWorld();
				//?}
				BlockPos pos = stand.getBlockPos();
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
				stand.equipStack(cameraSlot, camera);
			});
		});

		// EquipCameraToArmorStandPayload: move camera from player's main hand to armor stand's main hand
		ServerPlayNetworking.registerGlobalReceiver(EquipCameraToArmorStandPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) player.getEntityWorld()).getEntityById(payload.entityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = player.getServerWorld().getEntityById(payload.entityId());
				//?}
				if (!(entity instanceof ArmorStandEntity stand)) return;

				// Safety: reject if stand already has a camera
				for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getEquippedStack(slot);
					if (!s.isEmpty() && (s.getItem() instanceof CameraItem
							|| s.getItem() instanceof FilmCameraItem
							|| s.getItem() instanceof MirrorlessCameraItem)) return;
				}

				ItemStack held = player.getStackInHand(Hand.MAIN_HAND);
				if (held.isEmpty()) return;
				if (!(held.getItem() instanceof CameraItem)
						&& !(held.getItem() instanceof FilmCameraItem)
						&& !(held.getItem() instanceof MirrorlessCameraItem)) return;

				// Equip camera to stand's main hand; return what was there to player
				ItemStack existing = stand.getEquippedStack(EquipmentSlot.MAINHAND).copy();
				stand.equipStack(EquipmentSlot.MAINHAND, held.copy());
				player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);

				if (!existing.isEmpty() && !player.getInventory().insertStack(existing)) {
					player.dropItem(existing, false);
				}
				player.playSound(SoundEvents.ITEM_BUNDLE_INSERT, 0.8f, 1.1f);
			});
		});

		// UnequipCameraFromArmorStandPayload: remove camera from armor stand → player inventory
		ServerPlayNetworking.registerGlobalReceiver(UnequipCameraFromArmorStandPayload.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				//? if >=1.21.11 {
				/*net.minecraft.entity.Entity entity = ((ServerWorld) player.getEntityWorld()).getEntityById(payload.entityId());*/
				//?} else {
				net.minecraft.entity.Entity entity = player.getServerWorld().getEntityById(payload.entityId());
				//?}
				if (!(entity instanceof ArmorStandEntity stand)) return;

				// Find the camera slot
				for (EquipmentSlot slot : new EquipmentSlot[]{
						EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.CHEST}) {
					ItemStack s = stand.getEquippedStack(slot);
					if (s.isEmpty()) continue;
					if (!(s.getItem() instanceof CameraItem)
							&& !(s.getItem() instanceof FilmCameraItem)
							&& !(s.getItem() instanceof MirrorlessCameraItem)
							&& !(s.getItem() instanceof VideoCameraItem)) continue;

					// Remove from stand and give to player
					stand.equipStack(slot, ItemStack.EMPTY);
					if (!player.getInventory().insertStack(s.copy())) {
						player.dropItem(s, false);
					}
					player.playSound(SoundEvents.ITEM_BUNDLE_REMOVE_ONE, 0.8f, 1.1f);
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
				//? if <1.21.11 {
				server.getCommandManager().executeWithPrefix(
						server.getCommandSource(), "time set day");
				//?}
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
