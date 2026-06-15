package dev.shunti.snapmatica.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.network.chat.Component;

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
 * Client-side video recording engine for Snapmatica (MC 26.1.2).
 *
 * Records the EVF-blurred framebuffer each frame (GPU DoF baked in),
 * then encodes via ffmpeg concat demuxer. Motion blur via ffmpeg tmix.
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    public static final int FPS        = 24;
    public static final int MAX_FRAMES = 30 * 120;  // 2 minutes @ 30 fps

    public static final float VIDEO_FOV_MIN   = 10.0f;  // max zoom (telephoto)
    public static final float VIDEO_FOV_MAX   = 70.0f;  // unzoomed
    public static final float VIDEO_ZOOM_STEP = 5.0f;   // deg per scroll tick
    /** Vertical FOV (deg) used while recording — Alt+scroll zoom adjusts this. */
    public static volatile float videoFov = VIDEO_FOV_MAX;

    private static int currentFps = FPS;

    // 0 = off, 1 = light (2-frame), 2 = strong (4-frame)
    private static volatile int motionBlur = 1;

    private static volatile boolean recording      = false;
    private static volatile boolean postProcessing = false;
    private static volatile int     ppProgress     = 0;
    private static volatile String  ppMessage      = "";
    public  static volatile long    doneAtMs       = 0L;

    private static String          sessionId;
    private static int             frameCount;
    private static int             virtualFrameCount;
    private static long            recordStartMs;
    private static long            nextFrameMs;
    private static File            rawDir;
    private static List<FrameMeta> frameMetas;

    private static final AtomicInteger writtenFrames = new AtomicInteger(0);

    // Autofocus state — independent of the still-camera focusDistance so video
    // DoF tracks the scene even when the viewfinder (sneak mode) is not active.
    private static final int   FOCUS_DWELL_FRAMES = 20;
    private static final float FOCUS_TOL          = 0.25f;
    private static float currentFocusDepth   = 5.0f;
    private static float focusCandidateDepth = 5.0f;
    private static int   focusCandidateFrames = 0;

    /** smoothCamera state saved at record start, restored on stop. */
    private static boolean prevSmoothCamera = false;

    private static final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "snapmatica-video-io");
                t.setDaemon(true);
                return t;
            });

    public static boolean isRecording()      { return recording; }
    public static boolean isPostProcessing() { return postProcessing; }
    public static int     getPpProgress()    { return ppProgress; }
    public static String  getPpMessage()     { return ppMessage; }
    public static long    getDoneAtMs()      { return doneAtMs; }
    public static int     getFrameCount()    { return frameCount; }
    public static long    getRecordStartMs() { return recordStartMs; }
    public static int     getCurrentFps()    { return currentFps; }
    public static void    setFps(int fps)    { if (!recording) currentFps = fps; }
    public static int     getMotionBlur()    { return motionBlur; }
    public static void    setMotionBlur(int v) { motionBlur = Math.max(0, Math.min(2, v)); }

    record FrameMeta(int idx, float durationSec) {}

    public static void toggleRecording() {
        if (recording) stopRecording();
        else if (!postProcessing) startRecording();
    }

    public static void startRecording() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        sessionId = ts;

        currentFps        = FPS;
        videoFov          = VIDEO_FOV_MAX;   // each recording starts unzoomed
        frameCount        = 0;
        virtualFrameCount = 0;
        writtenFrames.set(0);
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);

        // Probe scene depth immediately so the first frame doesn't start with the
        // 5-block default and show wrong blur until the dwell-time AF kicks in.
        float initDepth = computeSceneFocusDepth(mc);
        currentFocusDepth    = (initDepth > 0.3f && initDepth < 999.0f) ? initDepth : 5.0f;
        focusCandidateDepth  = currentFocusDepth;
        focusCandidateFrames = 0;

        rawDir = new File(mc.gameDirectory, "snapmatica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            System.err.println("[VideoRecorder] Could not create raw dir: " + rawDir);
            return;
        }

        // Enable cinematic (smooth) camera for steadier handheld panning footage.
        prevSmoothCamera = mc.options.smoothCamera;
        mc.options.smoothCamera = true;

        recording = true;
        mc.gui.setOverlayMessage(Component.literal("● REC 開始"), true);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;

        Minecraft mc = Minecraft.getInstance();
        mc.options.smoothCamera = prevSmoothCamera;
        if (mc.player != null)
            mc.gui.setOverlayMessage(Component.literal("■ 録画停止 — エンコード中..."), true);

        final List<FrameMeta> metas  = new ArrayList<>(frameMetas);
        final File            rawSnap = rawDir;
        final File            vidDir  = new File(mc.gameDirectory, "snapmatica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = "エンコード中...";

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vidDir),
                "snapmatica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Called at LevelRenderEvents.END_MAIN while the depth buffer is still valid.
     * Captures the scene depth texture needed by the EVF blur shader.
     * PhotoCapture.onWorldRenderEnd() only does this while Shift is held (viewfinder mode),
     * so we must do it independently here while recording.
     */
    public static void onWorldRenderEnd() {
        if (!recording) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainFb = mc.getMainRenderTarget();
        if (mainFb == null) return;
        int fbW = mainFb.width, fbH = mainFb.height;
        if (fbW > 0 && fbH > 0) {
            int rd = mc.options.renderDistance().get();
            EvfBlurRenderer.currentDepthFar = Math.max(rd * 64f, 256f);
            EvfBlurRenderer.captureDepth(fbW, fbH);
        }
    }

    /**
     * Called from GameRendererMixin after renderLevel() (after shaders have composited).
     * Applies preview DoF blur to the whole framebuffer, then screenshots the frame.
     */
    public static void captureFrameIfRecording() {
        if (!recording) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Update AF and apply blur every render frame so the live preview stays
        // consistently DoF'd — eliminates the sharp/blurred flicker.
        updateAutofocus(mc);
        applyPreviewBlur(mc);

        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (virtualFrameCount >= MAX_FRAMES) { stopRecording(); return; }

        long overdue = now - nextFrameMs;
        int slotsConsumed = 1 + (int)(overdue * currentFps / 1000L);
        slotsConsumed = Math.min(slotsConsumed, currentFps);
        float durationSec = (float) slotsConsumed / currentFps;

        virtualFrameCount += slotsConsumed;
        nextFrameMs = recordStartMs + (long)(virtualFrameCount * 1000.0 / currentFps);

        int  idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        frameMetas.add(new FrameMeta(idx, durationSec));
        frameCount++;

        if (virtualFrameCount >= currentFps * 60 && virtualFrameCount - slotsConsumed < currentFps * 60
                && mc.player != null)
            mc.gui.setOverlayMessage(Component.literal("⚠ 残り 1:00"), true);

        Screenshot.takeScreenshot(mc.getMainRenderTarget(), raw -> {
            NativeImage cropped = cropTo16x9(raw);
            NativeImage frame   = boxDownsample(cropped, 1280);
            if (cropped != raw) cropped.close();
            raw.close();
            ioExecutor.submit(() -> {
                try { frame.writeToFile(outFile.toPath()); }
                catch (IOException e) {
                    System.err.println("[VideoRecorder] Frame write failed: " + outFile);
                } finally { frame.close(); writtenFrames.incrementAndGet(); }
            });
        });
    }

    /**
     * Applies full-frame GPU depth-of-field blur using the video AF focus depth.
     * Uses currentFocusDepth (tracked via raycasts) rather than the still-camera
     * SnapmaticaClient.focusDistance, which is only updated in sneak-viewfinder mode.
     */
    private static void applyPreviewBlur(Minecraft mc) {
        if (SnapmaticaClient.lensType == 0) return;
        if (SnapmaticaClient.aperture >= 8.0f) return;
        double guiScale = mc.getWindow().getGuiScale();
        int guiW = (int)(mc.getWindow().getWidth()  / guiScale);
        int guiH = (int)(mc.getWindow().getHeight() / guiScale);
        EvfBlurRenderer.renderBlur(0, 0, guiW, guiH,
                currentFocusDepth, SnapmaticaClient.aperture,
                SnapmaticaClient.focalLengthMm);
    }

    private static void updateAutofocus(Minecraft mc) {
        float centreDepth = Math.max(computeSceneFocusDepth(mc), 0.3f);
        if (Math.abs(centreDepth - focusCandidateDepth)
                / Math.max(focusCandidateDepth, 0.1f) <= FOCUS_TOL) {
            focusCandidateFrames++;
            if (focusCandidateFrames >= FOCUS_DWELL_FRAMES) {
                currentFocusDepth = currentFocusDepth * 0.65f + focusCandidateDepth * 0.35f;
            }
        } else {
            focusCandidateDepth  = centreDepth;
            focusCandidateFrames = 0;
        }
    }

    private static float computeSceneFocusDepth(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.gameRenderer == null)
            return currentFocusDepth;
        net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
        if (cam == null || !cam.isInitialized()) return currentFocusDepth;

        net.minecraft.world.phys.Vec3 eye = cam.position();
        org.joml.Vector3fc f = cam.forwardVector();
        net.minecraft.world.phys.Vec3 look =
                new net.minecraft.world.phys.Vec3(f.x(), f.y(), f.z());

        final double maxBlockDist  = 1000.0;
        final double maxEntityDist = 60.0;
        net.minecraft.world.phys.BlockHitResult blockHit = mc.level.clip(
                new net.minecraft.world.level.ClipContext(
                        eye, eye.add(look.scale(maxBlockDist)),
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        double bestDist = (blockHit != null
                && blockHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS)
                ? eye.distanceTo(blockHit.getLocation()) : maxBlockDist;

        net.minecraft.world.phys.Vec3 entityEnd = eye.add(look.scale(maxEntityDist));
        net.minecraft.world.phys.AABB entityBox =
                new net.minecraft.world.phys.AABB(eye, entityEnd).inflate(1.0);
        net.minecraft.world.phys.EntityHitResult entityHit =
                net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                        mc.player, eye, entityEnd, entityBox,
                        e -> !e.isSpectator() && e.isAlive() && e != mc.player,
                        maxEntityDist * maxEntityDist);
        if (entityHit != null) {
            double eDist = eye.distanceTo(entityHit.getLocation());
            if (eDist < bestDist) bestDist = eDist;
        }
        return (float) Math.min(bestDist, 999.0);
    }

    private static void doPostProcess(List<FrameMeta> metas, File rawDirIn, File vidDir) {
        int total = metas.size();
        if (total == 0) {
            postProcessing = false;
            ppMessage      = "フレームなし";
            doneAtMs       = System.currentTimeMillis();
            return;
        }

        ppMessage = "フレーム書き込み中...";
        Future<?> sentinel = ioExecutor.submit(() -> {});
        while (!sentinel.isDone()) {
            ppProgress = writtenFrames.get() * 10 / total;
            try { Thread.sleep(200); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
        try { sentinel.get(); } catch (Exception ignored) {}

        ppMessage  = "MP4 エンコード中...";
        ppProgress = 10;

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
            ppMessage = "✓ 保存&コピー: snapmatica/videos/" + sessionId + ".mp4";
            System.out.println("[VideoRecorder] Video saved: " + outMp4);
            ClipboardUtil.copyFileAsync(new File(outMp4));
            deleteDir(rawDirIn);
        } else {
            File pngDir = new File(vidDir, sessionId);
            rawDirIn.renameTo(pngDir);
            ppMessage = "ffmpeg なし — PNG 保存: snapmatica/videos/" + sessionId + "/";
            System.out.println("[VideoRecorder] ffmpeg not found; PNGs at " + pngDir);
        }

        postProcessing = false;
        doneAtMs       = System.currentTimeMillis();
    }

    private static String motionBlurFilter() {
        switch (motionBlur) {
            case 1:  return "tmix=frames=2";
            case 2:  return "tmix=frames=4";
            default: return null;
        }
    }

    private static boolean runFfmpeg(File concatFile, String outPath) {
        String[] candidates = {"ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"};
        String vf = motionBlurFilter();
        for (String ff : candidates) {
            try {
                List<String> cmd = new ArrayList<>(List.of(
                        ff, "-y",
                        "-f", "concat", "-safe", "0",
                        "-i", concatFile.getAbsolutePath()));
                if (vf != null) { cmd.add("-vf"); cmd.add(vf); }
                cmd.addAll(List.of(
                        "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p",
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
                dst.setPixel(x, y, src.getPixel(x + offX, y + offY));
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
                        int c = src.getPixel(sx, sy);
                        aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
                        ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
                        n++;
                    }
                dst.setPixel(x, y,
                        ((int)(aa / n) << 24) | ((int)(ba / n) << 16)
                      | ((int)(ga / n) <<  8) |  (int)(ra / n));
            }
        }
        return dst;
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
