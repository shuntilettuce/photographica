package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;

import java.io.File;
import org.joml.Matrix4f;

/**
 * Depth of field by INTEGRATING THE APERTURE, instead of by blurring a picture of it.
 *
 * <p>Everything else this mod does about defocus — and everything every other shader pack and
 * mod does — starts from one pinhole image and reconstructs what a lens would have done to it.
 * {@code evf_blur.fsh}'s gather is a very careful version of that, but it is still a
 * reconstruction, and it inherits the two things a reconstruction cannot have. It has no
 * information about what sits BEHIND a defocused foreground, because a single image never
 * recorded it; and it estimates each pixel's coverage by counting taps, which is a binomial
 * trial whose error {@code sqrt(p(1-p)/N)} is exactly the grain the gather spends its whole
 * sample budget fighting.
 *
 * <p>A real lens has neither problem because it is not reconstructing anything. It collects
 * light through every point of its entrance pupil at once, and the picture is the sum. So:
 * render the scene from a point on the pupil, move to another point, render again, and add
 * them up. What comes out is not an approximation of defocus — it IS defocus, by the
 * definition. Occlusion resolves itself because each sub-frame rasterises its own depth from
 * its own viewpoint, and the background hidden behind a near branch in one sub-frame is
 * plainly visible in another. There is no coverage to estimate, so there is no binomial noise
 * to buy off with taps.
 *
 * <p><b>The geometry.</b> Moving the eye by {@code (dx, dy)} would swing the whole picture, so
 * the projection is sheared to put the focal plane back where it was — the standard
 * accumulation-buffer construction. In eye space that is
 *
 * <pre>  x' = x - (dx/F)·z - dx        y' = y - (dy/F)·z - dy  </pre>
 *
 * with {@code z} negative in front of the camera and {@code F} the focus distance, so a point
 * at {@code z = -F} lands exactly where it did and everything else parts around it.
 *
 * <p><b>It agrees with the shader.</b> Worth checking rather than assuming, because if the two
 * paths disagreed then turning this on would change the picture's optics and not just its
 * quality. A pupil offset {@code d} blocks displaces a point at distance {@code z} blocks by
 * {@code f_px · d · |1/F − 1/z|} pixels, so the full pupil {@code D_mm / DofScale} blocks
 * across sweeps a circle of
 *
 * <pre>  CoC_px = f_px · (D_mm/DofScale) · DofScale · |1/F_mm − 1/z_mm|
 *         = f_mm · PxPerMm · D_mm · |1/F_mm − 1/z_mm|  </pre>
 *
 * which is the thin-lens circle of confusion {@code evf_blur.fsh} already computes, with
 * {@code D_mm = f/N} — the same entrance pupil {@link SnapmaticaClient#apertureDiameterMm}
 * tracks for the aperture ring. The world scale cancels, as it must: it is a change of units,
 * not of optics. Same lens, same f-number, same picture — reached from the definition instead
 * of from the formula.
 *
 * <p><b>The cost is time, and time is what a shutter press has.</b> Each pupil sample is a
 * whole rendered frame, so this cannot run in a viewfinder — the gather still owns the live
 * view, and always will. It runs when the shutter is pressed and nowhere else, which is also
 * the only moment a camera integrates anything.
 *
 * <p><b>Accumulated in LINEAR light.</b> A lens sums radiance. The framebuffer holds
 * gamma-encoded numbers, and averaging those is the wrong sum everywhere the samples disagree
 * — which, at the edge of a bokeh disc, is everywhere that matters. Sub-frames are decoded to
 * linear before they are added, and the sensor's own non-linear steps (the dynamic-range
 * curve, the tone curve, the highlight rolloff) are held back until the sum is complete, since
 * those belong to the sensor reading one finished exposure rather than to each of the hundred
 * partial ones. {@code EvfBlurRenderer} is told to skip them per-frame for exactly that
 * reason, and {@link #finish} applies them once at the end.
 *
 * <p><b>Known limits.</b> The sub-frames are consecutive rendered frames, so anything that
 * moves during the burst smears — which is honest for a tripod shot of a build and is not for
 * a running mob. Mechanical vignetting (the cat's-eye clipping the gather applies
 * analytically) is not modelled here: it would need a per-pixel weight per sub-frame, and the
 * disc is round in this path. And a world scale of a centimetre a block puts the pupil metres
 * wide in world terms, which is physically what a diorama lens does but is far enough for
 * vanilla's chunk culling — which sees the unsheared frustum — to clip at the very edge of a
 * wide frame.
 */
@Environment(EnvType.CLIENT)
public final class ApertureIntegration {
    private ApertureIntegration() {}

    /** Ceiling, so a burst costs a bounded number of frames however it is configured. */
    public static final int MAX_SAMPLES = 256;
    /**
     * Rendered frames to let the scene settle at each pupil position before reading it back.
     *
     * <p>A shader pack is not a pure function of the current frame. Photon — and every pack with
     * TAA — builds each image partly out of the ones before it, reprojecting a history buffer
     * onto the new view. That is exactly what makes it sharp, and it assumes the camera moves
     * the way a camera moves. This burst does not: it JUMPS the viewpoint clear across the
     * entrance pupil between one frame and the next, which invalidates the history wholesale.
     * Read the frame immediately after such a jump and what comes back is a half-converged
     * blend of two unrelated viewpoints — already soft before the sum ever sees it, and no
     * amount of averaging recovers detail that was never resolved. Sixty-four soft frames
     * average to one soft frame.
     *
     * <p>So hold each pupil position still for a few frames and read the last one. The burst
     * costs that multiple in wall-clock time, which a shutter can afford and a viewfinder
     * cannot — the same trade this whole path is built on.
     */
    /** Never fewer pupil samples than this: below it the parallax stops resolving occlusion,
     *  which is the one thing only it can do. */
    private static final int MIN_SAMPLES = 8;
    /** Ceiling on rendered frames for one photograph — about five seconds at 60 fps. */
    private static final int MAX_BURST_FRAMES = 320;

