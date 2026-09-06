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
    private static final List<Integer> ISOS         = List.of(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
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
        // A keyframe being dragged takes the wheel outright — it's how depth gets set while
        // grabbing, and fighting a normal zoom/aperture adjustment for the same scroll would
        // be exactly the wrong moment for the lens to also start moving.
        if (Freecam.adjustDragDepth(delta)) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null) return false;

        // Active while the viewfinder is up (sneaking or freecam), OR any time while recording
        // (lets you zoom / adjust the lens mid-shot — recording happens in any pose, not
        // just the sneak viewfinder pose).
        if (!VideoRecorder.isRecording() && !SnapmaticaClient.viewfinderActive(mc)) return false;

        int dir = delta > 0 ? 1 : -1;

        boolean ctrl = ctrlDown();
        boolean alt  = altDown();

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

    /** The lens's range, so the viewfinder can name it without hard-coding a second copy. */
    public static int focalMinMm() { return FOCAL_STOPS.get(0); }
    public static int focalMaxMm() { return FOCAL_STOPS.get(FOCAL_STOPS.size() - 1); }

    private static void adjustFocalLength(int dir) {
        if (SnapmaticaClient.lensType == NONE) return;
        SnapmaticaClient.focalLengthMm = stepFocalLength(SnapmaticaClient.focalLengthMm, dir);
        // Zooming does not move the blades, so the f-number follows the focal length.
        SnapmaticaClient.applyFocalLengthToAperture();
    }

    /** Steps a focal length to the next lens stop in either direction — shared by the normal
     *  scroll-to-zoom handler above and Freecam's keyframe dolly-zoom edit, so both draw from
     *  the same stop table instead of keeping two copies of it in sync by hand. */
    public static int stepFocalLength(int currentMm, int dir) {
        int idx = nearestIntIdx(FOCAL_STOPS, currentMm);
        return FOCAL_STOPS.get(Math.max(0, Math.min(FOCAL_STOPS.size() - 1, idx + dir)));
    }

    private static void adjustAperture(int dir) {
        int idx = nearestIdx(APERTURES, SnapmaticaClient.aperture);
        // Scroll up → open aperture → lower f-number
        int newIdx = Math.max(0, Math.min(APERTURES.size() - 1, idx - dir));
        SnapmaticaClient.aperture = APERTURES.get(newIdx);
        // The ring is the only thing that actually moves the blades — record the opening it
        // implies, so a later zoom can carry it forward.
        SnapmaticaClient.syncApertureDiameter();
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
        // The ring is free to run ahead of the lens — that is what turning it quickly means,
        // and the rack catching up is the point. Picking the ring up from the lens belongs to
        // the AF -> MF handover only (AutoFocus.tick); doing it here, on every click, threw
        // away the destination each time and left the lens tracking the wheel one step at a
        // time. Fast scrolling then looked instant, which is exactly what the rack was for.
        // Moves the RING. The lens follows under AutoFocus.tick's rack, so the last step out
        // to infinity glides there instead of teleporting.
        float fd = SnapmaticaClient.focusTarget;
        if (dir > 0) {
            if (fd >= SnapmaticaClient.FOCUS_INFINITY) return;          // already at infinity
            if (fd >= FOCUS_MAX) { SnapmaticaClient.focusTarget = SnapmaticaClient.FOCUS_INFINITY; return; }
            SnapmaticaClient.focusTarget = Math.min(FOCUS_MAX, fd * focusStep(fd));
        } else {
            if (fd >= SnapmaticaClient.FOCUS_INFINITY) { SnapmaticaClient.focusTarget = FOCUS_MAX; return; }
            SnapmaticaClient.focusTarget = Math.max(FOCUS_MIN, fd / focusStep(fd));
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

    /**
     * Is a modifier held?
     *
     * <p>Asked of GLFW through Minecraft's thin wrapper rather than of {@code Screen}, because
     * {@code Screen.hasControlDown} does not exist on every version this mod builds for — and
     * because these are read while no screen is open at all, from the scroll handler. The window
     * argument changed shape at 1.21.10 (the Window object rather than its raw handle), which is
     * the whole of the branch.
     *
     * <p>Public because the camera roll needs the same question answered, and one branch is
     * better than two.
     */
    public static boolean ctrlDown() { return modifierDown(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL); }

    public static boolean altDown() { return modifierDown(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT); }

    /**
     * Whether a single key is physically down right now.
     *
     * <p>Here rather than as a key binding because the AF-point selector has to keep moving
     * while a direction is HELD, and a binding reports presses, not state. Sharing
     * {@link #modifierDown}'s body is the point: that method holds this mod's only branch for
     * how the window is addressed across versions, and a second copy is a second thing to get
     * wrong at the next one.
     */
    public static boolean keyDown(int key) { return modifierDown(key, key); }

    private static boolean modifierDown(int left, int right) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        //? if >=1.21.10 {
        return InputUtil.isKeyPressed(mc.getWindow(), left) || InputUtil.isKeyPressed(mc.getWindow(), right);
        //?} else {
        /*long win = mc.getWindow().getHandle();
        return InputUtil.isKeyPressed(win, left) || InputUtil.isKeyPressed(win, right);
        *///?}
    }

}
