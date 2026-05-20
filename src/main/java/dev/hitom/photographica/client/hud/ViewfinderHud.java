package dev.hitom.photographica.client.hud;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.item.MirrorlessCameraItem;
import dev.hitom.photographica.mixin.client.GameRendererAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * Camera viewfinder HUD overlay. Rendered when the player holds a camera in
 * either hand. Draws a 3:2 photo frame with dimmed bezels, corner brackets,
 * center focus reticle, exposure readout, and a recording dot.
 */
@Environment(EnvType.CLIENT)
public final class ViewfinderHud {
	private ViewfinderHud() {}

	private static final String[] SHUTTERS = {
			"30\"", "15\"", "8\"", "4\"", "2\"", "1\"",
			"1/2", "1/4", "1/8", "1/15", "1/30", "1/60",
			"1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"
	};

	private static final int COLOR_BEZEL    = 0xB8000000;
	private static final int COLOR_FRAME    = 0xFFFFFFFF;
	private static final int COLOR_FRAME_DIM = 0xFFC0C0C0;
	private static final int COLOR_TEXT     = 0xFFE8DCC4; // CREAM
	private static final int COLOR_TEXT_DIM = 0xFF9A8D72; // CREAM_DIM
	private static final int COLOR_GRID     = 0x60FFFFFF;
	// Safelight design palette (for meter and reticle)
	private static final int SAFELIGHT      = 0xFFC2362B;
	private static final int EMBER          = 0xFFE08A3C;

	public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;
		// Viewfinder only active while the player holds Shift
		if (!mc.player.isSneaking()) return;

		ItemStack stack = mc.player.getMainHandStack();
		boolean offhand = false;
		if (!isCamera(stack)) {
			stack = mc.player.getOffHandStack();
			offhand = true;
			if (!isCamera(stack)) return;
		}
		// Hide while a screen (camera GUI / inventory) is open
		if (mc.currentScreen != null) return;

		boolean isFilm = stack.getItem() instanceof FilmCameraItem;
		CameraSettings s = isFilm ? FilmCameraItem.getSettings(stack) : CameraItem.getSettings(stack);
		FilmRollData film = isFilm ? FilmCameraItem.getFilm(stack) : null;

		int sw = ctx.getScaledWindowWidth();
		int sh = ctx.getScaledWindowHeight();

		// Compute centered 3:2 frame
		float aspect = 3f / 2f;
		int frameH = (int) (sh * 0.86f);
		int frameW = (int) (frameH * aspect);
		if (frameW > sw * 0.94f) {
			frameW = (int) (sw * 0.94f);
			frameH = (int) (frameW / aspect);
		}
		int fx = (sw - frameW) / 2;
		int fy = (sh - frameH) / 2;
		int fx2 = fx + frameW;
		int fy2 = fy + frameH;

		// Mirrorless EVF: apply DoF blur to the rendered world BEFORE drawing any overlay.
		boolean isMirrorless = stack.getItem() instanceof MirrorlessCameraItem;
		if (isMirrorless) {
			applyEvfDofBlur(mc, s, tickCounter);
		}

		// Bezels (dim outside frame)
		ctx.fill(0, 0, sw, fy, COLOR_BEZEL);
		ctx.fill(0, fy2, sw, sh, COLOR_BEZEL);
		ctx.fill(0, fy, fx, fy2, COLOR_BEZEL);
		ctx.fill(fx2, fy, sw, fy2, COLOR_BEZEL);

		// Mirrorless EVF: live exposure tint and vignette inside the frame.
		if (isMirrorless) {
			renderEvfPreview(ctx, s, fx, fy, fx2, fy2);
		}

		// Corner brackets (L-shapes)
		int bl = 20;  // bracket length
		int bt = 2;   // bracket thickness
		drawBracket(ctx, fx, fy, bl, bt, +1, +1, COLOR_FRAME);
		drawBracket(ctx, fx2, fy, bl, bt, -1, +1, COLOR_FRAME);
		drawBracket(ctx, fx, fy2, bl, bt, +1, -1, COLOR_FRAME);
		drawBracket(ctx, fx2, fy2, bl, bt, -1, -1, COLOR_FRAME);

