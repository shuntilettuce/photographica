package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.client.render.EvfBlurRenderer;
import dev.hitom.photographica.component.VideoSettings;
import dev.hitom.photographica.item.VideoCameraItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side video recording engine for the Camcorder item.
 * DoF is applied via the GPU (EvfBlurRenderer) before each frame capture;
 * motion blur uses ffmpeg tmix (temporal frame blending).
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final int FPS        = 24;
    public static final int MAX_FRAMES = 30 * 120;   // 2 minutes @ 30 fps

    private static int currentFps = FPS;

    private static final int   FOCUS_DWELL_FRAMES = 20;
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

    private static int     recordingArmorStandEntityId = -1;
    private static int     motionBlurSetting           = 1;
    private static boolean prevSmoothCamera            = false;

    public static final float TRIPOD_FOV = 70.0f;

    // Autofocus dwell state
    private static float focusCandidateDepth  = 5.0f;
    private static int   focusCandidateFrames = 0;
    private static float currentFocusDepth    = 5.0f;

    // Frame write counter for post-process progress tracking.
    private static final AtomicInteger writtenFrames = new AtomicInteger(0);

    private static final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "photographica-video-io");
                t.setDaemon(true);
                return t;
            });

    /** Vertical FOV (degrees) used while holding a video camera. */
    public static float videoFov = 70.0f;

    // ── Public accessors ───────────────────────────────────────────────────────
    public static boolean   isRecording()                    { return recording; }
    public static boolean   isPostProcessing()               { return postProcessing; }
    public static int       getPpProgress()                  { return ppProgress; }
    public static String    getPpMessage()                   { return ppMessage; }
    public static int       getRecordingArmorStandEntityId() { return recordingArmorStandEntityId; }
    public static ItemStack getRecordingStack()              { return recordingStack; }
    public static long      getDoneAtMs()                    { return doneAtMs; }
    public static int       getFrameCount()                  { return frameCount; }
    public static long      getRecordStartMs()               { return recordStartMs; }
    public static int       getCurrentFps()                  { return currentFps; }

    public static boolean isTripodRecording() {
        return recording && recordingArmorStandEntityId >= 0;
    }

    public static boolean willCaptureThisFrame() {
        return recording
                && System.currentTimeMillis() >= nextFrameMs
                && frameCount < MAX_FRAMES;
    }

    // ── FrameMeta ──────────────────────────────────────────────────────────────
    record FrameMeta(int idx, float durationSec) {}

    // ── Start / Stop ───────────────────────────────────────────────────────────
    public static void toggle(ItemStack stack) {
        if (recording) stopRecording();
        else if (!postProcessing) startRecording(stack, -1);
    }

    public static void startRecording(ItemStack stack) {
        startRecording(stack, -1);
    }

    public static void startRecording(ItemStack stack, int armorStandEntityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        sessionId = ts;
        if (new File(mc.gameDirectory, "photographica/video_temp/" + sessionId).exists()) {
            sessionId = ts + "_" + (System.currentTimeMillis() % 1000);
        }

        VideoSettings startSettings = VideoCameraItem.getSettings(stack);
        currentFps        = startSettings.fps();
        motionBlurSetting = startSettings.motionBlur();
        frameCount    = 0;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);
        recordingStack = stack;
        writtenFrames.set(0);

        currentFocusDepth    = 5.0f;
        focusCandidateDepth  = 5.0f;
        focusCandidateFrames = 0;

        rawDir = new File(mc.gameDirectory,
                "photographica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            Photographica.LOGGER.error("[VideoRecorder] Could not create raw dir: {}", rawDir);
            return;
        }

        recordingArmorStandEntityId = armorStandEntityId;

        // Enable cinematic camera for steadier handheld panning; skip for tripod (stand is fixed).
        if (armorStandEntityId < 0) {
            prevSmoothCamera = mc.options.smoothCamera;
            mc.options.smoothCamera = true;
        }

        recording = true;
        if (mc.player != null)
            mc.gui.setOverlayMessage(Component.literal("● REC 開始"), false);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;
        boolean wasTripod = recordingArmorStandEntityId >= 0;
        recordingArmorStandEntityId = -1;
        Minecraft mc = Minecraft.getInstance();
        if (!wasTripod) mc.options.smoothCamera = prevSmoothCamera;
        if (mc.player != null)
            mc.gui.setOverlayMessage(Component.literal("■ 録画停止 — エンコード中..."), false);

        final List<FrameMeta> metas  = new ArrayList<>(frameMetas);
        final File            rawSnap = rawDir;
        final File            vidDir  = new File(mc.gameDirectory, "photographica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = "フレーム書き込み中...";

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vidDir),
                "photographica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    // ── Render-thread hook ─────────────────────────────────────────────────────

    public static void captureFrameIfRecording() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (frameCount >= MAX_FRAMES) { stopRecording(); return; }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Dwell-time autofocus using the centre scene depth from PhotoCapture.
        float centreDepth = Math.max(PhotoCapture.lastSceneDepthBlocks, 0.3f);
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

        int idx = frameCount;
        FrameMeta meta = new FrameMeta(idx, 1.0f / currentFps);
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        frameMetas.add(meta);
        frameCount++;
        nextFrameMs = recordStartMs + (long)(frameCount * 1000.0 / currentFps);

        if (frameCount == currentFps * 60 && mc.player != null)
            mc.gui.setOverlayMessage(Component.literal("⚠ 残り 1:00"), false);

        // Bake GPU DoF into the framebuffer so the recorded frame has the same blur
        // as the viewfinder.  captureDepth() has already run this frame in PhotoCapture.
        applyVideoBlur(mc);

        Screenshot.takeScreenshot(mc.getMainRenderTarget(), raw -> {
            if (raw == null) return;
            NativeImage cropped = cropTo16x9(raw);
            NativeImage frame   = boxDownsample(cropped, 1280);
            if (cropped != raw) cropped.close();
            raw.close();
            ioExecutor.submit(() -> {
                try { frame.writeToFile(outFile.toPath()); }
                catch (IOException e) {
                    Photographica.LOGGER.warn("[VideoRecorder] Frame write failed: {}", outFile, e);
                } finally {
                    frame.close();
                    writtenFrames.incrementAndGet();
                }
            });
        });
    }

    private static void applyVideoBlur(Minecraft mc) {
        if (recordingStack == null) return;
        VideoSettings vs = VideoCameraItem.getSettings(recordingStack);
        float aperture = vs.aperture();
        if (aperture >= 8.0f) return;
        float fovDeg = recordingArmorStandEntityId >= 0 ? TRIPOD_FOV : videoFov;
        float focalLenMm = (float)(12.0 / Math.tan(Math.toRadians(fovDeg / 2.0)));
        EvfBlurRenderer.applyVideoBlur(currentFocusDepth, aperture, focalLenMm);
    }

    // ── Post-processing ────────────────────────────────────────────────────────

    private static void doPostProcess(List<FrameMeta> metas, File rawDirIn, File vidDir) {
        int total = metas.size();
        if (total == 0) {
            postProcessing = false;
            ppMessage = "フレームなし";
            doneAtMs  = System.currentTimeMillis();
            return;
        }

        // Wait (up to 2 min) for the async I/O executor to finish writing all frames.
        ppMessage = "フレーム書き込み中...";
        long deadline = System.currentTimeMillis() + 120_000L;
        while (writtenFrames.get() < total && System.currentTimeMillis() < deadline) {
            ppProgress = writtenFrames.get() * 80 / total;
            try { Thread.sleep(100); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }

        ppProgress = 80;
        ppMessage  = "MP4 エンコード中...";

        if (!vidDir.exists()) vidDir.mkdirs();
        String outMp4 = new File(vidDir, sessionId + ".mp4").getAbsolutePath();
        boolean ffmpegOk = runFfmpeg(rawDirIn, metas, outMp4);

        ppProgress = 100;
        if (ffmpegOk) {
            ppMessage = "✓ 保存&コピー: photographica/videos/" + sessionId + ".mp4";
            Photographica.LOGGER.info("[VideoRecorder] Video saved: {}", outMp4);
            ClipboardUtil.copyFileAsync(new File(outMp4));
        } else {
            File pngDir = new File(vidDir, sessionId);
            rawDirIn.renameTo(pngDir);
            ppMessage = "ffmpeg なし — PNG 保存: photographica/videos/" + sessionId + "/";
            Photographica.LOGGER.warn("[VideoRecorder] ffmpeg not found; PNGs at {}", pngDir);
        }

        if (ffmpegOk) deleteDir(rawDirIn);

        postProcessing = false;
        doneAtMs       = System.currentTimeMillis();
    }

    // ── ffmpeg ─────────────────────────────────────────────────────────────────

    private static boolean runFfmpeg(File rawDirIn, List<FrameMeta> metas, String outPath) {
        // Write a concat demuxer file so each frame keeps its exact duration.
        File concatFile = new File(rawDirIn.getParentFile(), "concat.txt");
        try (PrintWriter pw = new PrintWriter(concatFile)) {
            for (FrameMeta m : metas) {
                String path = new File(rawDirIn, String.format("frame_%04d.png", m.idx()))
                        .getAbsolutePath().replace("\\", "/");
                pw.printf("file '%s'%n", path.replace("'", "'\\''"));
                pw.printf("duration %.6f%n", m.durationSec());
            }
        } catch (IOException e) {
            Photographica.LOGGER.error("[VideoRecorder] concat file write failed", e);
            return false;
        }

        String[] candidates = {"ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"};
        for (String ff : candidates) {
            try {
                List<String> cmd = new ArrayList<>(Arrays.asList(
                        ff, "-y", "-f", "concat", "-safe", "0",
                        "-i", concatFile.getAbsolutePath()));
                if (motionBlurSetting > 0) {
                    int blendFrames = motionBlurSetting >= 2 ? 5 : 3;
                    cmd.addAll(Arrays.asList("-vf", "tmix=frames=" + blendFrames));
                }
                cmd.addAll(Arrays.asList("-c:v", "libx264", "-crf", "18",
                        "-pix_fmt", "yuv420p", outPath));

                ProcessBuilder pb = new ProcessBuilder(cmd);
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
            } catch (IOException | InterruptedException ignored) {}
        }
        return false;
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
                setPixelAbgr(dst, x, y, getPixelAbgr(src, x + offX, y + offY));
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
                        int c = getPixelAbgr(src, sx, sy);
                        aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
                        ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
                        n++;
                    }
                setPixelAbgr(dst, x, y,
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

    private static int getPixelAbgr(NativeImage img, int x, int y) {
        int argb = img.getPixel(x, y);
        int a=(argb>>>24)&0xFF; int r=(argb>>>16)&0xFF; int g=(argb>>>8)&0xFF; int b=argb&0xFF;
        return (a<<24)|(b<<16)|(g<<8)|r;
    }
    private static void setPixelAbgr(NativeImage img, int x, int y, int abgr) {
        int a=(abgr>>>24)&0xFF; int b=(abgr>>>16)&0xFF; int g=(abgr>>>8)&0xFF; int r=abgr&0xFF;
        img.setPixel(x, y, (a<<24)|(r<<16)|(g<<8)|b);
    }
}
