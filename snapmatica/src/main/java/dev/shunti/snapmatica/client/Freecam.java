package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * A camera that flies free of the player, for framing a shot the player's own body is
 * standing in (or simply cannot reach) — snapmatica's own answer to Replay Mod / Flashback /
 * freecam-type mods, and the foundation a selfie mode builds on.
 *
 * <p>{@link dev.shunti.snapmatica.client.mixin.CameraMixin} is what actually moves the render
 * camera: while {@link #isActive()}, it cancels {@code Camera.update()} and applies the
 * position and orientation tracked here instead, interpolated between {@link #getPrevPos()}
 * and {@link #getPos()} so flight is smooth at whatever framerate the display runs, not
 * stepped at the 20 Hz tick rate movement is actually integrated at.
 *
 * <p>Orientation is tracked independently of the player (see {@link #onMouseLook}, fed by
 * {@link dev.shunti.snapmatica.client.mixin.MouseMixin}) rather than mirrored from it — mirroring
 * meant turning the view also turned the player's own body, since yaw/pitch IS the player's
 * body orientation. The player's WASD/jump/sneak input is blanked the same way, so it doesn't
 * also walk off wherever the camera flies — but gravity, momentum and collision are left
 * completely alone. They have to be: pinning position (an earlier version of this) beats
 * gravity, so a player who activated freecam mid-air just hung there — flight, in survival,
 * for free.
 *
 * <p>Two control feels, picked from the camera settings screen ({@link SnapmaticaClient#droneMode}):
 * direct WASD flight (instant response, precise placement — the default) or drone mode, aimed
 * at aerial footage instead: inertia rather than instant velocity, an altitude hold on the
 * vertical axis, and a pin ({@link #togglePin}) that turns WASD into an orbit around whatever
 * was under the reticle when it dropped.
 *
 * <p>Everything in snapmatica that needs to know where the "camera" is — autofocus, depth of
 * field, the photo/video capture origin — reads it from the live {@link Camera} object (via
 * {@code SnapmaticaClient.cameraPos}/{@code cameraLook}) rather than from {@code mc.player}, so
 * it naturally follows freecam when active and an external camera-driving mod's own override
 * when it is not.
 */
@Environment(EnvType.CLIENT)
public final class Freecam {

    private Freecam() {}

    private static boolean active = false;

    /**
     * Tripod mode: the camera holds exactly where it was left — no WASD flight, no mouse
     * look — while the player's own input is handed back so they can walk into frame and look
     * around normally. Toggled independently of {@link SnapmaticaClient#droneMode} and the
     * pin/orbit; whichever of those was active when locked just stops updating, and resumes
     * exactly where it left off if unlocked again.
     */
    private static boolean locked = false;

    private static Vec3d pos = Vec3d.ZERO;
    private static Vec3d prevPos = Vec3d.ZERO;
    private static float yaw, pitch;
    /** Orientation as of the previous tick, for interpolating orbit's auto-facing — see {@link #getPrevYaw}. */
    private static float prevYaw, prevPitch;

    /** The player's real {@link Input} while freecam is off, to restore on exit. */
    private static Input savedInput;

    /** Health as of the last tick freecam was active, to notice a hit the instant it lands. */
    private static float lastHealth;

    /** Blocks per tick at normal speed (direct-flight mode only); held Ctrl multiplies this. */
    private static final double SPEED = 0.5;
    private static final double SPEED_FAST_MULT = 4.0;

    // ── Drone mode: inertia flight ────────────────────────────────────────────────
    private static Vec3d velocity = Vec3d.ZERO;
    private static final double DRONE_ACCEL = 0.06;
    private static final double DRONE_MAX_SPEED = 1.2;
    private static final double DRONE_SPEED_FAST_MULT = 3.0;
    private static final double DRONE_DRAG = 0.90;
    /** How hard the vertical axis brakes with no Space/Shift held — the altitude hold. */
    private static final double DRONE_VERTICAL_HOLD_DRAG = 0.55;

    // ── Drone mode: pin / orbit ───────────────────────────────────────────────────
    private static boolean pinActive = false;
    /** Fixed orbit centre, used whenever {@link #pinEntity} isn't tracking something alive. */
    private static Vec3d pinPos = Vec3d.ZERO;
    /** Tracked subject the pin follows, or null for a pin dropped on terrain. */
    private static Entity pinEntity = null;
    private static double orbitAngle;
    private static double orbitRadius = 8.0;
    private static double orbitHeight = 2.0;
    /**
     * A/D's speed, in blocks of actual travel per tick — not radians per tick. A fixed radian
     * step covers radius*step blocks, so at a wide orbit (shooting a whole building) the same
     * step that felt right up close became far too fast; dividing by the current radius each
     * tick keeps the actual sideways speed the camera moves at constant regardless of how far
     * out the orbit is.
     */
    private static final double ORBIT_TANGENT_SPEED = 0.25;
    private static final double ORBIT_RADIUS_STEP = 0.3;
    private static final double ORBIT_HEIGHT_STEP = 0.25;
    private static final double ORBIT_FAST_MULT = 3.0;
    private static final double ORBIT_MIN_RADIUS = 1.5;
    // cos(5°) — how tight a cone around the look direction counts as "aimed at" an entity.
    private static final double PIN_CONE_COS = Math.cos(Math.toRadians(5.0));
    // A keyframe has no hitbox to aim at the way an entity does, and a point-in-space target
    // is a much smaller thing to land a 5° cone on — wider so the reticle doesn't have to sit
    // almost exactly on it before it counts as targeted.
    private static final double KEYFRAME_CONE_COS = Math.cos(Math.toRadians(12.0));

    // Orbit's own inertia, used only while droneMode is on — with it off, orbiting is the
    // same instant/snappy response as direct flight, just steering angle/radius/height
    // instead of xyz. Units are blocks/tick of the corresponding motion (tangential, radial,
    // vertical), matching DRONE_ACCEL/DRONE_DRAG/DRONE_MAX_SPEED's scale so the two feel like
    // the same underlying control, just aimed at different things.
    private static double orbitAngleVel;
    private static double orbitRadiusVel;
    private static double orbitHeightVel;

    // ── Camera path: multi-keyframe flythrough, snapmatica's answer to Flashback/Replay
    // Mod's camera paths ─────────────────────────────────────────────────────────────
    /**
     * One recorded viewpoint — position, orientation, and the lens at the time, so a path can
     * also carry a zoom/dolly move rather than just a fixed-focal-length flythrough. No timing
     * of its own; see {@link #pathDurationSec}.
     *
     * <p>{@code pinned}/{@code pinTarget} snapshot whether the orbit pin (X) was down when this
     * keyframe was dropped, and where it was aimed — not to keep tracking a moving subject
     * during playback, just a fixed world point recorded once, which is simpler and covers the
     * usual case (orbiting or dollying past a stationary subject) without a live entity
     * reference outliving the session it was recorded in.
     */
    public record Keyframe(Vec3d pos, float yaw, float pitch, int focalLengthMm,
                           boolean pinned, Vec3d pinTarget) {}

    private static final List<Keyframe> path = new ArrayList<>();
    private static boolean pathPlaying = false;
    private static double pathElapsedMs = 0;

    // ── Camera path: focus lock ─────────────────────────────────────────────────────
    /** Whether the Camera Path menu's "lock focus during playback" toggle is armed. */
    private static boolean pathFocusLockEnabled = false;
    /** Tracked subject the lock follows, or null if it caught a block instead. */
    private static Entity pathFocusLockEntity = null;
    /** Fixed world point the lock holds on, used whenever {@link #pathFocusLockEntity} is
     *  null — a block does not move, so one raycast at the moment the toggle was pressed is
     *  all a lock on it will ever need. */
    private static Vec3d pathFocusLockPoint = Vec3d.ZERO;

    // ── Camera path: grab-and-drag editing ──────────────────────────────────────────
    /** Index of the keyframe the reticle is currently aimed at (within a narrow cone, same
     *  test togglePin uses for an entity), or -1. Recomputed every tick while eligible. */
    private static int targetedKeyframeIndex = -1;
    /** Index currently being dragged, or -1. Only ever one at a time — grabbing another
     *  keyframe first needs this one released. */
    private static int draggingKeyframeIndex = -1;
    /** Distance from the camera the dragged keyframe is held at, along the look direction —
     *  the mouse wheel's only job while dragging is changing this. */
    private static double dragDepth;
    private static final double KEYFRAME_TARGET_MAX_DIST = 60.0;
    /** Segment index i (between recorded keyframes i and i+1) the reticle is aimed at along
     *  the CURVE itself, or -1 — only considered while no actual keyframe is targeted, so a
     *  click near an existing point always means that point, never an insert next to it. */
    private static int targetedCurveSegment = -1;
    private static Vec3d targetedCurvePos = Vec3d.ZERO;
    private static final int CURVE_TEST_SAMPLES = 12;
    /** Yaw values unwrapped into one continuous run (no 359°→0° jumps), built once when
     *  playback starts rather than re-derived every tick. */
    private static float[] pathUnwrappedYaw = new float[0];
    /** Total time to traverse every keyframe, split evenly by keyframe count — not distance,
     *  so evenly SPACED keyframes are what actually gets an even on-screen speed; see the
     *  distance readout in {@link #addPathKeyframe}. Configurable from the camera settings
     *  screen (Camera tab). */
    public static double pathDurationSec = 10.0;

    /** Precise (unrounded) focal length either side of the current tick, so the render loop
     *  can interpolate it the same way position and rotation already are — see
     *  {@link #currentFocalLengthMm}. Position/yaw/pitch step once per tick and still look
     *  smooth because {@link dev.shunti.snapmatica.client.mixin.CameraMixin} lerps them every
     *  frame; the lens was doing neither, snapping to a rounded integer once every 50 ms and
     *  reading as both a stepped zoom and a pulsing depth-of-field blur radius (the shader's
     *  circle of confusion is a function of focal length too). */
    private static float pathPrevFocalLenMm, pathFocalLenMm;

    public static boolean isActive() {
        return active;
    }

    public static boolean isLocked() {
        return locked;
    }

    public static boolean isPinActive() {
        return pinActive;
    }

    public static Vec3d getPos() {
        return pos;
    }

    public static Vec3d getPrevPos() {
        return prevPos;
    }

    public static float getYaw() {
        return yaw;
    }

    public static float getPitch() {
        return pitch;
    }

    public static float getPrevYaw() {
        return prevYaw;
    }

    public static float getPrevPitch() {
        return prevPitch;
    }

    public static void toggle(MinecraftClient client) {
        if (client.player == null) return;
        if (active) deactivate(client);
        else activate(client);
    }

    private static void activate(MinecraftClient client) {
        Camera camera = client.gameRenderer.getCamera();
        //? if >=1.21.10 {
        pos = camera.getCameraPos();
        //?} else {
        /*pos = camera.getPos();
        *///?}
        prevPos = pos;
        yaw = camera.getYaw();
        pitch = camera.getPitch();
        velocity = Vec3d.ZERO;
        pinActive = false;
        pinEntity = null;
        locked = false;

        // Blank the WASD/jump/sneak input so the body doesn't also walk off wherever freecam
        // flies — but leave gravity, momentum and collision completely alone. An earlier
        // version also re-pinned position and zeroed velocity every tick to stop any drift at
        // all, which was exactly wrong: pinned position beats gravity, so a player who
        // activated freecam mid-air hung there indefinitely — a flight hack in survival. No
        // input is all this needs; a player who just lets go of the keys still falls, still
        // gets bumped by an explosion, exactly as if they had stopped moving on purpose.
        savedInput = client.player.input;
        client.player.input = new Input();
        lastHealth = client.player.getHealth();
        active = true;
    }

    private static void deactivate(MinecraftClient client) {
        active = false;
        pinActive = false;
        pinEntity = null;
        locked = false;
        targetedKeyframeIndex = -1;
        draggingKeyframeIndex = -1;
        leftMouseHeld = false;
        rightMouseHeld = false;
        possessingKeyframe = false;
        pathFocusLockEnabled = false;
        pathFocusLockEntity = null;
        stopPathPlayback();
        if (client.player != null && savedInput != null) {
            client.player.input = savedInput;
        }
        savedInput = null;
    }

    /**
     * Freezes (or un-freezes) the camera in place and swaps the player's input accordingly.
     * Locked: the camera stops responding to WASD/mouse entirely, and the player's real input
     * comes back so they walk and look normally, as if freecam weren't running at all — the
     * shot just isn't looking through their eyes. Unlocked: the reverse, back to flying the
     * camera with the player's own input blanked again, exactly like on activation.
     */
    public static void toggleLock(MinecraftClient client) {
        if (!active || client.player == null) return;
        locked = !locked;
        if (locked) {
            if (savedInput != null) client.player.input = savedInput;
        } else {
            client.player.input = new Input();
        }
    }

    /**
     * Accumulates one frame's mouse-look delta into the freecam's own orientation. Fed by
     * {@link dev.shunti.snapmatica.client.mixin.MouseMixin}, which cancels the vanilla path
     * (player.changeLookDirection) while freecam is active so the player's own facing never
     * moves. Scaling matches {@code Entity.changeLookDirection} exactly, so freecam turns at
     * the same rate normal look does.
     *
     * <p>Ignored while orbiting a pin, or while a camera path is playing back — both compute
     * the camera's orientation themselves every tick, so mouse input would just be overwritten
     * a moment later anyway.
     */
    public static void onMouseLook(double dx, double dy) {
        if (!active || pinActive || pathPlaying) return;
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp((float) (pitch + dy * 0.15), -90.0f, 90.0f);
    }

    // ── Camera path API ─────────────────────────────────────────────────────────────

    public static boolean isPathPlaying() {
        return pathPlaying;
    }

    public static int pathKeyframeCount() {
        return path.size();
    }

    /** Read-only view of the recorded keyframes, for drawing the path in-world. */
    public static List<Keyframe> getPath() {
        return java.util.Collections.unmodifiableList(path);
    }

    /** Live distance from the current position to the last recorded keyframe, or -1 with none
     *  recorded yet — the HUD's running readout for spacing keyframes evenly. */
    public static double distanceFromLastKeyframe() {
        if (path.isEmpty()) return -1;
        return pos.distanceTo(path.get(path.size() - 1).pos());
    }

    /** The focal length to actually render THIS frame: smoothly interpolated between ticks
     *  while a camera path is playing, the plain stepped value otherwise (unchanged from
     *  before — nothing about manual zoom needed smoothing, only the automated dolly). */
    public static float currentFocalLengthMm(float tickDelta) {
        if (!pathPlaying) return SnapmaticaClient.focalLengthMm;
        return pathPrevFocalLenMm + (pathFocalLenMm - pathPrevFocalLenMm) * tickDelta;
    }

    /**
     * Drops a keyframe at the current position and orientation, and reports the distance from
     * the previous one on the action bar — evenly SPACED keyframes are what actually gives
     * even on-screen speed during playback, since the total duration is split by keyframe
     * count, not by distance travelled (see {@link #pathDurationSec}).
     */
    public static void addPathKeyframe(MinecraftClient client) {
        if (!active || pathPlaying) return;
        double dist = path.isEmpty() ? -1 : pos.distanceTo(path.get(path.size() - 1).pos());
        Vec3d pinTarget = pinActive
                ? (pinEntity != null ? pinEntity.getBoundingBox().getCenter() : pinPos)
                : Vec3d.ZERO;
        path.add(new Keyframe(pos, yaw, pitch, SnapmaticaClient.focalLengthMm, pinActive, pinTarget));
        if (client.player == null) return;
        if (dist < 0) {
            client.player.sendMessage(Text.translatable("snapmatica.path.keyframe_first", path.size()), true);
        } else {
            client.player.sendMessage(Text.translatable("snapmatica.path.keyframe_added",
                    path.size(), String.format("%.1f", dist)), true);
        }
    }

    /**
     * Removes whichever keyframe the reticle is currently on — middle click's other half, the
     * empty-space half being {@link #addPathKeyframe}. Drops the drag first if that happened
     * to be the keyframe being held, and shifts a live drag's index down when a keyframe
     * earlier in the list disappears out from under it.
     */
    public static void deleteTargetedKeyframe(MinecraftClient client) {
        if (!active || pathPlaying) return;
        int idx = targetedKeyframeIndex;
        if (idx < 0 || idx >= path.size()) return;

        if (draggingKeyframeIndex == idx) {
            if (possessingKeyframe) restoreFlyView();
            draggingKeyframeIndex = -1;
        } else if (draggingKeyframeIndex > idx) {
            draggingKeyframeIndex--;
        }
        path.remove(idx);
        targetedKeyframeIndex = -1;

        if (client.player != null) {
            client.player.sendMessage(Text.translatable("snapmatica.path.keyframe_removed", path.size()), true);
        }
    }

    /** Drops every recorded keyframe and stops any playback in progress. */
    public static void clearPath(MinecraftClient client) {
        path.clear();
        stopPathPlayback();
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("snapmatica.path.cleared"), true);
        }
    }

    /**
     * Starts (or stops) flying the recorded keyframes in order, spending
     * {@link #pathDurationSec} total across however many are recorded. Needs at least two —
     * with only one, "playing" it would just be sitting still. Cancels an active orbit pin the
     * same way togglePin's own toggle does, since path playback and orbiting both want to own
     * position and orientation outright.
     */
    public static void togglePathPlayback(MinecraftClient client) {
        if (!active) return;
        if (pathPlaying) {
            stopPathPlayback();
            return;
        }
        if (path.size() < 2) {
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("snapmatica.path.too_short"), true);
            }
            return;
        }
        pinActive = false;
        pinEntity = null;
        pathElapsedMs = 0;
        pathUnwrappedYaw = new float[path.size()];
        pathUnwrappedYaw[0] = path.get(0).yaw();
        for (int i = 1; i < path.size(); i++) {
            float prev = pathUnwrappedYaw[i - 1];
            float diff = ((path.get(i).yaw() - prev + 540f) % 360f) - 180f;
            pathUnwrappedYaw[i] = prev + diff;
        }
        pathFocalLenMm = pathPrevFocalLenMm = path.get(0).focalLengthMm();

        // Snap straight to the first keyframe's exact pose — both the "current" and
        // "previous" copies, so CameraMixin's per-frame lerp has nowhere to pan FROM. Left
        // alone, prevPos/prevYaw/prevPitch still held wherever the freecam was flying the
        // instant Play was pressed, so the render camera visibly snap-panned from there to
        // the path's start over the first tick — a real, if brief, jump, and exactly the kind
        // of thing that shows up as "unstable" in a recording that starts capturing
        // immediately (see playPathWithRecording).
        Keyframe first = path.get(0);
        pos = prevPos = first.pos();
        yaw = prevYaw = first.yaw();
        pitch = prevPitch = first.pitch();

        pathPlaying = true;
    }

    /** Set once by {@link #playPathWithRecording}; checked by {@link #stopPathPlayback} so
     *  recording started alongside a path always ends exactly when the path does, whether that
     *  is the path running its full length or a manual Stop cutting it short. */
    private static boolean pathAutoStopRecording = false;

    /**
     * Starts path playback and video recording together — the pairing Camera Path's menu
     * offers as a single button, since timing a separate record key press to the exact frame
     * playback starts (and stopping it the instant playback ends) is exactly the kind of thing
     * this mod exists to do FOR the photographer rather than ask them to do by hand.
     */
    public static void playPathWithRecording(MinecraftClient client) {
        if (!active || pathPlaying) return;
        togglePathPlayback(client);
        if (pathPlaying) {
            pathAutoStopRecording = true;
            if (!VideoRecorder.isRecording()) VideoRecorder.startRecording();
        }
    }

    private static void stopPathPlayback() {
        pathPlaying = false;
        if (pathAutoStopRecording) {
            pathAutoStopRecording = false;
            if (VideoRecorder.isRecording()) VideoRecorder.stopRecording();
        }
    }

    // ── Camera path: grab-and-drag editing ──────────────────────────────────────────

    public static int getTargetedKeyframeIndex() {
        return targetedKeyframeIndex;
    }

    public static int getDraggingKeyframeIndex() {
        return draggingKeyframeIndex;
    }

    public static int getTargetedCurveSegment() {
        return targetedCurveSegment;
    }

    /** Current look direction — same formula the pin/orbit target raycast uses. */
    private static Vec3d lookDir() {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        return new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
    }

    /** Finds the keyframe nearest the centre of the reticle within a narrow cone — the same
     *  targeting test {@link #togglePin} uses for an entity, just against recorded points. */
    private static void updateTargetedKeyframe() {
        if (pathPlaying) { targetedKeyframeIndex = -1; return; }
        if (draggingKeyframeIndex >= 0) { targetedKeyframeIndex = draggingKeyframeIndex; return; }

        Vec3d look = lookDir();
        int best = -1;
        double bestDist = KEYFRAME_TARGET_MAX_DIST;
        for (int i = 0; i < path.size(); i++) {
            Vec3d toKf = path.get(i).pos().subtract(pos);
            double dist = toKf.length();
            if (dist < 0.1 || dist > bestDist) continue;
            if (toKf.normalize().dotProduct(look) >= KEYFRAME_CONE_COS) {
                best = i;
                bestDist = dist;
            }
        }
        targetedKeyframeIndex = best;
    }

    /**
     * Finds the point along the curve itself (not one of the recorded keyframes) the reticle
     * is nearest to, within the same cone — the target for a mid-path insert. Computed
     * independently of {@link #updateTargetedKeyframe} now (it used to skip entirely whenever
     * a keyframe was also targeted, which meant aiming anywhere near the path at all almost
     * always resolved to "delete this keyframe" — a curve sample sits exactly ON every
     * keyframe at each segment's t=0/t=1, so the two tests were fighting over the same point
     * far more often than not). Middle click decides the actual priority between them; this
     * only skips the exact keyframe positions themselves (the segment interior, t in (0,1) —
     * see the loop bounds) so aiming precisely at a keyframe still means the keyframe.
     */
    private static void updateTargetedCurvePoint() {
        targetedCurveSegment = -1;
        if (pathPlaying || path.size() < 2) return;

        Vec3d look = lookDir();
        int bestSeg = -1;
        double bestDist = KEYFRAME_TARGET_MAX_DIST;
        Vec3d bestPos = Vec3d.ZERO;
        int segments = path.size() - 1;
        for (int i = 0; i < segments; i++) {
            Keyframe p0 = path.get(Math.max(0, i - 1));
            Keyframe p1 = path.get(i);
            Keyframe p2 = path.get(i + 1);
            Keyframe p3 = path.get(Math.min(path.size() - 1, i + 2));
            for (int s = 1; s < CURVE_TEST_SAMPLES; s++) {
                double t = (double) s / CURVE_TEST_SAMPLES;
                Vec3d pt = catmullRom(p0.pos(), p1.pos(), p2.pos(), p3.pos(), t);
                Vec3d toPt = pt.subtract(pos);
                double dist = toPt.length();
                if (dist < 0.1 || dist > bestDist) continue;
                if (toPt.normalize().dotProduct(look) >= KEYFRAME_CONE_COS) {
                    bestSeg = i;
                    bestDist = dist;
                    bestPos = pt;
                }
            }
        }
        targetedCurveSegment = bestSeg;
        targetedCurvePos = bestPos;
    }

    /**
     * Inserts a new keyframe exactly on the curve where it's currently targeted, between
     * whichever two recorded keyframes that point falls between — middle click's third case,
     * after "on a keyframe" (delete) and "nothing targeted" (append at the end). Orientation
     * and lens come from the current view, same as a normal append; only the position is the
     * point that was actually aimed at.
     */
    public static void insertKeyframeOnCurve(MinecraftClient client) {
        if (!active || pathPlaying || targetedCurveSegment < 0) return;
        int insertIdx = targetedCurveSegment + 1;
        Vec3d pinTarget = pinActive
                ? (pinEntity != null ? pinEntity.getBoundingBox().getCenter() : pinPos)
                : Vec3d.ZERO;
        path.add(insertIdx, new Keyframe(targetedCurvePos, yaw, pitch,
                SnapmaticaClient.focalLengthMm, pinActive, pinTarget));

        if (draggingKeyframeIndex >= insertIdx) draggingKeyframeIndex++;
        targetedKeyframeIndex = insertIdx;
        targetedCurveSegment = -1;

        if (client.player != null) {
            client.player.sendMessage(Text.translatable("snapmatica.path.keyframe_inserted", path.size()), true);
        }
    }

    /**
     * Left mouse button state, tracked by MouseMixin from raw press/release events (not
     * {@code wasPressed()} — this is a hold, and needs to know the instant it's let go, not
     * just the instant it went down). Held with nothing targeted does nothing; held with a
     * target grabs it; released drops whatever's held.
     */
    private static boolean leftMouseHeld = false;
    /** Right mouse button state, same tracking. Held together with the left button while
     *  dragging switches from moving the keyframe to re-aiming it — see
     *  {@link #isEditingKeyframeOrientation()}. */
    private static boolean rightMouseHeld = false;

    /**
     * Edge-triggered on the actual press/release event, not level-triggered every tick — a
     * fresh press grabs whatever is targeted RIGHT NOW, once. Doing this per-tick instead (as
     * long as the button is held and something is targeted) meant simply flying close enough
     * to a keyframe while already holding the button down — approaching it, not clicking on
     * it — silently grabbed it and dragged it along. A click has to actually land on the
     * keyframe to mean anything, the same way clicking anything else does.
     */
    public static void setLeftMouseHeld(boolean held) {
        boolean wasHeld = leftMouseHeld;
        leftMouseHeld = held;
        if (held && !wasHeld) {
            if (!active || pathPlaying || targetedKeyframeIndex < 0 || targetedKeyframeIndex >= path.size()) return;
            draggingKeyframeIndex = targetedKeyframeIndex;
            dragDepth = pos.distanceTo(path.get(draggingKeyframeIndex).pos());
        } else if (!held && wasHeld) {
            if (possessingKeyframe) restoreFlyView();
            draggingKeyframeIndex = -1;
        }
    }

    public static void setRightMouseHeld(boolean held) { rightMouseHeld = held; }

    /** True while the right button joins the left mid-drag — the camera's own look stops
     *  taking the mouse (see MouseMixin.snapmatica$freecamLook) and {@link #onMouseLookForKeyframe}
     *  takes it instead, so the SAME motion that would otherwise pan the shot re-aims the
     *  keyframe being held in place instead of moving it. */
    public static boolean isEditingKeyframeOrientation() {
        return draggingKeyframeIndex >= 0 && leftMouseHeld && rightMouseHeld;
    }

    /** Where the freecam actually was flying before an orientation edit borrowed the render
     *  camera to show the keyframe's own view instead — restored the moment the edit ends. */
    private static Vec3d savedFlyPos = Vec3d.ZERO;
    private static float savedFlyYaw, savedFlyPitch;
    private static int savedFlyFocal;
    private static boolean possessingKeyframe = false;

    /**
     * Advances the grab/drag state machine by one tick. Picking a keyframe up happens on the
     * press event itself (see {@link #setLeftMouseHeld}) rather than here — this only carries
     * an already-grabbed keyframe forward: following the reticle at {@link #dragDepth} (left
     * alone), or — left and right together — holding the rendered camera at the keyframe's own
     * position and orientation so re-aiming it is judged by the picture it would actually take
     * rather than the unrelated view the freecam happened to be flying past at the time.
     */
    private static void updateKeyframeDragState() {
        if (pathPlaying) {
            if (possessingKeyframe) restoreFlyView();
            draggingKeyframeIndex = -1;
            return;
        }
        if (draggingKeyframeIndex < 0) return;
        if (draggingKeyframeIndex >= path.size()) {
            if (possessingKeyframe) restoreFlyView();
            draggingKeyframeIndex = -1;
            return;
        }

        if (rightMouseHeld) {
            if (!possessingKeyframe) {
                savedFlyPos = pos; savedFlyYaw = yaw; savedFlyPitch = pitch;
                savedFlyFocal = SnapmaticaClient.focalLengthMm;
                possessingKeyframe = true;
                Keyframe kf = path.get(draggingKeyframeIndex);
                pos = kf.pos(); yaw = kf.yaw(); pitch = kf.pitch();
                SnapmaticaClient.focalLengthMm = kf.focalLengthMm();
            }
            // Position holds at the keyframe; orientation is already kept live by
            // onMouseLookForKeyframe every frame, nothing more to do per tick.
            return;
        }
        if (possessingKeyframe) restoreFlyView();

        Vec3d newPos = pos.add(lookDir().multiply(dragDepth));
        Keyframe old = path.get(draggingKeyframeIndex);
        path.set(draggingKeyframeIndex, new Keyframe(newPos, old.yaw(), old.pitch(),
                old.focalLengthMm(), old.pinned(), old.pinTarget()));
    }

    private static void restoreFlyView() {
        pos = savedFlyPos;
        yaw = savedFlyYaw;
        pitch = savedFlyPitch;
        SnapmaticaClient.focalLengthMm = savedFlyFocal;
        possessingKeyframe = false;
    }

    /**
     * Mouse-look while {@link #isEditingKeyframeOrientation()} — adjusts the dragged
     * keyframe's own stored yaw/pitch instead of the camera's, with the same scaling
     * {@link #onMouseLook} uses so it doesn't feel any different to steer. Also drives the
     * live camera's own yaw/pitch, since {@link #updateKeyframeDragState} has already put the
     * camera AT the keyframe — this is what actually makes the edit visible frame to frame,
     * rather than only ever landing in the recorded value off-screen.
     */
    public static void onMouseLookForKeyframe(double dx, double dy) {
        if (draggingKeyframeIndex < 0 || draggingKeyframeIndex >= path.size()) return;
        Keyframe old = path.get(draggingKeyframeIndex);
        float newYaw = old.yaw() + (float) (dx * 0.15);
        float newPitch = MathHelper.clamp((float) (old.pitch() + dy * 0.15), -90.0f, 90.0f);
        path.set(draggingKeyframeIndex, new Keyframe(old.pos(), newYaw, newPitch,
                old.focalLengthMm(), old.pinned(), old.pinTarget()));
        yaw = newYaw;
        pitch = newPitch;
    }

    /**
     * Mouse-wheel input while a keyframe is being dragged: normally moves it along the look
     * direction instead of the scroll wheel's usual zoom/aperture/etc. job (scroll up pushes it
     * away, scroll down pulls it closer), but while {@link #isEditingKeyframeOrientation()} —
     * right-click held too, re-aiming rather than repositioning — the wheel steps the
     * keyframe's own focal length instead, so a dolly-zoom shot can be dialled in from exactly
     * the possessed viewpoint that's already showing what the lens sees.
     *
     * @return true if a drag consumed this scroll (the caller should not also treat it as a
     *         normal camera-settings scroll)
     */
    public static boolean adjustDragDepth(double delta) {
        if (draggingKeyframeIndex < 0) return false;
        if (isEditingKeyframeOrientation()) {
            Keyframe old = path.get(draggingKeyframeIndex);
            int newFocal = CameraScrollHandler.stepFocalLength(old.focalLengthMm(), delta > 0 ? 1 : -1);
            path.set(draggingKeyframeIndex, new Keyframe(old.pos(), old.yaw(), old.pitch(),
                    newFocal, old.pinned(), old.pinTarget()));
            // Possession already put the live lens at the keyframe's focal length (see
            // updateKeyframeDragState) — keep it in step so the view being judged updates too.
            SnapmaticaClient.focalLengthMm = newFocal;
            return true;
        }
        dragDepth = Math.max(0.5, dragDepth + Math.signum(delta) * 0.5);
        return true;
    }

    /**
     * Drops a pin on whatever is under the reticle — a living entity within a narrow cone of
     * the look direction if one is closer than the block behind it, otherwise the block
     * itself — and starts orbiting it at the current distance and height. Works regardless of
     * {@link SnapmaticaClient#droneMode}, and the orbit itself follows that same toggle —
     * instant response with it off, inertia with it on — see {@link #tickOrbit}. Called again,
     * clears the pin and returns to free flight from wherever the orbit left off.
     */
    public static void togglePin(MinecraftClient client) {
        if (!active || client.world == null) return;

        if (pinActive) {
            pinActive = false;
            pinEntity = null;
            velocity = Vec3d.ZERO;
            orbitAngleVel = 0;
            orbitRadiusVel = 0;
            orbitHeightVel = 0;
            return;
        }

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        Vec3d look = new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));

        final double maxDist = 200.0;
        Vec3d end = pos.add(look.multiply(maxDist));
        BlockHitResult blockHit = AutoFocus.raycastThroughGlass(client, pos, look, maxDist);
        double bestDist = (blockHit != null && blockHit.getType() != HitResult.Type.MISS)
                ? pos.distanceTo(blockHit.getPos()) : maxDist;
        Vec3d hitPos = (blockHit != null && blockHit.getType() != HitResult.Type.MISS)
                ? blockHit.getPos() : end;

        Entity nearest = null;
        double nearestDist = bestDist;
        Box searchBox = new Box(pos, pos).expand(maxDist);
        for (Entity e : client.world.getOtherEntities(client.player, searchBox,
                ent -> ent.isAlive())) {
            Vec3d toEnt = e.getBoundingBox().getCenter().subtract(pos);
            double dist = toEnt.length();
            if (dist < 0.1 || dist > nearestDist) continue;
            if (toEnt.normalize().dotProduct(look) >= PIN_CONE_COS) {
                nearest = e;
                nearestDist = dist;
            }
        }
        // getOtherEntities always excludes the entity passed as its first argument — normally
        // exactly what you want (nobody raycasts themself), but with the camera flown away
        // from the player, they're a valid pin target like anyone else, so they're checked
        // separately — same reasoning as the AF self-focus fix.
        Vec3d toSelf = client.player.getBoundingBox().getCenter().subtract(pos);
        double selfDist = toSelf.length();
        if (selfDist >= 0.1 && selfDist <= nearestDist
                && toSelf.normalize().dotProduct(look) >= PIN_CONE_COS) {
            nearest = client.player;
            nearestDist = selfDist;
        }

        Vec3d center;
        if (nearest != null) {
            pinEntity = nearest;
            center = nearest.getBoundingBox().getCenter();
        } else {
            pinEntity = null;
            center = hitPos;
        }
        pinPos = center;

        Vec3d fromCenter = pos.subtract(center);
        orbitRadius = Math.max(ORBIT_MIN_RADIUS, fromCenter.horizontalLength());
        orbitHeight = fromCenter.y;
        orbitAngle = Math.atan2(fromCenter.z, fromCenter.x);
        pinActive = true;
    }

    public static boolean isPathFocusLockEnabled() { return pathFocusLockEnabled; }

    /**
     * Toggles the Camera Path menu's focus lock. Turning it on captures whatever sits under
     * the reticle right now — a living entity within a narrow cone if one is closer than the
     * block behind it (the player counts too, once freecam has moved the camera away from
     * them, same as {@link #togglePin}), otherwise the block itself — and holds focus on it
     * through every subsequent path playback however the shot moves the camera around it.
     * Turning it off drops the target and lets the selected focus mode (MF/AF/MOB) run
     * playback as before. Reuses {@link #togglePin}'s own block-vs-entity targeting rather
     * than a second copy of it, since "what's under the reticle right now" is exactly the
     * same question either way.
     */
    public static void togglePathFocusLock(MinecraftClient client) {
        if (pathFocusLockEnabled) {
            pathFocusLockEnabled = false;
            pathFocusLockEntity = null;
            return;
        }
        if (client.world == null || client.player == null) return;

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        Vec3d look = new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));

        final double maxDist = 200.0;
        Vec3d end = pos.add(look.multiply(maxDist));
        BlockHitResult blockHit = AutoFocus.raycastThroughGlass(client, pos, look, maxDist);
        double bestDist = (blockHit != null && blockHit.getType() != HitResult.Type.MISS)
                ? pos.distanceTo(blockHit.getPos()) : maxDist;
        Vec3d hitPos = (blockHit != null && blockHit.getType() != HitResult.Type.MISS)
                ? blockHit.getPos() : end;

        Entity nearest = null;
        double nearestDist = bestDist;
        Box searchBox = new Box(pos, pos).expand(maxDist);
        for (Entity e : client.world.getOtherEntities(client.player, searchBox,
                ent -> ent.isAlive())) {
            Vec3d toEnt = e.getBoundingBox().getCenter().subtract(pos);
            double dist = toEnt.length();
            if (dist < 0.1 || dist > nearestDist) continue;
            if (toEnt.normalize().dotProduct(look) >= PIN_CONE_COS) {
                nearest = e;
                nearestDist = dist;
            }
        }
        Vec3d toSelf = client.player.getBoundingBox().getCenter().subtract(pos);
        double selfDist = toSelf.length();
        if (selfDist >= 0.1 && selfDist <= nearestDist
                && toSelf.normalize().dotProduct(look) >= PIN_CONE_COS) {
            nearest = client.player;
            nearestDist = selfDist;
        }

        if (nearest != null) {
            pathFocusLockEntity = nearest;
        } else {
            pathFocusLockEntity = null;
            pathFocusLockPoint = hitPos;
        }
        pathFocusLockEnabled = true;
    }

    /**
     * Live distance from the current camera position to whatever the path focus lock
     * captured — an entity's own current position if it locked onto one (so it keeps
     * tracking a moving subject through the whole flythrough), a fixed world point otherwise
     * — or null if the lock isn't armed, or its entity has since died or unloaded (which also
     * drops the lock, same as the target simply not being there to hold focus on anymore).
     */
    public static Float pathFocusLockDistance() {
        if (!pathFocusLockEnabled) return null;
        if (pathFocusLockEntity != null) {
            if (!pathFocusLockEntity.isAlive() || pathFocusLockEntity.isRemoved()) {
                pathFocusLockEnabled = false;
                pathFocusLockEntity = null;
                return null;
            }
            return (float) pos.distanceTo(pathFocusLockEntity.getBoundingBox().getCenter());
        }
        return (float) pos.distanceTo(pathFocusLockPoint);
    }

    /** Advances the freecam by one tick, in whichever mode is currently selected. */
    public static void tick(MinecraftClient client) {
        if (!active || client.player == null) return;

        float hp = client.player.getHealth();
        if (hp < lastHealth) {
            // Hand control back the instant something hits the player — frozen and facing
            // wherever it was left is not a position to fight or flee from, so this is a
            // safety net, not a suggestion.
            deactivate(client);
            return;
        }
        lastHealth = hp;

        prevPos = pos;
        prevYaw = yaw;
        prevPitch = pitch;
        pathPrevFocalLenMm = pathFocalLenMm;

        updateTargetedKeyframe();
        updateTargetedCurvePoint();
        updateKeyframeDragState();

        // Camera path playback owns position and orientation outright, same as orbiting a
        // pin — runs regardless of locked, since it drives the camera itself rather than
        // reading WASD.
        if (pathPlaying) {
            tickPath();
            return;
        }

        // Tripod mode: the camera holds exactly here. WASD went back to the player at
        // toggleLock(), so there's nothing to read from the keyboard for the camera itself.
        if (locked) return;

        // Possessing a keyframe's viewpoint to judge an orientation edit: position holds at
        // the keyframe (see updateKeyframeDragState), so WASD has nothing to fly right now
        // either — reading it here would pull the camera off the keyframe while the edit
        // still thinks it's looking through it.
        if (possessingKeyframe) return;

        //? if >=1.21.10 {
        net.minecraft.client.util.Window window = client.getWindow();
        //?} else {
        /*long window = client.getWindow().getHandle();
        *///?}

        boolean w = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W);
        boolean s = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S);
        boolean a = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A);
        boolean d = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D);
        boolean up = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_SPACE);
        boolean down = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT);
        boolean fast = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_CONTROL);

        if (pinActive) {
            // Orbiting is its own control feel, independent of the direct/inertia choice below
            // — it was gated behind droneMode entirely at first, which silently ate every
            // pin drop while it was off rather than actually orbiting anything.
            tickOrbit(w, s, a, d, up, down, fast);
        } else if (!SnapmaticaClient.droneMode) {
            tickDirect(w, s, a, d, up, down, fast);
        } else {
            tickInertia(w, s, a, d, up, down, fast);
        }
    }

    /** The original control feel: instant velocity, no drift, easiest to place exactly. */
    private static void tickDirect(boolean w, boolean s, boolean a, boolean d,
                                   boolean up, boolean down, boolean fast) {
        if (!(w || s || a || d || up || down)) return;

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        Vec3d forward = new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        // -cos/-sin, not +cos/+sin: at yaw 0 (facing south, +Z) the player's right hand is
        // west (-X), not east. Was the other sign, which put D on the left and A on the right.
        Vec3d right = new Vec3d(-Math.cos(yawRad), 0, -Math.sin(yawRad));

        double speed = SPEED * (fast ? SPEED_FAST_MULT : 1.0);
        Vec3d move = Vec3d.ZERO;
        if (w) move = move.add(forward);
        if (s) move = move.subtract(forward);
        if (d) move = move.add(right);
        if (a) move = move.subtract(right);
        if (up) move = move.add(0, 1, 0);
        if (down) move = move.add(0, -1, 0);

        if (move.lengthSquared() > 0) {
            pos = pos.add(move.normalize().multiply(speed));
        }
    }

    /** Drone mode, no pin: accelerates rather than snapping to speed, and drifts to a stop. */
    private static void tickInertia(boolean w, boolean s, boolean a, boolean d,
                                    boolean up, boolean down, boolean fast) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        Vec3d forward = new Vec3d(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        Vec3d right = new Vec3d(-Math.cos(yawRad), 0, -Math.sin(yawRad));

        double accelMag = DRONE_ACCEL * (fast ? DRONE_SPEED_FAST_MULT : 1.0);
        Vec3d thrust = Vec3d.ZERO;
        if (w) thrust = thrust.add(forward);
        if (s) thrust = thrust.subtract(forward);
        if (d) thrust = thrust.add(right);
        if (a) thrust = thrust.subtract(right);
        if (thrust.lengthSquared() > 0) {
            velocity = velocity.add(thrust.normalize().multiply(accelMag));
        }

        // Altitude hold: with no vertical input, the vertical component brakes hard rather
        // than drifting on the same drag as horizontal flight — a real drone holds height,
        // it doesn't coast.
        double vy = velocity.y;
        if (up) vy += accelMag;
        else if (down) vy -= accelMag;
        else vy *= DRONE_VERTICAL_HOLD_DRAG;
        velocity = new Vec3d(velocity.x, vy, velocity.z);

        velocity = velocity.multiply(DRONE_DRAG);

        double maxSpeed = DRONE_MAX_SPEED * (fast ? DRONE_SPEED_FAST_MULT : 1.0);
        if (velocity.length() > maxSpeed) {
            velocity = velocity.normalize().multiply(maxSpeed);
        }

        pos = pos.add(velocity);
    }

    /**
     * Pin dropped: WASD/Space/Shift orbit it instead of flying freely. Follows
     * {@link SnapmaticaClient#droneMode} the same way the two free-flight modes do — instant,
     * snappy response with it off; accelerating and drifting to a stop with it on — rather
     * than being a fixed third feel of its own.
     */
    private static void tickOrbit(boolean w, boolean s, boolean a, boolean d,
                                  boolean up, boolean down, boolean fast) {
        if (pinEntity != null && !pinEntity.isAlive()) {
            // The subject died or unloaded — freeze the orbit centre where it last was rather
            // than snapping the shot to (0,0,0).
            pinPos = pinEntity.getBoundingBox().getCenter();
            pinEntity = null;
        }
        Vec3d center = (pinEntity != null) ? pinEntity.getBoundingBox().getCenter() : pinPos;

        double mult = fast ? ORBIT_FAST_MULT : 1.0;

        if (!SnapmaticaClient.droneMode) {
            double angleStep = (ORBIT_TANGENT_SPEED * mult) / Math.max(orbitRadius, ORBIT_MIN_RADIUS);
            // + on A, - on D: with the camera always turned to face the pin, increasing
            // orbitAngle moves it opposite its own right-hand side (same left/right mix-up as
            // the direct-fly strafe fix earlier, just derived from the orbit parameterization
            // instead of yaw).
            if (a) orbitAngle += angleStep;
            if (d) orbitAngle -= angleStep;
            if (w) orbitRadius = Math.max(ORBIT_MIN_RADIUS, orbitRadius - ORBIT_RADIUS_STEP * mult);
            if (s) orbitRadius += ORBIT_RADIUS_STEP * mult;
            if (up) orbitHeight += ORBIT_HEIGHT_STEP * mult;
            if (down) orbitHeight -= ORBIT_HEIGHT_STEP * mult;
        } else {
            double accel = DRONE_ACCEL * mult;

            double angleThrust = (a ? 1.0 : 0.0) - (d ? 1.0 : 0.0);
            orbitAngleVel = (orbitAngleVel + angleThrust * accel) * DRONE_DRAG;
            double maxTangent = ORBIT_TANGENT_SPEED * mult;
            if (Math.abs(orbitAngleVel) > maxTangent) {
                orbitAngleVel = Math.signum(orbitAngleVel) * maxTangent;
            }
            orbitAngle += orbitAngleVel / Math.max(orbitRadius, ORBIT_MIN_RADIUS);

            double radiusThrust = (s ? 1.0 : 0.0) - (w ? 1.0 : 0.0);
            orbitRadiusVel = (orbitRadiusVel + radiusThrust * accel) * DRONE_DRAG;
            orbitRadius = Math.max(ORBIT_MIN_RADIUS, orbitRadius + orbitRadiusVel);

            // Same altitude-hold feel as free-flight inertia mode: brakes hard with neither
            // Space nor Shift held, instead of coasting.
            if (up) orbitHeightVel += accel;
            else if (down) orbitHeightVel -= accel;
            else orbitHeightVel *= DRONE_VERTICAL_HOLD_DRAG;
            orbitHeightVel *= DRONE_DRAG;
            orbitHeight += orbitHeightVel;
        }

        pos = center.add(Math.cos(orbitAngle) * orbitRadius, orbitHeight,
                Math.sin(orbitAngle) * orbitRadius);

        Vec3d toCenter = center.subtract(pos);
        double horiz = Math.sqrt(toCenter.x * toCenter.x + toCenter.z * toCenter.z);
        yaw = (float) Math.toDegrees(Math.atan2(-toCenter.x, toCenter.z));
        pitch = (float) Math.toDegrees(-Math.atan2(toCenter.y, horiz));
    }

    /**
     * Advances camera-path playback by one tick: a Catmull-Rom spline through every recorded
     * keyframe, sampled at wherever {@link #pathElapsedMs} over {@link #pathDurationSec} says
     * to be. Catmull-Rom rather than a straight lerp chain because it passes exactly through
     * every keyframe while still curving smoothly between them — a lerp chain would visibly
     * kink at each one.
     */
    private static void tickPath() {
        pathElapsedMs += 50.0; // one tick at 20 TPS
        double totalMs = pathDurationSec * 1000.0;
        int segments = path.size() - 1;

        if (pathElapsedMs >= totalMs || segments <= 0) {
            // Land exactly on the last keyframe rather than overshooting past the end of the
            // spline's defined range, then stop.
            Keyframe last = path.get(path.size() - 1);
            pos = last.pos();
            yaw = last.yaw();
            pitch = last.pitch();
            pathFocalLenMm = pathPrevFocalLenMm = last.focalLengthMm();
            SnapmaticaClient.focalLengthMm = last.focalLengthMm();
            stopPathPlayback();
            return;
        }

        double segMs = totalMs / segments;
        double t = pathElapsedMs / segMs;
        int i = Math.min((int) Math.floor(t), segments - 1);
        double localT = t - i;

        int i0 = Math.max(0, i - 1);
        int i1 = i;
        int i2 = Math.min(path.size() - 1, i + 1);
        int i3 = Math.min(path.size() - 1, i + 2);

        pos = catmullRom(path.get(i0).pos(), path.get(i1).pos(), path.get(i2).pos(), path.get(i3).pos(), localT);

        // A segment keeps facing its subject instead of following the orientation spline only
        // when BOTH keyframes bounding it were recorded with the orbit pin down — one pinned
        // pan joined to another reads as one continuous locked shot; a pinned pan joined to a
        // free one, or two free ones, both interpolate orientation normally. Mixed segments
        // fall back to the spline on purpose: there is no single "target" to blend toward when
        // only one end has one.
        Keyframe kf1 = path.get(i1), kf2 = path.get(i2);
        if (kf1.pinned() && kf2.pinned()) {
            Vec3d target = kf1.pinTarget().lerp(kf2.pinTarget(), localT);
            Vec3d toTarget = target.subtract(pos);
            double horiz = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            yaw = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
            pitch = (float) Math.toDegrees(-Math.atan2(toTarget.y, horiz));
        } else {
            // A direct eased lerp between this segment's own two keyframes, not a spline
            // across four of them. Catmull-Rom used the SAME neighbour-driven tangents here as
            // it does for position — fine for position, where a slightly overshot curve still
            // reads as smooth, but for orientation an overshoot is visible as the camera
            // swinging past where it's aiming and pulling back, a wobble with nothing to do
            // with wherever the keyframes were actually pointed. Smoothstep's zero velocity at
            // each end keeps the pan itself gentle without needing to look past this segment
            // at all, so an odd angle recorded two keyframes away can no longer bend it.
            double ease = localT * localT * (3.0 - 2.0 * localT);
            yaw = (float) (pathUnwrappedYaw[i1] + (pathUnwrappedYaw[i2] - pathUnwrappedYaw[i1]) * ease);
            pitch = (float) (kf1.pitch() + (kf2.pitch() - kf1.pitch()) * ease);
        }

        // A zoom/dolly move rides along for free: the lens is just another spline channel.
        // Kept as an unrounded float here (rather than written straight into the int-typed
        // SnapmaticaClient.focalLengthMm as before) so the render loop can lerp it smoothly
        // every frame the same way it already does for position and rotation — see
        // currentFocalLengthMm(). Rounding it to a whole millimetre once per 50 ms tick was
        // exactly what made the zoom (and the depth-of-field blur radius, which is also a
        // function of focal length) visibly step instead of glide.
        double fmm = catmullRom(path.get(i0).focalLengthMm(), path.get(i1).focalLengthMm(),
                path.get(i2).focalLengthMm(), path.get(i3).focalLengthMm(), localT);
        pathFocalLenMm = (float) Math.max(1.0, fmm);
    }

    /** Uniform Catmull-Rom (tension 0.5) through p1→p2, using p0/p3 as the neighbours that
     *  shape the curve's tangent at each end. */
    private static Vec3d catmullRom(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, double t) {
        return new Vec3d(
                catmullRom(p0.x, p1.x, p2.x, p3.x, t),
                catmullRom(p0.y, p1.y, p2.y, p3.y, t),
                catmullRom(p0.z, p1.z, p2.z, p3.z, t));
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * p1)
                + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
