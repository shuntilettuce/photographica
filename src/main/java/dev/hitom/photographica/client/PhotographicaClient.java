package dev.hitom.photographica.client;

import dev.hitom.photographica.client.hud.VideoRecorderHud;
import dev.hitom.photographica.client.hud.ViewfinderHud;
import dev.hitom.photographica.client.render.PhotoFrameBlockEntityRenderer;
import dev.hitom.photographica.client.render.PhotoStandBlockEntityRenderer;
import dev.hitom.photographica.client.render.PhotoTextureCache;
import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.DarkroomScreen;
import dev.hitom.photographica.client.screen.EnlargerScreen;
import dev.hitom.photographica.client.screen.FilmCameraScreen;
import dev.hitom.photographica.client.screen.FilmStripScreen;
import dev.hitom.photographica.client.screen.PhotoViewerScreen;
import dev.hitom.photographica.client.screen.PrinterScreen;
import dev.hitom.photographica.client.screen.VideoCameraScreen;
import dev.hitom.photographica.registry.ModBlockEntities;
import dev.hitom.photographica.registry.ModItems;
import dev.hitom.photographica.registry.ModScreenHandlers;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.DevelopedFilmItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.item.VideoCameraItem;
import dev.hitom.photographica.network.LoadSdCardPayload;
import dev.hitom.photographica.network.UnloadSdCardPayload;
import dev.hitom.photographica.network.WindFilmPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
//? if >=1.21.11 {
/*import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
*///?} else {
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
//?}
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//? if <1.21.11 {
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?}
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.OverlayTexture;
// ModelTransformationMode: removed/unused in 1.21.11; in the item package on 1.21.4;
// in the model.json package before that. (The >=1.21.11 branch below is intentionally
// empty — a bare "//" note inside it gets its marker stripped on activation.)
//? if >=1.21.11 {
//?} else if >=1.21.4 {
/*import net.minecraft.item.ModelTransformationMode;*/
//?} else {
import net.minecraft.client.render.model.json.ModelTransformationMode;
//?}
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.RotationAxis;
import org.lwjgl.glfw.GLFW;

import java.io.File;

public class PhotographicaClient implements ClientModInitializer {
	//? if >=1.21.11 {
	/*private static final KeyBinding.Category PHOTOGRAPHICA_CATEGORY =
			KeyBinding.Category.create(net.minecraft.util.Identifier.of("photographica", "photographica"));
	*///?}

	/** Reassembles chunked photo downloads fetched on a local cache miss — see
	 *  PhotoTextureCache.getOrLoad() and the DownloadPhotoChunkPayload receiver below. */
	private static final dev.hitom.photographica.network.PhotoChunkAssembler photoDownloadAssembler =
			new dev.hitom.photographica.network.PhotoChunkAssembler();