    private static final int SETTLE_FRAMES_PLAIN    = 1;
    /**
     * Frames to hold the FIRST pupil position before any sample is taken.
     *
     * <p>Twenty, once, when the viewpoint used to TELEPORT: the shear wrote the translation into
     * a column Photon zeroes, so a pack saw half a viewpoint change it could not reproject, threw
     * its history away and needed Photon's own CLOUDS_ACCUMULATION_LIMIT — twenty frames — to
     * rebuild the slowest buffer it keeps. That reason is gone. The translation lives in the
     * modelview now, where it is indistinguishable from the player walking, and the spiral steps
     * 0.09 blocks between neighbours, so reprojection SUCCEEDS and there is no rebuild to wait
     * out. Nothing is discontinuous at the shutter any more except the field of view, if the
     * finder was not already open when it was pressed, and the clocks changing rate rather than
     * value — one frame of adjustment each.
     *
     * <p>Four rather than zero because that argument is a derivation, not a measurement, and four
     * frames cost 80 ms. Turn on {@code apertureDebugSamples} and read the per-sample gains: if
     * the opening samples meter with the rest, this can go to zero.
     */
    private static final int WARMUP_FRAMES_TEMPORAL = 4;
    private static volatile int warmup = 0;
    // Six, not twelve: with the clock held (see IrisTimerMixin) the only thing left changing
    // between sub-frames is the viewpoint, so a pack's history has a still scene to converge
    // onto and gets there in half the frames — and the frames saved go back into pupil samples.


    /**
     * Whether a shader pack is drawing — and therefore whether this whole path stands down.
     *
     * <p>Integrating the aperture needs the renderer to be a function of the frame it is given.
     * A modern pack is not: it accumulates temporally, it re-meters its own exposure against
     * what it sees, and it animates from clocks of its own. A burst breaks every one of those
     * assumptions, because it JUMPS the viewpoint across the entrance pupil between frames.
     * Measured against Photon: the clouds swept the scene during the exposure until two separate
     * clocks were frozen to stop them; the pack's auto-exposure still re-metered each viewpoint,
     * leaving sub-frames a factor of 1.7 apart in brightness after the clocks were held; and the
     * TAA history had to be flushed with settling frames that made the shutter take seconds,
     * which in turn gave the adaptation more time to drift. Each fix bought back part of what the
     * previous one cost. Without a pack the same code produces the best photographs this mod has
     * taken; the incompatibility is with the temporal renderer, not with the optics.
     *
     * <p>So when a pack is drawing, the gather takes the shot — which is what it did before 2.0,
     * at full quality, in one frame. The burst is not a better gather, it is a different
     * instrument, and it needs a renderer that answers the same question the same way twice.
     *
     * <p>Reflection rather than a compile dependency: Iris is optional, and this is one call on
     * its published v0 API.
     */
    private static boolean temporalShaderActive() {
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(inst));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static volatile int settleFrames = SETTLE_FRAMES_PLAIN;
    private static volatile int settle = 0;

    /** Give up rather than hang if a readback callback never arrives. */
    private static final long TIMEOUT_MS = 60_000L;

    private static volatile boolean active   = false;
    private static volatile int     total    = 0;   // pupil samples this burst will take
    private static volatile int     issued   = 0;   // sub-frames whose readback was requested
    private static volatile int     received = 0;   // sub-frames actually folded into the sum
    private static volatile long    startMs  = 0L;
    private static volatile long    nextMs   = 0L;
    private static volatile long    intervalMs = 0L;
    private static volatile int     format   = 0;

    /**
     * How far the viewpoint may leave the camera's own position, in blocks.
     *
     * <p>The pupil is a real distance in the world, and the world scale decides how big: a
     * 135 mm f/1.4 at a centimetre to the block has a 96 mm entrance pupil, which is 1.9 BLOCKS
     * across. Sampling that honestly means flying the viewpoint a metre either side of where
     * the photographer is standing — wider than the gap between the bars it is meant to see
     * through, far enough to put the camera inside the terrain, and far enough that a close
     * subject leaves the frame entirely on the outer samples. The picture that comes back is a
     * smear with nothing in it, and no amount of focusing rescues it, because the subject was
     * not in most of the sub-frames to begin with.
     *
     * <p>That is not the optics being unrealistic — it is the optics being followed past the
     * point where the lens still fits in the scene. A real lens that large could not be that
     * close to that subject either. So the burst moves as far as it can without leaving the
     * camera's own neighbourhood, and hands whatever aperture is left to the gather, which has
     * never needed the room. See {@link #subApertureFNumber}.
     */
    private static final float MAX_EXCURSION_BLOCKS = 0.35f;

    /** Optics latched at the shutter, so nothing drifts under the burst mid-exposure. */
    private static volatile float radiusFullBlocks = 0f;   // the aperture the LENS has
    private static volatile float radiusMoveBlocks = 0f;   // the part the camera actually travels
    private static volatile float gatherDiameterBlocks = 0f; // the part one sub-frame blurs by
    private static volatile float focalMmAtArm     = 50f;
    private static volatile float mmPerBlockAtArm  = 375f;

