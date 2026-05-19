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
import java.util.Random;
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
		NativeImage processed = null;
		try {
			cropped = cropTo3to2(raw);
			downsampled = boxDownsample(cropped, 1280);
			processed = applyPhotographicEffects(downsampled, settings);
			File dir = new File(mc.runDirectory, "photographica/photos");
			if (!dir.exists() && !dir.mkdirs()) {
				Photographica.LOGGER.error("Could not create photo dir: {}", dir);
				return;
			}
			File outFile = new File(dir, id + ".png");
			processed.writeTo(outFile);
			Photographica.LOGGER.info("Photo saved: {} ({}x{})",
					outFile.getAbsolutePath(), processed.getWidth(), processed.getHeight());
		} catch (IOException e) {
			Photographica.LOGGER.error("Photo capture failed", e);
		} finally {
			if (processed != null) processed.close();
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

	// -------------------------------------------------------------------------
	// Photographic image effects
	// -------------------------------------------------------------------------

	/**
	 * Applies physically-motivated photographic effects in four passes:
	 *   1. Exposure scaling + highlight rolloff (aperture / SS / ISO triangle)
	 *   2. Lens vignetting (aperture-dependent peripheral darkening)
	 *   3. ISO grain / chroma noise
	 *   4. Motion blur (shutter speeds ≤ 1/30 s)
	 *   5. Diffraction softening (f/16 and narrower)
	 *
	 * Returns a NEW NativeImage; the caller is responsible for closing it.
	 * The src image is NOT modified and NOT closed.
	 */
	private static NativeImage applyPhotographicEffects(NativeImage src, CameraSettings settings) {
		double t    = settings.shutterSeconds();
		double n    = settings.aperture();
		double s    = settings.iso();

		// Exposure multiplier relative to the reference (F5.6 · 1/60 · ISO 400).
		// mult > 1 → overexposed; mult < 1 → underexposed.
		float mult = (float) (t * 60.0 * ((5.6 / n) * (5.6 / n)) * (s / 400.0));

		int  w   = src.getWidth();
		int  h   = src.getHeight();
		float cx  = w * 0.5f;
		float cy  = h * 0.5f;
		float vig = apertureToVignette((float) n);

		float noiseSigma = isoToNoiseSigma((int) s);
		Random rng = new Random();

		NativeImage pass1 = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int color = src.getColor(x, y);
				int a     = (color >>> 24) & 0xFF;
				int blue  = (color >>> 16) & 0xFF;
				int green = (color >>>  8) & 0xFF;
				int red   =  color         & 0xFF;

				// Pass 1a: Exposure (with smooth film-like highlight rolloff)
				red   = applyExposure(red,   mult);
				green = applyExposure(green, mult);
				blue  = applyExposure(blue,  mult);

				// Pass 1b: Vignetting — quadratic falloff, normalised so corners = vigStr
				float dx = (x - cx) / cx;
				float dy = (y - cy) / cy;
				// (dx²+dy²)*0.5 maps to 1.0 at corners
				float vFactor = Math.max(0.0f, 1.0f - vig * (dx * dx + dy * dy) * 0.5f);
				red   = clampCh((int)(red   * vFactor));
				green = clampCh((int)(green * vFactor));
				blue  = clampCh((int)(blue  * vFactor));

				// Pass 1c: ISO grain (luminance + chroma at high ISO)
				if (noiseSigma > 0.5f) {
					int lum = gaussNoise(rng, noiseSigma);
					red   = clampCh(red   + lum);
					green = clampCh(green + lum);
					blue  = clampCh(blue  + lum);
					if (s >= 1600) {
						float col = noiseSigma * 0.30f;
						red   = clampCh(red   + gaussNoise(rng, col));
						green = clampCh(green + gaussNoise(rng, col));
						blue  = clampCh(blue  + gaussNoise(rng, col));
					}
				}

				pass1.setColor(x, y, (a << 24) | (blue << 16) | (green << 8) | red);
			}
		}

		// Pass 2: Motion blur (slow shutters — simulates hand-camera shake)
		NativeImage pass2;
		if (t >= 1.0 / 30.0) {
			pass2 = applyMotionBlur(pass1, t, w, h);
			pass1.close();
		} else {
			pass2 = pass1;
		}

		// Pass 3: Diffraction softening at narrow apertures (f/16+)
		NativeImage pass3;
		if (n >= 16.0) {
			pass3 = applyBoxBlur3x3(pass2, w, h);
			pass2.close();
		} else {
			pass3 = pass2;
		}

		return pass3;
	}

	/** Exposure scaling with film-like highlight rolloff above ~78% brightness. */
	private static int applyExposure(int v, float mult) {
		float f = v * mult;
		if (f > 200.0f) {
			float excess = f - 200.0f;
			f = 200.0f + 55.0f * (1.0f - (float) Math.exp(-excess / 55.0f));
		}
		return clampCh((int) f);
	}

	/** ISO → luminance noise sigma (in pixel-level units 0–255). */
	private static float isoToNoiseSigma(int iso) {
		if (iso <=   100) return  0.0f;
		if (iso <=   200) return  1.5f;
		if (iso <=   400) return  3.0f;
		if (iso <=   800) return  6.0f;
		if (iso <=  1600) return 11.0f;
		if (iso <=  3200) return 18.0f;
		if (iso <=  6400) return 28.0f;
		if (iso <= 12800) return 42.0f;
		return 60.0f; // ISO 25600
	}

	/** Aperture → vignette strength (0 = no vignetting, 1 = severe). */
	private static float apertureToVignette(float aperture) {
		if (aperture <=  1.4f) return 0.70f;
		if (aperture <=  2.0f) return 0.55f;
		if (aperture <=  2.8f) return 0.40f;
		if (aperture <=  4.0f) return 0.25f;
		if (aperture <=  5.6f) return 0.15f;
		if (aperture <=  8.0f) return 0.08f;
		if (aperture <= 11.0f) return 0.05f;
		return 0.03f;
	}

	/** Box-Muller Gaussian noise sample with the given standard deviation. */
	private static int gaussNoise(Random rng, float sigma) {
		double u1 = Math.max(1e-10, rng.nextDouble());
		double u2 = rng.nextDouble();
		double z  = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
		return (int) Math.round(z * sigma);
	}

	private static int clampCh(int v) {
		return Math.max(0, Math.min(255, v));
	}

	/** Horizontal motion blur — simulates camera shake during long exposures. */
	private static NativeImage applyMotionBlur(NativeImage src, double t, int w, int h) {
		int radius;
		if      (t >= 8.0)   radius = 60;
		else if (t >= 4.0)   radius = 45;
		else if (t >= 2.0)   radius = 32;
		else if (t >= 1.0)   radius = 22;
		else if (t >= 0.5)   radius = 14;
		else if (t >= 0.25)  radius = 9;
		else if (t >= 0.125) radius = 5;
		else                 radius = 3; // 1/30 s
		radius = Math.min(radius, w / 12);

		NativeImage dst = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				long ra = 0, ga = 0, ba = 0, aa = 0;
				int n = 0;
				for (int dx = -radius; dx <= radius; dx++) {
					int sx = Math.max(0, Math.min(w - 1, x + dx));
					int c  = src.getColor(sx, y);
					aa += (c >>> 24) & 0xFF;
					ba += (c >>> 16) & 0xFF;
					ga += (c >>>  8) & 0xFF;
					ra +=  c         & 0xFF;
					n++;
				}
				dst.setColor(x, y,
						(((int)(aa / n)) << 24) | (((int)(ba / n)) << 16)
						| (((int)(ga / n)) << 8) | (int)(ra / n));
			}
		}
		return dst;
	}

	/** 3×3 box blur used to simulate diffraction softening at f/16+. */
	private static NativeImage applyBoxBlur3x3(NativeImage src, int w, int h) {
		NativeImage dst = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				long ra = 0, ga = 0, ba = 0, aa = 0;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						int c = src.getColor(
								Math.max(0, Math.min(w - 1, x + dx)),
								Math.max(0, Math.min(h - 1, y + dy)));
						aa += (c >>> 24) & 0xFF;
						ba += (c >>> 16) & 0xFF;
						ga += (c >>>  8) & 0xFF;
						ra +=  c         & 0xFF;
					}
				}
				dst.setColor(x, y,
						(((int)(aa / 9)) << 24) | (((int)(ba / 9)) << 16)
						| (((int)(ga / 9)) << 8) | (int)(ra / 9));
			}
		}
		return dst;
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
