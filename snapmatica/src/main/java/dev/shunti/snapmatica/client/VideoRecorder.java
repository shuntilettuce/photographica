package dev.shunti.snapmatica.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side video recording engine for Snapmatica.
 *
 * Design philosophy: "record the viewfinder preview".
 *
 * The live EVF preview already applies a high-quality, GPU depth-of-field blur
 * (EvfBlurRenderer, a physical thin-lens shader). Rather than re-deriving DoF on
 * the CPU per frame — which stalled the render thread on a full-buffer depth
 * read and then spent seconds in post-processing — we simply bake that same GPU
 * blur into the framebuffer for each recorded frame and screenshot it. No depth
 * read-back, no CPU DoF, no CPU motion blur. Post-processing is just the ffmpeg
 * encode.
 *
 * Pipeline per recorded frame (render thread, in GameRendererMixin after renderWorld):
 *   captureFrameIfRecording() – bake preview DoF (GPU) → screenshot → crop 16:9
 *                               → downsample → async PNG write.
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ────────────────────────────────────────────────────────────────
    public static final int FPS        = 24;
    public static final int MAX_FRAMES = 30 * 120;   // 2 minutes @ 30 fps

    private static int currentFps = FPS;

    // Motion blur via ffmpeg frame-blending (tmix), applied at encode time. The
    // per-frame CPU motion blur was removed in the "record the preview" rewrite to
    // stop render-thread stalls; blending adjacent frames in the encoder gives the
    // same look at zero in-game cost. 0 = off, 1 = light (2-frame), 2 = strong (4-frame).
    private static volatile int motionBlur = 1;

    // ── Camera state saved for the duration of a take ────────────────────────────
    private static boolean prevSmoothCamera = false;
    private static boolean prevBobView      = true;

    // ── Autofocus constants (identical to AutoFocus.java — same feel as photo viewfinder) ──
    // Focus easing is TIME-based, not per-tick. Stepping a fixed fraction on a fixed 20 Hz
    // schedule while frames are captured at 24 fps means consecutive frames advance the focus
    // by different amounts — two steps, then one, then two — and that beat is visible in the
    // footage as the focus stuttering even though the frame pacing is even. Deriving the step
    // from elapsed wall-clock time makes the motion identical regardless of when frames land.
    //
    // The time constant also has to be LONGER than the interval at which the target itself
    // is refreshed. The scene raycast is throttled to 10 Hz, so the target arrives as a
    // staircase; with the old 72 ms constant the focus reached each new step almost at once
    // and then waited, reproducing that staircase in the footage. At 24 fps its ~2.4-frame
    // period is exactly the alternation measured frame to frame. Easing over 180 ms instead
    // means the focus is always still travelling when the next target lands, so it glides
    // through the steps rather than landing on each one.
    private static final float AF_TAU_SEC        = 0.18f;   // e-folding time of the rack
    private static final float AF_MAX_LOG_PER_S  = 4.4f;    // rack speed ceiling
    private static final float AF_SNAP_EPS   = 0.01f;
    // Throttle scene raycast to 10 Hz to avoid stalling on long-range/DH raycasts.
    private static final long  AF_QUERY_INTERVAL_MS = 100L;

    // ── Autofocus state ──────────────────────────────────────────────────────────
    private static float currentFocusDepth = 5.0f;
    private static float focusTargetDepth  = 5.0f;
    private static long  lastAfQueryMs     = 0L;
    private static long  lastAfStepMs      = 0L;

    // ── Recording state ──────────────────────────────────────────────────────────
    private static volatile boolean recording      = false;
    private static volatile boolean postProcessing = false;
    private static volatile int     ppProgress     = 0;
    private static volatile Text    ppMessage      = Text.empty();
    public  static volatile long    doneAtMs       = 0L;

    private static String          sessionId;
    private static int             frameCount;        // sequential PNG file index (0,1,2...)
    private static int             virtualFrameCount; // timing index; skips slots when render is slow
    private static long            recordStartMs;
    private static long            nextFrameMs;
    private static File            rawDir;
    private static List<FrameMeta> frameMetas;

    // Count of frames whose PNG write has completed (success or failure).
    // Incremented by the ioExecutor thread; read by the post-processing thread
    // to display write-phase progress (0–10%).
    private static final AtomicInteger writtenFrames = new AtomicInteger(0);

    // Frame scaling and PNG encoding are pure CPU work on independent frames, and each
    // output file carries its own index, so they parallelise freely. A single thread could
    // not keep up with the capture rate: the queue grew all recording long, holding a
    // full-resolution NativeImage per pending frame. Capped low to leave cores for the game.
    private static final ExecutorService ioExecutor =
            Executors.newFixedThreadPool(
                    Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)),
                    r -> {
                        Thread t = new Thread(r, "snapmatica-video-io");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    });

    // ── Public accessors ─────────────────────────────────────────────────────────
    public static boolean isRecording()      { return recording; }
    public static boolean isPostProcessing() { return postProcessing; }
    public static int     getPpProgress()    { return ppProgress; }
    public static Text    getPpMessage()     { return ppMessage; }
    public static long    getDoneAtMs()      { return doneAtMs; }
    public static int     getFrameCount()    { return frameCount; }
    public static long    getRecordStartMs() { return recordStartMs; }
    public static int     getCurrentFps()    { return currentFps; }
    public static void    setFps(int fps)    { if (!recording) currentFps = fps; }
    public static int     getMotionBlur()    { return motionBlur; }
    public static void    setMotionBlur(int v) { motionBlur = Math.max(0, Math.min(2, v)); }

    // ── FrameMeta ────────────────────────────────────────────────────────────────
    // durationSec lets the concat demuxer hold each frame for the right wall-clock
    // time, so dropped frames (render slower than target FPS) don't speed the video up.
    record FrameMeta(int idx, float durationSec) {}

    // ── Start / Stop ─────────────────────────────────────────────────────────────
    public static void toggleRecording() {
        if (recording) stopRecording();
        else if (!postProcessing) startRecording();
    }

    public static void startRecording() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        sessionId = ts;

        // currentFps is deliberately left alone — it holds whatever the recorder screen was
        // set to, and resetting it here silently threw that choice away on every take.
        frameCount        = 0;
        virtualFrameCount = 0;
        writtenFrames.set(0);
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);

        // Probe scene depth immediately so the first frame gets correct focus/DoF.
        float initDepth = computeSceneFocusDepth(mc);
        currentFocusDepth = (initDepth > 0.3f && initDepth < 999.0f) ? initDepth : 5.0f;
        focusTargetDepth  = currentFocusDepth;
        lastAfQueryMs     = System.currentTimeMillis();
        lastAfStepMs      = System.currentTimeMillis();

        rawDir = new File(mc.runDirectory, "snapmatica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            System.err.println("[VideoRecorder] Could not create raw dir: " + rawDir);
            return;
        }

        // Enable cinematic (smooth) camera for steadier panning footage.
        prevSmoothCamera = mc.options.smoothCameraEnabled;
        mc.options.smoothCameraEnabled = true;
        // Stabilisation: view bobbing is the walking shake, and it is worse on video than it
        // looks in play because the DoF blur is baked per frame — the bob swings the whole
        // frame while the defocus stays put, so the two disagree and the image reads as
        // smeared rather than moved. Cinematic (smooth) camera above already damps the mouse;
        // this damps the gait.
        prevBobView = mc.options.getBobView().getValue();
        mc.options.getBobView().setValue(false);

        recording = true;
        mc.player.sendMessage(Text.translatable("snapmatica.rec.started"), true);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.smoothCameraEnabled = prevSmoothCamera;
        mc.options.getBobView().setValue(prevBobView);

        if (mc.player != null)
            mc.player.sendMessage(Text.translatable("snapmatica.rec.stopped"), true);

        final List<FrameMeta> metas   = new ArrayList<>(frameMetas);
        final File            rawSnap = rawDir;
        final File            vidDir  = new File(mc.runDirectory, "snapmatica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = Text.translatable("snapmatica.pp.encoding");

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vidDir),
                "snapmatica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    // ── Render-thread hooks ───────────────────────────────────────────────────────

    // ── Frame capture (render thread) ─────────────────────────────────────────────
    /**
     * Called from GameRendererMixin after renderWorld() (after Iris shader compositing).
     * Bakes the preview DoF into the framebuffer, then screenshots it.
     */
    public static void captureFrameIfRecording() {
        if (!recording) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // AF + DoF run every render frame so the preview stays smooth (applying only on
        // capture frames caused sharp/blurred flickering each render cycle).
        updateAutofocus(mc);
        applyPreviewBlur(mc);

        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (virtualFrameCount >= MAX_FRAMES) { stopRecording(); return; }

        // How many frame slots this single PNG covers. When the render thread is
        // slower than the target FPS, several time slots pass between captures; the
        // duration stamp keeps playback at correct real-world speed.
        long overdue = now - nextFrameMs;           // ≥ 0 here
        int slotsConsumed = 1 + (int)(overdue * currentFps / 1000L);
        slotsConsumed = Math.min(slotsConsumed, currentFps); // cap at 1 s to absorb pauses
        float durationSec = (float) slotsConsumed / currentFps;

        virtualFrameCount += slotsConsumed;
        nextFrameMs = recordStartMs + (long)(virtualFrameCount * 1000.0 / currentFps);

        int  idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        frameMetas.add(new FrameMeta(idx, durationSec));
        frameCount++;

        if (virtualFrameCount >= currentFps * 60 && virtualFrameCount - slotsConsumed < currentFps * 60
                && mc.player != null)
            mc.player.sendMessage(Text.translatable("snapmatica.rec.one_minute"), true);

        // The framebuffer is already DoF-blurred (applyPreviewBlur ran above). Screenshot it.
        // Nothing but the read-back itself happens here — scaling and encoding are handed
        // straight to the I/O pool so the render thread is freed as early as possible.
        //? if >=1.21.11 {
        /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> submitFrame(raw, outFile));
        *///?} else {
        // takeScreenshot does the glReadPixels on the render thread — unavoidable.
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            System.err.println("[VideoRecorder] Screenshot failed frame " + idx);
            return;
        }
        submitFrame(raw, outFile);
        //?}
    }

    /**
     * Takes ownership of a freshly read-back frame and does the whole scale-and-encode on
     * the I/O pool. {@code raw} is closed here exactly once, on every path.
     */
    private static void submitFrame(NativeImage raw, File outFile) {
        ioExecutor.submit(() -> {
            try {
                NativeImage frame = cropAndDownsample(raw, 1280);
                try { frame.writeTo(outFile); }
                catch (IOException e) {
                    System.err.println("[VideoRecorder] Frame write failed: " + outFile);
                } finally { frame.close(); }
            } catch (Exception e) {
                System.err.println("[VideoRecorder] Frame process failed: " + outFile);
            } finally {
                raw.close();
                writtenFrames.incrementAndGet();
            }
        });
    }

    /**
     * Applies the viewfinder's GPU depth-of-field blur across the whole framebuffer.
     * Uses the exact same focus / aperture / focal-length the live preview uses, so
     * the recorded frame is literally the preview. Gated identically to the preview:
     * a lens must be attached, the aperture wide enough, and focus finite.
     */
    private static void applyPreviewBlur(MinecraftClient mc) {
        if (SnapmaticaClient.lensType == 0) return;
        // No f-number gate — EvfBlurRenderer decides from the actual circle of confusion.
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        // Video renders at the lens FOV (focalLengthMm) and uses that same focal length
        // for DoF with DOF_SCALE_STILL — so the video bokeh equals the still viewfinder's
        // at the same lens (F5.6 video = F5.6 stills). No boost: the DoF is exactly what a
        // still photo through this lens would show. Changing the lens focal length zooms
        // both the framing and the bokeh together, like a real lens.
        // Recording keeps its own CPU autofocus (currentFocusDepth) for now — its rack easing
        // is part of the footage's look, and GPU AF would snap instantly.
        EvfBlurRenderer.renderBlur(0, 0, sw, sh,
                currentFocusDepth, SnapmaticaClient.aperture,
                SnapmaticaClient.focalLengthMm, EvfBlurRenderer.DOF_SCALE_STILL, false);
    }

    // ── Autofocus ────────────────────────────────────────────────────────────────

    private static void updateAutofocus(MinecraftClient mc) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastAfQueryMs >= AF_QUERY_INTERVAL_MS) {
            lastAfQueryMs = nowMs;
            float sceneDepth = computeSceneFocusDepth(mc);
            focusTargetDepth = Math.max(sceneDepth, 0.3f);
        }
        // Every frame, by elapsed time — no fixed-rate gate to beat against the capture rate.
        float dt = (nowMs - lastAfStepMs) / 1000.0f;
        lastAfStepMs = nowMs;
        if (dt > 0.0f) stepFocusLerp(Math.min(dt, 0.25f));
    }

    private static void stepFocusLerp(float dt) {
        float logCur = (float) Math.log(Math.max(0.1f, currentFocusDepth));
        float logTar = (float) Math.log(Math.max(0.1f, focusTargetDepth));
        float diff   = logTar - logCur;
        if (Math.abs(diff) < AF_SNAP_EPS) { currentFocusDepth = focusTargetDepth; return; }
        // Exponential approach over dt — the frame-rate-independent form of "move a fixed
        // fraction of what remains", so the same wall-clock interval always covers the same
        // ground however the frames happen to be spaced.
        float step    = diff * (1.0f - (float) Math.exp(-dt / AF_TAU_SEC));
        float ceiling = AF_MAX_LOG_PER_S * dt;
        if (Math.abs(step) > ceiling) step = Math.signum(step) * ceiling;
        currentFocusDepth = (float) Math.exp(logCur + step);
    }

    private static float computeSceneFocusDepth(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return currentFocusDepth;
        final double maxDist = 1000.0;
        net.minecraft.util.math.Vec3d eye = mc.player.getCameraPosVec(1.0f);
        net.minecraft.util.math.Vec3d look = mc.player.getRotationVec(1.0f);
        net.minecraft.util.math.Vec3d end = eye.add(look.multiply(maxDist));
        net.minecraft.util.hit.BlockHitResult blockHit =
                AutoFocus.raycastThroughGlass(mc, eye, look, maxDist);
        double bestDist = (blockHit != null
                && blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS)
                ? eye.distanceTo(blockHit.getPos()) : maxDist;
        final double entityDist = Math.min(bestDist, 60.0);
        net.minecraft.util.math.Vec3d entityEnd = eye.add(look.multiply(entityDist));
        net.minecraft.util.math.Box searchBox =
                mc.player.getBoundingBox().stretch(look.multiply(entityDist)).expand(1.0);
        net.minecraft.util.hit.EntityHitResult entityHit =
                net.minecraft.entity.projectile.ProjectileUtil.raycast(mc.player, eye, entityEnd,
                        searchBox, e -> !e.isSpectator() && e.isAlive(), entityDist * entityDist);
        if (entityHit != null) {
            double eDist = eye.distanceTo(entityHit.getPos());
            if (eDist < bestDist) bestDist = eDist;
        }
        return (float) Math.min(bestDist, 999.0);
    }

    // ── Post-processing (encode only) ─────────────────────────────────────────────

    private static void doPostProcess(List<FrameMeta> metas, File rawDirIn, File vidDir) {
        int total = metas.size();
        if (total == 0) {
            postProcessing = false;
            ppMessage      = Text.translatable("snapmatica.pp.no_frames");
            doneAtMs       = System.currentTimeMillis();
            return;
        }

        // Wait for the I/O thread to finish writing all frame PNGs,
        // updating the progress bar (0–10%) while we wait.
        ppMessage = Text.translatable("snapmatica.pp.writing");
        Future<?> sentinel = ioExecutor.submit(() -> {});
        while (!sentinel.isDone()) {
            ppProgress = writtenFrames.get() * 10 / total;
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
        try { sentinel.get(); } catch (Exception ignored) {}

        ppMessage  = Text.translatable("snapmatica.pp.encoding_mp4");
        ppProgress = 10;

        // Per-frame duration file → ffmpeg concat demuxer holds each PNG for exactly
        // the right wall-clock time, correcting for dropped frames.
        File concatFile = new File(rawDirIn, "frames.txt");
        try (PrintWriter pw = new PrintWriter(concatFile, java.nio.charset.StandardCharsets.UTF_8)) {
            for (FrameMeta meta : metas) {
                String fname = String.format("frame_%04d.png", meta.idx());
                pw.println("file '" + fname.replace("'", "\\'") + "'");
                pw.printf("duration %.6f%n", meta.durationSec());
            }
        } catch (IOException e) {
            System.err.println("[VideoRecorder] concat file write failed: " + e);
        }

        if (!vidDir.exists()) vidDir.mkdirs();
        String outMp4    = new File(vidDir, sessionId + ".mp4").getAbsolutePath();
        boolean ffmpegOk = runFfmpeg(concatFile, outMp4);

        ppProgress = 100;
        if (ffmpegOk) {
            ppMessage = Text.translatable("snapmatica.pp.saved",
                    "snapmatica/videos/" + sessionId + ".mp4");
            System.out.println("[VideoRecorder] Video saved: " + outMp4);
            ClipboardUtil.copyFileAsync(new File(outMp4));
            deleteDir(rawDirIn);
        } else {
            File pngDir = new File(vidDir, sessionId);
            rawDirIn.renameTo(pngDir);
            ppMessage = Text.translatable("snapmatica.pp.no_ffmpeg",
                    "snapmatica/videos/" + sessionId + "/");
            System.out.println("[VideoRecorder] ffmpeg not found; PNGs at " + pngDir);
        }

        postProcessing = false;
        doneAtMs       = System.currentTimeMillis();
    }

    // ── ffmpeg ────────────────────────────────────────────────────────────────────

    /**
     * ffmpeg frame-blend motion-blur filter for the current strength, or null if off.
     * tmix slides a window of N frames and averages them, one output frame per input
     * frame, so the framerate (and the concat duration stamps that set playback speed)
     * are preserved — it just adds the blended motion trail.
     */
    private static String motionBlurFilter() {
        switch (motionBlur) {
            case 1:  return "tmix=frames=2:weights='3 1'";  // light — 75/25 blend
            case 2:  return "tmix=frames=4";   // strong — ~4-frame trail
            default: return null;              // off
        }
    }

    /** Cores to hand ffmpeg: half the machine, at least 2, never more than 8. */
    private static int encodeThreads() {
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static boolean runFfmpeg(File concatFile, String outPath) {
        String[] candidates = {"ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"};
        String vf = motionBlurFilter();
        for (String ff : candidates) {
            try {
                // concat demuxer: each frame carries its own duration so the video
                // plays at correct wall-clock speed even when frames were dropped.
                List<String> cmd = new ArrayList<>(List.of(
                        ff, "-y",
                        "-f", "concat", "-safe", "0",
                        "-i", concatFile.getAbsolutePath()));
                if (vf != null) { cmd.add("-vf"); cmd.add(vf); }
                // Leave the machine usable while encoding. Unconstrained, libx264 defaults
                // to preset=medium across every core and — being a separate process — is
                // beyond the reach of the I/O pool's thread priorities, so it starved the
                // game right as recording stopped. Half the cores at veryfast encodes far
                // faster than realtime for 1280x720 anyway; at the same CRF the only cost
                // is a somewhat larger file.
                cmd.addAll(List.of(
                        "-threads", Integer.toString(encodeThreads()),
                        "-c:v", "libx264", "-preset", "veryfast",
                        "-crf", "18", "-pix_fmt", "yuv420p",
                        outPath));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process proc = pb.start();

                long startMs = System.currentTimeMillis();
                while (proc.isAlive()) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    ppProgress = 10 + (int) Math.min(88,
                            (System.currentTimeMillis() - startMs) / 500);
                }

                int exit = proc.waitFor();
                if (exit == 0) return true;
                System.err.println("[VideoRecorder] ffmpeg exited " + exit);
                return false;
            } catch (IOException | InterruptedException ignored) {}
        }
        return false;
    }

    // ── Image utilities ───────────────────────────────────────────────────────────

    /**
     * Crops to 16:9 and box-downsamples to {@code maxWidth} in ONE pass.
     *
     * <p>This replaced a {@code cropTo16x9()} then {@code boxDownsample()} pair. That pair
     * allocated a full-resolution intermediate image per frame and walked every source pixel
     * twice — a read+write to crop, then a read to average — which at recording rates was the
     * single largest cost in the capture path and the reason frames were being dropped. Going
     * straight from the source rect to the destination halves the pixel traffic and removes
     * the per-frame full-resolution allocation entirely.
     */
    private static NativeImage cropAndDownsample(NativeImage src, int maxWidth) {
        int w = src.getWidth(), h = src.getHeight();
        float aspect = 16f / 9f;
        int cW, cH;
        if ((float) w / h > aspect) { cH = h; cW = Math.round(h * aspect); }
        else                        { cW = w; cH = Math.round(w / aspect); }
        int offX = (w - cW) / 2, offY = (h - cH) / 2;

        int dw = Math.min(maxWidth, cW);
        int dh = Math.max(1, Math.round((float) cH * dw / cW));
        NativeImage dst = new NativeImage(dw, dh, false);

        // Whole-pixel copy when no scaling is needed — avoids the averaging arithmetic.
        if (dw == cW && dh == cH) {
            for (int y = 0; y < dh; y++)
                for (int x = 0; x < dw; x++)
                    setPixel(dst, x, y, getPixel(src, x + offX, y + offY));
            return dst;
        }

        float xS = (float) cW / dw, yS = (float) cH / dh;
        for (int y = 0; y < dh; y++) {
            int sy0 = (int) Math.floor(y * yS);
            int sy1 = Math.min(cH, (int) Math.ceil((y + 1) * yS));
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int x = 0; x < dw; x++) {
                int sx0 = (int) Math.floor(x * xS);
                int sx1 = Math.min(cW, (int) Math.ceil((x + 1) * xS));
                if (sx1 <= sx0) sx1 = sx0 + 1;
                long ra = 0, ga = 0, ba = 0, aa = 0;
                int  n  = 0;
                for (int sy = sy0; sy < sy1; sy++)
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = getPixel(src, sx + offX, sy + offY);
                        aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
                        ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
                        n++;
                    }
                setPixel(dst, x, y,
                        ((int)(aa / n) << 24) | ((int)(ba / n) << 16)
                      | ((int)(ga / n) <<  8) |  (int)(ra / n));
            }
        }
        return dst;
    }


    // ── Pixel access (NativeImage format changed in 1.21.4) ──────────────────────

    //? if >=1.21.4 {
    /*private static int getPixel(NativeImage img, int x, int y) {
        int argb = img.getColorArgb(x, y);
        int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF,
            g = (argb >>>  8) & 0xFF, b =  argb         & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
    private static void setPixel(NativeImage img, int x, int y, int abgr) {
        int a = (abgr >>> 24) & 0xFF, b = (abgr >>> 16) & 0xFF,
            g = (abgr >>>  8) & 0xFF, r =  abgr         & 0xFF;
        img.setColorArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
    }
    *///?} else {
    private static int getPixel(NativeImage img, int x, int y) { return img.getColor(x, y); }
    private static void setPixel(NativeImage img, int x, int y, int abgr) { img.setColor(x, y, abgr); }
    //?}

    // ── Misc helpers ──────────────────────────────────────────────────────────────

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
