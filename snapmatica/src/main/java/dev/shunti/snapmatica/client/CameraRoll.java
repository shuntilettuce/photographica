package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

/**
 * Turning the camera about its own lens axis: a tilted frame, and past 45 degrees a
 * portrait one.
 *
 * <p>Minecraft's camera has no roll: {@code Camera.setRotation} builds its orientation from yaw
 * and pitch alone, with the third angle hard-wired to zero. A camera held in hands has three
 * axes, and a tilted horizon is one of the oldest deliberate choices in photography, so
 * {@code CameraMixin} turns the camera about its view axis after vanilla has aimed it.
 *
 * <p><b>One angle, both orientations.</b> Turning a real camera a quarter turn is how it
 * shoots portrait, so there is no separate portrait switch any more: the grip turns the
 * camera through one continuous angle, and past 45 degrees either way the frame goes tall --
 * exactly what a phone's screen does at the same angle. What is left over after the quarter
 * turn is the tilt: 100 degrees is a portrait frame tilted 10. The frame itself is never drawn
 * turned, because the viewfinder is the camera's own back and turns with it; the world inside
 * does.
 *
 * <p>Only while the camera is up -- the viewfinder, freecam, or a recording. Rolling the
 * player's ordinary view would be a different mod.
 *
 * <p><b>The grip.</b> The right button, dragged sideways. In freecam it needs nothing else,
 * because clicks are the camera's there already. At the sneak viewfinder it needs Alt as well:
 * the viewfinder IS sneaking, and sneaking with the right button is how blocks get placed at
 * an edge -- taking the plain right button would have made the camera cost every builder that
 * move. Alt is already the camera's modifier (Alt + wheel is the shutter dial).
 *
 * <p><b>Getting level again.</b> A tilt is only a choice if level is easy to come back to.
 * The angle has a notch at level in both orientations -- 0 and a quarter turn either way --
 * that holds it exactly level until the drag has pushed a few degrees past. The viewfinder
 * draws an electronic level while the camera is tilted or being gripped -- a finder aid,
 * never in the photograph.
 */
@Environment(EnvType.CLIENT)
public final class CameraRoll {
    private CameraRoll() {}

    /** Tilt either side of level, in either orientation, before the frame changes over. */
    public static final float MAX_DEG = 45f;
    /** Furthest the camera turns: a portrait frame tilted the full 45 degrees. */
    public static final float MAX_TURN_DEG = 135f;
    /** Half-width of the notch at each level, in degrees of drag. */
    private static final float DETENT_DEG = 4f;
    /** Drag between the centres of two neighbouring notches. */
    private static final double NOTCH_SPACING = 90.0 + 2.0 * DETENT_DEG;
    /** Degrees per unit of scaled mouse motion: vanilla's own look rate, so it feels like looking. */
    private static final double DEG_PER_UNIT = 0.15;

    private static boolean rightHeld = false, leftHeld = false;
    private static boolean gripping = false;
    /** The drag in raw degrees, notches included. */
    private static double raw = 0.0;

    /** Whether roll applies at all right now: the camera is up. */
    public static boolean inCameraContext(MinecraftClient mc) {
        return mc != null && mc.player != null
                && (SnapmaticaClient.viewfinderActive(mc) || VideoRecorder.isRecording());
    }

    /** The tilt to apply to the render camera this frame, in degrees; clockwise positive. */
    public static float effectiveDeg(MinecraftClient mc) {
        return inCameraContext(mc) ? SnapmaticaClient.cameraRollDeg : 0f;
    }

    /** True while the grip is held and the mouse turns the camera about its axis. */
    public static boolean isGripping() { return gripping; }

    /**
     * Sets the camera's whole turn, and from it the orientation and the tilt that the rest of
     * the mod reads: a quarter turn either way is portrait, what is left over is the tilt.
     */
    public static void setTurn(float turnDeg) {
        float t = Math.max(-MAX_TURN_DEG, Math.min(MAX_TURN_DEG, turnDeg));
        int quarter = quarterOf(t);
        boolean portrait = quarter != 0;
        if (portrait != SnapmaticaClient.portraitOrientation) {
            SnapmaticaClient.portraitOrientation = portrait;
        }
        SnapmaticaClient.cameraTurnDeg = t;
        SnapmaticaClient.cameraRollDeg = t - 90f * quarter;
    }

    /**
     * Which quarter turn an angle belongs to: -1, 0 or 1. Clamped, because at exactly 135
     * degrees Math.round lands on 2 -- an upside-down landscape frame this camera does not
     * have -- rather than on a portrait one tilted the full 45.
     */
    private static int quarterOf(float t) {
        return Math.max(-1, Math.min(1, Math.round(t / 90f)));
    }

    /** Raw drag -> turn, with a flat notch of {@link #DETENT_DEG} either side of each level. */
    private static float turnFromRaw(double r) {
        int k = (int) Math.max(-1, Math.min(1, Math.round(r / NOTCH_SPACING)));
        double d = r - k * NOTCH_SPACING;
        double a = Math.abs(d);
        return (float) (90.0 * k + (a <= DETENT_DEG ? 0.0 : Math.signum(d) * (a - DETENT_DEG)));
    }

    /** Turn -> raw drag, for picking the grip up where the camera already is. */
    private static double rawFromTurn(float t) {
        int k = quarterOf(t);
        double d = t - 90.0 * k;
        return k * NOTCH_SPACING + (d == 0.0 ? 0.0 : Math.signum(d) * (Math.abs(d) + DETENT_DEG));
    }

    /**
     * A mouse button went down or up. Returns true when the event is the grip's and must not
     * also reach the game -- a right press that starts a roll must not also place a block.
     */
    public static boolean onButton(MinecraftClient mc, int button, boolean pressed) {
        if (button == 0) leftHeld = pressed;
        if (button != 1) return false;
        rightHeld = pressed;
        if (pressed) {
            if (mc.currentScreen != null || !inCameraContext(mc)) return false;
            boolean freecamFlying = Freecam.isActive() && !Freecam.isLocked();
            // In freecam the right button also re-aims a keyframe while the left drags it;
            // that combination stays the keyframe's.
            if (freecamFlying ? leftHeld : !CameraScrollHandler.altDown()) return false;
            gripping = true;
            raw = rawFromTurn(SnapmaticaClient.cameraTurnDeg);
            return !freecamFlying;   // freecam already swallows its own clicks
        }
        if (gripping) {
            gripping = false;
            SnapmaticaConfig.save();
            return !(Freecam.isActive() && !Freecam.isLocked());
        }
        return false;
    }

    /** Horizontal mouse motion while gripping, already scaled the way vanilla scales look. */
    public static void onDrag(double dx) {
        double lim = NOTCH_SPACING + (MAX_TURN_DEG - 90.0) + DETENT_DEG;
        raw = Math.max(-lim, Math.min(lim, raw + dx * DEG_PER_UNIT));
        setTurn(turnFromRaw(raw));
    }

    /** Level again from the settings screen, keeping whichever orientation it is in. */
    public static void reset() {
        setTurn(90f * quarterOf(SnapmaticaClient.cameraTurnDeg));
        raw = rawFromTurn(SnapmaticaClient.cameraTurnDeg);
    }

    /** Drops a grip left open by a screen or a lost window focus. */
    public static void releaseIfStale(MinecraftClient mc) {
        if (gripping && (mc.currentScreen != null || !rightHeld || !inCameraContext(mc))) {
            gripping = false;
            SnapmaticaConfig.save();
        }
    }
}
