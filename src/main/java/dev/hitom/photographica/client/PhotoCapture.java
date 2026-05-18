package dev.hitom.photographica.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.network.CreatePhotoPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Environment(EnvType.CLIENT)
public final class PhotoCapture {
	private PhotoCapture() {}

	private static long lastCaptureMs = 0L;
	private static final long COOLDOWN_MS = 500L;

	/** True while a screenshot render call is queued/executing — suppresses the viewfinder HUD. */
	public static volatile boolean capturing = false;

	public static void take(ItemStack cameraStack) {
		long now = System.currentTimeMillis();
		if (now - lastCaptureMs < COOLDOWN_MS) return;
		lastCaptureMs = now;

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;

		CameraSettings settings = CameraItem.getSettings(cameraStack);
		UUID id = UUID.randomUUID();

		// Suppress the HUD overlay before queueing so it is absent in the captured frame.
		capturing = true;
		RenderSystem.recordRenderCall(() -> doCapture(mc, id, settings));
	}

	private static void doCapture(MinecraftClient mc, UUID id, CameraSettings settings) {
		try {
			Framebuffer fb = mc.getFramebuffer();
			NativeImage img = ScreenshotRecorder.takeScreenshot(fb);
			try {
				File dir = new File(mc.runDirectory, "photographica/photos");
				if (!dir.exists() && !dir.mkdirs()) {
					Photographica.LOGGER.error("Could not create photo dir: {}", dir);
					return;
				}
				File out = new File(dir, id + ".png");
				img.writeTo(out);
				Photographica.LOGGER.info("Photo saved: {}", out.getAbsolutePath());
			} finally {
				img.close();
			}

			ClientPlayNetworking.send(new CreatePhotoPayload(id, settings));

			if (mc.player != null) {
				mc.player.sendMessage(Text.literal("📸 撮影"), true);
			}
		} catch (IOException e) {
			Photographica.LOGGER.error("Photo capture failed", e);
		} finally {
			capturing = false;
		}
	}
}
