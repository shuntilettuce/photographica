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
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//? if >=1.21.11 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.util.Identifier;*/
//?} else {
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?}
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.OverlayTexture;
//? if >=1.21.11 {
/*import net.minecraft.item.ItemDisplayContext;*/
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

public class PhotographicaClient implements ClientModInitializer {
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

		HandledScreens.register(ModScreenHandlers.DARKROOM, DarkroomScreen::new);
		HandledScreens.register(ModScreenHandlers.PRINTER, PrinterScreen::new);
		HandledScreens.register(ModScreenHandlers.ENLARGER, EnlargerScreen::new);

		//? if >=1.21.11 {
		/*KeyBinding.Category photographicaCategory = KeyBinding.Category.create(Identifier.of("photographica", "photographica"));
		KeyBinding settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.camera_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));
		KeyBinding windKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.wind_film",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));
		KeyBinding loadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.load_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));
		KeyBinding unloadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.unload_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));*/
		//?} else {
		// Settings key (unbound by default).
		KeyBinding settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.camera_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				"category.photographica"
		));
		// Wind-film key (unbound by default).
		KeyBinding windKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.wind_film",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				"category.photographica"
		));
		// Load SD card key (unbound by default).
		KeyBinding loadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.load_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				"category.photographica"
		));
		// Unload SD card key (unbound by default).
		KeyBinding unloadSdCardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.unload_sd_card",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				"category.photographica"
		));
		//?}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			AutoCamera.tick(client);
			// While recording from an armor stand, keep the camera entity pointing at
			// the stand. If the stand has been destroyed, stop recording gracefully.
			int standId = VideoRecorder.getRecordingArmorStandEntityId();
			if (standId >= 0 && client.world != null) {
				net.minecraft.entity.Entity stand = client.world.getEntityById(standId);
				if (stand != null) {
					//? if >=1.21.11 {
					/*client.setCameraEntity(stand);*/
					//?} else {
					client.cameraEntity = stand;
					//?}
				} else {
					VideoRecorder.stopRecording();
				}
			}
			if (client.player == null) return;
			if (settingsKey.wasPressed()) {
				ItemStack stack = client.player.getMainHandStack();
				if (!openCameraScreen(stack)) {
					openCameraScreen(client.player.getOffHandStack());
				}
			}
			if (windKey.wasPressed()) {
				ItemStack stack = client.player.getMainHandStack();
				if (!(stack.getItem() instanceof FilmCameraItem)) {
					stack = client.player.getOffHandStack();
				}
				if (stack.getItem() instanceof FilmCameraItem) {
					ClientPlayNetworking.send(new WindFilmPayload());
					//? if >=1.21.11 {
					/*client.getSoundManager().play(PositionedSoundInstance.ui(
							SoundEvents.BLOCK_LEVER_CLICK, 0.7f, 1.6f));*/
					//?} else {
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
		});

		HudRenderCallback.EVENT.register(ViewfinderHud::render);
		HudRenderCallback.EVENT.register(VideoRecorderHud::render);
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
		/*WorldRenderEvents.END_MAIN.register(ctx -> {
			PhotoCapture.onWorldRenderEnd();
			VideoRecorder.onWorldRenderEnd();
		});*/
		//?} else {
		WorldRenderEvents.LAST.register(ctx -> {
			PhotoCapture.onWorldRenderEnd();
			VideoRecorder.onWorldRenderEnd();
		});
		//?}

		BlockEntityRendererFactories.register(ModBlockEntities.PHOTO_FRAME,
				PhotoFrameBlockEntityRenderer::new);
		BlockEntityRendererFactories.register(ModBlockEntities.PHOTO_STAND,
				PhotoStandBlockEntityRenderer::new);

		// Render all four camera item models on the player's chest when worn.
		// Uses the humanoid body bone for correct rotation with body/head animations.
		//? if >=1.21.11 {
		/*ArmorRenderer.register((matrices, queue, stack, state, slot, light, contextModel) -> {
			if (slot != EquipmentSlot.CHEST) return;
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.world == null || mc.player == null) return;
			matrices.push();
			// Align with the body's current rotation (1.21.11 renamed rotate→applyTransform)
			contextModel.body.applyTransform(matrices);
			matrices.translate(0.0, 0.12, -0.175);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f));
			matrices.scale(0.35f, 0.35f, 0.35f);
			// 1.21.11 renders items through an ItemRenderState submitted to the queue.
			// The camera model is static, so any living entity is fine as the model
			// context (it only supplies world/pos/seed, which don't affect this model).
			net.minecraft.client.render.item.ItemRenderState itemState =
					new net.minecraft.client.render.item.ItemRenderState();
			mc.getItemModelManager().updateForLivingEntity(
					itemState, stack, net.minecraft.item.ItemDisplayContext.FIXED, mc.player);
			itemState.render(matrices, queue, light, OverlayTexture.DEFAULT_UV, 0);
			matrices.pop();
		}, ModItems.VIDEO_CAMERA, ModItems.CAMERA, ModItems.MIRRORLESS_CAMERA, ModItems.FILM_CAMERA);*/
		//?} else if >=1.21.4 {
		/*ArmorRenderer.register((matrices, vertexConsumers, stack, state, slot, light, contextModel) -> {
			if (slot != EquipmentSlot.CHEST) return;
			matrices.push();
			contextModel.body.rotate(matrices);
			matrices.translate(0.0, 0.12, -0.175);
			matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f));
			matrices.scale(0.35f, 0.35f, 0.35f);
			MinecraftClient mc = MinecraftClient.getInstance();
			mc.getItemRenderer().renderItem(
					stack,
					ModelTransformationMode.FIXED,
					light, OverlayTexture.DEFAULT_UV,
					matrices, vertexConsumers,
					mc.world, 0);
			matrices.pop();
		}, ModItems.VIDEO_CAMERA, ModItems.CAMERA, ModItems.MIRRORLESS_CAMERA, ModItems.FILM_CAMERA);*/
		//?} else {
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

		// Discard cached photo textures when disconnecting so stale GPU resources are freed.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PhotoTextureCache.clear());
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
