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
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.gui.screens.MenuScreens;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.Options;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.lwjgl.glfw.GLFW;

public class PhotographicaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		VideoCameraItem.clientToggleRecord = VideoRecorder::toggle;
		VideoCameraItem.clientOpenScreen = stack ->
				Minecraft.getInstance().setScreen(new VideoCameraScreen(stack));

		CameraItem.clientOpenScreen = stack ->
				Minecraft.getInstance().setScreen(new CameraScreen(stack));
		CameraItem.clientTakePhoto = PhotoCapture::take;

		MirrorlessCameraItem.clientOpenScreen = stack ->
				Minecraft.getInstance().setScreen(new CameraScreen(stack));
		MirrorlessCameraItem.clientTakePhoto = PhotoCapture::take;

		FilmCameraItem.clientOpenScreen = stack ->
				Minecraft.getInstance().setScreen(new FilmCameraScreen(stack));
		FilmCameraItem.clientTakePhoto = PhotoCapture::take;

		PhotoItem.clientOpenViewer = data ->
				Minecraft.getInstance().setScreen(new PhotoViewerScreen(data));

		DevelopedFilmItem.clientOpenFilmStrip = stack ->
				Minecraft.getInstance().setScreen(new FilmStripScreen(stack));

		MenuScreens.register(ModScreenHandlers.DARKROOM, DarkroomScreen::new);
		MenuScreens.register(ModScreenHandlers.PRINTER, PrinterScreen::new);
		MenuScreens.register(ModScreenHandlers.ENLARGER, EnlargerScreen::new);

		KeyMapping.Category photographicaCategory = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("photographica", "photographica"));
		KeyMapping settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.photographica.camera_settings",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));
		KeyMapping windKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.photographica.wind_film",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));
		KeyMapping loadSdCardKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.photographica.load_sd_card",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));
		KeyMapping unloadSdCardKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.photographica.unload_sd_card",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				photographicaCategory
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			AutoCamera.tick(client);
			// While recording from an armor stand, keep the camera entity pointing at
			// the stand. If the stand has been destroyed, stop recording gracefully.
			int standId = VideoRecorder.getRecordingArmorStandEntityId();
			if (standId >= 0 && client.level != null) {
				net.minecraft.world.entity.Entity stand = client.level.getEntity(standId);
				if (stand != null) {
					client.setCameraEntity(stand);
				} else {
					VideoRecorder.stopRecording();
				}
			}
			if (client.player == null) return;
			if (settingsKey.consumeClick()) {
				ItemStack stack = client.player.getMainHandItem();
				if (!openCameraScreen(stack)) {
					openCameraScreen(client.player.getOffhandItem());
				}
			}
			if (windKey.consumeClick()) {
				ItemStack stack = client.player.getMainHandItem();
				if (!(stack.getItem() instanceof FilmCameraItem)) {
					stack = client.player.getOffhandItem();
				}
				if (stack.getItem() instanceof FilmCameraItem) {
					ClientPlayNetworking.send(new WindFilmPayload());
					client.getSoundManager().play(SimpleSoundInstance.forUI(
							SoundEvents.LEVER_CLICK, 0.7f, 1.6f));
				}
			}
			if (loadSdCardKey.consumeClick()) {
				ClientPlayNetworking.send(new LoadSdCardPayload());
			}
			if (unloadSdCardKey.consumeClick()) {
				ClientPlayNetworking.send(new UnloadSdCardPayload());
			}
		});

		HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("photographica", "viewfinder"), ViewfinderHud::extractRenderState);
		HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("photographica", "video_recorder"), VideoRecorderHud::extractRenderState);
		HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath("photographica", "flash"), (ctx, tick) -> {
			long now = System.currentTimeMillis();

			if (PhotoCapture.secondClickAtMs > 0 && now >= PhotoCapture.secondClickAtMs) {
				PhotoCapture.playMirrorDownClick();
				PhotoCapture.secondClickAtMs = 0;
			}

			int sw = ctx.guiWidth();
			int sh = ctx.guiHeight();

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

		LevelRenderEvents.END_MAIN.register(ctx -> {
			PhotoCapture.onWorldRenderEnd();
			VideoRecorder.onWorldRenderEnd();
		});

		BlockEntityRenderers.register(ModBlockEntities.PHOTO_FRAME,
				PhotoFrameBlockEntityRenderer::new);
		BlockEntityRenderers.register(ModBlockEntities.PHOTO_STAND,
				PhotoStandBlockEntityRenderer::new);

		// Render all four camera item models on the player's chest when worn.
		// Uses the humanoid body bone for correct rotation with body/head animations.
		ArmorRenderer.register((matrices, queue, stack, state, slot, light, contextModel) -> {
			if (slot != EquipmentSlot.CHEST) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) return;
			matrices.pushPose();
			// Align with the body's current rotation
			contextModel.body.translateAndRotate(matrices);
			matrices.translate(0.0, 0.12, -0.175);
			matrices.mulPose(com.mojang.math.Axis.XP.rotationDegrees(180f));
			matrices.scale(0.35f, 0.35f, 0.35f);
			// Render items through an ItemStackRenderState submitted to the queue.
			net.minecraft.client.renderer.item.ItemStackRenderState itemState =
					new net.minecraft.client.renderer.item.ItemStackRenderState();
			mc.getItemModelResolver().updateForLiving(
					itemState, stack, ItemDisplayContext.FIXED, mc.player);
			itemState.submit(matrices, queue, light, OverlayTexture.NO_OVERLAY, 0);
			matrices.popPose();
		}, ModItems.VIDEO_CAMERA, ModItems.CAMERA, ModItems.MIRRORLESS_CAMERA, ModItems.FILM_CAMERA);

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
