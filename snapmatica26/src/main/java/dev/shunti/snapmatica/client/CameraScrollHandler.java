package dev.shunti.snapmatica.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.List;

@Environment(EnvType.CLIENT)
public final class CameraScrollHandler {
    private CameraScrollHandler() {}

    private static final List<Float>   APERTURES    = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final List<Integer> ISOS         = List.of(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
    private static final List<Integer> FOCAL_STOPS  = List.of(
            8, 10, 12, 14, 17, 20, 24, 28, 35, 50, 70, 85, 100, 135, 200, 300, 400, 500, 600, 800);
    private static final List<Float>   FOCUS_VALUES = List.of(
            0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f,
            5.0f, 6.0f, 7.0f, 8.0f, 10.0f, 12.0f, 15.0f, 20.0f, 25.0f, 30.0f,
            40.0f, 50.0f, 70.0f, 100.0f, 150.0f, 200.0f, 300.0f, 500.0f, 700.0f, 1000.0f, 1500.0f, 2000.0f, SnapmaticaClient.FOCUS_INFINITY);
    private static final int SHUTTER_COUNT = 18;

    private static final int NONE        = 0;
    private static final int EXP_AV = 1;
    private static final int EXP_P  = 3;
    private static final int FOCUS_MF = 0;

    public static boolean onScroll(double delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return false;

        int dir = delta > 0 ? 1 : -1;

        com.mojang.blaze3d.platform.Window win = mc.getWindow();
        boolean ctrl = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean alt = InputConstants.isKeyDown(win, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(win, GLFW.GLFW_KEY_RIGHT_ALT);

        // Alt + scroll = video zoom while recording (works without sneaking, mirrors
        // photographica). Scroll up = zoom in = narrower FOV.
        if (alt && VideoRecorder.isRecording()) {
            VideoRecorder.videoFov = Math.max(VideoRecorder.VIDEO_FOV_MIN,
                    Math.min(VideoRecorder.VIDEO_FOV_MAX,
                            VideoRecorder.videoFov - dir * VideoRecorder.VIDEO_ZOOM_STEP));
            return true;
        }

        if (!SnapmaticaClient.viewfinderSneakEnabled || !mc.player.isShiftKeyDown()) return false;

        if (ctrl && alt) {
            adjustFocusDistance(dir);
        } else if (ctrl) {
            adjustAperture(dir);
        } else if (alt) {
            adjustShutterSpeed(dir);
        } else {
            adjustFocalLength(dir);
        }
        return true;
    }

    private static void adjustFocalLength(int dir) {
        if (SnapmaticaClient.lensType == NONE) return;
        int idx = nearestIntIdx(FOCAL_STOPS, SnapmaticaClient.focalLengthMm);
        SnapmaticaClient.focalLengthMm = FOCAL_STOPS.get(
                Math.max(0, Math.min(FOCAL_STOPS.size() - 1, idx + dir)));
    }

    private static void adjustAperture(int dir) {
        int idx = nearestIdx(APERTURES, SnapmaticaClient.aperture);
        int newIdx = Math.max(0, Math.min(APERTURES.size() - 1, idx - dir));
        SnapmaticaClient.aperture = APERTURES.get(newIdx);
        SnapmaticaClient.updateAutoValues();
    }

    private static void adjustShutterSpeed(int dir) {
        if (SnapmaticaClient.exposureMode == EXP_AV
                || SnapmaticaClient.exposureMode == EXP_P) return;
        SnapmaticaClient.shutterSpeedIdx = Math.max(0,
                Math.min(SHUTTER_COUNT - 1, SnapmaticaClient.shutterSpeedIdx + dir));
    }

    private static void adjustFocusDistance(int dir) {
        if (SnapmaticaClient.focusMode != FOCUS_MF) return;
        int idx = nearestIdx(FOCUS_VALUES, SnapmaticaClient.focusDistance);
        int newIdx = Math.max(0, Math.min(FOCUS_VALUES.size() - 1, idx + dir));
        SnapmaticaClient.focusDistance = FOCUS_VALUES.get(newIdx);
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
