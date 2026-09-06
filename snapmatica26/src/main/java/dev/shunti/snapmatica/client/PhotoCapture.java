package dev.shunti.snapmatica.client;


import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Handles photo capture: screenshot + post-processing effects.
 * <p>
 * Ported from Photographica's PhotoCapture, stripped of all server networking,
 * film/digital workflows, and armour-stand logic.
 */
@Environment(EnvType.CLIENT)
public final class PhotoCapture {
    private PhotoCapture() {}

    // ── Timing / state ──────────────────────────────────────────────────────────
    public static long mirrorEndMs = 0L;
    public static long flashEndMs  = 0L;
    public static long secondClickAtMs = 0L;

    /** Depth at the centre of the screen (blocks), updated each frame. */
    public static float lastSceneDepthBlocks = 5.0f;

    private static long lastShotMs   = 0L;
    /** Said once per session, not once per shutter press. */
    private static boolean warnedShaderPackSuppression = false;
    private static boolean capturePending = false;

    /** Output format for the shot currently pending (single-shot or mid-long-exposure), set
     *  from {@link SnapmaticaClient#photoFormat} in {@link #take()} and read again once the
     *  capture actually resolves — a shot's format is decided at the moment the shutter is
     *  pressed, not whenever the format setting next happens to be read. */
    private static int pendingFormat = SnapmaticaClient.PHOTO_FORMAT_PNG;

    private static volatile float[] pendingLinearDepth = null;
    private static volatile int pendingDepthFbW = 0;
    private static volatile int pendingDepthFbH = 0;

    private static final long COOLDOWN_MS = 700L;

    // AF subject-distance query throttle. The long-range raycast (and especially the
    // Distant Horizons LOD raycast) is far too expensive to run every rendered frame —
    // focusing on distant terrain / sky froze the game. Sampling at ~10 Hz is plenty
    // since focus racks slowly; the depth texture for the blur is still copied each frame.
    private static final long AF_QUERY_INTERVAL_MS = 100L;
    private static long lastAfQueryMs = 0L;

    // ── Long exposure ───────────────────────────────────────────────────────────
    /**
     * Shutter speed used to change nothing but the brightness and the length of the mirror
     * blackout: 1/4000 and 1/15 produced an identical image. A real camera integrates light
     * for the whole time the shutter is open, so anything that moves — the subject, or the
     * camera itself — smears.
     *
     * <p>Ported from photographica, which does this by sampling the framebuffer repeatedly
     * across the exposure and averaging. The trails are therefore genuine: whatever actually
     * moved on screen during those milliseconds is what smears. There is no separate
     * handheld-shake simulation layered on top, and no warning tied to one — snapmatica never
     * had that effect to warn about in the first place.
     *
     * <p>Only armed at 1/30 s or slower. Faster than that, a single frame IS the exposure.
     */
    private static final double ACCUM_MIN_SHUTTER_SEC = 1.0 / 30.0;
    /**
     * Ceiling on samples, so a 30 s exposure costs a bounded amount rather than growing
     * without limit. Doubled from 120: at the old ceiling a long exposure past about 2 s
     * (where this — not the 8 ms floor below — is what sets the gap between samples) sampled
     * as coarsely as one frame every 250 ms at 30 s, and the per-sample motion smear, exact
     * only for perfectly linear motion, could not fully hide the seam on anything less linear
     * than a straight pan — an orbit's arc, a hand's wobble. Denser samples shrink the gap
     * each one has to cover instead of asking the smear to cover more of it.
     */
    private static final int  ACCUM_MAX_SAMPLES = 120;
    /** Floor on the gap between samples, so a short exposure cannot spin the readback. */
    private static final long ACCUM_MIN_INTERVAL_MS = 8L;

    private static volatile boolean accumArmed   = false;
    private static volatile long    accumEndMs   = 0L;
    private static volatile long    accumNextMs  = 0L;
    private static volatile long    accumIntervalMs = ACCUM_MIN_INTERVAL_MS;
    private static volatile int     accumSamples = 0;
    private static volatile int     accumW = 0, accumH = 0;
    private static volatile float[] accumR = null, accumG = null, accumB = null;
    // Previous real sample's raw channel values (not yet averaged), kept only to build the
    // cheap virtual mid-sample below. Doubling ACCUM_MAX_SAMPLES cost a full extra screenshot
    // readback + shader pass per sample; a same-size CPU blend of two adjacent real frames
    // buys back most of the smoothness for none of that GPU cost.
    private static volatile float[] accumPrevR = null, accumPrevG = null, accumPrevB = null;
    private static volatile boolean accumHasPrev = false;
    private static volatile float[] accumDepth = null;
    private static volatile int     accumDepthFbW = 0, accumDepthFbH = 0;

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * True while a photo is being taken — including for the whole duration of a long exposure.
     *
     * <p>Callers use this to suppress the hand, the player model and block outlines, and to ask
     * EvfBlurRenderer for a FULL-frame blur rather than one scissored to the viewfinder. All of
     * those have to hold for every frame the shutter is open, not just the instant it opens, or
     * a long exposure would average one correctly-prepared frame together with a hundred that
     * still had the hand in them and only the viewfinder rectangle defocused.
     */
    public static boolean isCapturePending() {
        return capturePending || accumArmed || ApertureIntegration.isActive();
    }

    /**
     * True only for the single frame of a FAST-shutter capture — never during a long exposure's
     * accumulation.
     *
     * <p>Exists apart from {@link #isCapturePending} because it answers a different question.
     * That one asks "does this frame need the hand hidden and a full-frame blur," which is true
     * for every sample a long exposure accumulates. This one asks "is this the one frame worth
     * spending far more of the gather's sample budget on," and for a long exposure the answer is
     * no for all of them — each of its samples already gets averaged with up to 120 others, so
     * paying per-sample for extra spatial taps too would be both redundant and, at roughly
     * 150 ms a frame, slow enough to turn a several-second exposure into the better part of a
     * minute. A fast shutter has no such accumulation to lean on, and produces exactly one frame
     * to make count.
     */
    public static boolean isSingleShotCapturePending() {
        return capturePending;
    }

    /** True while a long exposure is integrating — used to decide whether to smear samples. */
    public static boolean isLongExposing() { return accumArmed; }

    /**
     * True when the currently pending capture (single-shot, or every sample of a long
     * exposure still accumulating) is being saved as a DNG.
     *
     * <p>Used by {@link EvfBlurRenderer#applyBlur} to skip the whole sensor-side pass (Pass
     * 5/6 — white balance, exposure, the dynamic-range curve, the tone curve and the highlight
     * rolloff) for the frame(s) that become a DNG capture. Every one of those is a decision a
     * raw file is supposed to leave to the developer, and they run on the GPU before this class
     * ever reads the framebuffer back, so they have to not happen at all rather than merely not
     * be re-applied.
     */
    public static boolean isDngCapturePending() {
        return isCapturePending() && pendingFormat == SnapmaticaClient.PHOTO_FORMAT_DNG;
    }

    public static boolean isBusy() {
        return System.currentTimeMillis() < mirrorEndMs;
    }

