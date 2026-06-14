package dev.shunti.snapmatica.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side video recording engine for Snapmatica.
 * Ported from Photographica's VideoRecorder — tripod/item code removed.
 *
 * Pipeline per rendered frame (render thread):
 *   onWorldRenderEnd(vpW, vpH)    – called from PhotoCapture.onWorldRenderEnd():
 *                                   depth buffer → 32×18 grid.
 *   captureFrameIfRecording()     – called from GameRendererMixin after renderWorld():
 *                                   screenshot + dwell-AF + queue I/O.
 *
 * Post-processing (background thread):
 *   Pass 1 – histogram auto-exposure + vignette
 *   Pass 2 – DoF bokeh: separable variable-radius box blur (CoC from optics model)
 *   Pass 3 – directional motion blur (angular + translational + radial)
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ────────────────────────────────────────────────────────────────
    public static final int FPS        = 24;
    public static final int MAX_FRAMES = 30 * 120;   // 2 minutes @ 30 fps

    private static int currentFps = FPS;

    // Depth grid resolution. A coarse grid makes DoF boundaries miss object edges,
    // so background bokeh bleeds onto sharp subjects — the main source of the
    // "unnatural blur" look. 128×72 follows silhouettes closely while staying tiny
    // (and only every DEPTH_EVERY_N-th frame allocates a new grid, so memory is low).
    private static final int   DEP_W = 128;
    private static final int   DEP_H = 72;
    private static final float NEAR  = 0.05f;
    private static final float FAR   = 512.0f;

    private static final float FOCAL_PX = 600f;

    // ── Depth of field — compact/phone camera: deep focus, gentle bokeh ─────────
    // Small sensors keep a wide band around the focus plane perfectly sharp and
    // only softly separate strongly defocused regions. Nothing like cinema glass.
    private static final float DOF_DEADZONE = 0.70f;  // relative defocus kept fully sharp
    private static final float DOF_GAIN     = 1.7f;   // bokeh px per unit relative defocus
    private static final float DOF_MAX_PX   = 5.0f;   // hard ceiling on bokeh radius
    private static final int   DOF_SMOOTH_R = 4;      // CoC map smoothing radius (px)

    // ── Motion blur — stabilised (EIS): short, only on genuinely fast motion ────
    private static final float MB_STAB      = 0.40f;  // fraction of apparent motion that survives
    private static final float MB_THRESHOLD = 1.5f;   // ignore sub-threshold motion (px)
    private static final float MB_MAX_DIV   = 50.0f;  // maxBlurPx = frameWidth / MB_MAX_DIV

    // Per-frame blend α for continuous focus tracking (τ ≈ 80 frames ≈ 3 s at 24 fps).
    private static final float FOCUS_ALPHA = 0.012f;

    // ── Smooth camera state ──────────────────────────────────────────────────────
    private static boolean prevSmoothCamera = false;

    // ── Recording state ──────────────────────────────────────────────────────────
    private static volatile boolean recording      = false;
    private static volatile boolean postProcessing = false;
    private static volatile int     ppProgress     = 0;
    private static volatile String  ppMessage      = "";
    public  static volatile long    doneAtMs       = 0L;

    private static String          sessionId;
    private static int             frameCount;        // sequential PNG file index (0,1,2...)
    private static int             virtualFrameCount; // timing index; skips slots when render is slow
    private static long            recordStartMs;
    private static long            nextFrameMs;
    private static File            rawDir;
    private static List<FrameMeta> frameMetas;

    // ── Autofocus state ──────────────────────────────────────────────────────────
    private static float currentFocusDepth = 5.0f;

    // ── Angular velocity tracking ────────────────────────────────────────────────
    private static float   prevFrameYaw       = 0f;
    private static float   prevFramePitch     = 0f;
    private static boolean prevFrameValid     = false;
    private static float   smoothedDeltaYaw   = 0f;
    private static float   smoothedDeltaPitch = 0f;

    // ── Depth-buffer state ───────────────────────────────────────────────────────
    private static float[]     pendingDepthGrid  = null;
    private static boolean     pendingDepthReady = false;
    private static FloatBuffer depthReadBuf      = null;
    private static int         depthReadBufCap   = 0;
    private static int pendingVpW = 0, pendingVpH = 0;
    private static int pendingCropOffX = 0, pendingCropOffY = 0;

    // Read depth every DEPTH_EVERY_N frames to amortise the synchronous glReadPixels stall.
    private static final int DEPTH_EVERY_N    = 4;
    private static       int depthSkipCounter = 0;
    private static float[]   cachedDepthGrid  = null;

    // ── DoF temp arrays ──────────────────────────────────────────────────────────
    private static int[] dofTempR, dofTempG, dofTempB, dofTempA;
    private static int   dofTempCap = 0;

    private static float smoothedExpMult = 1.0f;

    private static final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "snapmatica-video-io");
                t.setDaemon(true);
                return t;
            });

    // ── Public accessors ─────────────────────────────────────────────────────────
    public static boolean isRecording()      { return recording; }
    public static boolean isPostProcessing() { return postProcessing; }
    public static int     getPpProgress()    { return ppProgress; }
    public static String  getPpMessage()     { return ppMessage; }
    public static long    getDoneAtMs()      { return doneAtMs; }
    public static int     getFrameCount()    { return frameCount; }
    public static long    getRecordStartMs() { return recordStartMs; }
    public static int     getCurrentFps()    { return currentFps; }
    public static void    setFps(int fps)    { if (!recording) currentFps = fps; }

    // ── FrameMeta ────────────────────────────────────────────────────────────────
    record FrameMeta(int   idx,
                     float velX,      float velY,      float velZ,
                     float yaw,       float pitch,
                     float deltaYaw,  float deltaPitch,
                     float fovDeg,
                     float aperture,
                     float focusDepth,
                     float[] depthGrid,
                     int vpW, int vpH,
                     int cropOffX, int cropOffY,
                     float durationSec) {}

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

        currentFps         = FPS;
        smoothedExpMult    = 1.0f;
        prevFrameValid     = false;
        prevFrameYaw       = 0f;
        prevFramePitch     = 0f;
        smoothedDeltaYaw   = 0f;
        smoothedDeltaPitch = 0f;
        frameCount        = 0;
        virtualFrameCount = 0;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);

        currentFocusDepth = 5.0f;
        depthSkipCounter  = 0;
        cachedDepthGrid   = null;

        rawDir = new File(mc.runDirectory, "snapmatica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            System.err.println("[VideoRecorder] Could not create raw dir: " + rawDir);
            return;
        }

        // Enable cinematic (smooth) camera so angular velocity tracking benefits
        // from sub-tick interpolation rather than per-tick quantisation spikes.
        prevSmoothCamera = mc.options.smoothCameraEnabled;
        mc.options.smoothCameraEnabled = true;

        recording = true;
        mc.player.sendMessage(Text.literal("● REC 開始"), true);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.smoothCameraEnabled = prevSmoothCamera;

        if (mc.player != null)
            mc.player.sendMessage(Text.literal("■ 録画停止 — 後処理中..."), true);

        final List<FrameMeta> metas   = new ArrayList<>(frameMetas);
        final File            rawSnap = rawDir;
        final File            vidDir  = new File(mc.runDirectory, "snapmatica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = "後処理中...";

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vidDir),
                "snapmatica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    // ── Depth capture (render thread) ─────────────────────────────────────────────
    /**
     * Called from PhotoCapture.onWorldRenderEnd() after EvfBlurRenderer.captureDepth().
     * Reads per-frame depth and downsamples to a 32×18 grid for post-processing.
     */
    public static void onWorldRenderEnd(int vpW, int vpH) {
        if (!recording) return;
        if (pendingDepthReady) return;

        // Skip the expensive glReadPixels most frames: reuse the cached grid instead.
        if (depthSkipCounter > 0) {
            depthSkipCounter--;
            if (cachedDepthGrid != null) {
                pendingDepthGrid  = cachedDepthGrid;
                pendingDepthReady = true;
                // pendingVpW/H and crop offsets remain from the last real read
            }
            return;
        }
        depthSkipCounter = DEPTH_EVERY_N - 1;

        //? if >=1.21.11 {
        /*// In 1.21.11, depth lives in EvfBlurRenderer's texture (captured via glCopyImageSubData).
        // glReadPixels(GL_DEPTH_COMPONENT) reads the legacy FBO which has no scene depth.
        float[] linDepth = EvfBlurRenderer.readLinearDepthCpu();
        if (linDepth != null) {
            int tw = EvfBlurRenderer.depthTexW;
            int th = EvfBlurRenderer.depthTexH;
            if (tw > 0 && th > 0) {
                pendingDepthGrid  = downsampleLinearDepth(linDepth, tw, th, DEP_W, DEP_H);
                cachedDepthGrid   = pendingDepthGrid;
                pendingDepthReady = true;
                pendingVpW    = tw;
                pendingVpH    = th;
                float a16 = 16f / 9f;
                int cW, cH;
                if ((float) tw / th > a16) { cH = th; cW = Math.round(th * a16); }
                else                       { cW = tw; cH = Math.round(tw / a16); }
                pendingCropOffX = (tw - cW) / 2;
                pendingCropOffY = (th - cH) / 2;
            }
        }*/
        //?} else {
        GL11.glGetError();
        if (vpW <= 0 || vpH <= 0) return;
        int needed = vpW * vpH;
        if (depthReadBuf == null || depthReadBufCap < needed) {
            depthReadBuf    = BufferUtils.createFloatBuffer(needed);
            depthReadBufCap = needed;
        }
        depthReadBuf.clear();
        GL11.glReadPixels(0, 0, vpW, vpH,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthReadBuf);
        pendingDepthGrid  = downsampleDepth(depthReadBuf, vpW, vpH, DEP_W, DEP_H);
        cachedDepthGrid   = pendingDepthGrid;
        pendingDepthReady = true;
        float a16 = 16f / 9f;
        int cW, cH;
        if ((float) vpW / vpH > a16) { cH = vpH; cW = Math.round(vpH * a16); }
        else                         { cW = vpW; cH = Math.round(vpW / a16); }
        pendingVpW    = vpW;
        pendingVpH    = vpH;
        pendingCropOffX = (vpW - cW) / 2;
        pendingCropOffY = (vpH - cH) / 2;
        //?}
    }

    // ── Frame capture (render thread) ─────────────────────────────────────────────
    /**
     * Called from GameRendererMixin after renderWorld() (after Iris shader compositing).
     */
    public static void captureFrameIfRecording() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (virtualFrameCount >= MAX_FRAMES) { stopRecording(); return; }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Consume depth grid
        float[] depthGrid;
        if (pendingDepthReady && pendingDepthGrid != null) {
            depthGrid         = pendingDepthGrid;
            pendingDepthGrid  = null;
            pendingDepthReady = false;
        } else {
            depthGrid = flatDepthGrid(currentFocusDepth);
        }

        // ── Continuous gentle autofocus — slow EMA toward centre-frame depth ────
        // α=0.012 → τ≈80 frames ≈ 3 s at 24 fps. No dwell threshold or sudden jump,
        // so focus creeps gradually to wherever the player is looking.
        float centreDepth = Math.max(depthGrid[(DEP_H / 2) * DEP_W + DEP_W / 2], 0.3f);
        currentFocusDepth = currentFocusDepth * (1f - FOCUS_ALPHA) + centreDepth * FOCUS_ALPHA;

        Vec3d vel = mc.player.getVelocity();
        float ap  = SnapmaticaClient.aperture;

        // Horizontal FOV of the 16:9 video derived from the focal length setting
        double halfSensorMm = SnapmaticaClient.portraitOrientation ? 18.0 : 12.0;
        double vFovRad = 2.0 * Math.atan(halfSensorMm / Math.max(SnapmaticaClient.focalLengthMm, 1));
        float fovDeg = (float) Math.toDegrees(2.0 * Math.atan(Math.tan(vFovRad / 2.0) * 16.0 / 9.0));

        Camera camera = mc.gameRenderer != null ? mc.gameRenderer.getCamera() : null;
        float yaw   = (camera != null && camera.isReady()) ? camera.getYaw()   : mc.player.getYaw();
        float pitch = (camera != null && camera.isReady()) ? camera.getPitch() : mc.player.getPitch();

        float deltaYaw, deltaPitch;
        if (prevFrameValid) {
            float rawDY = yaw - prevFrameYaw;
            if (rawDY >  180f) rawDY -= 360f;
            if (rawDY < -180f) rawDY += 360f;
            float rawDP = pitch - prevFramePitch;
            final float A = 0.4f;
            smoothedDeltaYaw   = smoothedDeltaYaw   * (1 - A) + rawDY * A;
            smoothedDeltaPitch = smoothedDeltaPitch * (1 - A) + rawDP * A;
            deltaYaw   = smoothedDeltaYaw;
            deltaPitch = smoothedDeltaPitch;
        } else {
            deltaYaw = deltaPitch = 0f;
            prevFrameValid = true;
        }
        prevFrameYaw   = yaw;
        prevFramePitch = pitch;

        // Calculate how many frame slots this single PNG covers.
        // When the render thread is slower than the target FPS, multiple time slots
        // pass between captures. Storing durationSec lets the concat demuxer tell
        // ffmpeg to hold each frame for exactly the right wall-clock duration, so the
        // output video plays back at the correct speed regardless of render FPS.
        long overdue = now - nextFrameMs;  // ≥ 0 at this point
        int slotsConsumed = 1 + (int)(overdue * currentFps / 1000L);
        slotsConsumed = Math.min(slotsConsumed, currentFps); // cap at 1 s to absorb pauses
        float durationSec = (float) slotsConsumed / currentFps;

        virtualFrameCount += slotsConsumed;
        nextFrameMs = recordStartMs + (long)(virtualFrameCount * 1000.0 / currentFps);

        FrameMeta meta = new FrameMeta(
                frameCount,
                (float) vel.x, (float) vel.y, (float) vel.z,
                yaw, pitch,
                deltaYaw, deltaPitch, fovDeg,
                ap,
                currentFocusDepth,
                depthGrid,
                pendingVpW, pendingVpH,
                pendingCropOffX, pendingCropOffY,
                durationSec);

        int  idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        frameMetas.add(meta);
        frameCount++;

        if (virtualFrameCount >= currentFps * 60 && virtualFrameCount - slotsConsumed < currentFps * 60
                && mc.player != null)
            mc.player.sendMessage(Text.literal("⚠ 残り 1:00"), true);

        //? if >=1.21.11 {
        /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> {
            // crop+downsample happen in the callback (already off render thread in 1.21.11)
            NativeImage cropped = cropTo16x9(raw);
            NativeImage frame   = boxDownsample(cropped, 1280);
            if (cropped != raw) cropped.close();
            raw.close();
            ioExecutor.submit(() -> {
                try { frame.writeTo(outFile); }
                catch (IOException e) {
                    System.err.println("[VideoRecorder] Frame write failed: " + outFile);
                } finally { frame.close(); }
            });
        });*/
        //?} else {
        // takeScreenshot does the glReadPixels stall on the render thread — unavoidable.
        // Offload crop+downsample+write to the I/O thread so the render thread is freed ASAP.
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            System.err.println("[VideoRecorder] Screenshot failed frame " + idx);
            return;
        }
        ioExecutor.submit(() -> {
            try {
                NativeImage cropped = cropTo16x9(raw);
                NativeImage frame   = boxDownsample(cropped, 1280);
                if (cropped != raw) cropped.close();
                raw.close();
                try { frame.writeTo(outFile); }
                catch (IOException e) {
                    System.err.println("[VideoRecorder] Frame write failed: " + outFile);
                } finally { frame.close(); }
            } catch (Exception e) {
                raw.close();
                System.err.println("[VideoRecorder] Frame process failed: " + outFile);
            }
        });
        //?}
    }

    // ── Post-processing ───────────────────────────────────────────────────────────

    private static void doPostProcess(List<FrameMeta> metas, File rawDirIn, File vidDir) {
        // Wait for the I/O thread to finish writing all raw frame PNGs before we read them.
        try { ioExecutor.submit(() -> {}).get(); } catch (Exception ignored) {}

        int total = metas.size();
        if (total == 0) {
            postProcessing = false;
            ppMessage      = "フレームなし";
            doneAtMs       = System.currentTimeMillis();
            return;
        }

        File processedDir = new File(rawDirIn.getParentFile(), "processed");
        if (!processedDir.mkdirs()) {
            System.err.println("[VideoRecorder] Could not create processed dir");
            postProcessing = false;
            return;
        }

        ppMessage = "エフェクト適用中...";

        for (int i = 0; i < total; i++) {
            FrameMeta meta    = metas.get(i);
            File inFile  = new File(rawDirIn,     String.format("frame_%04d.png", meta.idx()));
            File outFile = new File(processedDir, String.format("frame_%04d.png", meta.idx()));

            if (!inFile.exists()) { ppProgress = (i + 1) * 80 / total; continue; }

            try (NativeImage img =
                         NativeImage.read(inFile.toPath().toUri().toURL().openStream())) {
                NativeImage processed = applyVideoEffects(img, meta);
                processed.writeTo(outFile);
                processed.close();
            } catch (IOException e) {
                System.err.println("[VideoRecorder] Post-process failed frame " + meta.idx());
            }

            ppProgress = (i + 1) * 80 / total;
            ppMessage  = "エフェクト適用中... " + ppProgress + "%";
        }

        ppMessage  = "MP4 エンコード中...";
        ppProgress = 80;

        // Write per-frame duration file so ffmpeg holds each PNG for exactly the
        // right wall-clock duration — this corrects for dropped frames when the
        // game rendered slower than the target FPS.
        File concatFile = new File(processedDir, "frames.txt");
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
            ppMessage = "✓ 保存&コピー: snapmatica/videos/" + sessionId + ".mp4";
            System.out.println("[VideoRecorder] Video saved: " + outMp4);
            // Auto-copy the finished MP4 to the system clipboard as a file reference.
            ClipboardUtil.copyFileAsync(new File(outMp4));
        } else {
            File pngDir = new File(vidDir, sessionId);
            processedDir.renameTo(pngDir);
            ppMessage = "ffmpeg なし — PNG 保存: snapmatica/videos/" + sessionId + "/";
            System.out.println("[VideoRecorder] ffmpeg not found; PNGs at " + pngDir);
        }

        deleteDir(rawDirIn);
        if (ffmpegOk) deleteDir(processedDir);

        postProcessing = false;
        doneAtMs       = System.currentTimeMillis();
    }

    // ── Video effects ─────────────────────────────────────────────────────────────

    private static NativeImage applyVideoEffects(NativeImage src, FrameMeta meta) {
        int   w     = src.getWidth();
        int   h     = src.getHeight();
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        float ap    = meta.aperture();
        float focus = Math.max(meta.focusDepth(), 0.5f);
        float[] grid = meta.depthGrid();

        // ── Pass 1: auto-exposure + vignette ─────────────────────────────────────
        float rawExpMult = computeAutoExposure(src);
        float alpha = rawExpMult > smoothedExpMult ? 0.04f : 0.15f;
        smoothedExpMult = smoothedExpMult * (1f - alpha) + rawExpMult * alpha;
        float expMult = smoothedExpMult;
        float vig     = apertureToVignette(ap);

        NativeImage pass1 = new NativeImage(w, h, false);
        for (int py = 0; py < h; py++) {
            float dy  = (py - halfH) / halfH;
            float dy2 = dy * dy;
            for (int px = 0; px < w; px++) {
                int c = getPixel(src, px, py);
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

                setPixel(pass1, px, py, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        // ── Pass 2: depth-of-field bokeh ─────────────────────────────────────────
        int vw = meta.vpW(), vh = meta.vpH();
        int cOffX = meta.cropOffX(), cOffY = meta.cropOffY();
        float aspect16_9 = 16f / 9f;
        int cropW, cropH;
        if (vw > 0 && vh > 0) {
            if ((float) vw / vh > aspect16_9) { cropH = vh; cropW = Math.round(vh * aspect16_9); }
            else                              { cropW = vw; cropH = Math.round(vw / aspect16_9); }
        } else {
            cropW = w; cropH = h;
        }

        // Wider apertures separate a touch more, but the ceiling stays low so the
        // frame reads as deep-focus phone/vlog footage rather than cinema bokeh.
        float apFactor = Math.max(0.4f, Math.min(1.5f, 4.0f / ap));
        float[] cocMap = new float[w * h];
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                float d;
                if (grid != null && vw > 0) {
                    float glX = px * (float) cropW / w + cOffX;
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
                // Relative defocus: how many focus-distances away the pixel sits.
                // A dead zone keeps the near field sharp (small-sensor deep focus).
                float rel  = Math.abs(d - focus) / Math.max(focus, 0.5f);
                float soft = Math.max(0f, rel - DOF_DEADZONE);
                cocMap[py * w + px] = Math.min(soft * DOF_GAIN * apFactor, DOF_MAX_PX);
            }
        }

        // Light CoC smoothing removes the residual grid banding without smearing the
        // blur boundary across object edges (the heavy r=15 smoothing used to create
        // a halo around sharp subjects).
        float[] smoothedCoc = boxBlurMap(cocMap, w, h, DOF_SMOOTH_R);

        // Gaussian, bleed-guarded gather (same model as the photo path): produces a
        // soft round falloff instead of a boxy average, and the per-neighbour
        // min(1, cocN/coc) weight stops sharp foreground pixels smearing into the
        // blurred background — which is what reads as "natural" bokeh.
        NativeImage pass2 = gaussianDofBlur(pass1, smoothedCoc, w, h);
        pass1.close();

        // ── Pass 3: motion blur ───────────────────────────────────────────────────
        float fovH = meta.fovDeg();
        float fovV = fovH * 9f / 16f;

        // Electronic stabilisation absorbs most apparent motion: only MB_STAB of it
        // survives as blur, capped short — like handheld phone/vlog footage with EIS.
        float rotSampleX =  meta.deltaYaw()   * w / fovH * MB_STAB;
        float rotSampleY = -meta.deltaPitch() * h / fovV * MB_STAB;

        float yawRad    = (float) Math.toRadians(meta.yaw());
        float strafeVel = ((float)(Math.cos(yawRad) * meta.velX()
                                 + Math.sin(yawRad) * meta.velZ()))
                        * (20.0f / currentFps);
        float transScale = strafeVel * FOCAL_PX * MB_STAB;

        float fwdVel = ((float)(-Math.sin(yawRad) * meta.velX()
                               + Math.cos(yawRad) * meta.velZ()))
                     * (20.0f / currentFps) * MB_STAB;
        float cx = w * 0.5f, cy = h * 0.5f;

        float totalAtFocus = (float) Math.sqrt(
                (rotSampleX + transScale / focus) * (rotSampleX + transScale / focus)
              + rotSampleY * rotSampleY);
        float cornerFwdBlur = (float) Math.sqrt(cx * cx + cy * cy) * Math.abs(fwdVel) / focus;
        if (totalAtFocus < MB_THRESHOLD && cornerFwdBlur < MB_THRESHOLD) return pass2;

        float maxBlurPx = w / MB_MAX_DIV;

        NativeImage pass3 = new NativeImage(w, h, false);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
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

                float sampleX = rotSampleX + transScale / d + (px - cx) * fwdVel / d;
                float sampleY = rotSampleY                  + (py - cy) * fwdVel / d;
                float blurMag = (float) Math.sqrt(sampleX * sampleX + sampleY * sampleY);
                int blurLen = (int) Math.min(blurMag, maxBlurPx);

                if (blurLen < 1) {
                    setPixel(pass3, px, py, getPixel(pass2, px, py));
                    continue;
                }

                float ndx = sampleX / blurMag;
                float ndy = sampleY / blurMag;

                float ra = 0, ga = 0, ba = 0, aa = 0, sumW = 0;
                for (int s = 0; s <= blurLen; s++) {
                    float wt = (blurLen - s + 1);
                    int sx = Math.max(0, Math.min(w - 1, px + (int)(s * ndx)));
                    int sy = Math.max(0, Math.min(h - 1, py + (int)(s * ndy)));
                    int c  = getPixel(pass2, sx, sy);
                    aa += ((c >>> 24) & 0xFF) * wt;
                    ba += ((c >>> 16) & 0xFF) * wt;
                    ga += ((c >>>  8) & 0xFF) * wt;
                    ra += ( c         & 0xFF) * wt;
                    sumW += wt;
                }
                setPixel(pass3, px, py,
                        (clamp((int)(aa / sumW)) << 24) | (clamp((int)(ba / sumW)) << 16)
                      | (clamp((int)(ga / sumW)) <<  8) |  clamp((int)(ra / sumW)));
            }
        }
        pass2.close();
        return pass3;
    }

    // ── Auto-exposure ─────────────────────────────────────────────────────────────

    private static float computeAutoExposure(NativeImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] hist  = new int[256];
        int   count = 0;
        for (int py = 0; py < h; py += 8) {
            for (int px = 0; px < w; px += 8) {
                int c = getPixel(src, px, py);
                int r =  c         & 0xFF;
                int g = (c >>>  8) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int luma = (r * 299 + g * 587 + b * 114) / 1000;
                hist[Math.min(luma, 255)]++;
                count++;
            }
        }
        if (count == 0) return 1.0f;

        int threshold = (int)(count * 0.50), cumulative = 0, p50 = 128;
        for (int i = 0; i < 256; i++) {
            cumulative += hist[i];
            if (cumulative >= threshold) { p50 = i; break; }
        }
        if (p50 < 6) return 1.0f;

        float mult = 140.0f / p50;
        return Math.max(0.7f, Math.min(4.0f, mult));
    }

    // ── CoC map smoothing (prefix-sum box blur) ───────────────────────────────────
    // Blends CoC values across depth discontinuities so DoF transitions look
    // gradual rather than stepping along the coarse 32×18 depth grid.

    private static float[] boxBlurMap(float[] src, int w, int h, int r) {
        float[] tmp = new float[src.length];
        float[] dst = new float[src.length];
        // Horizontal pass
        for (int y = 0; y < h; y++) {
            float[] pref = new float[w + 1];
            for (int x = 0; x < w; x++) pref[x + 1] = pref[x] + src[y * w + x];
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                tmp[y * w + x] = (pref[x1 + 1] - pref[x0]) / (x1 - x0 + 1);
            }
        }
        // Vertical pass
        for (int x = 0; x < w; x++) {
            float[] pref = new float[h + 1];
            for (int y = 0; y < h; y++) pref[y + 1] = pref[y] + tmp[y * w + x];
            for (int y = 0; y < h; y++) {
                int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
                dst[y * w + x] = (pref[y1 + 1] - pref[y0]) / (y1 - y0 + 1);
            }
        }
        return dst;
    }

    // ── Gaussian, bleed-guarded variable-radius blur ─────────────────────────────
    // Separable two-pass gather with true Gaussian weights, plus a per-neighbour
    // min(1, cocNeighbor / cocCenter) guard so a sharp foreground pixel cannot
    // smear into a blurred neighbour. Same approach the photo pipeline uses.

    private static NativeImage gaussianDofBlur(NativeImage src, float[] cocMap, int w, int h) {
        int size = w * h;
        if (dofTempCap < size) {
            dofTempR = new int[size]; dofTempG = new int[size];
            dofTempB = new int[size]; dofTempA = new int[size];
            dofTempCap = size;
        }
        int maxR = Math.max(1, (int) Math.ceil(DOF_MAX_PX));

        // Precompute Gaussian weight tables once per frame — eliminates ~20M Math.exp()
        // calls (≈400ms at 1280x720 with r=5) and replaces them with array lookups.
        // gaussW[r][d+r] = exp(-d^2 / (2*sigma^2)) for d in [-r..r].
        float[][] gaussW = new float[maxR + 1][];
        for (int r = 1; r <= maxR; r++) {
            float sigma = Math.max(r * 0.5f, 1.0f);
            float inv2s2 = 1.0f / (2.0f * sigma * sigma);
            float[] wt = new float[2 * r + 1];
            for (int d = -r; d <= r; d++) wt[d + r] = (float) Math.exp(-(d * d) * inv2s2);
            gaussW[r] = wt;
        }

        // Horizontal pass → dofTemp* (ABGR channels)
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int base = py * w + px;
                float coc = cocMap[base];
                if (coc < 0.5f) {
                    int c = getPixel(src, px, py);
                    dofTempA[base] = (c >>> 24) & 0xFF;
                    dofTempB[base] = (c >>> 16) & 0xFF;
                    dofTempG[base] = (c >>>  8) & 0xFF;
                    dofTempR[base] =  c         & 0xFF;
                    continue;
                }
                int r = Math.min(maxR, (int) Math.ceil(coc));
                float[] wts = gaussW[r];
                float ra = 0, ga = 0, ba = 0, aa = 0, tw = 0;
                for (int dx = -r; dx <= r; dx++) {
                    int sx = Math.max(0, Math.min(w - 1, px + dx));
                    float wt = wts[dx + r] * Math.min(1.0f, cocMap[py * w + sx] / coc);
                    if (wt < 0.001f) continue;
                    int c = getPixel(src, sx, py);
                    aa += ((c >>> 24) & 0xFF) * wt; ba += ((c >>> 16) & 0xFF) * wt;
                    ga += ((c >>>  8) & 0xFF) * wt; ra += ( c         & 0xFF) * wt;
                    tw += wt;
                }
                if (tw < 0.001f) {
                    int c = getPixel(src, px, py);
                    dofTempA[base] = (c >>> 24) & 0xFF;
                    dofTempB[base] = (c >>> 16) & 0xFF;
                    dofTempG[base] = (c >>>  8) & 0xFF;
                    dofTempR[base] =  c         & 0xFF;
                } else {
                    dofTempA[base] = clamp(Math.round(aa / tw));
                    dofTempB[base] = clamp(Math.round(ba / tw));
                    dofTempG[base] = clamp(Math.round(ga / tw));
                    dofTempR[base] = clamp(Math.round(ra / tw));
                }
            }
        }

        // Vertical pass → dst
        NativeImage dst = new NativeImage(w, h, false);
        for (int px = 0; px < w; px++) {
            for (int py = 0; py < h; py++) {
                int base = py * w + px;
                float coc = cocMap[base];
                if (coc < 0.5f) {
                    setPixel(dst, px, py,
                            (dofTempA[base] << 24) | (dofTempB[base] << 16)
                          | (dofTempG[base] <<  8) |  dofTempR[base]);
                    continue;
                }
                int r = Math.min(maxR, (int) Math.ceil(coc));
                float[] wts = gaussW[r];
                float ra = 0, ga = 0, ba = 0, aa = 0, tw = 0;
                for (int dy = -r; dy <= r; dy++) {
                    int sy = Math.max(0, Math.min(h - 1, py + dy));
                    float wt = wts[dy + r] * Math.min(1.0f, cocMap[sy * w + px] / coc);
                    if (wt < 0.001f) continue;
                    int sbase = sy * w + px;
                    aa += dofTempA[sbase] * wt; ba += dofTempB[sbase] * wt;
                    ga += dofTempG[sbase] * wt; ra += dofTempR[sbase] * wt;
                    tw += wt;
                }
                if (tw < 0.001f) {
                    setPixel(dst, px, py,
                            (dofTempA[base] << 24) | (dofTempB[base] << 16)
                          | (dofTempG[base] <<  8) |  dofTempR[base]);
                } else {
                    setPixel(dst, px, py,
                            (clamp(Math.round(aa / tw)) << 24) | (clamp(Math.round(ba / tw)) << 16)
                          | (clamp(Math.round(ga / tw)) <<  8) |  clamp(Math.round(ra / tw)));
                }
            }
        }
        return dst;
    }

    // ── ffmpeg ────────────────────────────────────────────────────────────────────

    private static boolean runFfmpeg(File concatFile, String outPath) {
        String[] candidates = {"ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"};
        for (String ff : candidates) {
            try {
                // concat demuxer: each frame carries its own duration so the video
                // plays at correct wall-clock speed even when frames were dropped.
                ProcessBuilder pb = new ProcessBuilder(
                        ff, "-y",
                        "-f", "concat", "-safe", "0",
                        "-i", concatFile.getAbsolutePath(),
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
                System.err.println("[VideoRecorder] ffmpeg exited " + exit);
                return false;
            } catch (IOException | InterruptedException ignored) {}
        }
        return false;
    }

    // ── Depth utilities ───────────────────────────────────────────────────────────

    //? if >=1.21.11 {
    /*private static float[] downsampleLinearDepth(float[] linear, int vpW, int vpH, int dW, int dH) {
        float[] grid = new float[dW * dH];
        for (int dy = 0; dy < dH; dy++) {
            int sy0 = dy * vpH / dH, sy1 = Math.min((dy + 1) * vpH / dH, vpH);
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int dx = 0; dx < dW; dx++) {
                int sx0 = dx * vpW / dW, sx1 = Math.min((dx + 1) * vpW / dW, vpW);
                if (sx1 <= sx0) sx1 = sx0 + 1;
                double sum = 0; int cnt = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int row = sy * vpW;
                    for (int sx = sx0; sx < sx1; sx++) { sum += linear[row + sx]; cnt++; }
                }
                grid[dy * dW + dx] = cnt > 0 ? (float)(sum / cnt) : 0f;
            }
        }
        return grid;
    }*/
    //?} else {
    private static float[] downsampleDepth(FloatBuffer raw, int vpW, int vpH, int dW, int dH) {
        float[] grid = new float[dW * dH];
        for (int dy = 0; dy < dH; dy++) {
            int sy0 = dy * vpH / dH, sy1 = Math.min((dy + 1) * vpH / dH, vpH);
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int dx = 0; dx < dW; dx++) {
                int sx0 = dx * vpW / dW, sx1 = Math.min((dx + 1) * vpW / dW, vpW);
                if (sx1 <= sx0) sx1 = sx0 + 1;
                double sum = 0; int cnt = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int row = sy * vpW;
                    for (int sx = sx0; sx < sx1; sx++) { sum += raw.get(row + sx); cnt++; }
                }
                float rawD = (float)(sum / cnt);
                float ndc  = 2.0f * rawD - 1.0f;
                grid[dy * dW + dx] = 2.0f * NEAR * FAR / (FAR + NEAR - ndc * (FAR - NEAR));
            }
        }
        return grid;
    }
    //?}

    private static float bilinearDepth(float[] grid, int dW, int dH, float fx, float fy) {
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

    // ── Image utilities ───────────────────────────────────────────────────────────

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
                setPixel(dst, x, y, getPixel(src, x + offX, y + offY));
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
                        int c = getPixel(src, sx, sy);
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
    }*/
    //?} else {
    private static int getPixel(NativeImage img, int x, int y) { return img.getColor(x, y); }
    private static void setPixel(NativeImage img, int x, int y, int abgr) { img.setColor(x, y, abgr); }
    //?}

    // ── Misc helpers ──────────────────────────────────────────────────────────────

    private static int applyExposure(int v, float mult) {
        float f = v * mult;
        if (f > 200f) {
            float excess = f - 200f;
            f = 200f + 55f * (1f - (float) Math.exp(-excess / 55f));
        }
        return clamp((int) f);
    }

    /**
     * Phone / compact-camera lenses are well-corrected and the footage is usually
     * shading-corrected in-camera — only a whisper of corner darkening remains,
     * a touch more wide open. Nothing like a cinematic vignette.
     */
    private static float apertureToVignette(float aperture) {
        if (aperture <= 2.0f) return 0.10f;
        if (aperture <= 4.0f) return 0.07f;
        return 0.04f;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
