package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.network.CreatePhotoPayload;
import dev.hitom.photographica.network.TakeFilmPhotoPayload;
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
import java.nio.FloatBuffer;
import java.util.Random;
import java.util.UUID;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

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

	/**
	 * Linear depth (in blocks) at the centre of the screen, updated every frame
	 * during WorldRenderEvents.LAST while the camera is held. Read by ViewfinderHud
	 * to colour the focus reticle.
	 */
	public static volatile float lastSceneDepthBlocks = 10.0f;

	/** True if the next capture should be routed through the film-camera flow (TakeFilmPhotoPayload). */
	private static volatile boolean pendingIsFilm = false;

	/** Called when the player presses the shutter (game thread). */
	public static void take(ItemStack cameraStack) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) return;

		boolean isFilm = cameraStack.getItem() instanceof FilmCameraItem;
		CameraSettings settings = isFilm
				? FilmCameraItem.getSettings(cameraStack)
				: CameraItem.getSettings(cameraStack);

		if (!LensKind.hasLens(settings.lensType())) {
			mc.player.sendMessage(Text.literal("⚠ レンズが取り付けられていません"), true);
			mc.getSoundManager().play(PositionedSoundInstance.master(
					SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.6f, 0.8f));
			return;
		}

		// Film-camera prerequisites: must have film, must be wound, must have frames left.
		if (isFilm) {
			FilmRollData film = FilmCameraItem.getFilm(cameraStack);
			if (film.totalExposures() == 0) {
				mc.player.sendMessage(Text.literal("⚠ フィルムが装填されていません"), true);
				mc.getSoundManager().play(PositionedSoundInstance.master(
						SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value(), 0.5f, 0.7f));
				return;
			}
			if (film.isExposed()) {
				mc.player.sendMessage(Text.literal("⚠ フィルム使用済み — 現像してください"), true);
				return;
			}
			if (!film.wound()) {
				mc.player.sendMessage(Text.literal("⚠ フィルムを巻き上げてください"), true);
				mc.getSoundManager().play(PositionedSoundInstance.master(
						SoundEvents.BLOCK_LEVER_CLICK, 0.5f, 0.9f));
				return;
			}
		}

		long now = System.currentTimeMillis();
		if (now - lastCaptureMs < COOLDOWN_MS) return;
		if (pendingId != null) return;
		lastCaptureMs = now;

		pendingSettings = settings;
		pendingId = UUID.randomUUID();
		pendingIsFilm = isFilm;

		mirrorEndMs = now + MIRROR_DURATION_MS;
		flashEndMs = now + FLASH_TOTAL_MS;
		secondClickAtMs = now + MIRROR_DOWN_DELAY_MS;

		// Shutter click — heavier and more mechanical on a film SLR.
		if (isFilm) {
			mc.getSoundManager().play(PositionedSoundInstance.master(
					SoundEvents.BLOCK_PISTON_CONTRACT, 1.2f, 1.4f));
		} else {
			mc.getSoundManager().play(PositionedSoundInstance.master(
					SoundEvents.BLOCK_TRIPWIRE_CLICK_ON, 1.5f, 0.9f));
		}
	}

	/** Called from WorldRenderEvents.LAST every frame; performs the capture if pending. */
	public static void onWorldRenderEnd() {
		MinecraftClient mc = MinecraftClient.getInstance();
		Framebuffer fb = mc.getFramebuffer();

		// Update centre-pixel depth for the viewfinder focus indicator (cheap: 1 pixel).
		updateCenterDepth(mc, fb);

		if (pendingId == null) return;
		UUID id = pendingId;
		CameraSettings settings = pendingSettings;
		boolean isFilm = pendingIsFilm;
		pendingId = null;
		pendingSettings = null;
		pendingIsFilm = false;

		// Read the full depth buffer when DoF will be applied.
		float[] linearDepth = null;
		int fbW = fb.textureWidth;
		int fbH = fb.textureHeight;
		if (LensKind.hasLens(settings.lensType())
				&& settings.aperture() <= 5.6f
				&& settings.focusDistance() < 999.0f) {
			linearDepth = readLinearDepth(fb, fbW, fbH);
		}

		NativeImage raw = ScreenshotRecorder.takeScreenshot(fb);

		NativeImage cropped = null;
		NativeImage downsampled = null;
		NativeImage processed = null;
		try {
			cropped = cropTo3to2(raw);
			downsampled = boxDownsample(cropped, 1280);
			processed = applyPhotographicEffects(downsampled, settings, linearDepth, fbW, fbH);
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

		if (isFilm) {
			ClientPlayNetworking.send(new TakeFilmPhotoPayload(id, settings));
			if (mc.player != null) {
				mc.player.sendMessage(Text.literal("📸 撮影 (フィルム — 巻き上げ待ち)"), true);
			}
		} else {
			ClientPlayNetworking.send(new CreatePhotoPayload(id, settings));
			if (mc.player != null) {
				mc.player.sendMessage(Text.literal("📸 撮影"), true);
			}
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
	 * Applies physically-motivated photographic effects:
	 *   1. Exposure scaling + highlight rolloff
	 *   2. Lens vignetting
	 *   3. ISO grain / chroma noise
	 *   4. Depth-of-field blur  (when linearDepth != null and aperture ≤ f/5.6)
	 *   5. Motion blur          (shutters ≤ 1/30 s)
	 *   6. Diffraction softening (f/16+)
	 *
	 * Returns a NEW NativeImage; src is NOT modified or closed.
	 */
	private static NativeImage applyPhotographicEffects(NativeImage src, CameraSettings settings,
	                                                    float[] linearDepth, int fbW, int fbH) {
		double t    = settings.shutterSeconds();
		double n    = settings.aperture();
		double s    = settings.iso();

		// Exposure multiplier relative to the reference (F5.6 · 1/60 · ISO 400).
		float mult = (float) (t * 60.0 * ((5.6 / n) * (5.6 / n)) * (s / 400.0));

		// Reciprocity failure: film loses sensitivity at long exposures (>= 1s).
		if (FilmKind.isFilm(settings.filmType()) && t >= 1.0) {
			mult *= reciprocityFactor(t);
		}

		int  w   = src.getWidth();
		int  h   = src.getHeight();
		float cx  = w * 0.5f;
		float cy  = h * 0.5f;
		float vig = apertureToVignette((float) n);

		float noiseSigma = isoToNoiseSigma((int) s);
		boolean filmStock = FilmKind.isFilm(settings.filmType());
		if (filmStock) {
			// Each film stock has a characteristic baseline grain.
			float baseGrain = switch (settings.filmType()) {
				case FilmKind.COLOR_100    -> 1.5f;  // fine-grain slide stock
				case FilmKind.BW_400       -> 5.5f;  // B&W: slightly more visible grain structure
				case FilmKind.COLOR_1600   -> 9.0f;  // push-processed look
				default                   -> 4.5f;  // COLOR_400 / COLOR_400_24
			};
			noiseSigma = Math.max(noiseSigma, baseGrain);
		}
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

				// Pass 1a': Film stock tonal & colour signature.
				if (filmStock) {
					int ft = settings.filmType();
					if (FilmKind.isBW(ft)) {
						// Desaturate using luminance weights, then boost contrast.
						int lum = clampCh((int)(red * 0.299f + green * 0.587f + blue * 0.114f));
						lum = bwContrast(lum);
						red = lum; green = lum; blue = lum;
					} else if (ft == FilmKind.COLOR_100) {
						// ISO 100: slightly cool/neutral, higher saturation, shadow lift
						red   = filmTone(red,   1.02f);
						green = filmTone(green, 1.02f);
						blue  = filmTone(blue,  1.00f);
						red   = boostSaturationChannel(red,   green, blue,  1.12f);
						green = boostSaturationChannel(green, red,   blue,  1.08f);
						blue  = boostSaturationChannel(blue,  red,   green, 1.10f);
					} else {
						// COLOR_400, COLOR_400_24, COLOR_1600: classic warm negative
						red   = filmTone(red,   1.05f);
						green = filmTone(green, 1.00f);
						blue  = filmTone(blue,  0.94f);
					}
				}

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

		// Pass 2: Depth-of-field blur
		NativeImage pass2;
		if (linearDepth != null && n <= 5.6 && settings.focusDistance() < 999.0f) {
			pass2 = applyDepthOfField(pass1, settings, linearDepth, w, h, fbW, fbH);
			pass1.close();
		} else {
			pass2 = pass1;
		}

		// Pass 3: Motion blur (slow shutters — simulates hand-camera shake)
		NativeImage pass3;
		if (t >= 1.0 / 30.0) {
			pass3 = applyMotionBlur(pass2, t, w, h);
			pass2.close();
		} else {
			pass3 = pass2;
		}

		// Pass 4: Diffraction softening at narrow apertures (f/16+)
		NativeImage pass4;
		if (n >= 16.0) {
			pass4 = applyBoxBlur3x3(pass3, w, h);
			pass3.close();
		} else {
			pass4 = pass3;
		}

		return pass4;
	}

	/**
	 * Per-channel film tonal mapping: lifts the deepest shadows, softens highlights,
	 * applies a colour-channel multiplier for the film's warm/cool bias.
	 *
	 *   shadow lift  : low values get a small additive bias (films never go true black)
	 *   highlight    : a soft asymptote prevents harsh clipping (negative-style rolloff)
	 *   colour bias  : per-channel gain (warm films boost red, cool films boost blue)
	 */
	private static int filmTone(int v, float channelGain) {
		float f = v;
		// Shadow lift: f' = f + 6*(1 - f/255), nudges very dark pixels up.
		f += 6.0f * (1.0f - f / 255.0f);
		// Channel bias.
		f *= channelGain;
		// Soft highlight rolloff above 180.
		if (f > 180.0f) {
			float ex = f - 180.0f;
			f = 180.0f + 70.0f * (1.0f - (float) Math.exp(-ex / 70.0f));
		}
		return clampCh(Math.round(f));
	}

	/**
	 * Reciprocity failure factor: film loses effective sensitivity on long exposures.
	 * Returns a multiplier < 1.0 that further dims the image.
	 * Reference: approximate Schwarzschild p-values for color negative film.
	 */
	private static float reciprocityFactor(double tSeconds) {
		if (tSeconds < 1.0)  return 1.0f;
		if (tSeconds < 2.0)  return 0.90f; // ~0.15 stop
		if (tSeconds < 4.0)  return 0.79f; // ~0.33 stop
		if (tSeconds < 8.0)  return 0.65f; // ~0.62 stop
		if (tSeconds < 15.0) return 0.50f; // 1 stop
		return 0.35f;                       // 30s: ~1.5 stops
	}

	/** B&W contrast S-curve: lifts midtones, crushes shadows, clips highlights. */
	private static int bwContrast(int v) {
		float f = v / 255.0f;
		// Sigmoid-like: increased contrast around mid-grey
		f = (float) (1.0 / (1.0 + Math.exp(-8.0 * (f - 0.5))));
		return clampCh(Math.round(f * 255.0f));
	}

	/**
	 * Nudge one channel away from the average (boosts saturation for that channel).
	 * factor > 1 increases distance from average, < 1 desaturates.
	 */
	private static int boostSaturationChannel(int ch, int ch2, int ch3, float factor) {
		int avg = (ch + ch2 + ch3) / 3;
		return clampCh(avg + (int)((ch - avg) * factor));
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

	private static boolean isAnyCamera(ItemStack stack) {
		return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
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

	// -------------------------------------------------------------------------
	// Depth-of-field
	// -------------------------------------------------------------------------

	/**
	 * Reads the depth buffer from the currently-bound framebuffer and returns a
	 * float array of linear depth values (in blocks).  Index layout: OpenGL
	 * convention (Y=0 at bottom), matching glReadPixels output directly.
	 *
	 * Near/far clip values are Minecraft defaults (0.05 / 512 blocks).
	 */
	private static float[] readLinearDepth(Framebuffer fb, int fbW, int fbH) {
		fb.beginWrite(false); // binds the FBO
		FloatBuffer buf = BufferUtils.createFloatBuffer(fbW * fbH);
		GL11.glReadPixels(0, 0, fbW, fbH, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);

		final float near = 0.05f;
		final float far  = 512.0f;
		float[] depth = new float[fbW * fbH];
		for (int i = 0; i < depth.length; i++) {
			float d   = buf.get(i);
			float ndc = 2.0f * d - 1.0f;
			depth[i]  = 2.0f * near * far / (far + near - ndc * (far - near));
		}
		return depth;
	}

	/**
	 * Samples the single centre pixel of the depth buffer and stores the linear
	 * depth in {@link #lastSceneDepthBlocks} for the viewfinder HUD.
	 * Only updates when the player is sneaking with a camera.
	 */
	private static void updateCenterDepth(MinecraftClient mc, Framebuffer fb) {
		if (mc.player == null || !mc.player.isSneaking()) return;
		ItemStack hand = mc.player.getMainHandStack();
		if (!isAnyCamera(hand)) {
			hand = mc.player.getOffHandStack();
			if (!isAnyCamera(hand)) return;
		}
		fb.beginWrite(false);
		int cx = fb.textureWidth  / 2;
		int cy = fb.textureHeight / 2; // OpenGL Y=0 is bottom → centre is fine
		FloatBuffer buf = BufferUtils.createFloatBuffer(1);
		GL11.glReadPixels(cx, cy, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
		float d   = buf.get(0);
		float ndc = 2.0f * d - 1.0f;
		final float near = 0.05f;
		final float far  = 512.0f;
		lastSceneDepthBlocks = 2.0f * near * far / (far + near - ndc * (far - near));
	}

	/**
	 * Depth-of-field blur.
	 *
	 * For each image pixel the circle-of-confusion (CoC) radius is derived from
	 * the depth buffer and the focus distance / aperture settings:
	 *
	 *   maxBlurPx  = 80 / aperture²          (wider aperture → larger blur)
	 *   coc(front) = (1 − depth/focus) × max  (foreground: linear increase)
	 *   coc(back)  = (r−1)/(r) × max          (background: asymptotic at max)
	 *                where r = depth/focus
	 *
	 * A single separable box-blur at maxBlurPx is blended against the sharp
	 * image using t = coc/maxBlurPx per pixel.
	 */
	private static NativeImage applyDepthOfField(NativeImage src, CameraSettings settings,
	                                              float[] linearDepth,
	                                              int iw, int ih, int fbW, int fbH) {
		float aperture  = settings.aperture();
		float focusDist = settings.focusDistance();

		// Maximum blur radius in pixels (at 1280px wide image).
		float maxBlurPx = 80.0f / (aperture * aperture);
		int   maxR      = Math.max(1, (int) Math.ceil(maxBlurPx));

		// Pre-compute per-pixel CoC radii using depth buffer.
		float[] cocMap = new float[iw * ih];
		for (int iy = 0; iy < ih; iy++) {
			for (int ix = 0; ix < iw; ix++) {
				// Map image pixel → framebuffer pixel (approximate; crop is centred).
				int fx    = Math.max(0, Math.min(fbW - 1, ix * fbW / iw));
				// NativeImage Y=0 top, OpenGL Y=0 bottom → flip fy.
				int fy_gl = Math.max(0, Math.min(fbH - 1, fbH - 1 - (iy * fbH / ih)));
				float depth = linearDepth[fy_gl * fbW + fx];

				float coc;
				float r = depth / focusDist;
				if (depth <= focusDist) {
					coc = (1.0f - r) * maxBlurPx;          // foreground
				} else {
					float bg = r - 1.0f;
					coc = (bg / (1.0f + bg)) * maxBlurPx;  // background (asymptotic)
				}
				cocMap[iy * iw + ix] = Math.min(coc, maxBlurPx);
			}
		}

		// Separable box blur at maxR (H then V pass).
		NativeImage hBlur = applyHBoxBlur(src, maxR, iw, ih);
		NativeImage blurred = applyVBoxBlur(hBlur, maxR, iw, ih);
		hBlur.close();

		// Blend sharp + blurred per pixel.
		NativeImage result = new NativeImage(iw, ih, false);
		for (int iy = 0; iy < ih; iy++) {
			for (int ix = 0; ix < iw; ix++) {
				float t  = cocMap[iy * iw + ix] / maxBlurPx; // 0=sharp, 1=fully blurred
				int   cs = src.getColor(ix, iy);
				int   cb = blurred.getColor(ix, iy);
				int ra = lerpCh((cs >>> 24) & 0xFF, (cb >>> 24) & 0xFF, t);
				int bl = lerpCh((cs >>> 16) & 0xFF, (cb >>> 16) & 0xFF, t);
				int gr = lerpCh((cs >>>  8) & 0xFF, (cb >>>  8) & 0xFF, t);
				int rd = lerpCh( cs         & 0xFF,  cb         & 0xFF, t);
				result.setColor(ix, iy, (ra << 24) | (bl << 16) | (gr << 8) | rd);
			}
		}
		blurred.close();
		return result;
	}

	private static int lerpCh(int a, int b, float t) {
		return clampCh(Math.round(a + (b - a) * t));
	}

	/** Horizontal separable box blur. */
	private static NativeImage applyHBoxBlur(NativeImage src, int radius, int w, int h) {
		NativeImage dst = new NativeImage(w, h, false);
		int diam = radius * 2 + 1;
		for (int y = 0; y < h; y++) {
			// Sliding-window accumulator (fast O(N) per row).
			long ra = 0, ga = 0, ba = 0, aa = 0;
			for (int dx = -radius; dx <= radius; dx++) {
				int c = src.getColor(Math.max(0, Math.min(w - 1, dx)), y);
				aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
				ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
			}
			for (int x = 0; x < w; x++) {
				dst.setColor(x, y, (((int)(aa/diam))<<24)|(((int)(ba/diam))<<16)
						|(((int)(ga/diam))<<8)|(int)(ra/diam));
				// Slide: remove left pixel, add right pixel.
				int rem = src.getColor(Math.max(0, x - radius), y);
				aa -= (rem >>> 24) & 0xFF; ba -= (rem >>> 16) & 0xFF;
				ga -= (rem >>>  8) & 0xFF; ra -=  rem         & 0xFF;
				int add = src.getColor(Math.min(w - 1, x + radius + 1), y);
				aa += (add >>> 24) & 0xFF; ba += (add >>> 16) & 0xFF;
				ga += (add >>>  8) & 0xFF; ra +=  add         & 0xFF;
			}
		}
		return dst;
	}

	/** Vertical separable box blur. */
	private static NativeImage applyVBoxBlur(NativeImage src, int radius, int w, int h) {
		NativeImage dst = new NativeImage(w, h, false);
		int diam = radius * 2 + 1;
		for (int x = 0; x < w; x++) {
			long ra = 0, ga = 0, ba = 0, aa = 0;
			for (int dy = -radius; dy <= radius; dy++) {
				int c = src.getColor(x, Math.max(0, Math.min(h - 1, dy)));
				aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
				ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
			}
			for (int y = 0; y < h; y++) {
				dst.setColor(x, y, (((int)(aa/diam))<<24)|(((int)(ba/diam))<<16)
						|(((int)(ga/diam))<<8)|(int)(ra/diam));
				int rem = src.getColor(x, Math.max(0, y - radius));
				aa -= (rem >>> 24) & 0xFF; ba -= (rem >>> 16) & 0xFF;
				ga -= (rem >>>  8) & 0xFF; ra -=  rem         & 0xFF;
				int add = src.getColor(x, Math.min(h - 1, y + radius + 1));
				aa += (add >>> 24) & 0xFF; ba += (add >>> 16) & 0xFF;
				ga += (add >>>  8) & 0xFF; ra +=  add         & 0xFF;
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
