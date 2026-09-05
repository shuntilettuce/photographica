package dev.shunti.snapmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextComponentString;

/**
 * Mouse wheel adjustments while the viewfinder is up, on the same mapping as the 1.21.x
 * version — the wheel is the zoom ring, Ctrl makes it the aperture ring, Ctrl+Alt makes it the
 * focus ring.
 *
 * <p>Alt alone is shutter speed there. Mini has no exposure simulation, so it does nothing
 * here rather than being remapped to something else: a control that moves the wrong dial is
 * worse than one that does not move.
 */
public final class CameraScrollHandler {
    private CameraScrollHandler() {}

    /** Positive delta = wheel up. Returns true when the scroll was consumed. */
    public static boolean onScroll(int delta) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.currentScreen != null) return false;
        if (!ClientEvents.viewfinderActive()) return false;

        int dir = delta > 0 ? 1 : -1;
        boolean ctrl = GuiScreen.isCtrlKeyDown();
        boolean alt  = GuiScreen.isAltKeyDown();

        if (ctrl && alt) {
            // Focus is manual by definition once you have turned the ring.
            CameraState.focusMode = CameraState.FOCUS_MF;
            CameraState.focusIdx = CameraState.step(CameraState.focusIdx, dir,
                                                    CameraState.FOCUS_VALUES.length);
            show(mc, "MF  " + focusLabel());
        } else if (ctrl) {
            CameraState.apertureIdx = CameraState.step(CameraState.apertureIdx, dir,
                                                       CameraState.APERTURES.length);
            show(mc, "F" + fmt(CameraState.aperture()));
        } else if (alt) {
            return true;   // shutter speed: nothing to move, but do not scroll the hotbar
        } else {
            CameraState.focalIdx = CameraState.step(CameraState.focalIdx, dir,
                                                    CameraState.FOCAL_STOPS.length);
            show(mc, CameraState.focalLenMm() + "mm");
        }
        return true;
    }

    public static String focusLabel() {
        float d = CameraState.focusDist();
        if (d >= CameraState.FOCUS_INFINITY) return "inf";
        float m = d * 0.375f;
        if (m >= 100f) return String.format("%.0fm", m);
        if (m >= 10f)  return String.format("%.1fm", m);
        return String.format("%.2fm", m);
    }

    private static String fmt(float a) {
        return (a < 10f) ? String.format("%.1f", a) : String.format("%.0f", a);
    }

    private static void show(Minecraft mc, String msg) {
        if (mc.ingameGUI != null) {
            mc.ingameGUI.setOverlayMessage(new TextComponentString(msg), false);
        }
    }
}