	@Override
	public void onInitializeClient() {
		VideoCameraItem.clientToggleRecord = VideoRecorder::toggle;
		VideoCameraItem.clientOpenScreen = stack ->
				MinecraftClient.getInstance().setScreen(new VideoCameraScreen(stack));

		CameraItem.clientOpenScreen = stack ->
				MinecraftClient.getInstance().setScreen(new CameraScreen(stack));
		CameraItem.clientTakePhoto = PhotoCapture::take;

		MirrorlessCameraItem.clientOpenScreen = stack ->
				MinecraftClient.getInstance().setScreen(new CameraScreen(stack));
		MirrorlessCameraItem.clientTakePhoto = PhotoCapture::take;

		FilmCameraItem.clientOpenScreen = stack ->
				MinecraftClient.getInstance().setScreen(new FilmCameraScreen(stack));
		FilmCameraItem.clientTakePhoto = PhotoCapture::take;

		PhotoItem.clientOpenViewer = data ->
				MinecraftClient.getInstance().setScreen(new PhotoViewerScreen(data));

		DevelopedFilmItem.clientOpenFilmStrip = stack ->
				MinecraftClient.getInstance().setScreen(new FilmStripScreen(stack));

		// Touching a drone bare-handed no longer flies it (see DroneEntity.onInteract) — flying
		// now goes through whichever DroneRemoteItem is paired to it, used from anywhere within
		// "radio range" rather than requiring the pilot to physically touch the airframe. Range
		// is the same DronePilot.computeSignal() the pilot's own per-tick monitoring uses — one
		// definition of "can I reach it" shared between connecting and staying connected.
		dev.hitom.photographica.item.DroneRemoteItem.clientTryPilot = stack -> {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null || client.world == null) return;
			Integer freq = stack.get(dev.hitom.photographica.component.ModDataComponents.DRONE_FREQUENCY);
			if (freq == null) {
				client.player.sendMessage(net.minecraft.text.Text.literal(
						"📡 このリモコンは未ペアリングです。ドローンにタッチしてください"), true);
				return;
			}
			net.minecraft.util.math.Vec3d eye = client.player.getEyePos();
			net.minecraft.util.math.Box searchBox = client.player.getBoundingBox().expand(DronePilot.getFullRange());
			dev.hitom.photographica.entity.DroneEntity target = null;
			int bestSignal = 0;
			for (dev.hitom.photographica.entity.DroneEntity d : client.world.getEntitiesByClass(
					dev.hitom.photographica.entity.DroneEntity.class, searchBox, e -> e.getFrequency() == freq)) {
				//? if >=1.21.11 {
				/*net.minecraft.util.math.Vec3d dPos = d.getEntityPos();
				*///?} else {
				net.minecraft.util.math.Vec3d dPos = d.getPos();
				//?}
				int sig = DronePilot.computeSignal(client, eye, dPos);
				if (sig > bestSignal) {
					bestSignal = sig;
					target = d;
				}
			}
			if (target == null) {
				client.player.sendMessage(net.minecraft.text.Text.literal(
						"📡 チャンネル " + freq + " のドローンに電波が届きません"), true);
				return;
			}
			DronePilot.toggle(client, target);
		};

