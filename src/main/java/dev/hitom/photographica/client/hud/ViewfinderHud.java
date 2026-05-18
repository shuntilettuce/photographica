package dev.hitom.photographica.client.hud;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
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

	private static final int COLOR_BEZEL = 0xB0000000;
	private static final int COLOR_FRAME = 0xFFFFFFFF;
	private static final int COLOR_FRAME_DIM = 0xFFC0C0C0;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_TEXT_DIM = 0xFFB0B0B0;
	private static final int COLOR_GRID = 0x60FFFFFF;

	public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) return;

		ItemStack stack = mc.player.getMainHandStack();
		boolean offhand = false;
		if (!(stack.getItem() instanceof CameraItem)) {
			stack = mc.player.getOffHandStack();
			offhand = true;
			if (!(stack.getItem() instanceof CameraItem)) return;
		}
		// Hide while a screen (camera GUI / inventory) is open
		if (mc.currentScreen != null) return;

		CameraSettings s = CameraItem.getSettings(stack);

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

		// Bezels (dim outside frame)
		ctx.fill(0, 0, sw, fy, COLOR_BEZEL);
		ctx.fill(0, fy2, sw, sh, COLOR_BEZEL);
		ctx.fill(0, fy, fx, fy2, COLOR_BEZEL);
		ctx.fill(fx2, fy, sw, fy2, COLOR_BEZEL);

		// Corner brackets (L-shapes)
		int bl = 14;  // bracket length
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

		// Center focus reticle (broken crosshair)
		int cx = sw / 2;
		int cy = sh / 2;
		ctx.fill(cx - 10, cy, cx - 3, cy + 1, COLOR_FRAME);
		ctx.fill(cx + 3, cy, cx + 10, cy + 1, COLOR_FRAME);
		ctx.fill(cx, cy - 10, cx + 1, cy - 3, COLOR_FRAME);
		ctx.fill(cx, cy + 3, cx + 1, cy + 10, COLOR_FRAME);

		// Settings readout (bottom of frame)
		TextRenderer tr = mc.textRenderer;
		boolean hasLens = LensKind.hasLens(s.lensType());
		String focalPart = hasLens ? (s.focalLengthMm() + "mm") : "レンズなし";
		String exposure = String.format("F%s · %s · ISO%d · %s",
				formatFloat(s.aperture()),
				SHUTTERS[clampIdx(s.shutterSpeedIdx(), SHUTTERS.length)],
				s.iso(),
				focalPart);
		ctx.drawTextWithShadow(tr, Text.literal(exposure), fx + 6, fy2 - tr.fontHeight - 4, COLOR_TEXT);

		// Lens label (top-left of frame)
		ctx.drawTextWithShadow(tr, Text.literal(LensKind.displayName(s.lensType())),
				fx + 6, fy + 4, COLOR_TEXT_DIM);

		// Hand indicator (top-right)
		String hand = offhand ? "OFF" : "MAIN";
		int handW = tr.getWidth(hand);
		ctx.drawTextWithShadow(tr, Text.literal(hand), fx2 - handW - 6, fy + 4, COLOR_TEXT_DIM);

		// Big "no lens" warning centered in the frame.
		if (!hasLens) {
			String warn = "⚠ レンズが取り付けられていません";
			int ww = tr.getWidth(warn);
			ctx.drawTextWithShadow(tr, Text.literal(warn),
					(fx + fx2 - ww) / 2, fy + frameH / 2 - tr.fontHeight - 2, 0xFFFF5555);
			String hint = "shift + 右クリックで設定 → レンズ";
			int hw = tr.getWidth(hint);
			ctx.drawTextWithShadow(tr, Text.literal(hint),
					(fx + fx2 - hw) / 2, fy + frameH / 2 + 4, COLOR_TEXT_DIM);
		}
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
