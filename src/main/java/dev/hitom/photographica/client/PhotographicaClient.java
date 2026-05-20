package dev.hitom.photographica.client;

import dev.hitom.photographica.client.hud.ViewfinderHud;
import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.DarkroomScreen;
import dev.hitom.photographica.client.screen.EnlargerScreen;
import dev.hitom.photographica.client.screen.FilmCameraScreen;
import dev.hitom.photographica.client.screen.PhotoViewerScreen;
import dev.hitom.photographica.client.screen.PrinterScreen;
import dev.hitom.photographica.registry.ModScreenHandlers;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.item.PhotoItem;
import dev.hitom.photographica.network.LoadSdCardPayload;
import dev.hitom.photographica.network.UnloadSdCardPayload;
import dev.hitom.photographica.network.WindFilmPayload;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import org.lwjgl.glfw.GLFW;

public class PhotographicaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
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

		HandledScreens.register(ModScreenHandlers.DARKROOM, DarkroomScreen::new);
		HandledScreens.register(ModScreenHandlers.PRINTER, PrinterScreen::new);
		HandledScreens.register(ModScreenHandlers.ENLARGER, EnlargerScreen::new);

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

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			AutoCamera.tick(client);
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
					client.getSoundManager().play(PositionedSoundInstance.master(
							SoundEvents.BLOCK_LEVER_CLICK, 0.7f, 1.6f));
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

		WorldRenderEvents.LAST.register(ctx -> PhotoCapture.onWorldRenderEnd());
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
		return false;
	}
}