    // ── The exposure clock ──────────────────────────────────────────────────────────────────
    /**
     * A photograph is a double integral, and until now this class only did half of it.
     *
     * <p>A lens collects light through every point of its entrance pupil AND across every
     * instant the shutter is open; the picture is the sum over both. The burst samples the
     * pupil honestly and then held every clock in the renderer still, which makes the second
     * integral a single instant — a shutter of 1/∞, the one speed no camera has. The shutter
     * dial was decorative: 30 seconds and 1/4000 produced the same frozen sky.
     *
     * <p>So sub-frame {@code i} of {@code n} is not just a point on the pupil, it is a point in
     * the exposure: {@code τ_i = ((i + 0.5)/n) · T}. Every clock the renderer animates from is
     * driven off {@code τ} instead of being frozen at zero, and the same stratification that
     * covers the pupil covers the exposure. Freezing was never the right answer; it was the
     * right answer to a question with {@code T} left out of it.
     *
     * <p>Three clocks, because Minecraft and Iris keep three:
     * <ul>
     *   <li>{@code World.getTimeOfDay} — the sun, the moon, the sky colour, and (through
     *       {@code world_age}) the wind a shader pack drifts its clouds along.
     *   <li>the vanilla cloud tick, which arrives as a render argument and is a different clock
     *       from the world's.
     *   <li>Iris's {@code frameTimeCounter}, the only one of the three with sub-tick resolution
     *       — waving foliage, water surface, aurora, drifting fog.
     * </ul>
     *
     * <p>What that buys, at 64 samples: 1/1000 s spans one millisecond and nothing moves, which
     * is what 1/1000 s means; 1/4 s waves the grass for a quarter second; 1 s drifts the clouds;
     * 30 s turns the world clock 600 ticks, which is nine degrees of sun — shadows sweep, clouds
     * streak, water goes to glass. The picture finally knows which number the dial is on.
     *
     * <p>Entities are not on this clock and cannot be: they are simulated by the tick loop, and
     * a client mod does not get to stop the tick loop. What holds them together instead is the
     * pacing already in {@link #arm} — a shutter of 1/30 s or slower spreads the samples across
     * that many real seconds, so mobs move through the exposure at their real speed for exactly
     * as long as the virtual clock says the shutter was open, and the two agree. Below 1/30 s
     * they do not: the burst still needs its frames, and anything walking through the frame
     * trails over the seconds those frames took rather than over the millisecond the photograph
     * claims. That is the one place the exposure is still a fiction.
     */
    private static volatile double exposureSec = 0.0;

    /** Seconds into the exposure for the sub-frame now being rendered. Advanced in step with the
     *  pupil offset, in {@link #setPupil}, because they are two coordinates of one sample. */
    private static volatile double expTau = 0.0;

    /** @return ticks elapsed since the shutter opened, for the sub-frame now being rendered. */
    private static long expTicks() { return (long) Math.floor(expTau * 20.0); }

    /** The same, unrounded. */
    public static double exposureTicks() { return expTau * 20.0; }

    /** The world clock as it stood when the shutter OPENED; -1 when no burst is running.
     *  Read through {@link #heldWorldTime()}, which adds the exposure clock to it. */
    private static volatile long heldWorldTime = -1L;

    /** The cloud tick count as it stood when the shutter opened; -1 outside a burst.
     *  Latched lazily on first use, because it arrives as a render argument rather than from
     *  anything this class can read at arm time. */
    private static volatile long heldClouds = -1L;

    /** Real wall-clock millis at the first frame of the burst, so the animation clock can be
     *  handed a value that continues from where Iris left off rather than from zero. */
    private static volatile long animBaseMs = -1L;

    /** @return the tick the clouds should be drawn at, or -1 to leave them alone. */
    public static long heldCloudTicks(long current) {
        if (!active) { heldClouds = -1L; return -1L; }
        if (heldClouds < 0L) heldClouds = current;
        return heldClouds + expTicks();
    }

    /** @return the instant of the exposure this sub-frame is photographing, or -1 outside a
     *  burst. */
    public static long heldWorldTime() {
        return heldWorldTime < 0L ? -1L : heldWorldTime + expTicks();
    }

    /**
     * The animation clock a shader pack should see for the sub-frame now being rendered.
     *
     * <p>Iris advances {@code frameTimeCounter} by the gap between successive calls, so handing
     * it a virtual clock is enough to make every animation it drives run at exposure time
     * instead of at wall-clock time — no field of Iris's is touched and nothing needs to know
     * this is happening. {@code frameTime} comes out of the same subtraction, so a pack's own
     * framerate-independent smoothing slows down with the rest of it.
     *
     * @param realMillis what Iris was about to use
     * @return the same value outside a burst; inside one, the shutter's own clock
     */
    public static long animationClockMillis(long realMillis) {
        if (!active) { animBaseMs = -1L; return realMillis; }
        if (animBaseMs < 0L) animBaseMs = realMillis;
        return animBaseMs + Math.round(expTau * 1000.0);
    }

    /** Focus distance in blocks, latched at shutter press — AF drift must not move the plane. */
    private static volatile float   focusBlocks = 1.0f;
    /** Pupil offset in BLOCKS for the sub-frame currently being rendered. */
    private static volatile float   offX = 0f, offY = 0f;
    /** The offsets the frame now in flight was actually rendered with. */
    private static volatile float   debugOffX = 0f, debugOffY = 0f;

