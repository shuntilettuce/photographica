package dev.hitom.photographica.client;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Handles mouse-scroll camera adjustments while the viewfinder is active (player sneaking).
 *
 * Shift is always held while the viewfinder is active (sneaking), so it is NOT
 * used as a scroll modifier. Only Ctrl and Alt serve as modifiers:
 *
 *   Scroll           → focal length  (zoom lenses only)
 *   Ctrl  + Scroll   → aperture
 *   Alt   + Scroll   → shutter speed
 *   Ctrl+Alt + Scroll → focus distance (MF mode only)
 */
@Environment(EnvType.CLIENT)
public final class CameraScrollHandler {
	private CameraScrollHandler() {}

	private static final List<Float>   APERTURES    = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
	private static final List<Integer> ISOS         = List.of(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
	private static final List<Float>   FOCUS_VALUES = List.of(0.3f, 0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f, 20.0f, 50.0f, 999.0f);
	private static final int           SHUTTER_COUNT = 18;

	/** Positive delta = scroll up. Returns true if consumed. */
	public static boolean onScroll(double delta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.currentScreen != null) return false;
		if (!mc.player.isSneaking()) return false;

		ItemStack stack = mc.player.getMainHandStack();
		if (!isCamera(stack)) {
			stack = mc.player.getOffHandStack();
			if (!isCamera(stack)) return false;
		}

		CameraSettings s = stack.getItem() instanceof FilmCameraItem
				? FilmCameraItem.getSettings(stack)
				: CameraItem.getSettings(stack);
		int dir = delta > 0 ? 1 : -1;

		long win = mc.getWindow().getHandle();
		boolean ctrl = InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_LEFT_CONTROL)
				|| InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
		boolean alt  = InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_LEFT_ALT)
				|| InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_RIGHT_ALT);
		// Shift is always held (viewfinder baseline = sneaking), so it is not a modifier.

		CameraSettings updated;
		if (ctrl && alt) {
			updated = adjustFocusDistance(s, dir);
		} else if (ctrl) {
			updated = adjustAperture(s, dir);
		} else if (alt) {
			updated = adjustShutterSpeed(s, dir);
		} else {
			updated = adjustFocalLength(s, dir);
		}

		if (updated == s) return true; // already at limit — still consume scroll

		if (stack.getItem() instanceof FilmCameraItem) {
			FilmCameraItem.setSettings(stack, updated);
		} else {
			CameraItem.setSettings(stack, updated);
		}
		ClientPlayNetworking.send(new UpdateCameraSettingsPayload(updated));
		return true;
	}

	private static boolean isCamera(ItemStack stack) {
		return stack.getItem() instanceof CameraItem || stack.getItem() instanceof FilmCameraItem;
	}

	// -------------------------------------------------------------------------
	// Individual parameter adjusters
	// -------------------------------------------------------------------------

	private static CameraSettings adjustFocalLength(CameraSettings s, int dir) {
		if (!LensKind.isZoom(s.lensType())) return s;
		List<Integer> stops = LensKind.focalLengthStops(s.lensType());
		int idx = stops.indexOf(s.focalLengthMm());
		if (idx < 0) idx = 0;
		int newIdx = Math.max(0, Math.min(stops.size() - 1, idx + dir));
		if (newIdx == idx) return s;
		return new CameraSettings(s.aperture(), s.shutterSpeedIdx(), s.iso(),
				s.focusDistance(), stops.get(newIdx), s.lensType(),
				s.filmType(), s.remainingShots(), s.exposureMode(), s.focusMode(), s.autoWind());
	}

	private static CameraSettings adjustAperture(CameraSettings s, int dir) {
		// Scroll up → open aperture → lower f-number → decrease index
		int idx = nearestIdx(APERTURES, s.aperture());
		int newIdx = Math.max(0, Math.min(APERTURES.size() - 1, idx - dir));
		if (newIdx == idx) return s;
		return new CameraSettings(APERTURES.get(newIdx), s.shutterSpeedIdx(), s.iso(),
				s.focusDistance(), s.focalLengthMm(), s.lensType(),
				s.filmType(), s.remainingShots(), s.exposureMode(), s.focusMode(), s.autoWind());
	}

	private static CameraSettings adjustShutterSpeed(CameraSettings s, int dir) {
		if (s.exposureMode() == CameraSettings.EXP_AV || s.exposureMode() == CameraSettings.EXP_P)
			return s; // auto-controlled in these modes
		// Scroll up → faster shutter → higher index
		int newIdx = Math.max(0, Math.min(SHUTTER_COUNT - 1, s.shutterSpeedIdx() + dir));
		if (newIdx == s.shutterSpeedIdx()) return s;
		return s.withShutterIdx(newIdx);
	}

	private static CameraSettings adjustISO(CameraSettings s, int dir) {
		if (s.exposureMode() != CameraSettings.EXP_M) return s; // manual only
		int idx = nearestIdxInt(ISOS, s.iso());
		int newIdx = Math.max(0, Math.min(ISOS.size() - 1, idx + dir));
		if (ISOS.get(newIdx) == s.iso()) return s;
		return new CameraSettings(s.aperture(), s.shutterSpeedIdx(), ISOS.get(newIdx),
				s.focusDistance(), s.focalLengthMm(), s.lensType(),
				s.filmType(), s.remainingShots(), s.exposureMode(), s.focusMode(), s.autoWind());
	}

	private static CameraSettings adjustFocusDistance(CameraSettings s, int dir) {
		if (s.focusMode() != CameraSettings.FOCUS_MF) return s; // manual focus only
		int idx = nearestIdxFloat(FOCUS_VALUES, s.focusDistance());
		int newIdx = Math.max(0, Math.min(FOCUS_VALUES.size() - 1, idx + dir));
		if (FOCUS_VALUES.get(newIdx).equals(s.focusDistance())) return s;
		return s.withFocusDistance(FOCUS_VALUES.get(newIdx));
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private static int nearestIdx(List<Float> list, float v) {
		int best = 0; float bestDiff = Float.MAX_VALUE;
		for (int i = 0; i < list.size(); i++) {
			float d = Math.abs(list.get(i) - v);
			if (d < bestDiff) { bestDiff = d; best = i; }
		}
		return best;
	}

	private static int nearestIdxInt(List<Integer> list, int v) {
		int best = 0; int bestDiff = Integer.MAX_VALUE;
		for (int i = 0; i < list.size(); i++) {
			int d = Math.abs(list.get(i) - v);
			if (d < bestDiff) { bestDiff = d; best = i; }
		}
		return best;
	}

	private static int nearestIdxFloat(List<Float> list, float v) {
		int best = 0; float bestDiff = Float.MAX_VALUE;
		for (int i = 0; i < list.size(); i++) {
			float d = Math.abs(list.get(i) - v);
			if (d < bestDiff) { bestDiff = d; best = i; }
		}
		return best;
	}
}
