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
 *   Scroll            → focal length  (zoom in/out; zoom lenses only)
 *   Ctrl + Scroll     → aperture      (open / close)
 *
 * Called from MouseMixin. Returns true if the scroll event was consumed (should
 * not pass through to vanilla hotbar switching).
 */
@Environment(EnvType.CLIENT)
public final class CameraScrollHandler {
	private CameraScrollHandler() {}

	private static final List<Float> APERTURES =
			List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);

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

		CameraSettings updated = ctrl ? adjustAperture(s, dir) : adjustFocalLength(s, dir);
		if (updated == s) return true; // already at limit, still consume

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

	/**
	 * Scroll up → zoom in (longer focal length).
	 * Only effective when a zoom lens is mounted.
	 */
	private static CameraSettings adjustFocalLength(CameraSettings s, int dir) {
		if (!LensKind.isZoom(s.lensType())) return s;
		List<Integer> stops = LensKind.focalLengthStops(s.lensType());
		int idx = stops.indexOf(s.focalLengthMm());
		if (idx < 0) idx = 0;
		int newIdx = Math.max(0, Math.min(stops.size() - 1, idx + dir));
		if (newIdx == idx) return s;
		return new CameraSettings(s.aperture(), s.shutterSpeedIdx(), s.iso(),
				s.focusDistance(), stops.get(newIdx), s.lensType(),
				s.filmType(), s.remainingShots());
	}

	/**
	 * Ctrl+Scroll up → open aperture (lower f-number).
	 * Ctrl+Scroll down → close aperture (higher f-number).
	 */
	private static CameraSettings adjustAperture(CameraSettings s, int dir) {
		// Lower index = lower f-number = wider/open aperture.
		// Scroll up (dir=+1) opens aperture → decrease index.
		int idx = nearestIdx(APERTURES, s.aperture());
		int newIdx = Math.max(0, Math.min(APERTURES.size() - 1, idx - dir));
		if (newIdx == idx) return s;
		return new CameraSettings(APERTURES.get(newIdx), s.shutterSpeedIdx(), s.iso(),
				s.focusDistance(), s.focalLengthMm(), s.lensType(),
				s.filmType(), s.remainingShots());
	}

	private static int nearestIdx(List<Float> list, float v) {
		int best = 0;
		float bestDiff = Float.MAX_VALUE;
		for (int i = 0; i < list.size(); i++) {
			float d = Math.abs(list.get(i) - v);
			if (d < bestDiff) { bestDiff = d; best = i; }
		}
		return best;
	}
}
