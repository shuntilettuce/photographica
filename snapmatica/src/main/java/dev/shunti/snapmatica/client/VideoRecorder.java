package dev.shunti.snapmatica.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *
 * A late capture (render thread slower than target fps) is written as exactly one file
 * with a stretched duration stamp — it used to also synthesize extra in-between files, a
 * plain per-pixel cross-fade between the two real frames either side of the gap, but that
 * only reads as motion when the two frames are nearly identical; on anything actually
 * moving (which is most of what freecam/path footage is) it read as several frames of
 * translucent ghosting, worse than the honest freeze-then-jump it replaced. Real
 * motion-compensated gap-filling — actually estimating where things moved to, not just
 * dissolving between two positions — is exactly what ffmpeg's own {@code minterpolate}
 * filter does, and post-processing already has the time budget a per-frame render-thread
 * blend never did, so that job moved to the encode step in {@link #runFfmpeg}.
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ────────────────────────────────────────────────────────────────
    public static final int FPS        = 24;
    public static final int MAX_RECORD_SECONDS = 120;
    // Output width, 16:9 height follows. Was hard-coded at 1280 (720p) with no way to change
    // it — nothing about the capture path actually needed that number, it was just never
    // wired up to a setting. 1080p by default: the crop/downsample and PNG-write cost scale
    // with pixel count, so this is a real trade against frame-drop risk on a slower machine,
    // which is exactly why it's adjustable rather than just raised outright.
    public static final int WIDTH_720P  = 1280;
    public static final int WIDTH_1080P = 1920;
    public static final int WIDTH_1440P = 2560;

    private static int currentFps   = FPS;
    private static int currentWidth = WIDTH_1080P;

    /** The frame-count cap, in {@code virtualFrameCount} slots — a fixed TIME limit, not a
     *  fixed frame count, so recording at 60 fps gets the same 2 minutes 30 fps always did
     *  rather than being quietly capped at half the length. */
    private static int maxFrames() {
        return currentFps * MAX_RECORD_SECONDS;
    }

    // Motion blur via ffmpeg frame-blending (tmix), applied at encode time. The
    // per-frame CPU motion blur was removed in the "record the preview" rewrite to
    // stop render-thread stalls; blending adjacent frames in the encoder gives the
    // same look at zero in-game cost. 0 = off, 1 = light (2-frame), 2 = strong (4-frame).
    // Off by default: tmix blends EVERY consecutive frame pair unconditionally, with no
    // regard for how much the scene actually moved between them — on footage that's panning
    // or flying the whole time (which freecam/path recordings mostly are), that reads as a
    // constant low-grade smear rather than an occasional stylistic touch. Opt-in from the
    // recorder screen for anyone who wants the cinematic trail on purpose.
    private static volatile int motionBlur = 0;

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
    // Whether ANY capture this take fell behind its target slot. minterpolate's real motion
    // estimation at encode time (see runFfmpeg) is genuinely expensive — several minutes for a
    // clip that's seconds long is not unusual at 1080p — and worth paying only for what it
    // actually fixes. A take that never dropped a frame has nothing for it to smooth over, so
    // encoding skips straight to the plain constant-fps resample instead of motion-estimating
    // a timeline that's already perfectly even.
    private static volatile boolean hadFrameDrops;
    private static long            recordStartMs;
    private static long            nextFrameMs;
    private static File            rawDir;
    private static List<FrameMeta> frameMetas;

    // Count of frames whose PNG write has completed (success or failure).
    // Incremented by an ioExecutor thread; read by the post-processing thread
    // to display write-phase progress (0–10%).
    private static final AtomicInteger writtenFrames = new AtomicInteger(0);

    // Every in-flight crop-then-write task for the current take, so doPostProcess can wait
    // for all of them regardless of which ioExecutor thread finishes which frame when — since
    // frames no longer blend against their neighbour (see the class doc), each one is fully
    // independent and none of them need to finish in capture order. Appended to only from the
    // render thread (every captureFrameIfRecording call); read/cleared from the pp thread and
    // startRecording, so the list itself needs to be a synchronized collection.
    private static final List<CompletableFuture<Void>> pendingWrites =
            java.util.Collections.synchronizedList(new ArrayList<>());

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
    public static int     getCurrentWidth()  { return currentWidth; }
    public static void    setWidth(int w)    { if (!recording) currentWidth = w; }
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
        hadFrameDrops     = false;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(maxFrames());
        pendingWrites.clear();

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
        // Snapshotted, not read fresh in the encode thread — setFps() is blocked only while
        // recording is true, and that flag just flipped false a few lines up, so a menu
        // change made while this take is still encoding must not retroactively change what
        // it renders at.
        final int fpsForEncode = currentFps;
        final boolean needsInterpolation = hadFrameDrops;

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = Text.translatable("snapmatica.pp.encoding");

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vidDir, fpsForEncode, needsInterpolation),
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
        if (virtualFrameCount >= maxFrames()) { stopRecording(); return; }

        // How many frame slots this single PNG covers. When the render thread is
        // slower than the target FPS, several time slots pass between captures.
        long overdue = now - nextFrameMs;           // ≥ 0 here
        int slotsConsumed = 1 + (int)(overdue * currentFps / 1000L);
        slotsConsumed = Math.min(slotsConsumed, currentFps); // cap at 1 s to absorb pauses
        if (slotsConsumed > 1) hadFrameDrops = true;

        virtualFrameCount += slotsConsumed;
        nextFrameMs = recordStartMs + (long)(virtualFrameCount * 1000.0 / currentFps);

        // A late capture still gets exactly one file, its duration stamp stretched to cover
        // the whole gap — real motion-compensated smoothing across that gap happens at
        // encode time now (minterpolate in runFfmpeg), which can afford actual motion
        // estimation in a way a per-frame render-thread blend never could.
        int idx = frameCount++;
        float slotSec = 1f / currentFps;
        frameMetas.add(new FrameMeta(idx, slotsConsumed * slotSec));

        if (virtualFrameCount >= currentFps * 60 && virtualFrameCount - slotsConsumed < currentFps * 60
                && mc.player != null)
            mc.player.sendMessage(Text.translatable("snapmatica.rec.one_minute"), true);

        // The framebuffer is already DoF-blurred (applyPreviewBlur ran above). Screenshot it.
        // Nothing but the read-back itself happens here — scaling and encoding are handed
        // straight to the I/O pool so the render thread is freed as early as possible.
        int fIdx = idx;
        //? if >=1.21.10 {
        ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> submitFrame(raw, fIdx));
        //?} else {
        /*// takeScreenshot does the glReadPixels on the render thread — unavoidable.
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            System.err.println("[VideoRecorder] Screenshot failed frame " + fIdx);
            return;
        }
        submitFrame(raw, fIdx);
        *///?}
    }

    /**
     * Takes ownership of a freshly read-back frame and crops/downsamples/writes it entirely
     * on the parallel {@link #ioExecutor} pool. Frames no longer depend on their neighbours
     * (see the class doc), so unlike the old blend-based pipeline there is nothing here that
     * needs to happen in capture order — each frame's whole pipeline is one independent task,
     * tracked in {@link #pendingWrites} only so post-processing can wait for all of them.
     * {@code raw} is closed here exactly once, on every path.
     */
    private static void submitFrame(NativeImage raw, int idx) {
        // Read here rather than snapshotted earlier — setWidth() is blocked while recording
        // is true (same guard as setFps()), so this can't change mid-take regardless.
        int width = currentWidth;
        CompletableFuture<Void> task = CompletableFuture.supplyAsync(() -> {
                    try {
                        return cropAndDownsample(raw, width);
                    } finally {
                        raw.close();
                    }
                }, ioExecutor)
                .thenAccept(frame -> { writeOne(frame, idx); frame.close(); })
                .exceptionally(ex -> {
                    System.err.println("[VideoRecorder] Frame pipeline failed: idx " + idx + " — " + ex);
                    return null;
                });
        pendingWrites.add(task);
    }

    private static void writeOne(NativeImage img, int idx) {
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        try {
            img.writeTo(outFile);
        } catch (IOException e) {
            System.err.println("[VideoRecorder] Frame write failed: " + outFile);
        } finally {
            writtenFrames.incrementAndGet();
        }
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
                SnapmaticaClient.focalLengthMm, SnapmaticaClient.dofScaleMm, false);
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
        net.minecraft.util.math.Vec3d eye = SnapmaticaClient.cameraPos(mc);
        net.minecraft.util.math.Vec3d look = SnapmaticaClient.cameraLook(mc);
        net.minecraft.util.math.Vec3d end = eye.add(look.multiply(maxDist));
        net.minecraft.util.hit.BlockHitResult blockHit =
                AutoFocus.raycastThroughGlass(mc, eye, look, maxDist);
        double bestDist = (blockHit != null
                && blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS)
                ? eye.distanceTo(blockHit.getPos()) : maxDist;
        final double entityDist = Math.min(bestDist, 60.0);
        net.minecraft.util.math.Vec3d entityEnd = eye.add(look.multiply(entityDist));
        // Rooted at the camera eye rather than the player's bounding box — see PhotoCapture's
        // matching search box for why.
        net.minecraft.util.math.Box searchBox =
                new net.minecraft.util.math.Box(eye, eye).stretch(look.multiply(entityDist)).expand(1.0);
        net.minecraft.util.hit.EntityHitResult entityHit =
                net.minecraft.entity.projectile.ProjectileUtil.raycast(mc.player, eye, entityEnd,
                        searchBox, e -> !e.isSpectator() && e.isAlive(), entityDist * entityDist);
        if (entityHit != null) {
            double eDist = eye.distanceTo(entityHit.getPos());
            if (eDist < bestDist) bestDist = eDist;
        }
        // See PhotoCapture's matching check — ProjectileUtil.raycast always excludes mc.player,
        // so the player is checked separately once freecam has moved the camera away from them.
        if (Freecam.isActive()) {
            java.util.Optional<net.minecraft.util.math.Vec3d> playerHit = mc.player
                    .getBoundingBox().expand(mc.player.getTargetingMargin()).raycast(eye, entityEnd);
            if (playerHit.isPresent()) {
                double pDist = eye.distanceTo(playerHit.get());
                if (pDist < bestDist) bestDist = pDist;
            }
        }
        return (float) Math.min(bestDist, 999.0);
    }

    // ── Post-processing (encode only) ─────────────────────────────────────────────

    private static void doPostProcess(List<FrameMeta> metas, File rawDirIn, File vidDir, int fps,
                                      boolean needsInterpolation) {
        int total = metas.size();
        if (total == 0) {
            postProcessing = false;
            ppMessage      = Text.translatable("snapmatica.pp.no_frames");
            doneAtMs       = System.currentTimeMillis();
            return;
        }

        // Wait for every frame's crop-then-write to finish, updating the progress bar
        // (0–10%) while we wait. Each frame's task is independent now (see the class doc),
        // so this waits on all of them together rather than a single ordered chain.
        ppMessage = Text.translatable("snapmatica.pp.writing");
        CompletableFuture<Void> sentinel;
        synchronized (pendingWrites) {
            sentinel = CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0]));
        }
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
        double totalDurationSec = 0;
        for (FrameMeta meta : metas) totalDurationSec += meta.durationSec();
        boolean ffmpegOk = runFfmpeg(concatFile, outMp4, fps, needsInterpolation, totalDurationSec);

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

    /**
     * Real motion-compensated retiming to the target constant frame rate — this both
     * replaces the plain {@code -r fps} resample (which just drops/duplicates frames to hit
     * the rate) and does the actual gap-smoothing the render-thread pixel-blend used to
     * attempt: {@code mi_mode=mci} estimates where content actually moved to between two
     * frames — including the stretched-duration ones a slow capture leaves behind — and
     * synthesizes real in-between frames along that motion, not a dissolve between two
     * fixed positions.
     *
     * <p>{@code mc_mode=obmc} rather than {@code aobmc}, and no {@code vsbmc} — the adaptive
     * and variable-block-size variants measurably improve quality but are the most expensive
     * part of an already expensive filter, and only ever run at all on a take that actually
     * had a gap to fix (see {@link #runFfmpeg}), so the plain block-based estimation here is
     * the one already paying for real motion compensation on every OTHER frame in the take
     * too, not just the gap — a 16 second recording with one dropped frame going to several
     * minutes to encode was that cost landing on content that never needed smoothing.
     */
    private static String interpolateFilter(int fps) {
        return "minterpolate=fps=" + fps + ":mi_mode=mci:mc_mode=obmc:me_mode=bidir";
    }

    /** Cores to hand ffmpeg: half the machine, at least 2, never more than 8. */
    private static int encodeThreads() {
        return Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static boolean runFfmpeg(File concatFile, String outPath, int fps,
                                     boolean needsInterpolation, double totalDurationSec) {
        String[] candidates = {"ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"};
        for (String ff : candidates) {
            // minterpolate motion-estimates the WHOLE timeline to retime it, not just the
            // gap — worth it on a take that actually dropped a frame somewhere, pure
            // overhead (the difference between an encode finishing in seconds and one taking
            // minutes) on the common case of a take that never fell behind and has nothing
            // for it to smooth over.
            if (needsInterpolation
                    && attemptEncode(ff, concatFile, outPath, fps, true, totalDurationSec)) return true;
            // Not every build/GPU/footage combo is guaranteed to sail through minterpolate
            // without a hiccup either — a working video at plain constant-fps quality beats
            // no video at all, so this is also the fallback if the attempt above failed.
            if (attemptEncode(ff, concatFile, outPath, fps, false, totalDurationSec)) return true;
        }
        return false;
    }

    private static boolean attemptEncode(String ff, File concatFile, String outPath, int fps,
                                         boolean useMinterpolate, double totalDurationSec) {
        try {
            // concat demuxer: each frame carries its own duration so the video
            // plays at correct wall-clock speed even when frames were dropped.
            List<String> cmd = new ArrayList<>(List.of(
                    ff, "-y",
                    "-f", "concat", "-safe", "0",
                    "-i", concatFile.getAbsolutePath()));

            List<String> filters = new ArrayList<>();
            if (useMinterpolate) {
                filters.add(interpolateFilter(fps));
            }
            String blur = motionBlurFilter();
            if (blur != null) filters.add(blur);
            if (!filters.isEmpty()) { cmd.add("-vf"); cmd.add(String.join(",", filters)); }
            if (!useMinterpolate) {
                // minterpolate already sets the output rate via its own fps= option; without
                // it, fall back to the plain resample rather than ffmpeg's undocumented
                // default (25 fps regardless of what the concat file's durations actually are).
                cmd.add("-r"); cmd.add(Integer.toString(fps));
            }
            // Leave the machine usable while encoding. Unconstrained, libx264 defaults
            // to preset=medium across every core and — being a separate process — is
            // beyond the reach of the I/O pool's thread priorities, so it starved the
            // game right as recording stopped. Half the cores at veryfast encodes far
            // faster than realtime for 1280x720 anyway; at the same CRF the only cost
            // is a somewhat larger file.
            cmd.addAll(List.of(
                    "-threads", Integer.toString(encodeThreads()),
                    "-c:v", "libx264", "-preset", "veryfast",
                    "-crf", "18", "-pix_fmt", "yuv420p"));
            // Real machine-readable progress on stdout instead of guessing elapsed-time
            // against an assumed duration — the old estimate capped out at 98% on its own
            // fixed schedule regardless of how far the encode had actually gotten, so
            // anything slower than what that schedule assumed (exactly what minterpolate
            // usually is) sat visibly stuck under 100% for however much longer it needed.
            cmd.addAll(List.of("-nostats", "-progress", "pipe:1"));
            cmd.add(outPath);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();

            long totalUs = Math.round(totalDurationSec * 1_000_000.0);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    // out_time_ms is, despite the name, microseconds — a long-standing ffmpeg
                    // quirk. out_time_us is the same value under its correct name on newer
                    // builds; either key gives an exact position in the encode, not a guess.
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq), val = line.substring(eq + 1);
                    if ((key.equals("out_time_us") || key.equals("out_time_ms")) && totalUs > 0) {
                        try {
                            long us = Long.parseLong(val.trim());
                            int pct = (int) Math.max(0, Math.min(88, us * 88L / totalUs));
                            // Monotonic — a late progress line arriving after a later one
                            // (buffering jitter) must not make the bar visibly step backward.
                            ppProgress = Math.max(ppProgress, 10 + pct);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            int exit = proc.waitFor();
            if (exit == 0) return true;
            System.err.println("[VideoRecorder] ffmpeg (" + ff + ", minterpolate=" + useMinterpolate
                    + ") exited " + exit);
            return false;
        } catch (IOException | InterruptedException ignored) {
            return false;
        }
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

    //? if >=1.21.2 {
    private static int getPixel(NativeImage img, int x, int y) {
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
    //?} else {
    /*private static int getPixel(NativeImage img, int x, int y) { return img.getColor(x, y); }
    private static void setPixel(NativeImage img, int x, int y, int abgr) { img.setColor(x, y, abgr); }
    *///?}

    // ── Misc helpers ──────────────────────────────────────────────────────────────

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
