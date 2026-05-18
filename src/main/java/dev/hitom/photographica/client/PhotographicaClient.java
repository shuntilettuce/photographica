package dev.hitom.photographica.client;

import dev.hitom.photographica.client.hud.ViewfinderHud;
import dev.hitom.photographica.client.screen.CameraScreen;
import dev.hitom.photographica.client.screen.PhotoViewerScreen;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.PhotoItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;

public class PhotographicaClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CameraItem.clientOpenScreen = stack ->
				MinecraftClient.getInstance().setScreen(new CameraScreen(stack));
		CameraItem.clientTakePhoto = PhotoCapture::take;
		PhotoItem.clientOpenViewer = data ->
				MinecraftClient.getInstance().setScreen(new PhotoViewerScreen(data));

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