    /** Where this burst's sub-frames go when {@link SnapmaticaClient#apertureDebugSamples} is
     *  on, and the running record of what each one metered at. */
    private static volatile File   debugDir = null;
    private static volatile StringBuilder debugLog = null;

    private static volatile double  refMean = 0.0;   // exposure the first sub-frame metered at
    private static volatile int     kept = 0, rejected = 0;
    private static volatile int     accW = 0, accH = 0;
    private static volatile float[] sumR = null, sumG = null, sumB = null;

    /**
     * sRGB→linear for the 256 values a readback can hold; the decode is otherwise per pixel.
     *
     * <p>Shared with {@code PhotoCapture}'s vignetting rather than copied, for the reason
     * {@link #linearToSrgb} gives: one definition of the transfer curve, matching the
     * shader's constant for constant.
     */
    static final float[] SRGB_TO_LINEAR = new float[256];
    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    // ── State ───────────────────────────────────────────────────────────────────

    /** True for every frame of a burst — the shear, the suppressed gather and the held-back
     *  sensor curves all key off this, so they cannot disagree about whether one is running. */
    public static boolean isActive() { return active; }

    /**
     * Whether a shutter press should take this path at all.
     *
     * <p>Needs a lens (there is no pupil without one) and needs the setting on.
     */
    public static boolean shouldUse() {
        return SnapmaticaClient.apertureIntegration && SnapmaticaClient.lensType != 0;
    }

    /**
     * Whether a burst is being held back only because a shader pack is drawing.
     *
     * <p>Used to say so once, rather than let the setting look broken.
     */
    public static boolean suppressedByShaderPack() { return false; }

    /**
     * The f-number ONE sub-frame represents.
     *
     * <p>A burst of {@code n} views does not sample the pupil continuously — it lays
     * {@code sqrt(n)} of them across the pupil's diameter, and between neighbours there is a
     * gap. Left as pinholes, a bokeh disc comes out as {@code sqrt(n)} hard copies of the
     * subject rather than as a disc: at 64 samples that is eight visible ghosts, and at a
     * diorama world scale, where the same 64 samples are spread over a circle of confusion
     * hundreds of pixels wide, they separate into stripes.
     *
     * <p>So a sub-frame is not a pinhole. It is a small aperture — one cell of the pupil,
     * {@code D/sqrt(n)} across — and cells tile the pupil they were cut from. Giving each
     * sub-frame the gather at that cell's own f-number fills exactly the gap to its neighbour,
     * because the gap and the cell's circle of confusion are the same quantity: both are the
     * full circle divided by {@code sqrt(n)}. The burst keeps doing the thing only a burst can
     * do — the parallax that resolves occlusion, and the hidden background it reveals — and
     * the gather does the thing it is good at, which is filling a continuum cheaply.
     *
     * <p>N = f/D, so cutting the diameter by {@code sqrt(n)} multiplies the f-number by it.
     */
    public static float subApertureFNumber() {
        float d = gatherDiameterBlocks;
        if (d <= 1e-5f) {
            int n = Math.max(1, total);
            return Math.max(0.1f, SnapmaticaClient.aperture) * (float) Math.sqrt(n);
        }
        return (float) (focalMmAtArm / (d * mmPerBlockAtArm));
    }

    /** The focus latched at shutter press, so the gather cannot drift off the plane the shear
     *  is registering every sub-frame against. */
    public static float latchedFocusBlocks() { return focusBlocks; }

    // ── The burst ───────────────────────────────────────────────────────────────