    public static void take() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastShotMs < COOLDOWN_MS) return;
        // A burst can outlast the cooldown — two hundred pupil samples is a couple of seconds
        // of real time — so the cooldown alone would let a second press re-arm on top of one
        // already running and throw away every sample taken so far.
        if (ApertureIntegration.isActive()) return;

        // Decided now, at shutter press — see the field doc on pendingFormat.
        pendingFormat = SnapmaticaClient.photoFormat;

        int em = SnapmaticaClient.exposureMode;
        int shutterIdx = (em == 1 || em == 3) ? SnapmaticaClient.autoShutterIdx : SnapmaticaClient.shutterSpeedIdx;
        double shutterSec = SnapmaticaClient.SHUTTER_SECONDS[
                Math.max(0, Math.min(SnapmaticaClient.SHUTTER_SECONDS.length - 1, shutterIdx))];
        long shutterMs = Math.min(1500, (long)(shutterSec * 1000));

        // Electronic shutter — no mirror, so no blackout. The blackout was modelling an SLR's
        // mirror swinging up, which is exactly the thing a mirrorless body does not have; it
        // also hid the live view for the whole of a long exposure, when watching the trails
        // build is the point. A brief exposure flash is all that marks the frame.
        mirrorEndMs = now;
        secondClickAtMs = 0L;
        flashEndMs = now + Math.min(200, 20 + shutterMs / 2);

        if (ApertureIntegration.shouldUse()) {
            ApertureIntegration.arm(pendingFormat, shutterSec);
            accumArmed = false;
            capturePending = false;
            lastShotMs = now;
            return;
        }

        // Arm the long exposure for slow shutters. capturePending stays false in that case:
        // the accumulator owns the capture from here, and finalises it when the shutter closes.
        if (shutterSec >= ACCUM_MIN_SHUTTER_SEC) {
            long durationMs = Math.max((long) (shutterSec * 1000), 1L);
            accumArmed   = true;
            accumEndMs   = now + durationMs;
            accumIntervalMs = Math.max(ACCUM_MIN_INTERVAL_MS, durationMs / ACCUM_MAX_SAMPLES);
            accumNextMs  = now;
            accumSamples = 0;
            accumR = null; accumG = null; accumB = null;
            accumDepth = null;
            capturePending = false;
        } else {
            capturePending = true;
        }
        lastShotMs = now;
    }

    public static void captureIfPending() {
        // A burst in progress owns the capture path until it has all its samples.
        if (ApertureIntegration.isActive()) { ApertureIntegration.tick(Minecraft.getInstance()); return; }
        // A long exposure in progress owns the capture path until the shutter closes.
        if (accumArmed) { tickAccumulation(); return; }
        if (!capturePending) return;
        capturePending = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Depth was already captured in onWorldRenderEnd() while the depth buffer was valid.
        final float[] capturedDepth = pendingLinearDepth;
        final int capturedFbW = pendingDepthFbW;
        final int capturedFbH = pendingDepthFbH;
        pendingLinearDepth = null;
        pendingDepthFbW = 0;
        pendingDepthFbH = 0;
        final int fmt = pendingFormat;
        // The layer belongs to this one shutter press; holding it would let the next photograph

        //? if >=1.21.10 {
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), raw -> processScreenshot(mc, raw, capturedDepth, capturedFbW, capturedFbH, fmt));
        //?} else {
        /*NativeImage raw;
        try {
            raw = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        } catch (Exception e) {
            System.err.println("[Snapmatica] Screenshot failed: " + e.getMessage());
            return;
        }
        processScreenshot(mc, raw, capturedDepth, capturedFbW, capturedFbH, fmt);
        *///?}
    }

    /** Samples the framebuffer across the open shutter, then hands the average to the normal
     *  photo pipeline. Runs once per rendered frame while a long exposure is armed. */
    private static void tickAccumulation() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) { resetAccumulation(); return; }
        long now = System.currentTimeMillis();

        // First tick: take over the depth pre-read the viewfinder left for us. The depth is
        // sampled once, at the start of the exposure — a moving scene has no single depth, and
        // the subject you focused on is the one that should drive the defocus.
        if (accumSamples == 0 && accumDepth == null) {
            accumDepth    = pendingLinearDepth;
            accumDepthFbW = pendingDepthFbW;
            accumDepthFbH = pendingDepthFbH;
            pendingLinearDepth = null;
            pendingDepthFbW = 0;
            pendingDepthFbH = 0;
        }

        if (now >= accumNextMs && accumSamples < ACCUM_MAX_SAMPLES) {
            //? if >=1.21.10 {
            Screenshot.takeScreenshot(mc.getMainRenderTarget(), PhotoCapture::accumulateFrame);
            //?} else {
            /*try {
                accumulateFrame(Screenshot.takeScreenshot(mc.getMainRenderTarget()));
            } catch (Exception e) {
                System.err.println("[Snapmatica] Long-exposure sample failed: " + e.getMessage());
            }
            *///?}
            accumNextMs = now + accumIntervalMs;
            // Reset the smear reference so the next one spans this gap, not one frame of it.
            EvfBlurRenderer.markMotionSampled();
        }

        if (now >= accumEndMs || accumSamples >= ACCUM_MAX_SAMPLES) finalizeAccumulation(mc);
    }

    /** Adds one framebuffer sample to the running per-channel sums, and closes {@code frame}. */
    private static void accumulateFrame(NativeImage frame) {
        if (frame == null) return;
        try {
            int w = frame.getWidth(), h = frame.getHeight();
            if (accumR == null) {
                accumW = w; accumH = h;
                accumR = new float[w * h];
                accumG = new float[w * h];
                accumB = new float[w * h];
                accumPrevR = new float[w * h];
                accumPrevG = new float[w * h];
                accumPrevB = new float[w * h];
            }
            // A resize mid-exposure changes the buffer dimensions; drop the odd frame rather
            // than corrupt the sums.
            if (w != accumW || h != accumH) return;

            boolean haveVirtual = accumHasPrev;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int   c   = getPixelAbgr(frame, x, y);
                    int   idx = y * w + x;
                    float rr =  c        & 0xFF;
                    float gg = (c >>> 8) & 0xFF;
                    float bb = (c >>> 16) & 0xFF;
                    if (haveVirtual) {
                        // Cheap stand-in for an extra real sample: the midpoint between this
                        // frame and the previous real one, folded in alongside this frame
                        // itself. No pixel motion estimation needed here — the renderer's
                        // per-sample smear already draws the real motion into both frames, so
                        // a straight blend of two adjacent real samples is a fair stand-in for
                        // whatever landed in between, at zero extra GPU cost.
                        accumR[idx] += (accumPrevR[idx] + rr) * 0.5f + rr;
                        accumG[idx] += (accumPrevG[idx] + gg) * 0.5f + gg;
                        accumB[idx] += (accumPrevB[idx] + bb) * 0.5f + bb;
                    } else {
                        accumR[idx] += rr;
                        accumG[idx] += gg;
                        accumB[idx] += bb;
                    }
                    accumPrevR[idx] = rr; accumPrevG[idx] = gg; accumPrevB[idx] = bb;
                }
            }
            accumSamples += haveVirtual ? 2 : 1;
            accumHasPrev = true;
        } finally {
            frame.close();
        }
    }

    /** Averages the exposure and pushes it through the normal crop / effects / save path. */
    private static void finalizeAccumulation(Minecraft mc) {
        int w = accumW, h = accumH, n = accumSamples;
        float[] r = accumR, g = accumG, b = accumB;
        float[] depth = accumDepth;
        int dFbW = accumDepthFbW, dFbH = accumDepthFbH;
        resetAccumulation();

        if (n == 0 || r == null) {
            System.err.println("[Snapmatica] Long exposure: no frames accumulated, discarding");
            return;
        }

        NativeImage averaged = new NativeImage(w, h, false);
        float inv = 1.0f / n;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int rr = Math.min(255, (int) (r[idx] * inv + 0.5f));
                int gg = Math.min(255, (int) (g[idx] * inv + 0.5f));
                int bb = Math.min(255, (int) (b[idx] * inv + 0.5f));
                setPixelAbgr(averaged, x, y, 0xFF000000 | (bb << 16) | (gg << 8) | rr);
            }
        }
        processScreenshot(mc, averaged, depth, dFbW, dFbH, pendingFormat);
    }

    private static void resetAccumulation() {
        accumArmed   = false;
        accumEndMs   = 0L;
        accumSamples = 0;
        accumR = null; accumG = null; accumB = null;
        accumPrevR = null; accumPrevG = null; accumPrevB = null;
        accumHasPrev = false;
        accumDepth = null;
        accumDepthFbW = 0; accumDepthFbH = 0;
    }

    /**
     * The photo's frame: the largest centred rectangle of the current aspect that fits.
     * Returned as {x, y, w, h}.
     *
     * <p>The single definition of what the camera sees, used both to crop the capture and to
     * lay out the viewfinder. They used to compute it separately, and disagreed: the viewfinder
     * drew a box 86% of the screen height while the capture kept the FULL height, so roughly
     * 16% more scene ended up in the photo than was ever framed. Beyond breaking
     * what-you-see-is-what-you-get, it made every focal length read about a stop longer than it
     * was — a 24 mm framed like a 35 mm, because the box was showing 46 degrees of a 53 degree
     * field.
     */
    public static int[] frameRect(int w, int h, boolean portrait) {
        float target = portrait ? 2f / 3f : 3f / 2f;
        int fw, fh;
        if ((float) w / h > target) { fh = h; fw = Math.round(h * target); }
        else                        { fw = w; fh = Math.round(w / target); }
        return new int[]{ (w - fw) / 2, (h - fh) / 2, fw, fh };
    }

    /**
     * Entry point for {@link ApertureIntegration}: an image whose defocus is already physical.
     *
     * <p>No depth is passed on, and that is the point — {@code applyPhotoEffects} would
     * otherwise run its own CPU circle-of-confusion blur over an image that has been blurred
     * once already, by the aperture, correctly. Everything else the photo pipeline does —
     * the crop, vignetting, sensor noise, the metadata, the save — is wanted exactly as usual.
     */
    static void deliverIntegrated(Minecraft mc, NativeImage img, int format) {
        processScreenshot(mc, img, null, 0, 0, format);
    }

    private static void processScreenshot(Minecraft mc, NativeImage raw, float[] linearDepth, int fbW, int fbH, int format) {
        // ── Crop to 3:2 (landscape) or 2:3 (portrait) aspect ratio ──────────────
        int w = raw.getWidth();
        int h = raw.getHeight();
        int[] fr = frameRect(w, h, SnapmaticaClient.portraitOrientation);
        int offX = fr[0], offY = fr[1], cropW = fr[2], cropH = fr[3];
        NativeImage cropped = new NativeImage(cropW, cropH, false);
        for (int y = 0; y < cropH; y++) {
            for (int x = 0; x < cropW; x++) {
                setPixelAbgr(cropped, x, y, getPixelAbgr(raw, x + offX, y + offY));
            }
        }
        raw.close();

        // ── Apply photo effects ─────────────────────────────────────────────────
        // The exposure the shader applied to this frame — or, for a DNG, would have applied
        // had the pass not been skipped. Read here rather than inside the writer so the DNG's
        // BaselineExposure tag and the shader's own gain are provably the same number.
        double expFactor = PhotoProcessor.exposureFactor();
        NativeImage processed = applyPhotoEffects(cropped, linearDepth, fbW, fbH);
        cropped.close();

        // ── Save to disk ────────────────────────────────────────────────────────
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File snapDir = new File(mc.gameDirectory, "snapmatica/photos");
        snapDir.mkdirs();
        String ext = switch (format) {
            case SnapmaticaClient.PHOTO_FORMAT_JPG -> "jpg";
            case SnapmaticaClient.PHOTO_FORMAT_DNG -> "dng";
            default -> "png";
        };
        File outFile = new File(snapDir, timestamp + "." + ext);

        // The settings the shot was actually taken at, for the file's own metadata block —
        // all three formats carry it, each in its own container. See PhotoExif.
        PhotoExif exif = PhotoExif.ofCurrentSettings(processed.getWidth(), processed.getHeight());

        try {
            switch (format) {
                case SnapmaticaClient.PHOTO_FORMAT_JPG -> writeJpg(processed, outFile, exif);
                case SnapmaticaClient.PHOTO_FORMAT_DNG -> writeDng(processed, outFile, expFactor, exif);
                default -> {
                    processed.writeToFile(outFile);
                    // PNG's own eXIf chunk, spliced in afterwards because NativeImage's writer
                    // has no way to add one. Same best-effort handling as the JPEG path: the
                    // photograph is already on disk and complete either way.
                    try {
                        exif.injectIntoPng(outFile);
                    } catch (Exception e) {
                        System.err.println("[Snapmatica] Could not attach EXIF to "
                                + outFile.getName() + " — " + e);
                    }
                }
            }
            System.out.println("[Snapmatica] Photo saved: " + outFile.getAbsolutePath());
            // Auto-copy the freshly saved photo to the system clipboard. ClipboardUtil reports
            // success/failure to the action bar itself. Skipped for DNG: ClipboardUtil's own
            // decode paths (System.Drawing.Image.FromFile on Windows, ImageIO.read elsewhere)
            // are ordinary raster-image readers and cannot open a raw container — there is no
            // "paste into Discord" equivalent for a raw file the way there is for PNG/JPG.
            if (format != SnapmaticaClient.PHOTO_FORMAT_DNG) {
                ClipboardUtil.copyImageAsync(outFile);
            }
        } catch (IOException e) {
            System.err.println("[Snapmatica] Failed to save photo: " + e.getMessage());
        } finally {
            processed.close();
        }
    }

    /**
     * How hard to compress, decided by the picture rather than fixed.
     *
     * <p>JPEG quantises in absolute levels, but a photograph's detail lives inside whatever
     * tonal range it actually occupies — and a defocused frame occupies very little of one. The
     * shot that prompted this spanned 92 levels of 255, so every quantisation step cost it
     * nearly three times what the same step costs a full-range picture, and the 8x8 grid came
     * through as visible mottling across the bokeh. Measured on a smooth low-range frame: at
     * the old fixed 0.92 the block boundaries carried 2.62x the step of the pixels inside a
     * block; at 0.98, 1.35; at 0.99, 1.24 — for 44 KB against 94 KB, on an image whose whole
     * point is smooth gradient.
     *
     * <p>So scale the quantiser's budget with the range the picture uses. A normal, contrasty
     * photograph still gets 0.92 and the file size that goes with it; a frame thrown wide open
     * gets most of the way to lossless, where its gradients need it.
     */
    private static float jpegQualityFor(NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int step = Math.max(1, Math.min(w, h) / 256);
        int[] hist = new int[256];
        int n = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = getPixelAbgr(img, x, y);
                int luma = ((c & 0xFF) * 77 + ((c >>> 8) & 0xFF) * 150 + ((c >>> 16) & 0xFF) * 29) >> 8;
                hist[Math.max(0, Math.min(255, luma))]++;
                n++;
            }
        }
        if (n == 0) return 0.92f;
        int lo = 0, hi = 255, acc = 0;
        for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc >= n / 100) { lo = i; break; } }
        acc = 0;
        for (int i = 255; i >= 0; i--) { acc += hist[i]; if (acc >= n / 100) { hi = i; break; } }
        float range = Math.max(hi - lo, 1) / 255.0f;
        // Square, not linear: the step has to shrink with the range AND the eye is judging it
        // against a flatter surround, so the two compound. Lands on 0.92 at full range and
        // 0.99 at the range this shot had.
        float q = 1.0f - 0.08f * range * range;
        return Math.max(0.92f, Math.min(0.995f, q));
    }

    /** Encodes {@code img} (already effect-processed, no alpha) as a JPEG via javax.imageio —
     *  the same codec subsystem {@link ClipboardUtil} already uses headlessly for PNG decode
     *  ({@code ImageIO.read}) on this exact runtime, which is what confirms ImageIO's plugin
     *  codecs (reading AND writing) work without a display here, unlike AWT's GUI/Toolkit
     *  classes (see this project's own notes on that distinction). */
    private static void writeJpg(NativeImage img, File outFile, PhotoExif exif) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        java.awt.image.BufferedImage buffered =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = getPixelAbgr(img, x, y);
                int r = c & 0xFF, g = (c >>> 8) & 0xFF, b = (c >>> 16) & 0xFF;
                buffered.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(jpegQualityFor(img));
        try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(outFile)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(buffered, null, null), param);
        } finally {
            writer.dispose();
        }
        // EXIF goes in as a second step on the finished file — see PhotoExif.injectIntoJpeg.
        // Failing to attach it is not worth failing the save over: the photograph is already
        // written and complete, and a JPEG with no metadata is still the shot that was taken.
        try {
            exif.injectIntoJpeg(outFile);
        } catch (Exception e) {
            System.err.println("[Snapmatica] Could not attach EXIF to " + outFile.getName() + " — " + e);
        }
    }

    /** Packs {@code img} (already effect-processed with the exposure multiply skipped, no
     *  alpha) into interleaved RGB bytes and hands them to {@link DngWriter}, carrying
     *  {@code expFactor} as the BaselineExposure tag instead of the baked-in pixel multiply
     *  PNG/JPG get. See {@link DngWriter} and {@code applyPhotoEffects}'s rawCapture doc. */
    private static void writeDng(NativeImage img, File outFile, double expFactor,
                                 PhotoExif exif) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        byte[] rgb = new byte[w * h * 3];
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = getPixelAbgr(img, x, y);
                rgb[idx++] = (byte) (c & 0xFF);
                rgb[idx++] = (byte) ((c >>> 8) & 0xFF);
                rgb[idx++] = (byte) ((c >>> 16) & 0xFF);
            }
        }
        double baselineExposureStops = Math.log(Math.max(expFactor, 1e-6)) / Math.log(2.0);
        // White balance was NOT multiplied into these pixels either — EvfBlurRenderer's
        // skipSensorPost left the GPU pass out for this frame — so the gain travels as
        // AsShotNeutral metadata instead. Same reasoning as BaselineExposure above.
        DngWriter.write(outFile, w, h, rgb, baselineExposureStops,
                SnapmaticaClient.whiteBalanceGain(), exif);
    }

    /**
     * The projection matrix the world was ACTUALLY rendered with — the one that produced
     * the depth buffer we are about to linearise.
     *
     * <p>On 1.21.11 this must be {@code getProjectionMatrix}, not {@code getBasicProjection-
     * Matrix}: the latter builds a fresh matrix out of vanilla parameters, so a LOD mod
     * (Voxy, DH) that extends the far plane elsewhere in the pipeline is invisible to it and
     * distant terrain gets linearised against a far plane that is far too small. Older
     * versions have no separate accessor — there {@code getBasicProjectionMatrix} IS what
     * {@code renderWorld} uses, so it is the correct source.
     *
     * <p>Far/near are fov-independent, so the 70° argument is irrelevant to what we read.
     * The per-version target selection lives in the invoker, so there is none here.
     */
    static org.joml.Matrix4f worldProjection(Minecraft mc) {
        // 26 hands the projection out through the render state rather than through
        // GameRenderer, so there is no invoker to reach for here.
        net.minecraft.client.renderer.state.level.CameraRenderState cam =
                mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
        return cam != null ? cam.projectionMatrix : null;
    }

    /**
     * Copies the scene depth for the EVF blur, BEFORE translucent geometry is drawn.
     *
     * <p>Glass writes depth at its own surface while the pixel shows what is behind it. Taken
     * at the end of the world render, the buffer therefore said "glass pane, two blocks away"
     * for a pixel displaying a building far beyond it — so the blur treated the view through
     * a window as near-field, and the pane's own rectangle appeared as a hard-edged shape in
     * the defocus however heavily blurred it was. Every translucent surface has the problem;
     * glass is only the one you notice.
     *
     * <p>Sampling before the translucent pass leaves the depth of whatever is actually behind
     * the glass, which is what the camera is looking at. Solid geometry and entities are
     * already drawn by this point, so nothing that should be focusable is missed.
     */
    public static void onBeforeTranslucent() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Ambient depth of field belongs in this list. It is not the viewfinder, not a capture
        // and not a recording, so leaving it out meant the depth texture simply stopped being
        // refreshed while it was the only thing rendering — and the blur then ran against
        // whatever view happened to be in there from the last time the camera WAS up. That is
        // not a subtle failure: the focus sits wherever the old frame's geometry was, and
        // anything the two frames disagree about reads as full defocus.
        if (!SnapmaticaClient.viewfinderActive(mc) && !capturePending
                && !VideoRecorder.isRecording() && !SnapmaticaClient.ambientDof) return;

        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) return;

        int rd = mc.options.renderDistance().get();
        EvfBlurRenderer.updateDepthFar(worldProjection(mc),
                -1.0f, Math.max(rd * 64f, 256f));
        EvfBlurRenderer.captureDepth(vpW, vpH);
        EvfBlurRenderer.markDepthFresh();
    }

    /**
     * Nearest valid subject distance from the camera, sampling either a single centre ray
     * ("spot" AF area) or a small cluster of rays around it ("zone" — see {@link
     * SnapmaticaClient#focusAreaWide}) and taking the CLOSEST hit across the cluster, the
     * same "whatever's nearest in the zone wins" rule a real camera's zone AF uses — good for
     * a subject that isn't sitting exactly on the crosshair, at the cost of occasionally
     * grabbing foreground clutter a spot reading would have looked straight past. Identical
     * block/entity/self logic to what this used to be inline, just run once per sample point
     * instead of duplicated across this file's two Stonecutter branches.
     */
    /**
     * The camera's look direction re-aimed at the AF point, as {yaw, pitch} in degrees.
     *
     * <p>Two things this is careful about, both of which the obvious version gets wrong.
     *
     * <p>First, the AF point is an ANGLE, not a pixel offset. The fraction is turned into a
     * tangent against the frame and back into an angle, so a point halfway to the edge leaves
     * at {@code atan(0.5 * tan(half-angle))} rather than at half the half-angle. The scale
     * comes from the same focal-length-in-pixels the blur shader works in -- the frame's half
     * width over that length IS the tangent of its half angle -- so the AF point, the reticle
     * drawn over it and the circle of confusion computed for it agree at every focal length,
     * instead of drifting apart as the lens gets long.
     *
     * <p>Second, the offset is applied in the CAMERA's basis and only then converted back to
     * yaw and pitch, rather than being added to them. Adding degrees to yaw is what the +-5
     * degree zone cluster does and is harmless at five degrees, but the AF point reaches half
     * the field -- thirty degrees or more on a wide lens -- and a yaw offset added while the
     * camera is pitched down does not point at the edge of the frame at all. It sweeps a line
     * of latitude, which is a circle that shrinks as the camera tilts: at sixty degrees down
     * the same yaw offset covers twice the angle asked for, and the AF point would slide off
     * the subject the moment you looked up or down.
     *
     * <p>Returns the base angles untouched when the point is centred, which is both the common
     * case and free.
     */
    static float[] afPointDirection(Minecraft mc, float baseYaw, float basePitch) {
        float ax = SnapmaticaClient.afPointX, ay = SnapmaticaClient.afPointY;
        if ((ax == 0f && ay == 0f) || mc.getWindow() == null) return new float[]{baseYaw, basePitch};

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        if (sw <= 0 || sh <= 0) return new float[]{baseYaw, basePitch};
        int[] fr = frameRect(sw, sh, SnapmaticaClient.portraitOrientation);

        // The full screen HEIGHT spans the sensor height -- that is how EvfBlurRenderer derives
        // its focal length in pixels -- so the frame's own half angles are its pixel half sizes
        // measured against that same length. Taking them from the FRAME rather than from the
        // screen is what makes the portrait flip and every letterboxed window come out right
        // without a second formula for each.
        double halfTanScreen = SnapmaticaClient.sensorHeightMm() * 0.5
                / Math.max(1e-4, SnapmaticaClient.imageDistanceMm(
                        Math.max(1f, SnapmaticaClient.focalLengthMm)));
        double tx =  ax * (fr[2] / (double) sh) * halfTanScreen;
        double ty = -ay * (fr[3] / (double) sh) * halfTanScreen;   // screen y down, camera y up

        net.minecraft.world.phys.Vec3 fwd   = net.minecraft.world.phys.Vec3.directionFromRotation(basePitch, baseYaw);
        net.minecraft.world.phys.Vec3 right = net.minecraft.world.phys.Vec3.directionFromRotation(0f, baseYaw + 90f);
        net.minecraft.world.phys.Vec3 up    = right.cross(fwd);
        net.minecraft.world.phys.Vec3 d = fwd.add(right.scale(tx))
                             .add(up.scale(ty)).normalize();

        float yaw   = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1.0, Math.min(1.0, d.y))));
        return new float[]{yaw, pitch};
    }

    private static double nearestSubjectDistance(Minecraft mc, net.minecraft.world.phys.Vec3 eye,
                                                 float baseYaw, float basePitch, double maxDist) {
        // A modest cluster, not a frame-wide scan — real zone AF areas cover a fraction of
        // the frame, not the whole thing, and a wider spread risks the zone reaching an
        // entirely different plane of the scene than the subject actually on the reticle.
        return nearestSubjectDistance(mc, eye, baseYaw, basePitch, maxDist,
                SnapmaticaClient.focusAreaWide
                        ? new float[][]{{0, 0}, {5, 0}, {-5, 0}, {0, 5}, {0, -5}}
                        : new float[][]{{0, 0}});
    }

    /**
     * The same search with the sample pattern supplied by the caller, so the ambient depth of
     * field can share it rather than keep a second, poorer copy.
     *
     * <p>Sharing matters here specifically because of what a plain block raycast MISSES: it
     * walks terrain and stops at the first solid face, so a mob standing in front of a wall is
     * invisible to it and the focus settles on the wall behind. Anything that decides what to
     * focus on has to consider entities, and having two implementations of that is how one of
     * them ends up not doing it — which is exactly what happened to the ambient mode.
     */
    static double nearestSubjectDistance(Minecraft mc, net.minecraft.world.phys.Vec3 eye,
                                         float baseYaw, float basePitch, double maxDist,
                                         float[][] offsets) {
        double best = maxDist;
        for (float[] o : offsets) {
            net.minecraft.world.phys.Vec3 look = net.minecraft.world.phys.Vec3.directionFromRotation(
                    basePitch + o[1], baseYaw + o[0]);
            net.minecraft.world.phys.Vec3 end = eye.add(look.scale(maxDist));
            net.minecraft.world.phys.BlockHitResult blockHit =
                    AutoFocus.raycastThroughGlass(mc, eye, look, maxDist);
            double bestDist = (blockHit != null
                    && blockHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS)
                    ? eye.distanceTo(blockHit.getLocation()) : maxDist;
            net.minecraft.world.phys.AABB searchBox =
                    new net.minecraft.world.phys.AABB(eye, eye).expandTowards(look.scale(maxDist)).inflate(1.0);
            net.minecraft.world.phys.EntityHitResult entityHit =
                    net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(mc.player, eye, end,
                            searchBox, e -> !e.isSpectator() && e.isAlive(), bestDist * bestDist);
            if (entityHit != null) {
                double eDist = eye.distanceTo(entityHit.getLocation());
                if (eDist < bestDist) bestDist = eDist;
            }
            if (Freecam.isActive()) {
                java.util.Optional<net.minecraft.world.phys.Vec3> playerHit = mc.player
                        .getBoundingBox().inflate(mc.player.getPickRadius()).clip(eye, end);
                if (playerHit.isPresent()) {
                    double pDist = eye.distanceTo(playerHit.get());
                    if (pDist < bestDist) bestDist = pDist;
                }
            }
            if (bestDist < best) best = bestDist;
        }
        return best;
    }

    /**
     * Samples the centre pixel of the currently bound depth buffer and stores the
     * linear depth in {@link #lastSceneDepthBlocks} for the viewfinder focus reticle.
     * Called from LevelRenderEvents.LAST (fires inside renderWorld).
     *
     * Mirrors Photographica's updateCenterDepth() exactly, including the
     * viewport query via glGetIntegerv(GL_VIEWPORT) and GL error clearing.
     */
    public static void onWorldRenderEnd() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Run when the viewfinder is active (sneaking + mode enabled), when a photo is
        // pending, OR when video is recording (needs depth every frame regardless of sneak).
        if (!SnapmaticaClient.viewfinderActive(mc) && !capturePending && !VideoRecorder.isRecording()) return;

        //? if >=1.21.10 {
        // Depth is captured earlier now — see onBeforeTranslucent().

        // AF subject distance — THROTTLED. The 1000-block vanilla raycast plus the
        // Distant Horizons LOD raycast are far too costly to run every frame; focusing
        // on far terrain / sky froze the game (DH traverses many thousands of blocks).
        // Sample at ~10 Hz and reuse lastSceneDepthBlocks in between; focus racks slowly
        // so this is imperceptible.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastAfQueryMs >= AF_QUERY_INTERVAL_MS) {
            lastAfQueryMs = nowMs;
            final double maxDist = 1000.0;
            net.minecraft.world.phys.Vec3 eye = SnapmaticaClient.cameraPos(mc);
            net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
            float[] af = afPointDirection(mc, camera.yRot(), camera.xRot());
            double bestDist = nearestSubjectDistance(mc, eye, af[0], af[1], maxDist);
            lastSceneDepthBlocks = (bestDist < maxDist) ? (float) bestDist : SnapmaticaClient.FOCUS_INFINITY;
            // Raycast missed (sky / beyond loaded range). The old GPU centre-depth readback
            // (readCenterLinearDepthBlocks -> glReadPixels on a depth FBO) crashed the NVIDIA
            // driver on hybrid-GPU laptops whenever a LOD mod (Voxy / Distant Horizons) was
            // drawing the distance — a hard EXCEPTION_ACCESS_VIOLATION inside nvoglv64.dll —
            // so it is removed. Fall back to the DH LOD raycast; without it the focus simply
            // stays at infinity, which reads distant terrain as far (sharp) — correct anyway.
            if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                float dhDist = -1f;
                if (dhDist > 0f) lastSceneDepthBlocks = dhDist;
            }
        }

        // Depth readback for CPU DoF is no longer needed in 1.21.11: the EVF blur
        // (GPU bokeh) is applied to mainTex in GameRendererMixin before captureIfPending(),
        // so the screenshot already contains the correct DoF. Eliminating readLinearDepthCpu()
        // removes the GPU→CPU sync stall that caused the freeze on photo capture.
        //?} else {
        /*// Read from the currently bound framebuffer without switching.
        GL11.glGetError(); // clear any pending GL error
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) return;

        // NO depth copy here. This hook fires after the translucent pass, so the buffer now
        // holds the glass surface rather than what is behind it; copying it would overwrite
        // the good pre-translucent copy onBeforeTranslucent() just made and blur every scene
        // seen through a window. The viewport query above is kept — the centre-depth fallback
        // further down still needs it.

        // AF subject distance — THROTTLED. The world raycast is the PRIMARY focus
        // distance (good to 1000 m, covers all vanilla render distances). The GPU
        // centre-depth reconstruction saturates at currentDepthFar (≈ rd*64), pinning
        // every distant reading to ~940 m, so it (and the DH LOD raycast) is consulted
        // ONLY when the raycast misses (sky / beyond loaded range). Without this the
        // focus was stuck near the far plane — the "950 m cap" fixed in 172eca8.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastAfQueryMs >= AF_QUERY_INTERVAL_MS) {
            lastAfQueryMs = nowMs;
            final double maxDist = 1000.0;
            net.minecraft.world.phys.Vec3 eye = SnapmaticaClient.cameraPos(mc);
            net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
            float[] af = afPointDirection(mc, camera.yRot(), camera.xRot());
            double bestDist = nearestSubjectDistance(mc, eye, af[0], af[1], maxDist);
            lastSceneDepthBlocks = (bestDist < maxDist) ? (float) bestDist : SnapmaticaClient.FOCUS_INFINITY;

            if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                // Raycast missed: reconstruct the GPU centre depth, rejecting saturated
                // far-plane readings, then fall back to the DH LOD raycast.
                int cx = vpW / 2;
                int cy = vpH / 2;
                FloatBuffer depthBuf = BufferUtils.createFloatBuffer(1);
                GL11.glReadPixels(cx, cy, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);
                float rawD = depthBuf.get(0);
                // Mirror the sky threshold used by readCenterLinearDepthBlocks (>=1.21.11):
                // reject only depth values at/beyond the far plane (rawD >= 0.999999).
                // The old farPlane*0.95 check on the linearised result rejected valid terrain
                // near the far plane (e.g. 974m with farPlane=1024 gives ~975m > 972.8).
                if (rawD >= 0.001f && rawD < 0.999999f) {
                    float ndc = 2.0f * rawD - 1.0f;
                    final float near     = 0.05f;
                    final float farPlane = EvfBlurRenderer.currentDepthFar;
                    float gpuDepth = 2.0f * near * farPlane / (farPlane + near - ndc * (farPlane - near));
                    if (gpuDepth > 0.0f) lastSceneDepthBlocks = gpuDepth;
                }
                if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                    float dhDist = -1f;
                    if (dhDist > 0f) lastSceneDepthBlocks = dhDist;
                }
            }
        }
        *///?}
    }

    // ── Photo effects pipeline ──────────────────────────────────────────────────

    /**
     * Applies photographic effects to the cropped screenshot:
     * exposure compensation, vignetting, ISO noise, tone curve,
     * highlight rolloff, and depth‑of‑field blur.
     *
     * <p>Only two of the original five steps are still here. Exposure, the tone curve and the
     * highlight rolloff moved to the shader (evf_blur.fsh Pass 5) so the viewfinder shows them
     * too; a DNG capture skips that whole pass, so it still carries none of the three.
     *
     * <p>Vignetting and ISO noise stay on the CPU, and are applied for a DNG as well. Both are
     * genuinely sensor/lens-level phenomena in a real camera — light falloff at the sensor, and
     * photon-shot/read noise at the sensor — that a real raw file legitimately carries, and
     * that a raw developer's lens-correction and noise-reduction tools are built to remove
     * from raw data rather than to find already gone. They also both want the CROPPED photo:
     * vignetting is measured against the frame's own corners, and the noise is drawn over
     * exactly the pixels being saved.
     *
     * <p>Depth-of-field blur (pass 2 below) is unconditional: a lens genuinely blurs
     * out-of-focus light at the sensor, before any raw processing.
     */
    private static NativeImage applyPhotoEffects(NativeImage src, float[] linearDepth,
                                                  int fbW, int fbH) {
        int w = src.getWidth();
        int h = src.getHeight();

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        // DOF parameters
        float focusDist = SnapmaticaClient.focusDistance;
        float depthCenter = lastSceneDepthBlocks;          // blocks at centre

        NativeImage dst = new NativeImage(w, h, false);

        // Chroma noise is generated COARSE and interpolated up, not per pixel.
        //
        // The two components of sensor noise do not live at the same spatial scale. Luminance
        // noise is essentially per-pixel: it is photon shot noise plus read noise, independent
        // at every site. Colour noise is not — it only exists after demosaicing, which builds
        // every pixel's missing two channels out of its neighbours, and that shared arithmetic
        // correlates the error over several pixels. Which is why a high-ISO frame looks
        // BLOTCHY in colour and merely grainy in luminance, and why per-pixel colour noise
        // reads as a fine rainbow shimmer instead: right amplitude, wrong scale entirely.
        //
        // One value per CHROMA_BLOB_PX square, bilinearly interpolated between, which gives
        // the smooth mottling for the cost of a grid a ninth the size of the image.
        final int CHROMA_BLOB_PX = 3;
        int noiseIso = (int) Math.round(SnapmaticaClient.autoIsoIdeal);
        // Noise is a property of the SENSOR as much as of the ISO, and until now it was not.
        //
        // ISO is a gain, not a quantity of light: what actually sets the grain is how many
        // photons each photosite caught, and a smaller sensor at the same f-number and shutter
        // catches them over a proportionally smaller area. Shot noise goes as the square root of
        // the count, so the noise scales as 1/sqrt(area) — which is the crop factor itself. That
        // is the whole of the "equivalent ISO" rule photographers already use: Micro Four Thirds
        // at ISO 400 grains like full frame at 1600, two stops, exactly the factor of two this
        // gives. Without it, choosing a sensor changed the framing and the depth of field and
        // left the one thing everyone actually buys a bigger sensor FOR untouched.
        float baseSigma = isoToNoiseSigma(noiseIso)
                * Math.max(0.2f, SnapmaticaClient.sensorCropFactor);
        float chromaSigma = baseSigma * chromaNoiseRatio(noiseIso);
        int cw = w / CHROMA_BLOB_PX + 2, ch = h / CHROMA_BLOB_PX + 2;
        float[] crField = null, cbField = null;
        if (baseSigma > 0.5f) {
            crField = new float[cw * ch];
            cbField = new float[cw * ch];
            for (int i = 0; i < crField.length; i++) {
                crField[i] = (float) (Math.random() - 0.5) * chromaSigma;
                cbField[i] = (float) (Math.random() - 0.5) * chromaSigma;
            }
        }

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int c = getPixelAbgr(src, px, py);
                int a = (c >>> 24) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int g = (c >>>  8) & 0xFF;
                int r =  c         & 0xFF;

                // Exposure, the tone curve and the highlight rolloff all used to be applied
                // here. They are the shader's job now (evf_blur.fsh Pass 5), which is what lets
                // the viewfinder show them — and, because it is then the same arithmetic on the
                // same buffer this is read back from, guarantees the two agree rather than
                // merely resembling each other. Skipped for a DNG by the same flag that skips
                // the rest of the pass, so a raw capture still carries none of them.
                //
                // What is left below stays on the CPU because it applies to the CROPPED photo:
                // vignetting is measured against the frame's own corners, and the noise is a
                // per-pixel draw over exactly the pixels being saved.
                //
                // One consequence worth stating: during a LONG exposure the shader runs per
                // accumulated sample, so exposure and the two curves are applied before the
                // samples are averaged rather than after. A real sensor integrates first and
                // clips once, so the strictly correct order is the other way round. It only
                // shows where the gain pushes a sample past full scale, which needs the shot to
                // be badly over-exposed already — at a gain near 1, which is what any auto mode
                // and any deliberate long exposure lands on, nothing clips and the two orders
                // agree. Traded for having ONE implementation of the exposure that the
                // viewfinder and the photograph both go through.

                // 1. Lens vignetting — applied for a DNG too; see this method's doc.
                float dx = (px - halfW) / halfW;
                float dy = (py - halfH) / halfH;
                float vig = vignetteStrength(SnapmaticaClient.aperture);
                float vf = Math.max(0f, 1f - vig * (dx * dx + dy * dy) * 0.5f);
                r = clamp((int)(r * vf));
                g = clamp((int)(g * vf));
                b = clamp((int)(b * vf));

                // 2. ISO noise — applied for a DNG too; see this method's doc. The
                // Auto-ISO assist's target, not the manual dial, so a shot that leaned on it
                // for a dark scene actually shows the grain that came with it (see
                // SnapmaticaClient.autoIsoIdeal); equals the manual dial otherwise.
                float noiseSigma = baseSigma;
                if (noiseSigma > 0.5f) {
                    // Luminance and CHROMA noise, separately — this used to add one identical
                    // value to all three channels, which is a purely luminance grain, and
                    // luminance grain alone is what film looks like, not what a digital sensor
                    // at high ISO looks like. A real sensor's three colour sites are read out
                    // independently and then demosaiced, so their errors do not agree: the
                    // result has a colour component that grows into the dominant one as the
                    // gain climbs, and it is the reason a high-ISO frame reads as mottled
                    // red/green blotches rather than as clean grain. Modelled here as a shared
                    // luminance term plus per-channel colour terms arranged to cancel in
                    // luminance, so the two are genuinely independent rather than one of them
                    // quietly brightening the frame.
                    float lum = (float)(Math.random() - 0.5) * noiseSigma * 1.5f;
                    float fx = (float) px / CHROMA_BLOB_PX, fy = (float) py / CHROMA_BLOB_PX;
                    float cr = sampleField(crField, cw, ch, fx, fy);
                    float cb = sampleField(cbField, cw, ch, fx, fy);
                    r = clamp((int)(r + lum + cr));
                    g = clamp((int)(g + lum - (cr + cb) * 0.5f));
                    b = clamp((int)(b + lum + cb));
                }

                setPixelAbgr(dst, px, py, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        // Pass 2: Depth-of-field blur
        NativeImage pass2;
        if (linearDepth != null) {
            pass2 = applyDepthOfField(dst, SnapmaticaClient.aperture, focusDist,
                                       linearDepth, w, h, fbW, fbH);
            dst.close();
        } else {
            pass2 = dst;
        }
        return pass2;
    }

    private static NativeImage applyDepthOfField(NativeImage src,
                                                  float aperture, float focusDist,
                                                  float[] linearDepth,
                                                  int iw, int ih, int fbW, int fbH) {
        // Ceiling for the CPU photo path. The old 80 / N^2 collapsed to 1.25 px at f/8 and
        // essentially nothing beyond, which is where the "no bokeh past f/8" behaviour came
        // from on this path. The per-pixel CoC below is already physical; this only bounds the
        // kernel, so bound it by what the optics can produce rather than by the f-number.
        float maxBlurPx = Math.min(32.0f,
                EvfBlurRenderer.maxCocPx(focusDist, aperture, SnapmaticaClient.focalLengthMm,
                        SnapmaticaClient.dofScaleMm, ih / SnapmaticaClient.sensorHeightMm()));
        int   maxR      = Math.max(1, (int) Math.ceil(maxBlurPx));

        // Match the depth-buffer crop to the output image aspect (iw/ih), so the same
        // mapping works for both 3:2 landscape and 2:3 portrait framing.
        float targetA = (float) iw / ih;
        int croppedW, croppedH, cropOffX, cropOffY;
        if ((float) fbW / fbH > targetA) {
            croppedH = fbH; croppedW = Math.round(fbH * targetA);
            cropOffX = (fbW - croppedW) / 2; cropOffY = 0;
        } else {
            croppedW = fbW; croppedH = Math.round(fbW / targetA);
            cropOffX = 0; cropOffY = (fbH - croppedH) / 2;
        }

        // Physical thin-lens circle of confusion (same model as the EVF shader):
        //   coc_mm = f^2 / (N * (S1 - f)) * |S2 - S1| / S2
        // This keeps deep depth-of-field for wide/normal lenses so distant terrain stays
        // sharp, instead of saturating to max blur a short distance past the focus plane.
        boolean infinityFocus = (focusDist >= SnapmaticaClient.FOCUS_INFINITY);
        float fmm = SnapmaticaClient.focalLengthMm;
        float pxPerMm = (float) ih / SnapmaticaClient.sensorHeightMm();  // frame height maps to ih px
        float[] cocMap = new float[iw * ih];
        boolean[] isFgMap = new boolean[iw * ih];  // true = closer than focus plane
        for (int iy = 0; iy < ih; iy++) {
            for (int ix = 0; ix < iw; ix++) {
                int fx    = Math.max(0, Math.min(fbW - 1, cropOffX + ix * croppedW / iw));
                int fy_gl = Math.max(0, Math.min(fbH - 1, fbH - 1 - (cropOffY + iy * croppedH / ih)));
                float depthM = Math.max(linearDepth[fy_gl * fbW + fx], 0.05f);
                float cocMM;
                if (infinityFocus) {
                    cocMM = (fmm * fmm) / (aperture * depthM * 200f);
                } else {
                    float s1mm = focusDist * 200f;
                    cocMM = (fmm * fmm) * Math.abs(depthM - focusDist)
                            / (depthM * aperture * Math.max(s1mm - fmm, 1.0f));
                    isFgMap[iy * iw + ix] = (depthM < focusDist);
                }
                cocMap[iy * iw + ix] = Math.min(cocMM * pxPerMm, maxBlurPx);
            }
        }

        int[] hBuf = new int[iw * ih];
        for (int iy = 0; iy < ih; iy++) {
            for (int ix = 0; ix < iw; ix++) {
                float coc = cocMap[iy * iw + ix];
                if (coc < 0.5f) { hBuf[iy * iw + ix] = getPixelAbgr(src, ix, iy); continue; }
                int r = Math.min(maxR, (int) Math.ceil(coc));
                float sigma = Math.max(coc * 0.5f, 1.0f);
                float ra = 0, ga = 0, ba = 0, aa = 0, tw = 0;
                boolean fg = isFgMap[iy * iw + ix];
                for (int dx = -r; dx <= r; dx++) {
                    int sx = ix + dx;
                    if (sx < 0 || sx >= iw) continue;
                    float gauss = (float) Math.exp(-(float)(dx * dx) / (2.0f * sigma * sigma));
                    float cocW = fg ? 1.0f : Math.max(0.10f, Math.min(1.0f, cocMap[iy * iw + sx] / coc));
                    float w = gauss * cocW;
                    if (w < 0.001f) continue;
                    int c = getPixelAbgr(src, sx, iy);
                    aa += ((c >>> 24) & 0xFF) * w; ba += ((c >>> 16) & 0xFF) * w;
                    ga += ((c >>>  8) & 0xFF) * w; ra += ( c         & 0xFF) * w;
                    tw += w;
                }
                hBuf[iy * iw + ix] = (tw < 0.001f) ? getPixelAbgr(src, ix, iy)
                        : ((clamp(Math.round(aa / tw)) << 24) | (clamp(Math.round(ba / tw)) << 16)
                         | (clamp(Math.round(ga / tw)) <<  8) |  clamp(Math.round(ra / tw)));
            }
        }

        NativeImage result = new NativeImage(iw, ih, false);
        for (int ix = 0; ix < iw; ix++) {
            for (int iy = 0; iy < ih; iy++) {
                float coc = cocMap[iy * iw + ix];
                if (coc < 0.5f) { setPixelAbgr(result, ix, iy, getPixelAbgr(src, ix, iy)); continue; }
                int r = Math.min(maxR, (int) Math.ceil(coc));
                float sigma = Math.max(coc * 0.5f, 1.0f);
                float ra = 0, ga = 0, ba = 0, aa = 0, tw = 0;
                boolean fg = isFgMap[iy * iw + ix];
                for (int dy = -r; dy <= r; dy++) {
                    int sy = iy + dy;
                    if (sy < 0 || sy >= ih) continue;
                    float gauss = (float) Math.exp(-(float)(dy * dy) / (2.0f * sigma * sigma));
                    float cocW = fg ? 1.0f : Math.max(0.10f, Math.min(1.0f, cocMap[sy * iw + ix] / coc));
                    float w = gauss * cocW;
                    if (w < 0.001f) continue;
                    int c = hBuf[sy * iw + ix];
                    aa += ((c >>> 24) & 0xFF) * w; ba += ((c >>> 16) & 0xFF) * w;
                    ga += ((c >>>  8) & 0xFF) * w; ra += ( c         & 0xFF) * w;
                    tw += w;
                }
                setPixelAbgr(result, ix, iy, (tw < 0.001f) ? hBuf[iy * iw + ix]
                        : ((clamp(Math.round(aa / tw)) << 24) | (clamp(Math.round(ba / tw)) << 16)
                         | (clamp(Math.round(ga / tw)) <<  8) |  clamp(Math.round(ra / tw))));
            }
        }
        return result;
    }

    // ── Pixel access ────────────────────────────────────────────────────────────
    // The version branches live in Pixels, which the gallery's own decoders need too.

    private static int getPixelAbgr(NativeImage img, int x, int y) { return Pixels.getAbgr(img, x, y); }
    private static void setPixelAbgr(NativeImage img, int x, int y, int abgr) { Pixels.setAbgr(img, x, y, abgr); }

    // ── Effect helpers ──────────────────────────────────────────────────────────

    private static float vignetteStrength(float aperture) {
        if (aperture <= 1.4f) return 0.70f;
        if (aperture <= 2.0f) return 0.55f;
        if (aperture <= 2.8f) return 0.40f;
        if (aperture <= 4.0f) return 0.25f;
        if (aperture <= 5.6f) return 0.15f;
        if (aperture <= 8.0f) return 0.08f;
        return 0.03f;
    }

    private static float isoToNoiseSigma(int iso) {
        if (iso <=   100) return  0.0f;
        if (iso <=   200) return  1.5f;
        if (iso <=   400) return  3.0f;
        if (iso <=   800) return  6.0f;
        if (iso <=  1600) return 11.0f;
        if (iso <=  3200) return 18.0f;
        if (iso <=  6400) return 28.0f;
        if (iso <= 12800) return 42.0f;
        return 60.0f;
    }

    /**
     * Bilinear read of a coarse noise field — see the chroma-noise comment in
     * {@code applyPhotoEffects}. Interpolating rather than taking the nearest cell is what
     * keeps the blotches looking like blotches: nearest-neighbour would draw the grid itself,
     * as visible 3-pixel squares in a regular lattice, which is not a thing any sensor does.
     */
    private static float sampleField(float[] field, int fw, int fh, float x, float y) {
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, fw - 1), y1 = Math.min(y0 + 1, fh - 1);
        float tx = x - x0, ty = y - y0;
        float a = field[y0 * fw + x0], b = field[y0 * fw + x1];
        float c = field[y1 * fw + x0], d = field[y1 * fw + x1];
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }

    /**
     * How strong the colour component of sensor noise is relative to the luminance component,
     * as a fraction of {@link #isoToNoiseSigma}.
     *
     * <p>Climbs with ISO rather than staying fixed, because that is the part of the behaviour
     * worth modelling: at base ISO the read noise is small enough that demosaicing averages
     * most of the colour disagreement away and what survives is close to pure luminance grain,
     * while at the top of the range chroma is the component that actually ruins the picture.
     * Kept below 1 throughout — colour noise dominating outright would need the per-channel
     * errors to be larger than the shared one, which they are not.
     */
    private static float chromaNoiseRatio(int iso) {
        if (iso <=   400) return 0.15f;
        if (iso <=  1600) return 0.35f;
        if (iso <=  6400) return 0.60f;
        return 0.80f;
    }



    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
