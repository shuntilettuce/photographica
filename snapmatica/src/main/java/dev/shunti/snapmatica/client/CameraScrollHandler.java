package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Mouse scroll adjustments while the viewfinder is active.
 * <p>
 * Ported from Photographica's CameraScrollHandler — stripped of server networking.
 * <p>
 *   Scroll           → focal length (zoom lenses only)
 *   Ctrl  + Scroll   → aperture
 *   Alt   + Scroll   → shutter speed
 *   Ctrl+Alt + Scroll → focus distance (MF mode only)
 */
@Environment(EnvType.CLIENT)
public final class CameraScrollHandler {
    private CameraScrollHandler() {}

    private static final List<Float>   APERTURES    = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final List<Integer> ISOS         = List.of(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
    private static final List<Integer> FOCAL_STOPS  = List.of(
            8, 10, 12, 14, 17, 20, 24, 28, 35, 50, 70, 85, 100, 135, 200, 300, 400, 500, 600, 800);
    private static final List<Float>   FOCUS_VALUES = List.of(
            0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f,
            6.0f, 7.0f, 8.0f, 9.0f, 10.0f, 12.0f, 14.0f, 16.0f, 18.0f, 20.0f, 24.0f, 28.0f, 32.0f,
            36.0f, 40.0f, 45.0f, 50.0f, 60.0f, 70.0f, 80.0f, 90.0f, 100.0f, 115.0f, 130.0f, 150.0f,
            170.0f, 200.0f, 230.0f, 270.0f, 300.0f, 350.0f, 400.0f, 450.0f, 500.0f, 600.0f, 700.0f,
            850.0f, 1000.0f, 1200.0f, 1500.0f, 2000.0f, 3000.0f, 5000.0f, 8000.0f, 10000.0f,
            SnapmaticaClient.FOCUS_INFINITY);
    private static final int SHUTTER_COUNT = 18;

    // Lens kind constants
    private static final int NONE        = 0;
    // Exposure mode constants
    private static final int EXP_AV = 1;
    private static final int EXP_P  = 3;
    // Focus mode constants
    private static final int FOCUS_MF = 0;

    /** Positive delta = scroll up. Returns true if consumed. */
    public static boolean onScroll(double delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return false;

        // Active while sneaking with viewfinder mode enabled, OR any time while recording
        // (lets you zoom / adjust the lens mid-shot — recording happens in any pose, not
        // just the sneak viewfinder pose).
        if (!VideoRecorder.isRecording()
                && (!SnapmaticaClient.viewfinderSneakEnabled || !mc.player.isSneaking())) return false;

        int dir = delta > 0 ? 1 : -1;

        //? if >=1.21.11 {
        /*boolean ctrl = InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean alt = InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
        *///?} else {
        long win = mc.getWindow().getHandle();
        boolean ctrl = InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean alt = InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(win, GLFW.GLFW_KEY_RIGHT_ALT);
        //?}

        if (ctrl && alt) {
            adjustFocusDistance(dir);
        } else if (ctrl) {
            adjustAperture(dir);
        } else if (alt) {
            adjustShutterSpeed(dir);
        } else {
            adjustFocalLength(dir);
        }
        SnapmaticaConfig.save();   // persist lens / aperture / shutter / focus changes
        return true;
    }

    // ── Adjusters ───────────────────────────────────────────────────────────────

    private static void adjustFocalLength(int dir) {
        if (SnapmaticaClient.lensType == NONE) return;
        int idx = nearestIntIdx(FOCAL_STOPS, SnapmaticaClient.focalLengthMm);
        SnapmaticaClient.focalLengthMm = FOCAL_STOPS.get(
                Math.max(0, Math.min(FOCAL_STOPS.size() - 1, idx + dir)));
    }

    private static void adjustAperture(int dir) {
        int idx = nearestIdx(APERTURES, SnapmaticaClient.aperture);
        // Scroll up → open aperture → lower f-number
        int newIdx = Math.max(0, Math.min(APERTURES.size() - 1, idx - dir));
        SnapmaticaClient.aperture = APERTURES.get(newIdx);
        SnapmaticaClient.updateAutoValues();
    }

    private static void adjustShutterSpeed(int dir) {
        if (SnapmaticaClient.exposureMode == EXP_AV
                || SnapmaticaClient.exposureMode == EXP_P) return; // auto
        SnapmaticaClient.shutterSpeedIdx = Math.max(0,
                Math.min(SHUTTER_COUNT - 1, SnapmaticaClient.shutterSpeedIdx + dir));
    }

    // Continuous focus stepping with a DISTANCE-ADAPTIVE ratio (no fixed table):
    //   • up close  → large ratio  → few ticks to sweep the macro range (0.3–5 m is a 16×
    //                                 span, so a small constant ratio there was finger-death)
    //   • telephoto → small ratio  → fine enough to nail focus at 800 mm
    // Past the top the focus snaps to infinity; scrolling back down returns from it.
    private static final float FOCUS_MIN = 0.3f;
    private static final float FOCUS_MAX = 10000.0f;

    private static float focusStep(float fd) {
        // t: 0 at 0.3 m → 1 at 1000 m (log-distance), ratio 1.18 (near) … 1.035 (far)
        double t = (Math.log(fd) - Math.log(0.3)) / (Math.log(1000.0) - Math.log(0.3));
        t = Math.max(0.0, Math.min(1.0, t));
        return (float) (1.18 - 0.145 * t);
    }

    private static void adjustFocusDistance(int dir) {
        if (SnapmaticaClient.focusMode != FOCUS_MF) return; // manual focus only
        float fd = SnapmaticaClient.focusDistance;
        if (dir > 0) {
            if (fd >= SnapmaticaClient.FOCUS_INFINITY) return;          // already at infinity
            if (fd >= FOCUS_MAX) { SnapmaticaClient.focusDistance = SnapmaticaClient.FOCUS_INFINITY; return; }
            SnapmaticaClient.focusDistance = Math.min(FOCUS_MAX, fd * focusStep(fd));
        } else {
            if (fd >= SnapmaticaClient.FOCUS_INFINITY) { SnapmaticaClient.focusDistance = FOCUS_MAX; return; }
            SnapmaticaClient.focusDistance = Math.max(FOCUS_MIN, fd / focusStep(fd));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static int nearestIdx(List<Float> list, float v) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            float d = Math.abs(list.get(i) - v);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    private static int nearestIntIdx(List<Integer> list, int v) {
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            int d = Math.abs(list.get(i) - v);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }
}