    /**
     * Begin a burst.
     *
     * @param photoFormat  the format latched at shutter press — see PhotoCapture.pendingFormat
     * @param shutterSec   the shutter in seconds — the second axis the samples are spread
     *                     across, so the burst integrates the aperture AND the exposure at once
     *                     and the trails come out of the same sum as the bokeh. See
     *                     {@link #exposureSec}. A shutter of 1/30 s or slower is also PACED in
     *                     real time, so entities — which the tick loop owns and a client mod
     *                     does not get to stop — move through the exposure at their own speed
     *                     for as long as the virtual clock says it lasted, and the two agree.
     *                     A faster one takes its samples as quickly as frames arrive: the
     *                     aperture is a property of the lens, not of how long the shutter was
     *                     open.
     */
    public static void arm(int photoFormat, double shutterSec) {
        int n = Math.max(1, Math.min(MAX_SAMPLES, SnapmaticaClient.apertureSamples));
        // A burst costs samples TIMES the frames each one has to settle for, and it is the
        // product the photographer waits through. Under a temporal pack that product runs away:
        // 128 samples at twelve frames each is over 1500 frames, half a minute of standing
        // still. Trade samples for settling, because they are not worth the same — the parallax
        // only has to carry the occlusion (what is actually BEHIND the foreground, which no
        // reconstruction can invent), and the gather picks up whatever spread the smaller
        // sample count leaves, exactly as it already does for the capped excursion.
        settleFrames = SETTLE_FRAMES_PLAIN;
        n = Math.max(MIN_SAMPLES, Math.min(n, MAX_BURST_FRAMES / (settleFrames + 1)));
        long now = System.currentTimeMillis();
        MinecraftClient mcNow = MinecraftClient.getInstance();
        heldWorldTime = (mcNow != null && mcNow.world != null) ? mcNow.world.getTimeOfDay() : -1L;
        // The exposure the samples will be spread across. Latched here with the optics, for the
        // same reason: a dial turned mid-burst must not change the photograph being taken.
        exposureSec = Math.max(0.0, shutterSec);
        expTau      = 0.0;
        animBaseMs  = -1L;
        active   = true;
        total    = n;
        issued   = 0;
        received = 0;
        format   = photoFormat;
        startMs  = now;
        nextMs   = now;
        // No real-time pacing any more. Spreading the samples across the exposure in WALL-CLOCK
        // time made which instant each one landed on a function of when a frame happened to
        // arrive — uneven, and different at every framerate. EntityExposure records the exposure
        // tick by tick instead and hands each sample the instant it is supposed to have, so the
        // burst itself can run as fast as frames come.
        intervalMs = 0L;
        focusBlocks = Math.max(0.05f, AutoFocus.shaderFocusDistance());
        focalMmAtArm    = SnapmaticaClient.focalLengthMm;
        mmPerBlockAtArm = Math.max(1e-4f, SnapmaticaClient.dofScaleMm);
        float fnum   = Math.max(0.1f, SnapmaticaClient.aperture);
        double rV    = Math.max(1e-4, SnapmaticaClient.imageDistanceMm(focalMmAtArm));
        double tV    = SnapmaticaClient.imageDistanceMmPhysical(focalMmAtArm);
        radiusFullBlocks = (float) ((focalMmAtArm / fnum) * 0.5f / mmPerBlockAtArm * (tV / rV));
        // Divide the aperture between the two mechanisms so their widths SUM to it exactly.
        // A sub-frame's own blur convolves with the spread of the viewpoints, so the two add:
        // travelling the full pupil AND blurring each frame by the sample gap overshoots by
        // 1/sqrt(n) — 12.5% at 64 samples, which is a real error in the f-number, not a
        // rounding one. Give the gather whichever is larger, the gap it has to bridge or the
        // excursion the cap held back, and let the parallax cover precisely the remainder.
        float dFull  = 2f * radiusFullBlocks;
        float dCap   = Math.min(dFull, 2f * MAX_EXCURSION_BLOCKS);
        float dGather = Math.max(dFull / (float) Math.sqrt(n), dFull - dCap);
        radiusMoveBlocks = Math.max(0f, (dFull - dGather) * 0.5f);
        gatherDiameterBlocks = dGather;
        sumR = null; sumG = null; sumB = null;
        accW = 0; accH = 0;
        refMean = 0.0; kept = 0; rejected = 0;
        debugDir = null; debugLog = null;
        if (SnapmaticaClient.apertureDebugSamples) {
            File d = new File(MediaLibrary.photoDir(),
                    "burst_" + new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
                            .format(new java.util.Date()));
            if (d.mkdirs() || d.isDirectory()) {
                debugDir = d;
                debugLog = new StringBuilder(
                        "# one line per pupil sample, in the order they were folded in\n"
                        + "# idx  offX(blk)  offY(blk)  mean(linear)  gain  verdict\n");
            }
        }
        setPupil(0, n);
        settle = settleFrames;
        warmup = temporalShaderActive() ? WARMUP_FRAMES_TEMPORAL : 0;
        // Record the exposure before any of it is photographed: entities are simulated by the
        // tick loop, so the only way to give a sample the instant it is supposed to have is to
        // have kept that instant. Costs exactly the exposure in real time. See EntityExposure.
        EntityExposure.arm(n, exposureSec * 20.0);
        // One line per shutter press. A burst is invisible from inside the game — the finder
        // does not change, and the only other evidence is a file appearing a second later — so
        // without this there is no way to tell a burst that ran from one that never armed.
        System.out.println(String.format(
                "[Snapmatica] aperture burst: %d samples, %dmm f/%.1f, focus %.2f blk, "
                + "scale %.0f mm/blk | pupil %.3f blk, camera travels %.3f blk (%.0f%% by "
                + "parallax), gather takes the rest at f/%.1f, settle %d frames/sample | "
                + "exposure %.4f s = %.2f ticks of world across the burst",
                n, SnapmaticaClient.focalLengthMm, SnapmaticaClient.aperture, focusBlocks,
                mmPerBlockAtArm, 2f * radiusFullBlocks, 2f * radiusMoveBlocks,
                100f * radiusMoveBlocks / Math.max(radiusFullBlocks, 1e-6f),
                subApertureFNumber(), settleFrames, exposureSec, exposureSec * 20.0));
    }

