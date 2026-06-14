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

    private static final int   DEP_W = 32;
    private static final int   DEP_H = 18;
    private static final float NEAR  = 0.05f;
    private static final float FAR   = 512.0f;

    private static final float FOCAL_PX = 600f;

    // ── Depth of field — compact/phone camera: deep focus, gentle bokeh ─────────
    // Small sensors keep a wide band around the focus plane perfectly sharp and
    // only softly separate strongly defocused regions. Nothing like cinema glass.
    private static final float DOF_DEADZONE = 0.45f;  // relative defocus kept fully sharp
    private static final float DOF_GAIN     = 3.0f;   // bokeh px per unit relative defocus
    private static final float DOF_MAX_PX   = 6.0f;   // hard ceiling on bokeh radius

    // ── Motion blur — stabilised (EIS): short, only on genuinely fast motion ────
    private static final float MB_STAB      = 0.40f;  // fraction of apparent motion that survives
    private static final float MB_THRESHOLD = 1.5f;   // ignore sub-threshold motion (px)
    private static final float MB_MAX_DIV   = 50.0f;  // maxBlurPx = frameWidth / MB_MAX_DIV

    private static final int   FOCUS_DWELL_FRAMES = 20;
    private static final float FOCUS_TOL          = 0.25f;

    // ── Smooth camera state ──────────────────────────────────────────────────────
    private static boolean prevSmoothCamera = false;

    // ── Recording state ──────────────────────────────────────────────────────────
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

    // ── Autofocus state ──────────────────────────────────────────────────────────
    private static float focusCandidateDepth  = 5.0f;
    private static int   focusCandidateFrames = 0;
    private static float currentFocusDepth    = 5.0f;

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
                     int cropOffX, int cropOffY) {}

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
        frameCount    = 0;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);

        currentFocusDepth    = 5.0f;
        focusCandidateDepth  = 5.0f;
        focusCandidateFrames = 0;

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

        //? if >=1.21.11 {
        /*// In 1.21.11, depth lives in EvfBlurRenderer's texture (captured via glCopyImageSubData).
        // glReadPixels(GL_DEPTH_COMPONENT) reads the legacy FBO which has no scene depth.
        float[] linDepth = EvfBlurRenderer.readLinearDepthCpu();
        if (linDepth != null) {
            int tw = EvfBlurRenderer.depthTexW;
            int th = EvfBlurRenderer.depthTexH;
            if (tw > 0 && th > 0) {
                pendingDepthGrid  = downsampleLinearDepth(linDepth, tw, th, DEP_W, DEP_H);
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
        if (frameCount >= MAX_FRAMES) { stopRecording(); return; }

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

        // ── Dwell-time autofocus ─────────────────────────────────────────────────
        float centreDepth = Math.max(depthGrid[(DEP_H / 2) * DEP_W + DEP_W / 2], 0.3f);
        if (Math.abs(centreDepth - focusCandidateDepth)
                / Math.max(focusCandidateDepth, 0.1f) <= FOCUS_TOL) {
            focusCandidateFrames++;
            if (focusCandidateFrames >= FOCUS_DWELL_FRAMES)
                currentFocusDepth = currentFocusDepth * 0.65f + focusCandidateDepth * 0.35f;
        } else {
            focusCandidateDepth  = centreDepth;
            focusCandidateFrames = 0;
        }

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

        FrameMeta meta = new FrameMeta(
                frameCount,
                (float) vel.x, (float) vel.y, (float) vel.z,
                yaw, pitch,
                deltaYaw, deltaPitch, fovDeg,
                ap,
                currentFocusDepth,
                depthGrid,
                pendingVpW, pendingVpH,
                pendingCropOffX, pendingCropOffY);

        int  idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        frameMetas.add(meta);
        frameCount++;
        nextFrameMs = recordStartMs + (long)(frameCount * 1000.0 / currentFps);

        if (frameCount == currentFps * 60 && mc.player != null)
            mc.player.sendMessage(Text.literal("⚠ 残り 1:00"), true);

        //? if >=1.21.11 {
        /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> {
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
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            System.err.println("[VideoRecorder] Screenshot failed frame " + idx);
            return;
        }
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
        //?}
    }

    // ── Post-processing ───────────────────────────────────────────────────────────

    private static void doPostProcess(List<FrameMeta> metas, File rawDirIn, File vidDir) {
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

        if (!vidDir.exists()) vidDir.mkdirs();
        String outMp4    = new File(vidDir, sessionId + ".mp4").getAbsolutePath();
        boolean ffmpegOk = runFfmpeg(processedDir, outMp4);

        ppProgress = 100;
        if (ffmpegOk) {
            ppMessage = "✓ 保存: snapmatica/videos/" + sessionId + ".mp4";
            System.out.println("[VideoRecorder] Video saved: " + outMp4);
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

        // Single gentle pass — a low-radius box blur is all a small sensor needs.
        NativeImage pass2 = separableVariableBlur(pass1, cocMap, w, h);
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

    // ── Separable variable-radius box blur ────────────────────────────────────────

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

        // Horizontal pass
        for (int py = 0; py < h; py++) {
            prefR[0] = prefG[0] = prefB[0] = prefA[0] = 0;
            for (int px = 0; px < w; px++) {
                int c = getPixel(src, px, py);
                prefA[px + 1] = prefA[px] + ((c >>> 24) & 0xFF);
                prefB[px + 1] = prefB[px] + ((c >>> 16) & 0xFF);
                prefG[px + 1] = prefG[px] + ((c >>>  8) & 0xFF);
                prefR[px + 1] = prefR[px] + ( c         & 0xFF);
            }
            for (int px = 0; px < w; px++) {
                int r    = (int) radiusMap[py * w + px];
                int base = py * w + px;
                if (r < 1) {
                    int c = getPixel(src, px, py);
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

        // Vertical pass
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
                    setPixel(dst, px, py,
                            (dofTempA[base] << 24) | (dofTempB[base] << 16)
                          | (dofTempG[base] <<  8) |  dofTempR[base]);
                } else {
                    int y0  = Math.max(0, py - r), y1 = Math.min(h - 1, py + r);
                    int cnt = y1 - y0 + 1;
                    int av  = (int)((vprefA[y1 + 1] - vprefA[y0]) / cnt);
                    int bv  = (int)((vprefB[y1 + 1] - vprefB[y0]) / cnt);
                    int gv  = (int)((vprefG[y1 + 1] - vprefG[y0]) / cnt);
                    int rv  = (int)((vprefR[y1 + 1] - vprefR[y0]) / cnt);
                    setPixel(dst, px, py, (av << 24) | (bv << 16) | (gv << 8) | rv);
                }
            }
        }
        return dst;
    }

    // ── ffmpeg ────────────────────────────────────────────────────────────────────

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