		// Rule-of-thirds guides (faint)
		int t1x = fx + frameW / 3;
		int t2x = fx + (frameW * 2) / 3;
		int t1y = fy + frameH / 3;
		int t2y = fy + (frameH * 2) / 3;
		ctx.fill(t1x, fy + 4, t1x + 1, fy2 - 4, COLOR_GRID);
		ctx.fill(t2x, fy + 4, t2x + 1, fy2 - 4, COLOR_GRID);
		ctx.fill(fx + 4, t1y, fx2 - 4, t1y + 1, COLOR_GRID);
		ctx.fill(fx + 4, t2y, fx2 - 4, t2y + 1, COLOR_GRID);

		// Center focus reticle — colour reflects whether the scene centre is in focus.
		int cx = sw / 2;
		int cy = sh / 2;
		int reticleColor = focusReticleColor(s);
		ctx.fill(cx - 10, cy, cx - 3, cy + 1, reticleColor);
		ctx.fill(cx + 3, cy, cx + 10, cy + 1, reticleColor);
		ctx.fill(cx, cy - 10, cx + 1, cy - 3, reticleColor);
		ctx.fill(cx, cy + 3, cx + 1, cy + 10, reticleColor);

		// Settings readout (bottom of frame) — shifted up to leave room for the exposure meter
		TextRenderer tr = mc.textRenderer;
		boolean hasLens = LensKind.hasLens(s.lensType());
		String focalPart = hasLens ? (s.focalLengthMm() + "mm") : "レンズなし";
		String exposure = String.format("F%s · %s · ISO%d · %s",
				formatFloat(s.aperture()),
				SHUTTERS[clampIdx(s.shutterSpeedIdx(), SHUTTERS.length)],
				s.iso(),
				focalPart);
		ctx.drawTextWithShadow(tr, Text.literal(exposure), fx + 6, fy2 - tr.fontHeight - 14, COLOR_TEXT);

		// Exposure meter — horizontal scale centred in the frame, ±3 EV range
		renderExposureMeter(ctx, s, fx, fx2, fy2);

		// Scroll hint (bottom-right, inside frame) — two lines
		boolean isZoom = dev.hitom.photographica.component.LensKind.isZoom(s.lensType());
		String hint1 = isZoom ? "⟳ zoom  Ctrl⟳ F値  Alt⟳ SS" : "Ctrl⟳ F値  Alt⟳ SS";
		String hint2 = "Ctrl+Alt⟳ MF距離";
		int hint1W = tr.getWidth(hint1);
		int hint2W = tr.getWidth(hint2);
		ctx.drawTextWithShadow(tr, Text.literal(hint1), fx2 - hint1W - 6, fy2 - tr.fontHeight * 2 - 16, 0x80FFFFFF);
		ctx.drawTextWithShadow(tr, Text.literal(hint2), fx2 - hint2W - 6, fy2 - tr.fontHeight - 14, 0x80FFFFFF);

		// Lens label (top-left of frame)
		ctx.drawTextWithShadow(tr, Text.literal(LensKind.displayName(s.lensType())),
				fx + 6, fy + 4, COLOR_TEXT_DIM);

		// Hand-shake warning — shown when shutter is slower than the safe speed (1/focal_length)
		if (hasLens) {
			double safeShutter = 1.0 / s.focalLengthMm();
			if (s.shutterSeconds() > safeShutter * 1.5) {
				ctx.drawTextWithShadow(tr, Text.literal("⚠ ブレ"),
						fx + 6, fy + 4 + tr.fontHeight + 2, 0xFFFF5555);
			}
		}

		// Exposure / focus mode indicator (e.g. "Av | AF") — top-left, below lens name
		String[] expLabels   = {"M", "Av", "Tv", "P"};
		String[] focusLabels = {"MF", "AF", "MOB"};
		String expLabel   = expLabels[Math.max(0, Math.min(expLabels.length - 1, s.exposureMode()))];
		String focusLabel = focusLabels[Math.max(0, Math.min(focusLabels.length - 1, s.focusMode()))];
		String modeStr = expLabel + " | " + focusLabel;
		int modeLabelY = fy + 4 + tr.fontHeight * 2 + 4;
		ctx.drawTextWithShadow(tr, Text.literal(modeStr), fx + 6, modeLabelY, 0xFFCCCCFF);

