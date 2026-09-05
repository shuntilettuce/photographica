package dev.shunti.snapmatica;

/**
 * The camera's settings, and the stops they move in.
 *
 * <p>Carried over from the 1.21.x version unchanged, values included. A lens that clicks
 * through the same f-stops and the same focal lengths is the point of the thing — continuous
 * sliders would be easier to write and would feel like a slider, not a lens. The focus scale
 * is spaced the way a focus ring is marked, crowded near the lens and stretched out toward
 * infinity, because that is where the depth of field actually changes.
 */
public final class CameraState {
    private CameraState() {}

    /** 1 block = 37.5 cm — the scale the thin-lens maths treats the world at. */
    public static final float DOF_SCALE = 375.0f;
    public static final float FOCUS_INFINITY = 100000.0f;

    public static final float[] APERTURES = {
            1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f };

    public static final int[] FOCAL_STOPS = {
            8, 10, 12, 14, 17, 20, 24, 28, 35, 50, 70, 85, 100, 135, 200, 300, 400, 500, 600, 800 };

    public static final float[] FOCUS_VALUES = {
            0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f,
            6.0f, 7.0f, 8.0f, 9.0f, 10.0f, 12.0f, 14.0f, 16.0f, 18.0f, 20.0f, 24.0f, 28.0f, 32.0f,
            36.0f, 40.0f, 45.0f, 50.0f, 60.0f, 70.0f, 80.0f, 90.0f, 100.0f, 115.0f, 130.0f, 150.0f,
            170.0f, 200.0f, 230.0f, 270.0f, 300.0f, 350.0f, 400.0f, 450.0f, 500.0f, 600.0f, 700.0f,
            850.0f, 1000.0f, 1200.0f, 1500.0f, 2000.0f, 3000.0f, 5000.0f, 8000.0f, 10000.0f,
            FOCUS_INFINITY };

    // Focus modes, matching the 1.21.x numbering so a config could be shared later.
    public static final int FOCUS_MF = 0;
    public static final int FOCUS_AF = 1;

    public static int   apertureIdx   = 0;                    // f/1.4
    public static int   focalIdx      = 8;                    // 35 mm
    public static int   focusIdx      = 18;                   // 10 blocks
    /** Autofocus by default: mini has no settings screen yet, and AF is the useful default. */
    public static int   focusMode     = FOCUS_AF;
    public static boolean viewfinderSneakEnabled = true;
    public static boolean portrait    = false;

    public static float aperture()   { return APERTURES[clamp(apertureIdx, APERTURES.length)]; }
    public static int   focalLenMm() { return FOCAL_STOPS[clamp(focalIdx, FOCAL_STOPS.length)]; }
    public static float focusDist()  { return FOCUS_VALUES[clamp(focusIdx, FOCUS_VALUES.length)]; }
    public static boolean autoFocus(){ return focusMode != FOCUS_MF; }

    public static int clamp(int i, int n) { return i < 0 ? 0 : (i >= n ? n - 1 : i); }

    public static int step(int idx, int dir, int n) {
        int v = idx + dir;
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }
}
