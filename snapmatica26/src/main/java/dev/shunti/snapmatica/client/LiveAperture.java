package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

/**
 * Averaging the live view over time, to spend more taps on it than one frame can hold.
 *
 * <p>A gather is a fixed tap budget over whatever disc the optics ask for, and at the extremes
 * the optics ask for more than any budget covers. Measured at 500 mm f/2 with 375 mm to the
 * block: a subject 20 blocks inside the focal plane wants a circle of confusion 609 px ACROSS,
 * and the 384 taps that go into it work out at one tap per 3000 px2 - a 55 px cell deciding one
 * pixel's colour. No single frame wins that, and the result is the broad, smooth blotching that
 * turns up on a long lens wide open and nowhere else.
 *
 * <p>A viewfinder has something a shutter does not: the NEXT frame, and the one after that.
 * Averaging {@code k} frames of INDEPENDENT noise divides it by {@code sqrt(k)} - 48 frames is
 * about seven times cleaner, the same picture as twelve thousand taps, for one fullscreen blend
 * a frame and no extra geometry. Independence is the whole condition, and it is why the
 * per-frame noise rotation and offset built for the burst run here too: with a fixed noise tile
 * every frame carries the same pattern, and averaging it with itself achieves nothing.
 *
 * <h2>What this deliberately does NOT do</h2>
 *
 * <p>The first version of this also walked the entrance pupil - one point per frame, a fifth of
 * the excursion - on the reasoning that the average would converge on true occlusion the way the
 * burst does, while the running mean hid the movement. It does converge, and the movement is not
 * hidden: the world swims under a stationary HUD at 60 Hz, which is a recipe for motion sickness
 * rather than a feature. Removed, not made optional - an option whose honest description is
 * "may make you ill" is not an option.
 *
 * <p>The two halves turned out to be separable, which is the useful part. Occlusion needs
 * parallax and therefore needs the camera to move; NOISE does not need the camera to move at
 * all, only the sampling pattern to change. So the live view keeps the half that costs nothing
 * to look at, and seeing behind a defocused foreground stays what it has been all along: a
 * property of the photograph, where the shutter can afford to move the eye and nobody is
 * watching it happen.
 */
@Environment(EnvType.CLIENT)
public final class LiveAperture {
    private LiveAperture() {}

    /** Where the average stops deepening and becomes an exponential one that keeps tracking. */
    private static final int MAX_ACCUM = 48;

    /** Beyond this the viewpoint is not the same viewpoint and the average is of two scenes. */
    private static final double MOVE_EPS_BLOCKS = 0.02;
    private static final double TURN_EPS_DEG    = 0.05;

    private static volatile int   sample = 0;     // frames drawn, and with it the noise pattern
    private static volatile int   accum  = 0;     // frames already in the average
    private static volatile double baseX, baseY, baseZ;
    private static volatile float  baseYaw, basePitch;
    private static volatile boolean haveBase = false;
    private static volatile int lastW = 0, lastH = 0;
    private static volatile float lastAperture = -1f, lastFocalMm = -1f, lastScale = -1f;

    /**
     * Whether the live view should be averaging at all.
     *
     * <p>The VIEWFINDER only. The ambient mode is depth of field while the game is being played,
     * and a running average is wrong for that in a way it is not wrong for a finder: the average
     * only deepens while the viewpoint holds still, so every pause cleans the picture up and
     * every step dirties it again, and a effect that changes texture with whether the player
     * happens to be moving reads as the renderer misbehaving rather than as a lens. A finder is
     * held still on purpose, by someone who is waiting for the picture and will not mind it
     * arriving.
     */
    public static boolean isActive() {
        Minecraft mc = Minecraft.getInstance();
        return SnapmaticaClient.liveTemporalIntegration
                && SnapmaticaClient.lensType != 0
                && mc != null && SnapmaticaClient.viewfinderActive(mc)
                && !ApertureIntegration.isActive()
                && !PhotoCapture.isLongExposing();
    }

    /** Frames already folded into the running average; 0 means the next frame starts one. */
    public static int accumulated() { return accum; }

    /**
     * Which noise pattern this frame draws with.
     *
     * <p>Keeps counting after the average stops deepening, which {@link #accumulated()} does
     * not. Once the weight has bottomed out the average is an exponential one that runs for
     * ever, and it only goes on cleaning up if the grain going into it keeps changing; a counter
     * that stopped would leave the steady state averaging one pattern with itself.
     */
    public static int sampleIndex() { return sample; }

    /**
     * Weight for the incoming frame.
     *
     * <p>{@code 1/(k+1)} while the average is still being built, which is the exact running mean
     * - every frame so far counts the same, as they must, being samples of one quantity. Floored
     * after {@link #MAX_ACCUM} so the view keeps following the world instead of freezing on a
     * picture of it.
     */
    public static float blendWeight() {
        return 1.0f / (float) (Math.min(accum, MAX_ACCUM) + 1);
    }

    /** Throw the average away - the next frame starts a new one. */
    public static void reset() { accum = 0; }

    /**
     * Advance the frame counter, and decide whether the average survives into it.
     *
     * <p>Called once per rendered frame, before the world is drawn. The camera is read straight,
     * with nothing of ours added to it, because nothing of ours is added to it any more.
     */
    public static void beginFrame() {
        if (!isActive()) { accum = 0; haveBase = false; return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.gameRenderer == null) return;
        int fbW = mc.getWindow().getWidth();
        int fbH = mc.getWindow().getHeight();

        float ap    = Math.max(0.1f, SnapmaticaClient.aperture);
        float fmm   = SnapmaticaClient.focalLengthMm;
        float scale = Math.max(1e-4f, SnapmaticaClient.dofScaleMm);
        // Anything that changes the optics changes what is being averaged, so the frames already
        // in it are frames of a different picture.
        if (fbW != lastW || fbH != lastH
                || ap != lastAperture || fmm != lastFocalMm || scale != lastScale) {
            reset();
            lastW = fbW; lastH = fbH;
            lastAperture = ap; lastFocalMm = fmm; lastScale = scale;
        }

        net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
        if (cam != null) {
            net.minecraft.world.phys.Vec3 p = SnapmaticaClient.cameraPos(mc);
            float yaw = cam.yRot(), pitch = cam.xRot();
            if (haveBase) {
                double dx = p.x - baseX, dy = p.y - baseY, dz = p.z - baseZ;
                double dYaw = Math.abs(((yaw - baseYaw + 540.0) % 360.0) - 180.0);
                if (dx * dx + dy * dy + dz * dz > MOVE_EPS_BLOCKS * MOVE_EPS_BLOCKS
                        || dYaw > TURN_EPS_DEG
                        || Math.abs(pitch - basePitch) > TURN_EPS_DEG) {
                    reset();
                }
            }
            baseX = p.x; baseY = p.y; baseZ = p.z; baseYaw = yaw; basePitch = pitch;
            haveBase = true;
        }
        sample++;
    }

    /** Called after a frame has been folded in. */
    public static void endFrame() {
        if (isActive() && accum < MAX_ACCUM) accum++;
    }
}
