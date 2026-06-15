package dev.shunti.snapmatica.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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
        frameCount        = 0;
        virtualFrameCount = 0;
        writtenFrames.set(0);
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);

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
     * Called from GameRendererMixin after renderLevel() (after shaders have composited).
     * Applies preview DoF blur to the whole framebuffer, then screenshots the frame.
     */
    public static void captureFrameIfRecording() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (virtualFrameCount >= MAX_FRAMES) { stopRecording(); return; }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

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

        // Bake the same GPU DoF the viewfinder uses into the full framebuffer.
        applyPreviewBlur(mc);

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
     * Applies full-frame GPU depth-of-field blur (same as the live EVF preview).
     */
    private static void applyPreviewBlur(Minecraft mc) {
        if (SnapmaticaClient.lensType == 0) return;
        if (SnapmaticaClient.aperture >= 8.0f) return;
        double guiScale = mc.getWindow().getGuiScale();
        int guiW = (int)(mc.getWindow().getWidth()  / guiScale);
        int guiH = (int)(mc.getWindow().getHeight() / guiScale);
        EvfBlurRenderer.renderBlur(0, 0, guiW, guiH,
                SnapmaticaClient.focusDistance, SnapmaticaClient.aperture,
                SnapmaticaClient.focalLengthMm);
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
