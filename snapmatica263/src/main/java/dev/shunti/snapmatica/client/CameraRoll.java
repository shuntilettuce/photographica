package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * Turning the camera about its own lens axis: a tilted frame, a portrait one past 45 degrees,
 * and all the way round to upside down.
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
 * <p>It turns without end, and half a turn is not quietly undone: at 180 degrees the frame is
 * landscape again and the world in it is upside down, which is the photograph a camera held
 * upside down takes. Only the quarter turns are folded into the orientation, because that is
 * the one change a photograph's frame can make.
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
 * The angle has a notch at every level -- 0, a quarter turn either way, and half a turn --
 * that holds it exactly level until the drag has pushed a few degrees past. The viewfinder
 * draws an electronic level while the camera is tilted or being gripped -- a finder aid,
 * never in the photograph.
 */
@Environment(EnvType.CLIENT)
public final class CameraRoll {
    private CameraRoll() {}

    /** Half-width of the notch at each level, in degrees of drag. */
    private static final float DETENT_DEG = 4f;
    /** Drag between the centres of two neighbouring notches. */
    private static final double NOTCH_SPACING = 90.0 + 2.0 * DETENT_DEG;
    /** Degrees per unit of scaled mouse motion: vanilla's own look rate, so it feels like looking. */
    private static final double DEG_PER_UNIT = 0.15;

    private static boolean rightHeld = false, leftHeld = false;
    private static boolean gripping = false;
    /** The drag in raw degrees, notches included; wraps with the turn. */
    private static double raw = 0.0;
    /** What is left of the turn past the nearest level, and whether that level is upside down. */
    private static float tilt = 0f;
    private static boolean upsideDown = false;

    /** Degrees off the nearest level, whichever orientation that is. */
    public static float tiltDeg() { return tilt; }
    /** Whether the camera is nearer half a turn than level: the world is upside down. */
    public static boolean isUpsideDown() { return upsideDown; }

    /** Whether roll applies at all right now: the camera is up. */
    public static boolean inCameraContext(Minecraft mc) {
        return mc != null && mc.player != null
                && (SnapmaticaClient.viewfinderActive(mc) || VideoRecorder.isRecording());
    }

    /** The tilt to apply to the render camera this frame, in degrees; clockwise positive. */
    public static float effectiveDeg(Minecraft mc) {
        return inCameraContext(mc) ? SnapmaticaClient.cameraRollDeg : 0f;
    }

    /** True while the grip is held and the mouse turns the camera about its axis. */
    public static boolean isGripping() { return gripping; }

    /**
     * Sets the camera's whole turn, and from it the orientation and the tilt that the rest of
     * the mod reads: a quarter turn either way is portrait, what is left over is the tilt.
     */
    public static void setTurn(float turnDeg) {
        float t = normalize(turnDeg);
        int quarter = quarterOf(t);                 // -2..2; +-2 are both half a turn
        boolean portrait = Math.abs(quarter) == 1;
        if (portrait != SnapmaticaClient.portraitOrientation) {
            SnapmaticaClient.portraitOrientation = portrait;
        }
        tilt = t - 90f * quarter;
        upsideDown = Math.abs(quarter) == 2;
        SnapmaticaClient.cameraTurnDeg = t;
        // The turn the render camera actually makes: the tilt, plus half a turn when upside
        // down. A portrait quarter turn is the frame's, not the world's, so it is not in here.
        SnapmaticaClient.cameraRollDeg = tilt + (upsideDown ? 180f : 0f);
    }

    /** Into (-180, 180]. */
    private static float normalize(float t) {
        float n = t - 360f * (float) Math.floor((t + 180f) / 360f);
        return (n == -180f) ? 180f : n;
    }

    /** Which quarter turn a normalized angle is nearest: -2..2, where +-2 are both upside down. */
    private static int quarterOf(float t) {
        return Math.round(t / 90f);
    }

    /** Raw drag -> turn, with a flat notch of {@link #DETENT_DEG} either side of each level. */
    private static float turnFromRaw(double r) {
        long k = Math.round(r / NOTCH_SPACING);
        double d = r - k * NOTCH_SPACING;
        double a = Math.abs(d);
        return normalize((float) (90.0 * k + (a <= DETENT_DEG ? 0.0 : Math.signum(d) * (a - DETENT_DEG))));
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
    public static boolean onButton(Minecraft mc, boolean isLeft, boolean isRight, boolean pressed) {
        // Which button is which is the caller's to say: 26.3 numbers them the SDL way.
        if (isLeft) leftHeld = pressed;
        if (!isRight) return false;
        rightHeld = pressed;
        if (pressed) {
            if (mc.gui.screen() != null || !inCameraContext(mc)) return false;
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
        // Round and round: the drag wraps a full turn at a time so it never grows without end.
        double full = 4.0 * NOTCH_SPACING;
        raw += dx * DEG_PER_UNIT;
        raw -= full * Math.floor((raw + full / 2.0) / full);
        setTurn(turnFromRaw(raw));
    }

    /** Level again from the settings screen, keeping whichever orientation it is nearest. */
    public static void reset() {
        setTurn(90f * quarterOf(SnapmaticaClient.cameraTurnDeg));
        raw = rawFromTurn(SnapmaticaClient.cameraTurnDeg);
    }

    /** Drops a grip left open by a screen or a lost window focus. */
    public static void releaseIfStale(Minecraft mc) {
        if (gripping && (mc.gui.screen() != null || !rightHeld || !inCameraContext(mc))) {
            gripping = false;
            SnapmaticaConfig.save();
        }
    }
}