		// Fetch-on-miss for photos taken by someone else (or on a different machine) — see
		// PhotoTextureCache.getOrLoad(), which sends RequestPhotoPayload on a local cache miss.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				dev.hitom.photographica.network.DownloadPhotoChunkPayload.ID, (payload, context) -> {
					byte[] full = photoDownloadAssembler.receive(
							payload.id(), payload.chunkIndex(), payload.totalChunks(), payload.data());
					if (full == null) return;
					context.client().execute(() -> {
						try {
							File dir = new File(MinecraftClient.getInstance().runDirectory, "photographica/photos");
							if (!dir.exists()) dir.mkdirs();
							java.nio.file.Files.write(new File(dir, payload.id() + ".jpg").toPath(), full);
							dev.hitom.photographica.client.render.PhotoTextureCache.onFetched(payload.id());
						} catch (java.io.IOException e) {
							dev.hitom.photographica.Photographica.LOGGER.error("Failed to save fetched photo {}", payload.id(), e);
							dev.hitom.photographica.client.render.PhotoTextureCache.onNotFound(payload.id());
						}
					});
				});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
				dev.hitom.photographica.network.PhotoNotFoundPayload.ID, (payload, context) ->
						context.client().execute(() ->
								dev.hitom.photographica.client.render.PhotoTextureCache.onNotFound(payload.id())));

		HandledScreens.register(ModScreenHandlers.DARKROOM, DarkroomScreen::new);
		HandledScreens.register(ModScreenHandlers.PRINTER, PrinterScreen::new);
		HandledScreens.register(ModScreenHandlers.CAMERA_GEAR, dev.hitom.photographica.client.screen.CameraGearScreen::new);
		HandledScreens.register(ModScreenHandlers.ALBUM, dev.hitom.photographica.client.screen.AlbumScreen::new);
		HandledScreens.register(ModScreenHandlers.ENLARGER, EnlargerScreen::new);
		HandledScreens.register(ModScreenHandlers.FAX_MACHINE, dev.hitom.photographica.client.screen.FaxMachineScreen::new);

		// Settings key (unbound by default).
		//? if >=1.21.11 {
		/*KeyBinding settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.camera_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.camera_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"category.photographica"
		));
		//?}
		// Wind-film key (unbound by default).
		//? if >=1.21.11 {
		/*KeyBinding windKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.wind_film",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_V,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding windKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.wind_film",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_V,
				"category.photographica"
		));
		//?}
		// Load SD card key (unbound by default).
		//? if >=1.21.11 {
		/*KeyBinding loadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.load_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_U,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding loadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.load_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_U,
				"category.photographica"
		));
		//?}
		// Unload SD card key (unbound by default).
		//? if >=1.21.11 {
		/*KeyBinding unloadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.unload_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_Y,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding unloadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.unload_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_Y,
				"category.photographica"
		));
		//?}
		// Stop-recording key (default G).  Always stops an in-progress recording —
		// works for handheld and for tripod recording where the view is locked to
		// the stand, so the player is never trapped without a way to stop.
		//? if >=1.21.11 {
		/*KeyBinding stopRecordingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.stop_recording",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding stopRecordingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.stop_recording",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"category.photographica"
		));
		//?}
		// Portrait / landscape orientation toggle (default V).
		//? if >=1.21.11 {
		/*KeyBinding orientationKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.orientation",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_B,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding orientationKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.orientation",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_B,
				"category.photographica"
		));
		//?}
		// Lock drone camera in place / hand movement back to the player (default C) — same key
		// and same tripod-lock behaviour as snapmatica's freecam. Pressing it again resumes
		// flying; a full exit back to the player's own view happens via Esc (the pause menu),
		// handled inside DronePilot.tick() itself.
		//? if >=1.21.11 {
		/*KeyBinding droneLockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.drone_release",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_C,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding droneLockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.drone_release",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_C,
				"category.photographica"
		));
		//?}
		// Fires the mounted camera directly while piloting (default X) — mouse clicks are
		// blocked outright while flying (see MouseMixin), so the normal handheld shutter (a
		// right-click) can never reach it; this is the fast path instead of needing to open the
		// full settings screen and click 撮影 every time.
		//? if >=1.21.11 {
		/*KeyBinding droneShutterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.drone_shutter",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_X,
				PHOTOGRAPHICA_CATEGORY
		));
		*///?} else {
		KeyBinding droneShutterKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.drone_shutter",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_X,
				"category.photographica"
		));
		//?}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			AutoCamera.tick(client);
			DronePilot.tick(client);
			while (droneShutterKey.wasPressed()) {
				DronePilot.triggerCapture(client);
			}
			while (droneLockKey.wasPressed()) {
				// Only meaningful while actually piloting — flying a drone back into range and
				// re-pairing the remote is now the only way back in, matching the rest of the
				// realistic/range-limited remote system (see DroneRemoteItem/DronePilot); there's
				// no longer a "jump back into whatever drone you last flew from anywhere" shortcut.
				if (DronePilot.isActive()) {
					DronePilot.toggleLock(client);
				}
			}
			// Tripod recording films from the stand via a render-only camera redirect,
			// but the camera ENTITY must stay the player every tick or movement, look,
			// sneak and interaction input all die.  Assert it here as a safety net — a
			// cheap no-op when already correct — so the camera can never get stuck on
			// the stand and freeze the player.
			if (VideoRecorder.getRecordingArmorStandEntityId() >= 0
					&& client.player != null
					&& client.getCameraEntity() != client.player) {
				client.setCameraEntity(client.player);
			}
			// If the armor stand a tripod-recording is attached to is destroyed,
			// stop recording gracefully.
			int standId = VideoRecorder.getRecordingArmorStandEntityId();
			if (standId >= 0 && client.world != null
					&& client.world.getEntityById(standId) == null) {
				VideoRecorder.stopRecording();
			}
			if (client.player == null) return;
			while (stopRecordingKey.wasPressed()) {
				if (VideoRecorder.isRecording()) {
					VideoRecorder.stopRecording();
				}
			}
			if (settingsKey.wasPressed()) {
				int recStandId = VideoRecorder.getRecordingArmorStandEntityId();
				dev.hitom.photographica.entity.DroneEntity pilotedDrone = null;
				if (DronePilot.isActive() && client.world != null
						&& client.world.getEntityById(DronePilot.droneEntityId())
								instanceof dev.hitom.photographica.entity.DroneEntity d) {
					pilotedDrone = d;
				}
				if (recStandId >= 0) {
					// Armor-stand recording active: open stop screen even without camera in hand
					client.setScreen(new VideoCameraScreen(VideoRecorder.getRecordingStack(), recStandId));
				} else if (pilotedDrone != null && !pilotedDrone.getEquippedCamera().isEmpty()) {
					// Piloting a drone: open its built-in camera's settings, using the same
					// screen the armor-stand tripod uses (armorStandEntityId repurposed to mean
					// "whichever entity is carrying this camera"). Always a plain CameraItem —
					// the drone's camera is fixed equipment (DroneEntity#createBuiltInCamera)
					// and nothing can swap it for a film or video body.
					client.setScreen(new CameraScreen(pilotedDrone.getEquippedCamera(), pilotedDrone.getId()));
				} else {
					ItemStack stack = client.player.getMainHandStack();
					if (!openCameraScreen(stack)) {
						openCameraScreen(client.player.getOffHandStack());
					}
				}
			}
			if (windKey.wasPressed()) {
				ItemStack stack = client.player.getMainHandStack();
				if (!(stack.getItem() instanceof FilmCameraItem)) {
					stack = client.player.getOffHandStack();
				}
				if (stack.getItem() instanceof FilmCameraItem) {
					ClientPlayNetworking.send(new WindFilmPayload());
					//? if <1.21.11 {
					client.getSoundManager().play(PositionedSoundInstance.master(
							SoundEvents.BLOCK_LEVER_CLICK, 0.7f, 1.6f));
					//?}
				}
			}
			if (loadSdCardKey.wasPressed()) {
				ClientPlayNetworking.send(new LoadSdCardPayload());
			}
			if (unloadSdCardKey.wasPressed()) {
				ClientPlayNetworking.send(new UnloadSdCardPayload());
			}
			while (orientationKey.wasPressed()) {
				dev.hitom.photographica.client.hud.ViewfinderHud.portraitOrientation =
						!dev.hitom.photographica.client.hud.ViewfinderHud.portraitOrientation;
			}
		});

		HudRenderCallback.EVENT.register(ViewfinderHud::render);
		HudRenderCallback.EVENT.register(VideoRecorderHud::render);
		HudRenderCallback.EVENT.register(dev.hitom.photographica.client.hud.DroneSignalHud::render);

		// Leaving a world has to clear the client-side state machines, because all of it is
		// static and none of it is otherwise tied to a world's lifetime. Piloting state is the
		// dangerous one: DronePilot.isActive() staying true into the next world leaves the
		// camera mixin driving the view from a stale position, mouse look and clicks cancelled,
		// and most of the HUD suppressed — a near-unrecoverable state on join.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			DronePilot.reset();
			PhotoCapture.resetOnDisconnect();
			// Photo textures are GPU-resident and keyed by a UUID that means nothing on the
			// next server. Without this they accumulate for the whole game session (each is a
			// full-size RGBA texture), and — more visibly — a photo left in the `fetching` set
			// because its request was cut short by this very disconnect would stay stuck
			// "loading" forever, even after rejoining.
			dev.hitom.photographica.client.render.PhotoTextureCache.clear();
			// Not a reset but a real stop: it finishes encoding whatever was already filmed
			// (so the footage isn't lost), and restores the smooth-camera option it borrowed —
			// a global setting that would otherwise stay flipped for the rest of the session.
			// Also clears the tripod recording id, which drives the FOV override, the camera
			// redirect and the hidden hand. No-ops when nothing is recording.
			VideoRecorder.stopRecording();
		});
		HudRenderCallback.EVENT.register((ctx, tick) -> {
			long now = System.currentTimeMillis();

			if (PhotoCapture.secondClickAtMs > 0 && now >= PhotoCapture.secondClickAtMs) {
				PhotoCapture.playMirrorDownClick();
				PhotoCapture.secondClickAtMs = 0;
			}

			int sw = ctx.getScaledWindowWidth();
			int sh = ctx.getScaledWindowHeight();

			if (now < PhotoCapture.mirrorEndMs) {
				ctx.fill(0, 0, sw, sh, 0xFF000000);
				return;
			}
			if (now < PhotoCapture.flashEndMs) {
				long duration = PhotoCapture.flashEndMs - PhotoCapture.mirrorEndMs;
				if (duration > 0) {
					long remaining = PhotoCapture.flashEndMs - now;
					int alpha = (int) Math.min(200L, (remaining * 200L) / duration);
					if (alpha > 0) {
						int color = (alpha << 24) | 0x00FFFFFF;
						ctx.fill(0, 0, sw, sh, color);
					}
				}
			}
		});

		//? if >=1.21.11 {
		/*net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_MAIN.register(ctx -> {
			PhotoCapture.onWorldRenderEnd();
			VideoRecorder.onWorldRenderEnd();
		});
		*///?} else {
		WorldRenderEvents.LAST.register(ctx -> {
			PhotoCapture.onWorldRenderEnd();
			VideoRecorder.onWorldRenderEnd();
		});
		//?}

		//? if >=1.21.11 {
		/*BlockEntityRendererRegistry.register(ModBlockEntities.PHOTO_FRAME,
				PhotoFrameBlockEntityRenderer::new);
		BlockEntityRendererRegistry.register(ModBlockEntities.PHOTO_STAND,
				PhotoStandBlockEntityRenderer::new);
		*///?} else {
		BlockEntityRendererFactories.register(ModBlockEntities.PHOTO_FRAME,
				PhotoFrameBlockEntityRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.PHOTO_STAND,
				PhotoStandBlockEntityRenderer::new);
		//?}

		net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(
				dev.hitom.photographica.client.render.DroneEntityModel.LAYER,
				dev.hitom.photographica.client.render.DroneEntityModel::getTexturedModelData);
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
				dev.hitom.photographica.registry.ModEntities.DRONE,
				dev.hitom.photographica.client.render.DroneEntityRenderer::new);

		// Render all four camera item models on the player's chest when worn.
		// Uses the humanoid body bone for correct rotation with body/head animations.
		//? if <1.21.4 {
		ArmorRenderer.register((matrices, vertexConsumers, stack, entity, slot, light, contextModel) -> {
			if (slot != EquipmentSlot.CHEST) return;
			matrices.push();
			// Align with the body's current rotation (handles swimming, crawling, etc.)
			contextModel.body.rotate(matrices);
			// Position: center of chest front face, slightly raised
			matrices.translate(0.0, 0.12, -0.175);
			// Item models render "upside-down" in FIXED mode; flip to correct orientation
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f));
			// Scale down to about 35% of a full block so it looks worn, not oversized
			matrices.scale(0.35f, 0.35f, 0.35f);
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.getItemRenderer().renderItem(
					stack,
					ModelTransformationMode.FIXED,
					light, OverlayTexture.DEFAULT_UV,
					matrices, vertexConsumers,
					entity.getWorld(), entity.getId());
			matrices.pop();
		}, ModItems.VIDEO_CAMERA, ModItems.CAMERA, ModItems.MIRRORLESS_CAMERA, ModItems.FILM_CAMERA);
		//?}
		//? if >=1.21.11 {
		/*ArmorRenderer.register((matrices, queue, stack, state, slot, light, contextModel) -> {
			if (slot != EquipmentSlot.CHEST) return;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			matrices.push();
			// Align with the body's current rotation (handles swimming, crawling, etc.)
			contextModel.body.applyTransform(matrices);
			matrices.translate(0.0, 0.12, -0.175);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f));
			matrices.scale(0.35f, 0.35f, 0.35f);
			net.minecraft.client.render.item.ItemRenderState itemState =
					new net.minecraft.client.render.item.ItemRenderState();
			mc.getItemModelManager().updateForLivingEntity(
					itemState, stack, net.minecraft.item.ItemDisplayContext.FIXED, mc.player);
			itemState.render(matrices, queue, light, OverlayTexture.DEFAULT_UV, 0);
			matrices.pop();
		}, ModItems.VIDEO_CAMERA, ModItems.CAMERA, ModItems.MIRRORLESS_CAMERA, ModItems.FILM_CAMERA);
		*///?}

		// Discard cached photo textures when disconnecting so stale GPU resources are freed.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PhotoTextureCache.clear());
		// Recording temp frames are only ever cleaned by the post-process thread, so anything
		// interrupted by a crash or a force-quit stays on disk forever. Swept once at startup,
		// where no recording can be in flight.
		VideoRecorder.sweepOrphanedTempDirs();
	}

	/** Opens the settings screen for whichever camera type is in the given stack. */
	private static boolean openCameraScreen(ItemStack stack) {
		if (stack.getItem() instanceof MirrorlessCameraItem) {
			MirrorlessCameraItem.clientOpenScreen.accept(stack);
			return true;
		}
		if (stack.getItem() instanceof CameraItem) {
			CameraItem.clientOpenScreen.accept(stack);
			return true;
		}
		if (stack.getItem() instanceof FilmCameraItem) {
			FilmCameraItem.clientOpenScreen.accept(stack);
			return true;
		}
		if (stack.getItem() instanceof VideoCameraItem) {
			VideoCameraItem.clientOpenScreen.accept(stack);
			return true;
		}
		return false;
	}
}
