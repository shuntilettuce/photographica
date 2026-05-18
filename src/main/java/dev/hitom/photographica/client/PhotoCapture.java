package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.network.CreatePhotoPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Photo capture pipeline. The screenshot is taken at WorldRenderEvents.LAST so
 * the framebuffer only contains the rendered world — no hand model, no vanilla
 * HUD, no viewfinder overlay. The captured image is then cropped to 3:2 to
 * match the viewfinder framing the player composed with.
 */
@Environment(EnvType.CLIENT)
public final class PhotoCapture {
	private PhotoCapture() {}

	private static final long COOLDOWN_MS = 700L;

	/** Mirror-flip black duration (ms). */
	public static final long MIRROR_DURATION_MS = 55L;
	/** Total flash overlay duration from start of mirror to fully transparent (ms). */
	public static final long FLASH_TOTAL_MS = 230L;
	/** When the mirror-down click should fire after the press (ms). */
	private static final long MIRROR_DOWN_DELAY_MS = 95L;

	private static volatile long lastCaptureMs = 0L;
	private static volatile UUID pendingId = null;
	private static volatile CameraSettings pendingSettings = null;

	// Animation timestamps (epoch ms). Read by the HUD overlay callback.
	public static volatile long mirrorEndMs = 0L;
	public static volatile long flashEndMs = 0L;
	public static volatile long secondClickAtMs = 0L;

	/** Called when the player presses the shutter (game thread). */
	public static void take(ItemStack cameraStack) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;

		CameraSettings settings = CameraItem.getSettings(cameraStack);
		if (!LensKind.hasLens(settings.lensType())) {
			mc.player.sendMessage(Text.literal("⚠ レンズが取り付けられていません"), true);
			mc.getSoundManager().play(PositionedSoundInstance.master(
					SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.6f, 0.8f));
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastCaptureMs < COOLDOWN_MS) return;
		if (pendingId != null) return;
		lastCaptureMs = now;

		pendingSettings = settings;
		pendingId = UUID.randomUUID();

		mirrorEndMs = now + MIRROR_DURATION_MS;
		flashEndMs = now + FLASH_TOTAL_MS;
		secondClickAtMs = now + MIRROR_DOWN_DELAY_MS;

		// Mirror-up / shutter-open click.
		mc.getSoundManager().play(PositionedSoundInstance.master(
				SoundEvents.BLOCK_TRIPWIRE_CLICK_ON, 1.5f, 0.9f));
	}

	/** Called from WorldRenderEvents.LAST every frame; performs the capture if pending. */
	public static void onWorldRenderEnd() {
		if (pendingId == null) return;
		UUID id = pendingId;
		CameraSettings settings = pendingSettings;
		pendingId = null;
		pendingSettings = null;

		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer fb = mc.getFramebuffer();
		NativeImage raw = ScreenshotRecorder.takeScreenshot(fb);

		NativeImage cropped = null;
		NativeImage downsampled = null;
		try {
			cropped = cropTo3to2(raw);
			// Box-filter downsample to a sensible save size — at the display sizes the
			// viewer typically uses (~1200–1300 physical px wide), saving at 1280
			// puts the displayed image at near-1:1 with the source, so it stays crisp.
			downsampled = boxDownsample(cropped, 1280);
			File dir = new File(mc.runDirectory, "photographica/photos");
			if (!dir.exists() && !dir.mkdirs()) {
				Photographica.LOGGER.error("Could not create photo dir: {}", dir);
				return;
			}
			File outFile = new File(dir, id + ".png");
			downsampled.writeTo(outFile);
			Photographica.LOGGER.info("Photo saved: {} ({}x{})",
					outFile.getAbsolutePath(), downsampled.getWidth(), downsampled.getHeight());
		} catch (IOException e) {
			Photographica.LOGGER.error("Photo capture failed", e);
		} finally {
			if (downsampled != null && downsampled != cropped && downsampled != raw) downsampled.close();
			if (cropped != null && cropped != raw) cropped.close();
			raw.close();
		}

		ClientPlayNetworking.send(new CreatePhotoPayload(id, settings));

		if (mc.player != null) {
			mc.player.sendMessage(Text.literal("📸 撮影"), true);
		}
	}

	/** Called by the HUD callback when the mirror-down click is due. */
	public static void playMirrorDownClick() {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(
				SoundEvents.BLOCK_TRIPWIRE_CLICK_OFF, 1.3f, 1.0f));
	}

	/** Box-filter downsample to a max width (preserving aspect). Returns src if already small enough. */
	private static NativeImage boxDownsample(NativeImage src, int maxWidth) {
		int sw = src.getWidth();
		int sh = src.getHeight();
		if (sw <= maxWidth) return src;
		int dw = maxWidth;
		int dh = Math.max(1, Math.round((float) sh * dw / sw));
		NativeImage dst = new NativeImage(dw, dh, false);
		float xScale = (float) sw / dw;
		float yScale = (float) sh / dh;
		for (int y = 0; y < dh; y++) {
			int sy0 = (int) Math.floor(y * yScale);
			int sy1 = Math.min(sh, (int) Math.ceil((y + 1) * yScale));
			if (sy1 <= sy0) sy1 = sy0 + 1;
			for (int x = 0; x < dw; x++) {
				int sx0 = (int) Math.floor(x * xScale);
				int sx1 = Math.min(sw, (int) Math.ceil((x + 1) * xScale));
				if (sx1 <= sx0) sx1 = sx0 + 1;
				long ra = 0, ga = 0, ba = 0, aa = 0;
				int n = 0;
				for (int sy = sy0; sy < sy1; sy++) {
					for (int sx = sx0; sx < sx1; sx++) {
						int c = src.getColor(sx, sy);
						aa += (c >>> 24) & 0xFF;
						ba += (c >>> 16) & 0xFF;
						ga += (c >>> 8) & 0xFF;
						ra += c & 0xFF;
						n++;
					}
				}
				int color = (((int) (aa / n)) << 24)
						| (((int) (ba / n)) << 16)
						| (((int) (ga / n)) << 8)
						| ((int) (ra / n));
				dst.setColor(x, y, color);
			}
		}
		return dst;
	}

	/** Crops a NativeImage to 3:2 (centered). Returns a new image; caller must close both. */
	private static NativeImage cropTo3to2(NativeImage src) {
		int w = src.getWidth();
		int h = src.getHeight();
		float aspect = 3f / 2f;
		int targetW, targetH;
		if ((float) w / h > aspect) {
			targetH = h;
			targetW = Math.round(h * aspect);
		} else {
			targetW = w;
			targetH = Math.round(w / aspect);
		}
		if (targetW == w && targetH == h) return src;
		int offX = (w - targetW) / 2;
		int offY = (h - targetH) / 2;
		NativeImage dst = new NativeImage(targetW, targetH, false);
		for (int y = 0; y < targetH; y++) {
			for (int x = 0; x < targetW; x++) {
				dst.setColor(x, y, src.getColor(x + offX, y + offY));
			}
		}
		return dst;
	}
}