		// Hand indicator (top-right). Mirrorless shows "EVF" badge before the hand label.
		String handLabel = offhand ? "OFF" : "MAIN";
		String handPrefix = isMirrorless ? "§bEVF §r" : "";
		String handFull = handPrefix + handLabel;
		int handW = tr.getWidth(Text.literal(handFull));
		ctx.drawTextWithShadow(tr, Text.literal(handFull), fx2 - handW - 6, fy + 4, COLOR_TEXT_DIM);

		// Film state — frame counter and wind indicator (top-right of frame, below hand label)
		if (isFilm && film != null) {
			String filmLabel;
			int color;
			if (film.totalExposures() == 0) {
				filmLabel = "フィルム未装填";
				color = 0xFFFF5555;
			} else if (film.isExposed()) {
				// All frames used — show total count to match real camera
				filmLabel = "■ " + film.usedExposures() + "/" + film.totalExposures() + " 撮影済";
				color = 0xFFFFCC00;
			} else {
				// Frame counter: show current frame number (shots taken so far)
				String wind = film.wound() ? "§a●§r" : "§c○§r";
				filmLabel = wind + " " + film.usedExposures() + "/" + film.totalExposures();
				color = 0xFFFFFFFF;
			}
			int fw = tr.getWidth(Text.literal(filmLabel));
			ctx.drawTextWithShadow(tr, Text.literal(filmLabel),
					fx2 - fw - 6, fy + 4 + tr.fontHeight + 2, color);

			if (film.totalExposures() > 0 && !film.isExposed() && !film.wound()) {
				String w = "⚠ 巻き上げてください";
				int ww = tr.getWidth(w);
				ctx.drawTextWithShadow(tr, Text.literal(w),
						(fx + fx2 - ww) / 2, fy2 - tr.fontHeight - 32, 0xFFFFAA00);
			}
		}

