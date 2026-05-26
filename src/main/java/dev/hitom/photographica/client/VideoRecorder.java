package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.VideoSettings;
import dev.hitom.photographica.item.VideoCameraItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side video recording engine for the Camcorder item.
 *
 * Pipeline per rendered frame (render thread):
 *   onWorldRenderEnd()          – WorldRenderEvents.LAST: one glReadPixels for the full
 *                                 viewport depth buffer, downsampled to 32×18 grid.
 *   captureFrameIfRecording()   – after GameRenderer.renderWorld(): screenshot + dwell-AF.
 *
 * Post-processing (background thread, per raw frame):
 *   Pass 1  – histogram auto-exposure (ISO AUTO simulation) + vignette
 *   Pass 2  – DoF bokeh blur: separable variable-radius box blur driven by per-pixel CoC
 *             (circle of confusion) computed from 50mm/f-number optics model
 *   Pass 3  – directional motion blur: physically-correct blur length = velocity/depth × focal_px
 *
 * Autofocus model:
 *   The camera accumulates how many consecutive frames the centre-pixel depth has been
 *   stable (within 25%).  After 24 frames (1 second), focus begins servo-tracking toward
 *   that depth.  Panning to a new subject resets the dwell counter.
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ─────────────────────────────────────────────────────────────
    /** Default FPS; actual recording FPS is read from VideoSettings at start time. */
    public static final int  FPS        = 24;
    /** Maximum frames at the highest supported FPS (30) for 2 minutes. */
    public static final int  MAX_FRAMES = 30 * 120;        // 2 minutes @ 30 fps

    // Runtime FPS (set when recording starts from VideoSettings).
    private static int currentFps = FPS;

    /** Depth-grid dimensions (32×18 — same 16:9 ratio as output, 1/40 scale). */
    private static final int   DEP_W = 32;
    private static final int   DEP_H = 18;
    private static final float NEAR  = 0.05f;
    private static final float FAR   = 512.0f;

    /**
     * K constant for CoC formula.
     * CoC_px = |depth - focus| × aperture × CoC_K / (depth × focus)
     *
     * Tuned for visible bokeh at Minecraft gaming distances (1–20 m).
     * Physical derivation gives ~28 for a 28 mm camcorder lens, but that
     * produces CoC < 3 px at typical distances — invisible after blur scaling.
     * K=100 gives:
     *   f/2.8, focus=5 m, d=1 m  → CoC radius ≈ 14 px  (strong foreground blur)
     *   f/2.8, focus=5 m, d=2 m  → CoC radius ≈ 11 px  (moderate)
     *   f/2.8, focus=5 m, d=10 m → CoC radius ≈  3.6 px (subtle background)
     *   f/8,   focus=5 m, d=1 m  → CoC radius ≈  5 px  (stopped-down, less blur)
     *   f/22,  focus=5 m, d=1 m  → CoC radius ≈  1.8 px (essentially sharp) ✓
     *   d=200 m, focus=300 m     → CoC radius ≈  0.5 px (essentially sharp) ✓
     */
    private static final float CoC_K    = 100.0f;
    /**
     * Focal pixels for motion blur.
     * pixel_displacement = velocity_blocks_per_frame × FOCAL_PX / depth_blocks
     *
     * At walking speed (~0.17 b/frame) and 3 m depth:
     *   blurLen = 0.17 × 600 / 3 ≈ 34 px — clearly visible.
     * Sprinting (~0.28 b/frame) at 3 m → ~56 px (capped by maxBlurPx).
     */
    private static final float FOCAL_PX = 600f;

    /** Dwell time (frames) before AF servo starts tracking a new depth. */
    private static final int   FOCUS_DWELL_FRAMES = 20;    // ~0.83 s
    /** Fractional depth change that counts as "same object." */
    private static final float FOCUS_TOL          = 0.25f;

    // ── Recording state ────────────────────────────────────────────────────────
    private static volatile boolean recording      = false;
    private static volatile boolean postProcessing = false;
    private static volatile int     ppProgress     = 0;
    private static volatile String  ppMessage      = "";
    public  static volatile long    doneAtMs       = 0L;

    private static String          sessionId;
    private static int             frameCount;
    private static long            recordStartMs;
    private static long            nextFrameMs;
    private static File            rawDir;
    private static List<FrameMeta> frameMetas;
    private static ItemStack       recordingStack;

    // ── Autofocus state ────────────────────────────────────────────────────────
    /** Depth the centre pixel has been showing for focusCandidateFrames frames. */
    private static float focusCandidateDepth  = 5.0f;
    /** How many consecutive frames the candidate depth has been stable. */
    private static int   focusCandidateFrames = 0;
    /** The actual locked / servo-tracking focus distance written into FrameMeta. */
    private static float currentFocusDepth    = 5.0f;

    // ── Angular velocity tracking (for motion blur) ────────────────────────────
    /** Yaw and pitch at the previous captured frame (degrees). */
    private static float prevFrameYaw   = 0f;
    private static float prevFramePitch = 0f;

    // ── Depth-buffer read ──────────────────────────────────────────────────────
    /** Pending depth grid from onWorldRenderEnd(), consumed by captureFrameIfRecording(). */
    private static float[]     pendingDepthGrid  = null;
    private static boolean     pendingDepthReady = false;
    /** Reusable GL buffer (grows, never shrinks within a recording session). */
    private static FloatBuffer depthReadBuf      = null;
    private static int         depthReadBufCap   = 0;
    /** Viewport and crop dimensions stored by onWorldRenderEnd() for the next frame. */
    private static int pendingVpW = 0, pendingVpH = 0;
    private static int pendingCropOffX = 0, pendingCropOffY = 0;

    // ── DoF temp arrays (reused across frames in the background thread) ────────
    private static int[] dofTempR, dofTempG, dofTempB, dofTempA;
    private static int   dofTempCap = 0;

    /**
     * Smoothed ISO AUTO exposure multiplier shared across post-processing frames.
     * Updated frame-by-frame with an asymmetric EMA:
     *   darkening  (mult rising)  → slow rate  (~3 s to fully adjust)
     *   brightening(mult falling) → fast rate  (~0.5 s)
     * This prevents a single dark frame from spiking the exposure.
     */
    private static float smoothedExpMult = 1.0f;

    private static final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "photographica-video-io");
                t.setDaemon(true);
                return t;
            });

    // ── Public accessors ───────────────────────────────────────────────────────
    public static boolean isRecording()      { return recording; }
    public static boolean isPostProcessing() { return postProcessing; }
    public static int     getPpProgress()    { return ppProgress; }
    public static String  getPpMessage()     { return ppMessage; }
    public static long    getDoneAtMs()      { return doneAtMs; }
    public static int     getFrameCount()    { return frameCount; }
    public static long    getRecordStartMs() { return recordStartMs; }
    public static int     getCurrentFps()    { return currentFps; }

    /**
     * Zoom FOV in degrees for the video camera viewfinder.
     * 70 = native (no zoom).  Reduced by Alt+scroll in CameraScrollHandler.
     * Applied in GameRendererMixin.photographica$applyFocalLength.
     */
    public static float videoFov = 70.0f;

    /**
     * Returns true if the current render frame will be captured as a video frame.
     * Called from GameRendererMixin BEFORE renderWorld() to decide whether to
     * suppress the player's hand model.
     */
    public static boolean willCaptureThisFrame() {
        return recording
                && System.currentTimeMillis() >= nextFrameMs
                && frameCount < MAX_FRAMES;
    }

    // ── FrameMeta ──────────────────────────────────────────────────────────────
    /**
     * All per-frame data captured on the render thread.
     *
     * @param depthGrid  32×18 linear-depth map (metres). Row 0 = bottom (GL convention).
     * @param focusDepth Locked AF distance at capture time (metres).
     * @param vpW/vpH    Full viewport size at capture time (GL pixels).
     * @param cropOffX/Y Pixel offset of the 16:9 crop within the full viewport (GL pixels).
     *                   Used in post-processing to map output image pixels to the depth grid.
     */
    record FrameMeta(int   idx,
                     float velX,      float velY,       float velZ,
                     float yaw,       float pitch,
                     float deltaYaw,  float deltaPitch,  // camera angular velocity (degrees/frame)
                     float fovDeg,                       // horizontal video FOV at capture time
                     float aperture,
                     float focusDepth,
                     float[] depthGrid,
                     int vpW, int vpH,
                     int cropOffX, int cropOffY) {}

    // ── Start / Stop ───────────────────────────────────────────────────────────
    public static void toggle(ItemStack stack) {
        if (recording) stopRecording();
        else if (!postProcessing) startRecording(stack);
    }

    public static void startRecording(ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        sessionId = ts;
        if (new File(mc.runDirectory, "photographica/video_temp/" + sessionId).exists()) {
            sessionId = ts + "_" + (System.currentTimeMillis() % 1000);
        }

        currentFps      = VideoCameraItem.getSettings(stack).fps();
        smoothedExpMult = 1.0f;   // reset ISO AUTO smoothing for new session
        prevFrameYaw    = mc.player != null ? mc.player.getYaw()   : 0f;
        prevFramePitch  = mc.player != null ? mc.player.getPitch() : 0f;
        frameCount    = 0;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);
        recordingStack = stack;

        // Reset AF
        currentFocusDepth    = 5.0f;
        focusCandidateDepth  = 5.0f;
        focusCandidateFrames = 0;

        rawDir = new File(mc.runDirectory,
                "photographica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            Photographica.LOGGER.error("[VideoRecorder] Could not create raw dir: {}", rawDir);
            return;
        }

        recording = true;
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("● REC 開始"), true);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("■ 録画停止 — 後処理中..."), true);

        final List<FrameMeta> metas    = new ArrayList<>(frameMetas);
        final File            rawSnap  = rawDir;
        final VideoSettings   vs       = VideoCameraItem.getSettings(recordingStack);
        final File            vidDir   = new File(mc.runDirectory, "photographica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = "後処理中...";

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vs, vidDir),
                "photographica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    // ── Render-thread hooks ────────────────────────────────────────────────────

    /**
     * Called from WorldRenderEvents.LAST.
     * Reads the entire viewport depth buffer in one glReadPixels call, then
     * downsamples on CPU to a 32×18 linear-depth grid.
     */
    public static void onWorldRenderEnd() {
        if (!recording) return;
        if (System.currentTimeMillis() < nextFrameMs) return;

        GL11.glGetError();
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        int vpW = vp[2], vpH = vp[3];
        if (vpW <= 0 || vpH <= 0) { pendingDepthReady = false; return; }

        int needed = vpW * vpH;
        if (depthReadBuf == null || depthReadBufCap < needed) {
            depthReadBuf    = BufferUtils.createFloatBuffer(needed);
            depthReadBufCap = needed;
        }
        depthReadBuf.clear();
        GL11.glReadPixels(0, 0, vpW, vpH,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthReadBuf);

        pendingDepthGrid  = downsampleDepth(depthReadBuf, vpW, vpH, DEP_W, DEP_H);
        pendingDepthReady = true;

        // Compute the 16:9 crop that cropTo16x9() will apply to the screenshot,
        // so post-processing can correctly map image pixels → depth grid.
        float aspect = 16f / 9f;
        int cropW, cropH;
        if ((float) vpW / vpH > aspect) { cropH = vpH; cropW = Math.round(vpH * aspect); }
        else                             { cropW = vpW; cropH = Math.round(vpW / aspect); }
        pendingVpW     = vpW;
        pendingVpH     = vpH;
        pendingCropOffX = (vpW - cropW) / 2;
        pendingCropOffY = (vpH - cropH) / 2;
    }

    /**
     * Called from GameRendererMixin after renderWorld().
     * Takes a screenshot, advances the dwell-time autofocus, and queues the frame for I/O.
     */
    public static void captureFrameIfRecording() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (frameCount >= MAX_FRAMES) { stopRecording(); return; }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Consume depth grid
        float[] depthGrid;
        if (pendingDepthReady && pendingDepthGrid != null) {
            depthGrid = pendingDepthGrid;
            pendingDepthGrid  = null;
            pendingDepthReady = false;
        } else {
            depthGrid = flatDepthGrid(currentFocusDepth);
        }

        // ── Dwell-time autofocus ──────────────────────────────────────────────
        // Centre cell of the depth grid = what the camera is looking at
        float centreDepth = Math.max(
                depthGrid[(DEP_H / 2) * DEP_W + DEP_W / 2], 0.3f);

        if (Math.abs(centreDepth - focusCandidateDepth)
                / Math.max(focusCandidateDepth, 0.1f) <= FOCUS_TOL) {
            focusCandidateFrames++;
            if (focusCandidateFrames >= FOCUS_DWELL_FRAMES) {
                // Servo toward the stable target — faster rate so it reaches
                // the new focus within ~0.3 s after the dwell period ends.
                currentFocusDepth =
                        currentFocusDepth * 0.65f + focusCandidateDepth * 0.35f;
            }
        } else {
            // Subject changed — reset dwell
            focusCandidateDepth  = centreDepth;
            focusCandidateFrames = 0;
        }
        // ─────────────────────────────────────────────────────────────────────

        Vec3d vel   = mc.player.getVelocity();
        float yaw   = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        float ap    = VideoCameraItem.getSettings(recordingStack).aperture();

        // Angular velocity: yaw/pitch change since the last captured frame.
        float rawDeltaYaw = yaw - prevFrameYaw;
        // Wrap to [-180, +180] to handle 0°/360° crossing.
        if (rawDeltaYaw >  180f) rawDeltaYaw -= 360f;
        if (rawDeltaYaw < -180f) rawDeltaYaw += 360f;
        float deltaYaw   = rawDeltaYaw;
        float deltaPitch = pitch - prevFramePitch;
        prevFrameYaw   = yaw;
        prevFramePitch = pitch;

        FrameMeta meta = new FrameMeta(
                frameCount,
                (float) vel.x, (float) vel.y, (float) vel.z,
                yaw, pitch,
                deltaYaw, deltaPitch, videoFov,
                ap,
                currentFocusDepth,
                depthGrid,
                pendingVpW, pendingVpH,
                pendingCropOffX, pendingCropOffY);

        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            Photographica.LOGGER.warn("[VideoRecorder] Screenshot failed frame {}", frameCount, e);
            nextFrameMs = recordStartMs + (long)((frameCount + 1) * 1000.0 / currentFps);
            return;
        }

        NativeImage cropped = cropTo16x9(raw);
        NativeImage frame   = boxDownsample(cropped, 1280);
        if (cropped != raw) cropped.close();
        raw.close();

        int  idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));

        frameMetas.add(meta);
        frameCount++;
        // Precise timing: avoids the 1.6% drift from integer 1000/fps.
        // Frame n should start at recordStartMs + n * 1000.0 / fps.
        nextFrameMs = recordStartMs + (long)(frameCount * 1000.0 / currentFps);

        if (frameCount == currentFps * 60 && mc.player != null)
            mc.player.sendMessage(Text.literal("⚠ 残り 1:00"), true);

        ioExecutor.submit(() -> {
            try { frame.writeTo(outFile); }
            catch (IOException e) {
                Photographica.LOGGER.warn("[VideoRecorder] Frame write failed: {}", outFile, e);
            } finally { frame.close(); }
        });
    }

    // ── Post-processing ────────────────────────────────────────────────────────

    private static void doPostProcess(List<FrameMeta> metas, File rawDirIn,
                                      VideoSettings vs, File vidDir) {
        int total = metas.size();
        if (total == 0) {
            postProcessing = false;
            ppMessage = "フレームなし";
            doneAtMs  = System.currentTimeMillis();
            return;
        }

        File processedDir = new File(rawDirIn.getParentFile(), "processed");
        if (!processedDir.mkdirs()) {
            Photographica.LOGGER.error("[VideoRecorder] Could not create processed dir");
            postProcessing = false;
            return;
        }

        ppMessage = "エフェクト適用中...";

        for (int i = 0; i < total; i++) {
            FrameMeta meta   = metas.get(i);
            File inFile  = new File(rawDirIn,     String.format("frame_%04d.png", meta.idx()));
            File outFile = new File(processedDir, String.format("frame_%04d.png", meta.idx()));

            if (!inFile.exists()) { ppProgress = (i + 1) * 80 / total; continue; }

            try (NativeImage img =
                         NativeImage.read(inFile.toPath().toUri().toURL().openStream())) {
                NativeImage processed = applyVideoEffects(img, meta);
                processed.writeTo(outFile);
                processed.close();
            } catch (IOException e) {
                Photographica.LOGGER.warn("[VideoRecorder] Post-process failed frame {}",
                        meta.idx(), e);
            }

            ppProgress = (i + 1) * 80 / total;
            ppMessage  = "エフェクト適用中... " + ppProgress + "%";
        }

        ppMessage  = "MP4 エンコード中...";
        ppProgress = 80;

        if (!vidDir.exists()) vidDir.mkdirs();
        String outMp4 = new File(vidDir, sessionId + ".mp4").getAbsolutePath();
        boolean ffmpegOk = runFfmpeg(processedDir, outMp4);

        ppProgress = 100;
        if (ffmpegOk) {
            ppMessage = "✓ 保存: photographica/videos/" + sessionId + ".mp4";
            Photographica.LOGGER.info("[VideoRecorder] Video saved: {}", outMp4);
        } else {
            File pngDir = new File(vidDir, sessionId);
            processedDir.renameTo(pngDir);
            ppMessage = "ffmpeg なし — PNG 保存: photographica/videos/" + sessionId + "/";
            Photographica.LOGGER.warn("[VideoRecorder] ffmpeg not found; PNGs at {}", pngDir);
        }

        deleteDir(rawDirIn);
        if (ffmpegOk) deleteDir(processedDir);

        postProcessing = false;
        doneAtMs       = System.currentTimeMillis();
    }

    // ── Video effects ──────────────────────────────────────────────────────────

    /**
     * Three-pass effect pipeline applied to each raw frame.
     *
     * Pass 1 – Auto-exposure (histogram, ISO AUTO simulation) + vignette
     * Pass 2 – DoF bokeh: per-pixel CoC from 50 mm optics model, separable blur
     * Pass 3 – Directional motion blur: blur_px = velocity × FOCAL_PX / depth
     */
    private static NativeImage applyVideoEffects(NativeImage src, FrameMeta meta) {
        int   w      = src.getWidth();
        int   h      = src.getHeight();
        float halfW  = w * 0.5f;
        float halfH  = h * 0.5f;
        float ap     = meta.aperture();
        float focus  = Math.max(meta.focusDepth(), 0.5f);
        float[] grid = meta.depthGrid();

        // ── Pass 1: auto-exposure + vignette ─────────────────────────────────
        float rawExpMult = computeAutoExposure(src);
        // Asymmetric EMA — slow to boost ISO (darken scene), fast to cut it.
        // Rising mult = getting brighter (scene went dark) → slow: ~3 s / 72 frames at 24fps
        // Falling mult = getting dimmer  (scene lit up)   → fast: ~0.5 s / 12 frames
        float alpha = rawExpMult > smoothedExpMult ? 0.04f : 0.15f;
        smoothedExpMult = smoothedExpMult * (1f - alpha) + rawExpMult * alpha;
        float expMult = smoothedExpMult;
        float vig     = apertureToVignette(ap);

        NativeImage pass1 = new NativeImage(w, h, false);
        for (int py = 0; py < h; py++) {
            float dy = (py - halfH) / halfH;
            float dy2 = dy * dy;
            for (int px = 0; px < w; px++) {
                int c = src.getColor(px, py);
                int a = (c >>> 24) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int g = (c >>>  8) & 0xFF;
                int r =  c         & 0xFF;

                r = applyExposure(r, expMult);
                g = applyExposure(g, expMult);
                b = applyExposure(b, expMult);

                float dx = (px - halfW) / halfW;
                float vf = Math.max(0f, 1f - vig * (dx * dx + dy2) * 0.5f);
                r = clamp((int)(r * vf));
                g = clamp((int)(g * vf));
                b = clamp((int)(b * vf));

                pass1.setColor(px, py, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        // ── Pass 2: depth-of-field bokeh ─────────────────────────────────────
        // Viewport / crop info needed to map image pixels → depth grid precisely.
        // cropTo16x9 removes (cropOffX, cropOffY) pixels from the GL-space edges;
        // the remaining cropW×cropH region is then downsampled to w×h.
        int vw = meta.vpW(), vh = meta.vpH();
        int cOffX = meta.cropOffX(), cOffY = meta.cropOffY();
        // Recompute cropW/cropH from VP and offsets (stored implicitly)
        float aspect16_9 = 16f / 9f;
        int cropW, cropH;
        if (vw > 0 && vh > 0) {
            if ((float) vw / vh > aspect16_9) { cropH = vh; cropW = Math.round(vh * aspect16_9); }
            else                               { cropW = vw; cropH = Math.round(vw / aspect16_9); }
        } else {
            cropW = w; cropH = h; // fallback: treat as identity
        }

        // Build per-pixel CoC radius map
        float maxCoC = 40.0f;      // hard cap: 40 px radius (80 px diameter)
        float[] cocMap = new float[w * h];
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float d;
                if (grid != null && vw > 0) {
                    // Map output-image pixel (px,py) → GL framebuffer coordinates
                    // Then normalize to [0,1] for the depth grid
                    float glX = px * (float) cropW / w + cOffX;
                    // Image row 0 = top; GL row 0 = bottom → flip
                    float glY = (vh - 1) - (py * (float) cropH / h + cOffY);
                    float fx  = (glX + 0.5f) / vw;
                    float fy  = (glY + 0.5f) / vh;
                    d = bilinearDepth(grid, DEP_W, DEP_H,
                            Math.max(0f, Math.min(1f, fx)),
                            Math.max(0f, Math.min(1f, fy)));
                } else {
                    d = focus;
                }
                d = Math.max(d, 0.2f);
                // CORRECT formula: CoC ∝ 1/aperture (larger f-number = smaller CoC = sharper)
                // CoC_diameter = |d - D| × CoC_K / (ap × d × D)
                float coc = Math.abs(d - focus) * CoC_K / (ap * d * focus);
                cocMap[py * w + px] = Math.min(coc * 0.5f, maxCoC * 0.5f);
            }
        }
        // 3-pass separable box blur approximates a Gaussian bokeh profile.
        // Each pass uses radius scaled by 1/√3 ≈ 0.58 so that three passes
        // together give the same total spread as one pass at the full radius.
        float[] scaledCoc = new float[w * h];
        for (int i = 0; i < cocMap.length; i++) scaledCoc[i] = cocMap[i] * 0.58f;

        NativeImage dof1 = separableVariableBlur(pass1,  scaledCoc, w, h);
        NativeImage dof2 = separableVariableBlur(dof1,   scaledCoc, w, h);  dof1.close();
        NativeImage pass2 = separableVariableBlur(dof2,  scaledCoc, w, h);  dof2.close();
        pass1.close();

        // ── Pass 3: motion blur (angular + translational) ────────────────────
        //
        // Angular velocity (camera rotation) is the dominant blur source during
        // gameplay.  A forward-walking player has zero LATERAL screen velocity
        // from translation alone, but any pan/tilt is fully captured here.
        //
        // Physics:
        //   When the camera rotates +deltaYaw°, every on-screen object appears
        //   to have moved -deltaYaw * w/fovH pixels (leftward) during the frame.
        //   At the END of the frame the object is at (px, py).
        //   At the START of the frame it was at (px + rotSampleX, py + rotSampleY).
        //   → We sample from (px) toward (px + rotSampleX), i.e. ndx = +rotSampleX.
        //
        //   For translational strafing (camera-right velocity):
        //   Strafe right → scene moves left → object was to the RIGHT at frame start.
        //   → transX is POSITIVE when strafing right (sample rightward).
        //
        float fovH     = meta.fovDeg();
        float fovV     = fovH * 9f / 16f;   // 16:9 output aspect

        // Rotational component (pixels, uniform across all depths)
        // Minecraft pitch: +90 = looking straight down (objects move UP = negative py)
        float rotSampleX =  meta.deltaYaw()   * w / fovH;
        float rotSampleY = -meta.deltaPitch() * h / fovV;  // sign: look down → objects up

        // Translational strafing (depth-dependent; pre-compute scale at 1 m)
        float yawRad    = (float) Math.toRadians(meta.yaw());
        float strafeVel = ((float)(Math.cos(yawRad) * meta.velX()
                                 + Math.sin(yawRad) * meta.velZ()))
                        * (20.0f / currentFps);           // blocks per frame
        float transScale = strafeVel * FOCAL_PX;          // pixels at 1 m depth

        // Early-exit: check total blur at focus distance
        float totalAtFocus = (float) Math.sqrt(
                (rotSampleX + transScale / focus) * (rotSampleX + transScale / focus)
              + rotSampleY * rotSampleY);
        if (totalAtFocus < 0.5f) return pass2;

        float maxBlurPx = w / 10.0f;   // hard cap ~128 px at 1280 wide

        NativeImage pass3 = new NativeImage(w, h, false);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                // Per-pixel depth for translational component
                float d;
                if (grid != null && vw > 0) {
                    float glX = px * (float) cropW / w + cOffX;
                    float glY = (vh - 1) - (py * (float) cropH / h + cOffY);
                    float fx  = Math.max(0f, Math.min(1f, (glX + 0.5f) / vw));
                    float fy  = Math.max(0f, Math.min(1f, (glY + 0.5f) / vh));
                    d = bilinearDepth(grid, DEP_W, DEP_H, fx, fy);
                } else {
                    d = focus;
                }
                d = Math.max(d, 0.2f);

                // Total pixel displacement to sample along
                float sampleX = rotSampleX + transScale / d;
                float sampleY = rotSampleY;
                float blurMag = (float) Math.sqrt(sampleX * sampleX + sampleY * sampleY);
                int blurLen = (int) Math.min(blurMag, maxBlurPx);

                if (blurLen < 1) {
                    pass3.setColor(px, py, pass2.getColor(px, py));
                    continue;
                }

                float ndx = sampleX / blurMag;
                float ndy = sampleY / blurMag;

                // Linear-falloff: s=0 = sharp leading edge, s=blurLen = faint ghost
                float ra = 0, ga = 0, ba = 0, aa = 0, sumW = 0;
                for (int s = 0; s <= blurLen; s++) {
                    float wt = (blurLen - s + 1);
                    int sx = Math.max(0, Math.min(w - 1, px + (int)(s * ndx)));
                    int sy = Math.max(0, Math.min(h - 1, py + (int)(s * ndy)));
                    int c  = pass2.getColor(sx, sy);
                    aa += ((c >>> 24) & 0xFF) * wt;
                    ba += ((c >>> 16) & 0xFF) * wt;
                    ga += ((c >>>  8) & 0xFF) * wt;
                    ra += ( c         & 0xFF) * wt;
                    sumW += wt;
                }
                pass3.setColor(px, py,
                        (clamp((int)(aa / sumW)) << 24) | (clamp((int)(ba / sumW)) << 16)
                      | (clamp((int)(ga / sumW)) <<  8) |  clamp((int)(ra / sumW)));
            }
        }
        pass2.close();
        return pass3;
    }

    // ── Auto-exposure ──────────────────────────────────────────────────────────

    /**
     * Histogram-based auto-exposure that simulates ISO AUTO metering.
     *
     * Finds the 70th-percentile luminance and scales the image so that
     * value maps to 110 (≈ 43% brightness).  This is equivalent to spot-metering
     * at 70% of sorted-pixels brightness — prevents highlight clipping while
     * keeping shadows visible.  Clamped to ±1 stop (0.5 – 2.0×).
     */
    private static float computeAutoExposure(NativeImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] hist  = new int[256];
        int   count = 0;
        // Sample every 8th pixel — sufficient for a histogram
        for (int py = 0; py < h; py += 8) {
            for (int px = 0; px < w; px += 8) {
                int c = src.getColor(px, py);
                // NativeImage is ABGR; extract r, g, b correctly
                int r =  c         & 0xFF;
                int g = (c >>>  8) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;  // BT.601
                hist[Math.min(luma, 255)]++;
                count++;
            }
        }
        if (count == 0) return 1.0f;

        // 50th-percentile (median) luminance.
        int threshold = (int)(count * 0.50), cumulative = 0, p50 = 128;
        for (int i = 0; i < 256; i++) {
            cumulative += hist[i];
            if (cumulative >= threshold) { p50 = i; break; }
        }
        if (p50 < 6) return 1.0f;  // extremely dark scene — don't over-boost

        // Target 140 (≈55% brightness) — bright and natural, not bleached.
        float mult = 140.0f / p50;
        // Allow up to +2 stop brightening (4.0×) and −0.5 stop darkening (0.7×).
        return Math.max(0.7f, Math.min(4.0f, mult));
    }

    // ── Separable variable-radius box blur (O(W×H) via prefix sums) ───────────

    /**
     * Applies a 2-pass (horizontal then vertical) separable box blur where each
     * pixel has its own blur radius from {@code radiusMap}.
     *
     * Uses prefix-sum tables per row / column so the per-pixel cost is O(1)
     * regardless of radius — total complexity is O(W×H).
     * Temp arrays are allocated once and reused across frames.
     */
    private static NativeImage separableVariableBlur(NativeImage src,
                                                     float[] radiusMap,
                                                     int w, int h) {
        int size = w * h;
        if (dofTempCap < size) {
            dofTempR = new int[size]; dofTempG = new int[size];
            dofTempB = new int[size]; dofTempA = new int[size];
            dofTempCap = size;
        }

        long[] prefR = new long[w + 1], prefG = new long[w + 1],
               prefB = new long[w + 1], prefA = new long[w + 1];

        // ── Horizontal pass ───────────────────────────────────────────────────
        for (int py = 0; py < h; py++) {
            prefR[0] = prefG[0] = prefB[0] = prefA[0] = 0;
            for (int px = 0; px < w; px++) {
                int c = src.getColor(px, py);
                prefA[px + 1] = prefA[px] + ((c >>> 24) & 0xFF);
                prefB[px + 1] = prefB[px] + ((c >>> 16) & 0xFF);
                prefG[px + 1] = prefG[px] + ((c >>>  8) & 0xFF);
                prefR[px + 1] = prefR[px] + ( c         & 0xFF);
            }
            for (int px = 0; px < w; px++) {
                int r = (int) radiusMap[py * w + px];
                int base = py * w + px;
                if (r < 1) {
                    int c = src.getColor(px, py);
                    dofTempA[base] = (c >>> 24) & 0xFF;
                    dofTempB[base] = (c >>> 16) & 0xFF;
                    dofTempG[base] = (c >>>  8) & 0xFF;
                    dofTempR[base] =  c         & 0xFF;
                } else {
                    int x0 = Math.max(0, px - r), x1 = Math.min(w - 1, px + r);
                    int cnt = x1 - x0 + 1;
                    dofTempA[base] = (int)((prefA[x1 + 1] - prefA[x0]) / cnt);
                    dofTempB[base] = (int)((prefB[x1 + 1] - prefB[x0]) / cnt);
                    dofTempG[base] = (int)((prefG[x1 + 1] - prefG[x0]) / cnt);
                    dofTempR[base] = (int)((prefR[x1 + 1] - prefR[x0]) / cnt);
                }
            }
        }

        long[] vprefR = new long[h + 1], vprefG = new long[h + 1],
               vprefB = new long[h + 1], vprefA = new long[h + 1];

        // ── Vertical pass ─────────────────────────────────────────────────────
        NativeImage dst = new NativeImage(w, h, false);
        for (int px = 0; px < w; px++) {
            vprefR[0] = vprefG[0] = vprefB[0] = vprefA[0] = 0;
            for (int py = 0; py < h; py++) {
                int base = py * w + px;
                vprefA[py + 1] = vprefA[py] + dofTempA[base];
                vprefB[py + 1] = vprefB[py] + dofTempB[base];
                vprefG[py + 1] = vprefG[py] + dofTempG[base];
                vprefR[py + 1] = vprefR[py] + dofTempR[base];
            }
            for (int py = 0; py < h; py++) {
                int r    = (int) radiusMap[py * w + px];
                int base = py * w + px;
                if (r < 1) {
                    dst.setColor(px, py,
                            (dofTempA[base] << 24) | (dofTempB[base] << 16)
                          | (dofTempG[base] <<  8) |  dofTempR[base]);
                } else {
                    int y0  = Math.max(0, py - r), y1 = Math.min(h - 1, py + r);
                    int cnt = y1 - y0 + 1;
                    int a   = (int)((vprefA[y1 + 1] - vprefA[y0]) / cnt);
                    int b   = (int)((vprefB[y1 + 1] - vprefB[y0]) / cnt);
                    int g   = (int)((vprefG[y1 + 1] - vprefG[y0]) / cnt);
                    int rv  = (int)((vprefR[y1 + 1] - vprefR[y0]) / cnt);
                    dst.setColor(px, py, (a << 24) | (b << 16) | (g << 8) | rv);
                }
            }
        }
        return dst;
    }

    // ── ffmpeg ─────────────────────────────────────────────────────────────────

    private static boolean runFfmpeg(File processedDir, String outPath) {
        String[] candidates = {"ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"};
        for (String ff : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        ff, "-y",
                        "-framerate", String.valueOf(currentFps),
                        "-i", new File(processedDir, "frame_%04d.png").getAbsolutePath(),
                        "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
                        outPath);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process proc = pb.start();

                long startMs = System.currentTimeMillis();
                while (proc.isAlive()) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    ppProgress = 80 + (int) Math.min(18,
                            (System.currentTimeMillis() - startMs) / 1000);
                }

                int exit = proc.waitFor();
                if (exit == 0) return true;
                Photographica.LOGGER.warn("[VideoRecorder] ffmpeg exited {}", exit);
                return false;
            } catch (IOException | InterruptedException ignored) {
                // try next candidate
            }
        }
        return false;
    }

    // ── Depth utilities ────────────────────────────────────────────────────────

    /**
     * Downsamples the raw GL depth buffer (non-linear NDC [0,1]) to a dW×dH grid
     * of linear depths in metres.  Averages raw depth values within each cell before
     * linearising (more accurate than linearising then averaging).
     */
    private static float[] downsampleDepth(FloatBuffer raw, int vpW, int vpH,
                                           int dW, int dH) {
        float[] grid = new float[dW * dH];
        for (int dy = 0; dy < dH; dy++) {
            int sy0 = dy * vpH / dH;
            int sy1 = Math.min((dy + 1) * vpH / dH, vpH);
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int dx = 0; dx < dW; dx++) {
                int sx0 = dx * vpW / dW;
                int sx1 = Math.min((dx + 1) * vpW / dW, vpW);
                if (sx1 <= sx0) sx1 = sx0 + 1;
                double sum = 0;
                int    cnt = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int rowBase = sy * vpW;
                    for (int sx = sx0; sx < sx1; sx++) {
                        sum += raw.get(rowBase + sx);
                        cnt++;
                    }
                }
                float rawD = (float)(sum / cnt);
                float ndc  = 2.0f * rawD - 1.0f;
                grid[dy * dW + dx] = 2.0f * NEAR * FAR / (FAR + NEAR - ndc * (FAR - NEAR));
            }
        }
        return grid;
    }

    /**
     * Bilinear interpolation over a dW×dH depth grid at normalised (fx, fy) ∈ [0,1].
     * Row 0 = bottom (OpenGL convention).
     */
    private static float bilinearDepth(float[] grid, int dW, int dH,
                                       float fx, float fy) {
        float gx = fx * (dW - 1), gy = fy * (dH - 1);
        int   x0 = (int) gx, y0 = (int) gy;
        int   x1 = Math.min(x0 + 1, dW - 1), y1 = Math.min(y0 + 1, dH - 1);
        float tx = gx - x0, ty = gy - y0;
        float d00 = grid[y0 * dW + x0], d10 = grid[y0 * dW + x1];
        float d01 = grid[y1 * dW + x0], d11 = grid[y1 * dW + x1];
        return (d00 * (1 - tx) + d10 * tx) * (1 - ty)
             + (d01 * (1 - tx) + d11 * tx) *      ty;
    }

    private static float[] flatDepthGrid(float depth) {
        float[] g = new float[DEP_W * DEP_H];
        Arrays.fill(g, depth);
        return g;
    }

    // ── Image utilities ────────────────────────────────────────────────────────

    private static NativeImage cropTo16x9(NativeImage src) {
        int w = src.getWidth(), h = src.getHeight();
        float aspect = 16f / 9f;
        int tW, tH;
        if ((float) w / h > aspect) { tH = h; tW = Math.round(h * aspect); }
        else                         { tW = w; tH = Math.round(w / aspect); }
        if (tW == w && tH == h) return src;
        int offX = (w - tW) / 2, offY = (h - tH) / 2;
        NativeImage dst = new NativeImage(tW, tH, false);
        for (int y = 0; y < tH; y++)
            for (int x = 0; x < tW; x++)
                dst.setColor(x, y, src.getColor(x + offX, y + offY));
        return dst;
    }

    private static NativeImage boxDownsample(NativeImage src, int maxWidth) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= maxWidth) return src;
        int dw = maxWidth, dh = Math.max(1, Math.round((float) sh * dw / sw));
        NativeImage dst = new NativeImage(dw, dh, false);
        float xS = (float) sw / dw, yS = (float) sh / dh;
        for (int y = 0; y < dh; y++) {
            int sy0 = (int) Math.floor(y * yS);
            int sy1 = Math.min(sh, (int) Math.ceil((y + 1) * yS));
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int x = 0; x < dw; x++) {
                int sx0 = (int) Math.floor(x * xS);
                int sx1 = Math.min(sw, (int) Math.ceil((x + 1) * xS));
                if (sx1 <= sx0) sx1 = sx0 + 1;
                long ra = 0, ga = 0, ba = 0, aa = 0;
                int  n  = 0;
                for (int sy = sy0; sy < sy1; sy++)
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = src.getColor(sx, sy);
                        aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
                        ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
                        n++;
                    }
                dst.setColor(x, y,
                        ((int)(aa / n) << 24) | ((int)(ba / n) << 16)
                      | ((int)(ga / n) <<  8) |  (int)(ra / n));
            }
        }
        return dst;
    }

    private static int applyExposure(int v, float mult) {
        float f = v * mult;
        // Shoulder curve for highlights: soft knee above 200
        if (f > 200f) {
            float excess = f - 200f;
            f = 200f + 55f * (1f - (float) Math.exp(-excess / 55f));
        }
        return clamp((int) f);
    }

    private static float apertureToVignette(float aperture) {
        if (aperture <= 1.4f) return 0.70f;
        if (aperture <= 2.0f) return 0.55f;
        if (aperture <= 2.8f) return 0.40f;
        if (aperture <= 4.0f) return 0.25f;
        if (aperture <= 5.6f) return 0.15f;
        if (aperture <= 8.0f) return 0.08f;
        return 0.03f;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
