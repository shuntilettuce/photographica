package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

/**
 * Turning the camera about its own lens axis -- the tilted, "Dutch" frame.
 *
 * <p>Minecraft's camera has no roll: {@code Camera.setRotation} builds its orientation from yaw
 * and pitch alone, with the third angle hard-wired to zero. A camera held in hands has three
 * axes, and a tilted horizon is one of the oldest deliberate choices in photography, so
 * {@code CameraMixin} turns the camera about its view axis after vanilla has aimed it. Nothing
 * else in the picture changes: the frame stays the frame, and the world turns inside it.
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
 * The angle has a notch at zero: it stays exactly level until the drag has pushed a little way
 * past, the same way the AF point catches at the centre. The viewfinder draws an electronic
 * level while the camera is tilted or being gripped -- a finder aid, never in the photograph.
 */
@Environment(EnvType.CLIENT)
public final class CameraRoll {
    private CameraRoll() {}

    /** Widest tilt, either way. Past this it stops being a tilted frame and becomes a sideways one. */
    public static final float MAX_DEG = 45f;
    /** Width of the notch at level, in degrees of drag. */
    private static final float DETENT_DEG = 1.5f;
    /** Degrees per unit of scaled mouse motion: vanilla's own look rate, so it feels like looking. */
    private static final double DEG_PER_UNIT = 0.15;

    private static boolean rightHeld = false, leftHeld = false;
    private static boolean gripping = false;
    /** The drag in raw degrees, notch included; {@link SnapmaticaClient#cameraRollDeg} is it with the notch taken out. */
    private static double raw = 0.0;

    /** Whether roll applies at all right now: the camera is up. */
    public static boolean inCameraContext(MinecraftClient mc) {
        return mc != null && mc.player != null
                && (SnapmaticaClient.viewfinderActive(mc) || VideoRecorder.isRecording());
    }

    /** The roll to apply to the render camera this frame, in degrees; clockwise positive. */
    public static float effectiveDeg(MinecraftClient mc) {
        return inCameraContext(mc) ? SnapmaticaClient.cameraRollDeg : 0f;
    }

    /** True while the grip is held and the mouse turns the camera about its axis. */
    public static boolean isGripping() { return gripping; }

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
            float d = SnapmaticaClient.cameraRollDeg;
            raw = (d == 0f) ? 0.0 : d + Math.signum(d) * DETENT_DEG;
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
        raw += dx * DEG_PER_UNIT;
        double lim = MAX_DEG + DETENT_DEG;
        raw = Math.max(-lim, Math.min(lim, raw));
        double a = Math.abs(raw);
        SnapmaticaClient.cameraRollDeg = (a <= DETENT_DEG) ? 0f
                : (float) (Math.signum(raw) * (a - DETENT_DEG));
    }

    /** Level again, from the settings screen. */
    public static void reset() {
        SnapmaticaClient.cameraRollDeg = 0f;
        raw = 0.0;
    }

    /** Drops a grip left open by a screen or a lost window focus. */
    public static void releaseIfStale(MinecraftClient mc) {
        if (gripping && (mc.currentScreen != null || !rightHeld || !inCameraContext(mc))) {
            gripping = false;
            SnapmaticaConfig.save();
        }
    }
}