		// Big "no lens" warning centered in the frame.
		if (!hasLens) {
			String warn = "⚠ レンズが取り付けられていません";
			int ww = tr.getWidth(warn);
			ctx.drawTextWithShadow(tr, Text.literal(warn),
					(fx + fx2 - ww) / 2, fy + frameH / 2 - tr.fontHeight - 2, 0xFFFF5555);
			String lensHint = "操作設定でキーを割り当てて → レンズ";
			int hw = tr.getWidth(lensHint);
			ctx.drawTextWithShadow(tr, Text.literal(lensHint),
					(fx + fx2 - hw) / 2, fy + frameH / 2 + 4, COLOR_TEXT_DIM);
		}
	}

	/**
	 * Applies a Gaussian blur to the framebuffer simulating shallow depth-of-field
	 * on the mirrorless EVF. Blur radius scales with aperture (wider = more blur)
	 * and focus distance (closer focus = stronger background blur).
	 * Called BEFORE any overlay elements are drawn so the UI stays crisp.
	 */
	private static void applyEvfDofBlur(MinecraftClient mc, CameraSettings s, RenderTickCounter tickCounter) {
		float blurRadius = evfDofBlurRadius(s);
		if (blurRadius < 0.5f) return;

		PostEffectProcessor pp = ((GameRendererAccessor) mc.gameRenderer).getBlurPostProcessor();
		if (pp == null) return;

		pp.setUniforms("Radius", blurRadius);
		pp.render(tickCounter.getLastFrameDuration());
		// Re-bind the main framebuffer so subsequent HUD drawing goes to the right target.
		mc.getFramebuffer().beginWrite(false);
	}

	/**
	 * Blur radius for the EVF DoF simulation.
	 * Formula: radius ∝ (1/f-number), amplified by how close the focus is set.
	 * At f/8+ the DoF is deep enough that no blur is applied.
	 */
	private static float evfDofBlurRadius(CameraSettings s) {
		float aperture = s.aperture();
		if (aperture >= 8.0f) return 0f;

		// Base radius: inversely proportional to f-number, scaled to ~8px at f/1.4
		float base = Math.min(8.0f, 11.2f / aperture - 1.0f);  // f/1.4→7.0  f/5.6→1.0  f/8→0.4→clamp
		if (base < 0.5f) return 0f;

		// Focus distance multiplier: close focus = full blur; infinity = reduced
		float focus = s.focusDistance();
		float focusMult;
		if (focus >= 999.0f) {
			focusMult = 0.25f; // focused at infinity — background nearly sharp
		} else if (focus <= 1.0f) {
			focusMult = 1.0f;  // macro / portrait distance — full bokeh
		} else {
			// Gentle falloff between 1m and 50m
			focusMult = Math.max(0.25f, 1.0f - (float) Math.log10(focus) / 2.0f);
		}

		return base * focusMult;
	}

	/**
	 * Renders a classic camera exposure meter at the bottom of the viewfinder.
	 * Scale spans −3 EV … 0 … +3 EV; the coloured pointer shows the deviation
	 * of the current settings from the reference exposure (F5.6 · 1/60 · ISO 400).
	 *   Green  : within ±0.7 EV  (correct exposure)
	 *   Yellow : ±0.7 – ±1.7 EV (slight under/over)
	 *   Red    : beyond ±1.7 EV  (significant under/over)
	 */
	private static void renderExposureMeter(DrawContext ctx, dev.hitom.photographica.component.CameraSettings s,
	                                        int fx, int fx2, int fy2) {
		final int METER_W = 120;
		int meterX  = (fx + fx2 - METER_W) / 2;
		int meterCx = meterX + METER_W / 2;
		int baseY   = fy2 - 5;
		float pixPerEv = METER_W / 6.0f; // ±3 EV spans the full width

		// Baseline
		ctx.fill(meterX, baseY, meterX + METER_W, baseY + 1, 0x80FFFFFF);

		// Tick marks: minor at every stop, major (taller) at 0
		for (int ev = -3; ev <= 3; ev++) {
			int tx = meterCx + (int)(ev * pixPerEv);
			int th = (ev == 0) ? 6 : 3;
			ctx.fill(tx, baseY - th, tx + 1, baseY + 1, 0xC0FFFFFF);
		}

		// Pointer — clamped to ±3.5 EV so it never flies off the scale
		double evDev   = s.evDeviation();
		float clamped  = (float) Math.max(-3.5, Math.min(3.5, evDev));
		int ptrX       = meterCx + (int)(clamped * pixPerEv);
		double absEv   = Math.abs(evDev);
		int ptrColor   = absEv <= 2.0 ? EMBER : SAFELIGHT;
		ctx.fill(ptrX - 1, baseY - 7, ptrX + 2, baseY + 2, ptrColor);
	}

	/**
	 * Draws an L-shaped corner bracket anchored at (ax, ay) extending in (dx, dy) direction.
	 */
	private static void drawBracket(DrawContext ctx, int ax, int ay, int len, int thick,
	                                int dx, int dy, int color) {
		// Horizontal stroke
		int hx1 = dx > 0 ? ax : ax - len;
		int hx2 = dx > 0 ? ax + len : ax;
		int hy1 = dy > 0 ? ay : ay - thick;
		int hy2 = dy > 0 ? ay + thick : ay;
		ctx.fill(hx1, hy1, hx2, hy2, color);
		// Vertical stroke
		int vx1 = dx > 0 ? ax : ax - thick;
		int vx2 = dx > 0 ? ax + thick : ax;
		int vy1 = dy > 0 ? ay : ay - len;
		int vy2 = dy > 0 ? ay + len : ay;
		ctx.fill(vx1, vy1, vx2, vy2, color);
	}

	/**
	 * Reticle colour based on how well the scene centre aligns with the focus distance.
	 * Uses the depth sampled by PhotoCapture.updateCenterDepth() each frame.
	 *
	 *   Green  : within DoF zone   (depth ≈ focusDist, scaled by aperture)
	 *   Yellow : moderately off
	 *   White  : lens not attached, or aperture too narrow to matter (f/8+)
	 */
	private static int focusReticleColor(dev.hitom.photographica.component.CameraSettings s) {
		if (!dev.hitom.photographica.component.LensKind.hasLens(s.lensType())) return COLOR_FRAME;
		if (s.aperture() >= 8.0f) return COLOR_FRAME; // deep DoF, colour unnecessary
		float focus = s.focusDistance();
		if (focus >= 999.0f) return COLOR_FRAME;       // infinity focus

		float sceneDepth = dev.hitom.photographica.client.PhotoCapture.lastSceneDepthBlocks;
		// DoF tolerance: wider aperture → tighter zone (real cameras behave this way)
		float tolerance = focus * s.aperture() * 0.08f;
		float diff = Math.abs(sceneDepth - focus);
		if (diff <= tolerance)           return 0xFF7CE67C; // green: in focus
		if (diff <= tolerance * 2.5f)    return 0xFFFFCC44; // yellow: close
		return 0xFFE04040;                                   // red: out of focus
	}

	/**
	 * EVF live preview overlays for mirrorless cameras.
	 *
	 * Two layers rendered inside the frame:
	 *   1. Exposure tint — black (underexposed) or white (overexposed) fill whose
	 *      opacity scales with EV deviation from zero.  Dead-zone ±0.5 EV.
	 *   2. Lens vignette — stepped dark gradient from all four edges; strength
	 *      mirrors the same aperture → vignette mapping used in the capture pipeline.
	 */
	private static void renderEvfPreview(DrawContext ctx, CameraSettings s,
	                                     int fx, int fy, int fx2, int fy2) {
		// --- Exposure tint ---
		double ev = s.evDeviation();
		double absEv = Math.abs(ev);
		if (absEv > 0.5) {
			double excess = Math.min(absEv - 0.5, 6.5);
			int alpha = (int) (excess * 22);
			alpha = Math.min(180, alpha);
			int tintColor = ev > 0
					? ((alpha << 24) | 0x00FFFFFF)  // white = overexposed
					: (alpha << 24);                 // black = underexposed
			ctx.fill(fx, fy, fx2, fy2, tintColor);
		}

		// --- Vignette ---
		float vigStr = evfVignetteStrength(s.aperture());
		if (vigStr > 0.01f) {
			int fw = fx2 - fx;
			int fh = fy2 - fy;
			// 8 bands from outermost (b=0) to innermost (b=7). Each band is a thin strip
			// along every edge; opacity decreases toward center. Corners accumulate all
			// overlapping bands and become the darkest area, matching real lens vignetting.
			for (int b = 0; b < 8; b++) {
				float t = (float) (8 - b) / 8.0f;        // 1.0 → 0.125
				int alpha = (int) (vigStr * 110 * t * t);
				if (alpha < 1) continue;
				int vc = (alpha << 24) & 0xFF000000;
				int bw = (8 - b) * fw / 32;
				int bh = (8 - b) * fh / 32;
				ctx.fill(fx,        fy, fx + bw,  fy2, vc);  // left
				ctx.fill(fx2 - bw, fy, fx2,       fy2, vc);  // right
				ctx.fill(fx,        fy, fx2, fy + bh,  vc);  // top
				ctx.fill(fx,  fy2 - bh, fx2, fy2,      vc);  // bottom
			}
		}
	}

	/** Vignette strength for EVF live preview. */
	private static float evfVignetteStrength(float aperture) {
		if (aperture <= 1.4f) return 0.90f;
		if (aperture <= 2.0f) return 0.72f;
		if (aperture <= 2.8f) return 0.55f;
		if (aperture <= 4.0f) return 0.38f;
		if (aperture <= 5.6f) return 0.22f;
		if (aperture <= 8.0f) return 0.11f;
		if (aperture <= 11.0f) return 0.05f;
		return 0.02f;
	}

	private static boolean isCamera(ItemStack stack) {
		return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
	}

	private static int clampIdx(int idx, int len) {
		if (idx < 0) return 0;
		if (idx >= len) return len - 1;
		return idx;
	}

	private static String formatFloat(float v) {
		if (v == (int) v) return String.valueOf((int) v);
		return String.format("%.1f", v);
	}
}
