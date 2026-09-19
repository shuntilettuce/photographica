package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.HashMap;
import java.util.Map;

/**
 * Put entities on the exposure clock — record the exposure, then replay it into the samples.
 *
 * <p>The three world clocks are re-based to the shutter ({@code WorldTimeMixin},
 * {@code CloudTimeMixin}, {@code IrisTimerMixin}), so the sun, the clouds and the waving grass
 * move by exactly as much as the shutter speed says. Mobs did not, and the measurement was
 * unambiguous — four bursts from the log:
 *
 * <pre>  1/15  s : exposure 1.33 ticks, burst ran 1912 ms ≈ 38 ticks
 *   1/60  s : exposure 0.33 ticks, burst ran 1555 ms ≈ 31 ticks
 *   1/250 s : exposure 0.08 ticks, burst ran 1620 ms ≈ 32 ticks  </pre>
 *
 * A photograph marked 1/250 s was carrying thirty-two ticks of walking. The shutter dial moved
 * the whole world except the one thing in it that anybody photographs.
 *
 * <h2>Why the obvious fixes are both wrong</h2>
 *
 * <p>Stopping the tick loop is not on the table: a client mod that stops simulating desyncs the
 * player off a server. Letting real time carry the entities — the burst is paced, so a
 * one-second photograph takes a real second and the mob really does walk through it — sounds
 * right and is not: which instant each sample lands on is then decided by when a frame happened
 * to arrive, so the exposure is sampled unevenly and differently at every framerate, and below
 * about half a second there is not enough real time to fit the frames into at all.
 *
 * <p>The sample's instant has to be CHOSEN, the way its pupil position is chosen:
 *
 * <pre>  e_i = ((i + 0.5) / n) · T · 20      exposure tick of sample i  </pre>
 *
 * <p>which is the whole of the specification. 1/20 s spans one tick and every sample lives
 * inside it; 1/10 s spans two, so the first sample is at tick 1 and the last at tick 2; 1/2 s
 * spans ten, first at 1 and last at 10.
 *
 * <h2>Record, then replay</h2>
 *
 * <p>An entity's drawn appearance is a pure function of its two-tick interpolation bracket and a
 * phase: {@code EntityRenderer.getAndUpdateRenderState(entity, φ)} returns a complete, freshly
 * built {@code EntityRenderState} for any {@code φ} inside the current tick — position,
 * body and head yaw, limb swing, age, held item, every field, computed by vanilla. Within a tick
 * the phase is free. Across ticks it is not, because the bracket has moved on.
 *
 * <p>So the shutter opens into a RECORD phase lasting exactly as long as the exposure. Each
 * client tick, the render frame that follows it asks vanilla for the states at the phases
 * belonging to that tick, and keeps them. When the last one is in, the EXPOSE phase runs the
 * pupil samples as fast as frames arrive, and each sub-frame is served the state recorded for
 * its own {@code e_i}. Nothing is interpolated by hand and no field is enumerated, so a mob's
 * legs, its turning head and its position all stay consistent with each other — they came out of
 * one call.
 *
 * <p>Total capture is {@code T} (recording, which is the exposure, and is what it costs a real
 * camera too) plus the burst's own frames. A 1/250 s photograph pays one tick for this; a
 * 30-second one pays thirty seconds, which is the correct price.
 *
 * <p>Nothing here touches the simulation. The recorded states are scratch objects the renderer
 * builds and throws away every frame; keeping copies of them is invisible to the tick loop, to
 * the server, and to the entity itself.
 *
 * <h2>Limits</h2>
 *
 * <ul>
 *   <li>{@link #MAX_SLOTS} distinct instants. Beyond that, samples share the nearest recorded
 *       one — 64 positions along a trail is finer than a trail is ever read.
 *   <li>Entities that come into view after their slot was recorded are not in the table and draw
 *       normally.
 *   <li>Particles are a separate system with their own tick, and are not covered.
 *   <li>1.21.2 and up. Below that there is no render state to record.
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class EntityExposure {
    private EntityExposure() {}

    /**
     * Distinct instants recorded across one exposure.
     *
     * <p>A cap rather than "one per sample" because the cost is slots TIMES entities in view,
     * and 256 samples against a busy scene is tens of thousands of live objects for one
     * photograph. Sixty-four positions along a trail is already finer than the trail reads —
     * beyond that the copies overlap by more than their own width.
     */
    private static final int MAX_SLOTS = 64;

    /** Guard against storing an unbounded number of states in a scene full of entities. */
    private static final int MAX_STORED = 20_000;

    private static final int IDLE = 0, RECORDING = 1, REPLAYING = 2;
    private static volatile int phase = IDLE;

    /** slot → (entity id → the render state vanilla built for that instant). */
    private static final Map<Integer, Map<Integer, Object>> slots = new HashMap<>();

    /**
     * slot → {x, y, z, yaw, pitch}: where the photographer was looking from at that instant.
     *
     * <p>The camera is on the exposure clock for the same reason everything else is, and for one
     * more: a PAN. Holding the viewpoint still through the exposure makes a moving subject
     * streak and the background sharp, which is the picture nobody wanted; following the subject
     * makes the subject sharp and the background streak, which is the shot. The photographer
     * does that with the mouse, in real time, during the exposure — so the exposure has to be a
     * real interval they can move through, and the burst afterwards has to REPLAY where they
     * were rather than average where they ended up.
     *
     * <p>Recorded at the same instants and the same sub-tick phases as the entities, which
     * matters more than it looks: a phase mismatch of a third of a tick between camera and
     * subject drags a walking mob 0.066 blocks across the frame, and at the framing this mod is
     * usually pointed at that is around eight pixels of smear on the one thing that was supposed
     * to come out sharp.
     */
    private static final Map<Integer, double[]> camSlots = new HashMap<>();

    /** slot → (entity id → {x, y, z, width, height}) — the body behind the recorded state. */
    private static final Map<Integer, Map<Integer, double[]>> bodySlots = new HashMap<>();

    private static volatile int    sampleCount   = 1;
    private static volatile int    slotCount     = 1;
    private static volatile double exposureTicks = 0.0;
    /** Client ticks completed since the shutter opened. */
    private static volatile int    ticksSinceArm = 0;
    /** Set by the tick handler, cleared once the render frame that follows it has recorded. */
    private static volatile boolean tickPending  = false;
    /** Lowest slot not yet recorded. Slots are filled in order, so a frame that arrives late
     *  catches up rather than leaving holes in the trail. */
    private static volatile int    nextSlot      = 0;
    private static volatile int    stored        = 0;
    private static volatile boolean overflowed   = false;
    /** Wall clock at the shutter, for the safety net below. */
    private static volatile long   startedMs     = 0L;

    /** True while our own re-entrant calls into the renderer are in flight. */
    private static final ThreadLocal<Boolean> reentrant = ThreadLocal.withInitial(() -> false);

    public static boolean isRecording() { return phase == RECORDING; }
    public static boolean isReplaying() { return phase == REPLAYING; }
    public static boolean isReentrant() { return reentrant.get(); }

    /** 0..1 through the recording, for the finder. */
    public static float recordProgress() {
        double need = Math.max(1.0, Math.floor(slotTick(slotCount - 1)) + 1.0);
        return (float) Math.min(1.0, ticksSinceArm / need);
    }

    /**
     * Open the shutter.
     *
     * @param samples       pupil samples the burst will take
     * @param exposureTicks the shutter in ticks — {@code T · 20}
     */
    public static void arm(int samples, double exposureTicks) {
        release();
        EntityExposure.sampleCount   = Math.max(1, samples);
        EntityExposure.exposureTicks = Math.max(0.0, exposureTicks);
        EntityExposure.slotCount     = Math.max(1, Math.min(MAX_SLOTS, EntityExposure.sampleCount));
        ticksSinceArm = 0;
        tickPending   = false;
        nextSlot      = 0;
        stored        = 0;
        overflowed    = false;
        startedMs     = System.currentTimeMillis();
        phase         = RECORDING;
    }

    /** Stop overriding anything and let go of the recording. */
    public static void release() {
        phase = IDLE;
        slots.clear();
        camSlots.clear();
        bodySlots.clear();
        stored = 0;
        tickPending = false;
        nextSlot = 0;
        ticksSinceArm = 0;
    }

    /** One client tick has completed; the next render frame can record the slots it carries. */
    public static void onClientTick() {
        if (phase != RECORDING) return;
        ticksSinceArm++;
        tickPending = true;
    }

    /** The exposure tick slot {@code j} stands for. */
    private static double slotTick(int j) {
        return ((j + 0.5) / slotCount) * exposureTicks;
    }

    /** Which slot a pupil sample reads. Monotone, so the trail runs in the right direction. */
    private static int slotForSample(int i) {
        int j = (int) ((long) i * slotCount / Math.max(1, sampleCount));
        return Math.max(0, Math.min(slotCount - 1, j));
    }

    /** Whether this render frame is one that follows a fresh tick, and so owes snapshots. */
    public static boolean snapshotFrameActive() {
        return phase == RECORDING && tickPending;
    }

    /** Called once per rendered frame, after the world has been drawn. */
    public static void endSnapshotFrame() {
        if (phase != RECORDING) return;
        if (tickPending) {
            tickPending = false;
            for (float[] p : pendingSlots()) nextSlot = Math.max(nextSlot, (int) p[0] + 1);
        }
        // Recording advances on client ticks, and a client that stops ticking — a pause menu in
        // single-player — would otherwise leave the shutter open for ever. Give up on the rest
        // of the exposure rather than hang; the slots that did arrive still carry the picture,
        // and the ones that did not fall back to the nearest instant that did.
        long budget = (long) (exposureTicks * 50.0) + 5000L;
        if (System.currentTimeMillis() - startedMs > budget) {
            System.out.println("[Snapmatica] entity exposure: timed out at slot " + nextSlot
                    + " of " + slotCount + "; exposing with what was recorded");
            nextSlot = slotCount;
        }
        finishRecordingIfDone();
    }

    /**
     * The phases to record on this frame, for the tick that just finished.
     *
     * <p>After tick {@code k} completes, an entity's bracket runs from its position at the end of
     * tick {@code k-1} to its position at the end of tick {@code k}, so a phase {@code φ}
     * addresses exposure tick {@code k-1+φ}. Every slot whose instant falls inside that window
     * belongs to this frame, because it is the only frame on which it can be asked for: the
     * bracket has moved on by the next one.
     *
     * <p>Slots older than the window are recorded too, at {@code φ = 0}, which is the earliest
     * instant still reachable. That only happens when the framerate drops below the tick rate and
     * a window went by without a frame in it; the trail compresses slightly there rather than
     * losing a piece of itself.
     *
     * @return {slot, phase} pairs, possibly empty
     */
    public static float[][] pendingSlots() {
        int k = ticksSinceArm;
        if (k < 1) return new float[0][];
        java.util.ArrayList<float[]> out = new java.util.ArrayList<>();
        for (int j = nextSlot; j < slotCount; j++) {
            double t = slotTick(j);
            if (t >= k) break;                       // not reachable yet; a later tick owns it
            double phi = t - (k - 1);
            out.add(new float[] { j, (float) Math.max(0.0, Math.min(1.0, phi)) });
        }
        return out.toArray(new float[0][]);
    }

    /** Keep one recorded state. */
    public static void store(int slot, int entityId, Object state) {
        if (state == null) return;
        if (stored >= MAX_STORED) {
            if (!overflowed) {
                overflowed = true;
                System.out.println("[Snapmatica] entity exposure: too many entities to record the "
                        + "whole scene; the rest draw live");
            }
            return;
        }
        slots.computeIfAbsent(slot, s -> new HashMap<>()).put(entityId, state);
        stored++;
    }

    /** Recording is done once the last slot's tick has gone by; the burst may start exposing. */
    private static void finishRecordingIfDone() {
        if (phase != RECORDING) return;
        if (nextSlot >= slotCount) {
            phase = REPLAYING;
            System.out.println(String.format(
                    "[Snapmatica] entity exposure: %.2f ticks recorded into %d instants, "
                    + "%d states held", exposureTicks, slotCount, stored));
        }
    }

    /**
     * Record where the camera is, at the same instants the entities are being recorded at.
     *
     * <p>Taken from the player rather than from the live {@code Camera}, because the camera for
     * the frame is at whatever sub-tick phase the frame happened to land on, and the entities
     * are at {@code φ}. {@code getCameraPosVec} and the lerped angles are the same three
     * quantities {@code Camera.update} interpolates, asked for at the phase that matters.
     *
     * <p>In freecam the flight path is snapmatica's own and interpolates the same way, so it is
     * asked at the same phase.
     */
    public static void recordCameraForPendingSlots(net.minecraft.client.Minecraft mc) {
        if (phase != RECORDING || !tickPending) return;
        if (mc == null || mc.player == null) return;
        for (float[] p : pendingSlots()) {
            float phi = p[1];
            double[] pose;
            if (Freecam.isActive()) {
                net.minecraft.world.phys.Vec3 fp =
                        Freecam.getPrevPos().lerp(Freecam.getPos(), phi);
                // Angles lerped the short way round, so a pan across the 360/0 seam does not
                // whip the camera the long way between two adjacent instants.
                pose = new double[] { fp.x, fp.y, fp.z,
                        net.minecraft.util.Mth.rotLerp(
                                phi, Freecam.getPrevYaw(), Freecam.getYaw()),
                        net.minecraft.util.Mth.lerp(
                                phi, Freecam.getPrevPitch(), Freecam.getPitch()) };
            } else {
                //? if >=1.21.2 {
                net.minecraft.world.phys.Vec3 cp = mc.player.getEyePosition(phi);
                pose = new double[] { cp.x, cp.y, cp.z,
                        mc.player.getViewYRot(phi), mc.player.getViewXRot(phi) };
                //?} else {
                /*net.minecraft.world.phys.Vec3 cp = mc.player.getEyePosition(phi);
                pose = new double[] { cp.x, cp.y, cp.z,
                        mc.player.prevYaw + (mc.player.getYRot() - mc.player.prevYaw) * phi,
                        mc.player.prevPitch + (mc.player.getXRot() - mc.player.prevPitch) * phi };
                *///?}
            }
            camSlots.put((int) p[0], pose);
        }
    }

    /** The instant of the exposure the sub-frame now being rendered stands for, in ticks. */
    private static double currentExposureTick() {
        int i = Math.max(0, Math.min(sampleCount - 1, ApertureIntegration.sampleIndex()));
        return exposureTicks * ((i + 0.5) / sampleCount);
    }

    /** The nearest recorded camera slot at or before {@code j}. */
    private static double[] slotAt(int j) {
        for (int k = Math.max(0, Math.min(slotCount - 1, j)); k >= 0; k--) {
            double[] c = camSlots.get(k);
            if (c != null) return c;
        }
        return null;
    }

    private static double wrapDeg(double d) { return ((d + 540.0) % 360.0) - 180.0; }

    /**
     * Where the camera was at an arbitrary instant of the exposure, interpolated between the
     * instants that were recorded.
     *
     * <p>Interpolated rather than snapped, because the slot count is capped and the sample count
     * is not: at 128 samples against 64 slots, snapping would hand pairs of consecutive
     * sub-frames the SAME viewpoint, and a pair of identical frames in an average is not half a
     * sample, it is a doubled image.
     */
    private static double[] poseAtTick(double t) {
        if (camSlots.isEmpty()) return null;
        double fj = (exposureTicks > 1e-9) ? (t * slotCount / exposureTicks - 0.5) : 0.0;
        fj = Math.max(0.0, Math.min(slotCount - 1.0, fj));
        int j0 = (int) Math.floor(fj);
        double f = fj - j0;
        double[] a = slotAt(j0);
        double[] b = slotAt(j0 + 1);
        if (a == null) return b;
        if (b == null || f <= 0.0) return a;
        return new double[] {
                a[0] + (b[0] - a[0]) * f,
                a[1] + (b[1] - a[1]) * f,
                a[2] + (b[2] - a[2]) * f,
                a[3] + wrapDeg(b[3] - a[3]) * f,
                a[4] + (b[4] - a[4]) * f
        };
    }

    /**
     * @return {x, y, z, yaw, pitch} for the sub-frame now being rendered, or {@code null} if the
     *         exposure has no camera recorded for it.
     */
    public static double[] cameraFor() {
        if (phase != REPLAYING) return null;
        return poseAtTick(currentExposureTick());
    }

    /**
     * How far the camera travels across the SLICE of the exposure this sub-frame stands for.
     *
     * <p>A sub-frame is an instant, and it is being asked to stand for {@code T/n} of the
     * exposure. Averaging n instants of a moving viewpoint is a multiple exposure, not motion
     * blur — the copies only merge into a streak once they are less than a pixel apart, so a pan
     * that carries the background 200 px across a 32-sample exposure arrives as 32 ghosts 6 px
     * apart. That is the doubling you see; it is temporal aliasing, and more samples is the
     * expensive way out of it.
     *
     * <p>The cheap way is the one this mod already uses on the other axis. A sub-frame is not a
     * pinhole, it is a cell of the pupil, blurred by the gather at that cell's own f-number; by
     * exactly the same argument a sub-frame is not an instant, it is a slice of the exposure,
     * and it should be smeared along its own motion by exactly the width of that slice. The
     * cells tile the pupil and the slices tile the shutter, so in both cases what is drawn sums
     * to the whole rather than sampling it.
     *
     * <p>Measured from the RECORDED path, so the pupil excursion is not in it: that displacement
     * belongs to the aperture, is integrated by the burst, and smearing it as well would blur
     * the picture by the entrance pupil a second time.
     *
     * @return {dx, dy, dz, dYaw, dPitch, yawAtCentre}, or {@code null} if there is nothing to
     *         smear
     */
    public static double[] cameraSliceDelta() {
        if (phase != REPLAYING || camSlots.isEmpty()) return null;
        double e = currentExposureTick();
        double half = 0.5 * exposureTicks / Math.max(1, sampleCount);
        double[] a = poseAtTick(e - half);
        double[] b = poseAtTick(e + half);
        double[] c = poseAtTick(e);
        if (a == null || b == null || c == null) return null;
        return new double[] { b[0] - a[0], b[1] - a[1], b[2] - a[2],
                              wrapDeg(b[3] - a[3]), b[4] - a[4], c[3] };
    }

    /**
     * @return the state recorded for this entity at the instant the sub-frame now being rendered
     *         stands for, or {@code null} to let the renderer answer for itself.
     */
    public static Object stateFor(int entityId) {
        if (phase != REPLAYING) return null;
        // Walk back to the nearest earlier instant this entity was recorded at. A slot can be
        // missing because the entity was not in view when it was taken, or because the recording
        // was cut short; either way an instant slightly too early is much closer to the truth
        // than the live position, which is wherever the tick loop has got to by now.
        for (int j = slotForSample(ApertureIntegration.sampleIndex()); j >= 0; j--) {
            Map<Integer, Object> m = slots.get(j);
            if (m != null) {
                Object st = m.get(entityId);
                if (st != null) return st;
            }
        }
        return null;
    }

    /** Keep the body behind one recorded state: where it was, and how big. */
    public static void storeBody(int slot, int entityId,
                                 double x, double y, double z, double w, double h) {
        if (stored >= MAX_STORED) return;
        bodySlots.computeIfAbsent(slot, k -> new HashMap<>())
                 .put(entityId, new double[] { x, y, z, w, h });
    }

    /** Body of one entity at an arbitrary instant, interpolated between recorded slots. */
    private static double[] bodyAtTick(int id, double t) {
        if (bodySlots.isEmpty()) return null;
        double fj = (exposureTicks > 1e-9) ? (t * slotCount / exposureTicks - 0.5) : 0.0;
        fj = Math.max(0.0, Math.min(slotCount - 1.0, fj));
        int j0 = (int) Math.floor(fj);
        double f = fj - j0;
        double[] a = bodyAtSlot(id, j0), b = bodyAtSlot(id, j0 + 1);
        if (a == null) return b;
        if (b == null || f <= 0.0) return a;
        return new double[] { a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f,
                              a[2] + (b[2] - a[2]) * f, a[3], a[4] };
    }

    private static double[] bodyAtSlot(int id, int j) {
        for (int k = Math.max(0, Math.min(slotCount - 1, j)); k >= 0; k--) {
            Map<Integer, double[]> m = bodySlots.get(k);
            if (m != null) {
                double[] b = m.get(id);
                if (b != null) return b;
            }
        }
        return null;
    }

    /**
     * The entities that MOVE across this sub-frame's slice, and by how much.
     *
     * <p>The answer to the ghosting a burst leaves on a mob, and the reason it needs no new
     * measurement: the exposure is already recorded, so the mob's velocity is a subtraction on
     * data that is sitting there. What the camera smear cannot do for it is only that the smear
     * is one vector for the whole frame — a mob crossing a still frame needs its OWN.
     *
     * <p>One row per entity: {@code {x, y, z, width, height, dx, dy, dz}} — where it is at this
     * sub-frame's instant, how big it is, and how far it travels across the slice the sub-frame
     * stands for. Everything else (projecting that into camera space, deciding which pixels are
     * the mob) belongs to the renderer, which knows the camera.
     *
     * @param max  keep at most this many, the fastest first — the slow ones do not ghost
     */
    public static double[][] movingBodies(int max) {
        if (phase != REPLAYING || bodySlots.isEmpty()) return new double[0][];
        double e = currentExposureTick();
        double half = 0.5 * exposureTicks / Math.max(1, sampleCount);
        if (half <= 0.0) return new double[0][];
        java.util.HashSet<Integer> ids = new java.util.HashSet<>();
        for (Map<Integer, double[]> m : bodySlots.values()) ids.addAll(m.keySet());
        java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        for (int id : ids) {
            double[] a = bodyAtTick(id, e - half);
            double[] b = bodyAtTick(id, e + half);
            double[] c = bodyAtTick(id, e);
            if (a == null || b == null || c == null) continue;
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            // A hundredth of a block over a slice is nothing at any framing; skipping it keeps
            // the uniform slots for the entities that are actually smearing.
            if (dx * dx + dy * dy + dz * dz < 1.0e-4) continue;
            out.add(new double[] { c[0], c[1], c[2], c[3], c[4], dx, dy, dz });
        }
        out.sort((u, v) -> Double.compare(
                v[5] * v[5] + v[6] * v[6] + v[7] * v[7],
                u[5] * u[5] + u[6] * u[6] + u[7] * u[7]));
        int n = Math.min(max, out.size());
        return out.subList(0, n).toArray(new double[0][]);
    }

    /** Stop intercepting, so the recorder can ask the renderer the questions it needs to ask.
     *  Always paired with {@link #endReentrant()} in a finally. */
    public static void beginReentrant() { reentrant.set(true); }

    public static void endReentrant() { reentrant.set(false); }
}
