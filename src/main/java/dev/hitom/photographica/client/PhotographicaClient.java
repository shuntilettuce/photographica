package dev.hitom.photographica.client;

import dev.hitom.photographica.client.hud.ViewfinderHud;
import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.PhotoViewerScreen;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.PhotoItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public class PhotographicaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CameraItem.clientOpenScreen = stack ->
				MinecraftClient.getInstance().setScreen(new CameraScreen(stack));
		CameraItem.clientTakePhoto = PhotoCapture::take;
		PhotoItem.clientOpenViewer = data ->
				MinecraftClient.getInstance().setScreen(new PhotoViewerScreen(data));

		// G key (rebindable) → open camera settings screen while holding a camera
		KeyBinding settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.photographica.camera_settings",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				"category.photographica"
		));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!settingsKey.wasPressed() || client.player == null) return;
			ItemStack stack = client.player.getMainHandStack();
			if (!(stack.getItem() instanceof CameraItem)) {
				stack = client.player.getOffHandStack();
				if (!(stack.getItem() instanceof CameraItem)) return;
			}
			CameraItem.clientOpenScreen.accept(stack);
		});

		// Viewfinder draws first, then the shutter flash overlay sits on top.
		HudRenderCallback.EVENT.register(ViewfinderHud::render);
		HudRenderCallback.EVENT.register((ctx, tick) -> {
			long now = System.currentTimeMillis();

			// Mirror-down click after mirror-up
			if (PhotoCapture.secondClickAtMs > 0 && now >= PhotoCapture.secondClickAtMs) {
				PhotoCapture.playMirrorDownClick();
				PhotoCapture.secondClickAtMs = 0;
			}

			int sw = ctx.getScaledWindowWidth();
			int sh = ctx.getScaledWindowHeight();

			// Mirror-up: full black
			if (now < PhotoCapture.mirrorEndMs) {
				ctx.fill(0, 0, sw, sh, 0xFF000000);
				return;
			}
			// Flash: white fading from start of flash window to flashEndMs
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

		// Capture must happen before the hand and HUD render, so we hook into
		// the end of the world render phase. The framebuffer at that point has
		// only the world drawn.
		WorldRenderEvents.LAST.register(ctx -> PhotoCapture.onWorldRenderEnd());
	}
}