    /** Runs once per rendered frame while a burst is armed; owns the capture until it ends. */
    public static void tick(MinecraftClient mc) {
        if (!active) return;
        if (mc == null || mc.player == null) { reset(); return; }
        long now = System.currentTimeMillis();

        // The shutter is open but nothing is being photographed yet: the exposure is being
        // recorded, one client tick at a time, and the burst cannot start until it has the
        // instants it is going to hand its samples. Runs after the world has been drawn, so this
        // frame's snapshots are already in.
        if (EntityExposure.isRecording()) {
            // Where the photographer was, at the same instants the entities were taken at.
            // Before endSnapshotFrame, which is what closes the window.
            EntityExposure.recordCameraForPendingSlots(mc);
            EntityExposure.endSnapshotFrame();
            // The clock for the timeout starts when the exposure does, not when the shutter was
            // pressed — a thirty-second exposure spends thirty seconds here quite legitimately.
            startMs = now;
            return;
        }

        // At most one readback in flight. The callback form is asynchronous, so issuing one
        // per frame unthrottled would leave a queue of full-resolution NativeImages alive at
        // once — sixty-four of them at 1080p is half a gigabyte, for no gain: the burst is
        // paced by rendered frames either way.
        // Let the renderer's accumulated buffers converge once, before the first sample.
        if (warmup > 0) { warmup--; return; }
        boolean inFlight = (issued - received) >= 1;
        // Let the pack's temporal history rebuild at this pupil position first.
        if (issued < total && !inFlight && settle > 0) { settle--; return; }
        if (issued < total && !inFlight && now >= nextMs) {
            //? if >=1.21.10 {
            ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), ApertureIntegration::accumulate);
            //?} else {
            /*try {
                accumulate(ScreenshotRecorder.takeScreenshot(mc.getFramebuffer()));
            } catch (Exception e) {
                System.err.println("[Snapmatica] Aperture sample failed: " + e.getMessage());
                received++;
            }
            *///?}
            debugOffX = offX; debugOffY = offY;
            issued++;
            nextMs = now + intervalMs;
            settle = settleFrames;
            // The next frame renders from the next point on the pupil. Advanced here rather
            // than in the readback callback because the callback may land a frame or more
            // later: what has to stay in step is the SHEAR and the frame it produced, and the
            // sum does not care what order the pieces arrive in.
            setPupil(issued, total);
        }

        boolean done      = received >= total;
        boolean timedOut  = now - startMs > TIMEOUT_MS;
        if (done || timedOut) {
            if (timedOut && !done) {
                System.err.println("[Snapmatica] Aperture integration timed out with "
                        + received + "/" + total + " samples; saving what arrived");
            }
            finish(mc);
        }
    }

    /** Where on the entrance pupil sub-frame {@code i} of {@code n} looks from, in blocks. */
    private static void setPupil(int i, int n) {
        if (i >= n) return;
        // A CONTINUOUS spiral, not a golden-angle disc — the order matters as much as the set.
        //
        // Golden angle is the right way to scatter points over a disc and the worst possible way
        // to visit them, because consecutive samples land on opposite sides: measured at 64
        // samples, the step from one to the next averages 1.25 of the pupil radius and reaches
        // 1.85, which is nearly the whole diameter. A renderer that carries anything between
        // frames sees that as a teleport. Photon's clouds are the clearest case — they are drawn
        // one sixteenth of the pixels at a time and reconstructed from a reprojected history
        // that needs twenty frames to converge (CLOUDS_TEMPORAL_UPSCALING 4,
        // CLOUDS_ACCUMULATION_LIMIT 20 in its settings) — so a jump throws the history away and
        // every sample is caught mid-rebuild, at a different stage. Averaging those is what
        // dissolved the sky.
        //
        // Winding the same points into a spiral keeps neighbours adjacent: the same 64 samples
        // step 0.26 of the radius on average and never more than 0.39, a fifth of what they did.
        // The history reprojects instead of resetting, so it converges once at the start and is
        // carried the rest of the way. The turn count is sqrt(n)/2, which is where the spacing
        // between arms matches the spacing along them, so the disc is still covered evenly.
        int turns = Math.max(2, (int) Math.round(Math.sqrt(n) / 2.0));
        double f  = (i + 0.5) / n;
        double r  = Math.sqrt(f);
        double t  = 2.0 * Math.PI * turns * f;
        // The travelling part of the pupil, latched at the shutter and capped so the camera
        // stays in its own neighbourhood — see MAX_EXCURSION_BLOCKS. Whatever the cap holds
        // back is not lost; subApertureFNumber gives it to the gather.
        float radiusBlocks = radiusMoveBlocks;
        offX = (float) (r * Math.cos(t)) * radiusBlocks;
        offY = (float) (r * Math.sin(t)) * radiusBlocks;
        // The sample's third coordinate: WHEN in the exposure it is taken. Stratified exactly
        // as the pupil is, and set here so the two can never fall out of step — a frame drawn
        // from one point on the pupil at another point's instant is not a sample of anything.
        expTau = exposureSec * f;
    }

    /**
     * Shear the world projection so this sub-frame looks from its own point on the pupil while
     * the focal plane stays registered. See the class doc for the derivation.
     *
     * <p>Touches only the two rows that carry x and y, and only their z and w columns, so the
     * depth range and the vertical scale come out untouched — which is what lets
     * {@code EvfBlurRenderer} keep reading the same matrix for its depth linearisation without
     * caring that a burst is running.
     */
    public static void shear(Matrix4f proj) {
        if (!active || proj == null) return;
        float dx = offX, dy = offY;
        if (dx == 0f && dy == 0f) return;
        // ONLY the registration term. The viewpoint's own translation used to live here too, in
        // m30/m31, and that is what broke under a shader pack: a pack reads the projection
        // element by element and assumes the translation column is empty, so it kept the
        // compensation, discarded the movement, and ran its deferred shading and ambient
        // occlusion on the difference. Measured, that swung a burst's brightness by 1.85x with
        // the sign of the pupil offset, for an eye movement of a centimetre and a half.
        //
        // The translation now goes where a moving viewpoint belongs — the modelview, via
        // Camera.moveBy in CameraMixin, which is the same thing walking does and which every
        // pack already handles. What is left here is the z-dependent term that holds the focal
        // plane still, in m20/m21, a slot packs read and preserve. The two together are exactly
        // the matrix the old shear produced; they are just written where their meaning is.
        // Nothing. Both halves of the viewpoint change now live in the modelview — the
        // translation as a camera move, the registration as the toe-in rotation that follows it
        // — because that is the only place a shader pack demonstrably reads them from. Kept as a
        // no-op rather than deleted so the call sites keep documenting where the shear used to
        // be and why it moved.
    }

    /**
     * The camera as it stood on the burst's first frame — position and orientation both.
     *
     * <p>An exposure is one instant, and that has to include where the camera was standing. The
     * burst spends a second or more taking its samples, and for all of that time the player is
     * still holding the keys that were keeping them in the air; let go, or drift, and the
     * viewpoint wanders through the exposure and smears the photograph in a way no aperture
     * produces. Worse, it made the shutter something to be endured — a second of holding shift
     * and space perfectly still, every shot.
     *
     * <p>So the burst takes the camera over. Every sub-frame is placed from this latch rather
     * than from wherever the player has drifted to, which means the keys can be released the
     * moment the shutter is pressed. It also removes camera drift from the picture entirely,
     * which is a quality change and not only a comfort one: the pupil is the only thing allowed
     * to move.
     */
    private static volatile boolean camLatched = false;
    private static volatile double camX, camY, camZ;
    private static volatile float  camYaw, camPitch;

    public static boolean hasCameraLatch() { return active && camLatched; }
    public static double camX()  { return camX; }
    public static double camY()  { return camY; }
    public static double camZ()  { return camZ; }
    public static float  camYaw()   { return camYaw; }
    public static float  camPitch() { return camPitch; }

    /** Taken on the first frame of the burst, from the camera the render is about to use. */
    public static void latchCamera(double x, double y, double z, float yaw, float pitch) {
        if (!active || camLatched) return;
        camX = x; camY = y; camZ = z; camYaw = yaw; camPitch = pitch;
        camLatched = true;
    }

    /** The pupil offset the camera itself is moved by this sub-frame, in blocks. */
    public static float pupilOffsetX() { return active ? offX : 0f; }
    public static float pupilOffsetY() { return active ? offY : 0f; }

    /** Folds one rendered sub-frame into the running linear-light sum, and closes it. */
    private static void accumulate(NativeImage frame) {
        if (frame == null) { received++; return; }
        try {
            if (!active) return;
            int w = frame.getWidth(), h = frame.getHeight();
            if (sumR == null) {
                accW = w; accH = h;
                sumR = new float[w * h];
                sumG = new float[w * h];
                sumB = new float[w * h];
            }
            // A window resize mid-burst changes the buffer's shape; drop the odd frame rather
            // than corrupt the sum. It still counts as received, or the burst never ends.
            if (w != accW || h != accH) return;

            // Every sub-frame is the same aperture cell, the same shutter and the same ISO, so
            // they are all the same exposure — any difference between them is the renderer's,
            // not the camera's. A pack with eye adaptation re-meters for each viewpoint the
            // burst jumps to (and the settling that TAA needs is exactly the time adaptation
            // needs to act), so the frames arrive at wildly different brightnesses and average
            // into a flat, muddy wash. Matching each one back to the first is restoring the
            // exposure the settings actually specify, not inventing one.
            float[] lin = new float[w * h * 3];
            double mean = 0.0;
            for (int y = 0, k = 0; y < h; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++, k += 3) {
                    int c = Pixels.getAbgr(frame, x, y);
                    float lr = SRGB_TO_LINEAR[ c         & 0xFF];
                    float lg = SRGB_TO_LINEAR[(c >>>  8) & 0xFF];
                    float lb = SRGB_TO_LINEAR[(c >>> 16) & 0xFF];
                    lin[k] = lr; lin[k + 1] = lg; lin[k + 2] = lb;
                    mean += 0.2126 * lr + 0.7152 * lg + 0.0722 * lb;
                }
            }
            mean /= (double) w * h;
            if (refMean <= 0.0) refMean = mean;          // the first frame sets the exposure
            double g = (mean > 1e-6) ? refMean / mean : 1.0;
            // Beyond this it is not adaptation drifting, it is a frame that went wrong — the
            // viewpoint ended up inside geometry, or the pack was still mid-reset. Amplifying
            // one of those only spreads its noise over the sum, so drop it instead.
            boolean reject = (g < 0.4 || g > 2.5);
            if (debugDir != null) {
                int idx = kept + rejected;
                debugLog.append(String.format("%4d  %+9.4f  %+9.4f  %12.6f  %5.3f  %s%n",
                        idx, debugOffX, debugOffY, mean, g, reject ? "REJECTED" : "kept"));
                // Encoded off the render thread. A 1920x1009 PNG costs the better part of a
                // second, and doing that inline turned a 1.4 s burst into fifty — which is not
                // just slow, it is a different experiment: the scene has a minute to change
                // under a diagnostic that exists to show what changed between sub-frames.
                final File out = new File(debugDir, String.format("sample_%03d.png", idx));
                final NativeImage copy = new NativeImage(w, h, false);
                for (int yy = 0; yy < h; yy++)
                    for (int xx = 0; xx < w; xx++)
                        Pixels.setAbgr(copy, xx, yy, Pixels.getAbgr(frame, xx, yy));
                Thread t = new Thread(() -> {
                    try { copy.writeTo(out); }
                    catch (Exception e) { System.err.println("[Snapmatica] burst dump failed: " + e.getMessage()); }
                    finally { copy.close(); }
                }, "snapmatica-burst-dump");
                t.setDaemon(true);
                t.start();
            }
            if (reject) { rejected++; return; }
            float gf = (float) g;
            for (int i = 0, k = 0; i < w * h; i++, k += 3) {
                sumR[i] += lin[k]     * gf;
                sumG[i] += lin[k + 1] * gf;
                sumB[i] += lin[k + 2] * gf;
            }
            kept++;
        } finally {
            received++;
            frame.close();
        }
    }

    /**
     * Average the pupil, then put the sensor back in the path.
     *
     * <p>The gain is applied here rather than per sub-frame only because it is cheaper to do
     * once; it is linear, so it would have given the same answer either way. The three curves
     * below would NOT have: they are the sensor reading a finished exposure, and running them
     * on each of two hundred partial ones and averaging the results is a different function.
     */
    private static void finish(MinecraftClient mc) {
        int w = accW, h = accH, n = Math.max(kept, 0);
        int tot = total;
        int rej = rejected;
        File dbgDir = debugDir;
        StringBuilder dbgLog = debugLog;
        long started = startMs;
        float[] r = sumR, g = sumG, b = sumB;
        int fmt = format;
        reset();

        if (n == 0 || r == null || w == 0 || h == 0) {
            System.err.println("[Snapmatica] Aperture integration: no samples, discarding");
            return;
        }
        if (dbgDir != null && dbgLog != null) {
            try {
                java.nio.file.Files.write(new File(dbgDir, "samples.txt").toPath(),
                        dbgLog.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                System.out.println("[Snapmatica] burst samples written to " + dbgDir);
            } catch (Exception e) {
                System.err.println("[Snapmatica] burst index failed: " + e.getMessage());
            }
        }
        System.out.println(String.format(
                "[Snapmatica] aperture burst done: %d kept + %d rejected of %d in %d ms, %dx%d",
                n, rej, tot, System.currentTimeMillis() - started, w, h));

        // A DNG is meant to reach the developer before any of this; the same reasoning that
        // makes PhotoCapture skip the shader's sensor pass for a raw capture applies here.
        boolean raw = (fmt == SnapmaticaClient.PHOTO_FORMAT_DNG);
        float[] wb  = raw ? new float[]{1f, 1f, 1f} : SnapmaticaClient.whiteBalanceGain();
        float   ev  = raw ? 1f : (float) PhotoProcessor.exposureFactor();

        float inv = 1.0f / n;
        float gr = wb[0] * ev, gg = wb[1] * ev, gb = wb[2] * ev;

        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                float lr = r[idx] * inv * gr;
                float lg = g[idx] * inv * gg;
                float lb = b[idx] * inv * gb;
                float cr = linearToSrgb(Math.max(lr, 0f));
                float cg = linearToSrgb(Math.max(lg, 0f));
                float cb = linearToSrgb(Math.max(lb, 0f));
                if (!raw) {
                    if (SnapmaticaClient.dynamicRangeSim) {
                        cr = dynamicRange(cr); cg = dynamicRange(cg); cb = dynamicRange(cb);
                    }
                    cr = rolloff(toneCurve(cr));
                    cg = rolloff(toneCurve(cg));
                    cb = rolloff(toneCurve(cb));
                }
                int ir = to8(cr), ig = to8(cg), ib = to8(cb);
                Pixels.setAbgr(out, x, y, 0xFF000000 | (ib << 16) | (ig << 8) | ir);
            }
        }
        // No depth handed on: the defocus is already IN this image, and the CPU path would
        // otherwise blur an image that has been correctly blurred once already.
        PhotoCapture.deliverIntegrated(mc, out, fmt);
    }

    private static void reset() {
        active = false;
        total = 0; issued = 0; received = 0;
        sumR = null; sumG = null; sumB = null;
        accW = 0; accH = 0;
        offX = 0f; offY = 0f;
        settle = 0; warmup = 0;
        camLatched = false;
        heldWorldTime = -1L;
        heldClouds = -1L;
        exposureSec = 0.0; expTau = 0.0; animBaseMs = -1L;
        EntityExposure.release();
        refMean = 0.0; kept = 0; rejected = 0;
        debugDir = null; debugLog = null;
    }

    /** Which pupil sample the frame being rendered right now stands for. */
    public static int sampleIndex() { return issued; }

    /** 0..1 through the burst, for the finder's blackout. */
    public static float progress() {
        int t = total;
        return t <= 0 ? 0f : Math.min(1f, received / (float) t);
    }

    /** Abandon a burst — a world change or a disconnect leaves nothing worth finishing. */
    public static void cancel() { if (active) reset(); }

    // ── The sensor's own steps, held back from the sub-frames ───────────────────
    // Deliberately the same arithmetic as evf_blur.fsh's Pass 5, constant for constant, so a
    // photo taken this way and one taken through the gather differ in their optics and in
    // nothing else.

    static float linearToSrgb(float c) {
        return c <= 0.0031308f ? c * 12.92f
                : (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055);
    }

    private static float dynamicRange(float c) {
        float stops      = Math.max(SnapmaticaClient.dynamicRangeStops, 1.0f);
        float blackLift  = clamp(0.10f * (8.0f / stops), 0.01f, 0.35f);
        float shoulder   = clamp(1.0f - 0.2f * (8.0f / stops), 0.4f, 0.97f);
        float x    = Math.max(c - blackLift, 0f) / (1.0f - blackLift);
        float over = Math.max(x - shoulder, 0f);
        return Math.min(x, shoulder) + over / (1.0f + over * 3.0f);
    }

    private static float toneCurve(float c) {
        return c * (1.0f + 0.15f * (1.0f - Math.abs(c - 0.5f) * 2.0f));
    }

    private static float rolloff(float c) {
        final float KNEE = 200.0f / 255.0f;
        final float SOFT = 55.0f / 255.0f;
        float over = Math.max(c - KNEE, 0f);
        return Math.min(c, KNEE) + SOFT * (1.0f - (float) Math.exp(-over / SOFT));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int to8(float c) {
        int v = (int) (clamp(c, 0f, 1f) * 255.0f + 0.5f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
