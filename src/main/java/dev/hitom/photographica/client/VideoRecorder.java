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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side video recording engine for the Camcorder item.
 *
 * Flow:
 *   1. startRecording()  — called from VideoCameraItem right-click
 *   2. onWorldRenderEnd()— called from WorldRenderEvents.LAST; reads centre depth pixel
 *   3. captureFrameIfRecording() — called after GameRenderer.renderWorld(); takes screenshot
 *   4. stopRecording()   — stops capture, starts background post-process thread
 *   5. doPostProcess()   — background: applies effects, encodes with ffmpeg
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ──────────────────────────────────────────────────────────────
    public static final int  FPS        = 24;
    public static final int  MAX_FRAMES = FPS * 120; // 2 minutes
    private static final long FRAME_MS  = 1000L / FPS;

    // ── Recording state ────────────────────────────────────────────────────────
    private static volatile boolean recording       = false;
    private static volatile boolean postProcessing  = false;
    private static volatile int     ppProgress      = 0;          // 0-100
    private static volatile String  ppMessage       = "";
    public  static volatile long    doneAtMs        = 0L;         // 0 = no "done" banner

    private static String          sessionId;
    private static int             frameCount;
    private static long            recordStartMs;
    private static long            nextFrameMs;
    private static File            rawDir;
    private static List<FrameMeta> frameMetas;
    private static ItemStack       recordingStack;

    // Depth sampled by onWorldRenderEnd() and consumed by captureFrameIfRecording()
    private static float   pendingCenterDepth = 5.0f;
    private static boolean pendingDepthReady  = false;

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

    // ── FrameMeta ──────────────────────────────────────────────────────────────
    record FrameMeta(int idx,
                     float velX, float velY, float velZ,
                     float yaw,  float pitch,
                     float aperture, float centerDepth) {}

    // ── Start / Stop ───────────────────────────────────────────────────────────
    public static void toggle(ItemStack stack) {
        if (recording) {
            stopRecording();
        } else if (!postProcessing) {
            startRecording(stack);
        }
    }

    public static void startRecording(ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        sessionId     = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        frameCount    = 0;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);
        recordingStack = stack;

        rawDir = new File(mc.runDirectory,
                "photographica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            Photographica.LOGGER.error("[VideoRecorder] Could not create raw dir: {}", rawDir);
            return;
        }

        recording = true;
        if (mc.player != null) mc.player.sendMessage(
                Text.literal("● REC 開始"), true);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(
                Text.literal("■ 録画停止 — 後処理中..."), true);

        // Snapshot what we captured so the background thread has stable references
        final List<FrameMeta> metas  = new ArrayList<>(frameMetas);
        final File            rawDirSnap = rawDir;
        final VideoSettings   vs     = VideoCameraItem.getSettings(recordingStack);
        final File            vidDir = new File(mc.runDirectory, "photographica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = "後処理中...";

        Thread t = new Thread(() -> doPostProcess(metas, rawDirSnap, vs, vidDir),
                "photographica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    // ── Render-thread hooks ────────────────────────────────────────────────────

    /**
     * Called from WorldRenderEvents.LAST (render thread).
     * Samples the centre depth pixel from the currently-bound framebuffer.
     * Must be called BEFORE captureFrameIfRecording().
     */
    public static void onWorldRenderEnd() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;

        // Read the depth at the viewport centre using the currently bound FBO
        // (same technique as PhotoCapture.readCentreSceneDepth)
        GL11.glGetError(); // clear pending errors
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) { pendingDepthReady = false; return; }

        int cx = vpW / 2;
        int cy = vpH / 2;
        FloatBuffer buf = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(cx, cy, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
        float d   = buf.get(0);
        float ndc = 2.0f * d - 1.0f;
        final float near = 0.05f;
        final float far  = 512.0f;
        pendingCenterDepth = 2.0f * near * far / (far + near - ndc * (far - near));
        pendingDepthReady  = true;
    }

    /**
     * Called from GameRendererMixin after renderWorld() (render thread).
     * Takes a screenshot and queues it for async disk write.
     */
    public static void captureFrameIfRecording() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;

        // Auto-stop at max length
        if (frameCount >= MAX_FRAMES) {
            stopRecording();
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Consume depth
        float depth = pendingDepthReady ? pendingCenterDepth : 5.0f;
        pendingDepthReady = false;

        // Player movement and orientation
        Vec3d vel   = mc.player.getVelocity();
        float yaw   = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        float ap    = VideoCameraItem.getSettings(recordingStack).aperture();

        FrameMeta meta = new FrameMeta(
                frameCount,
                (float) vel.x, (float) vel.y, (float) vel.z,
                yaw, pitch, ap, depth);

        // Take screenshot
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            Photographica.LOGGER.warn("[VideoRecorder] Screenshot failed frame {}", frameCount, e);
            nextFrameMs += FRAME_MS;
            return;
        }

        // Crop to 16:9 and downsample
        NativeImage cropped = cropTo16x9(raw);
        NativeImage frame   = boxDownsample(cropped, 1280);
        if (cropped != raw)  cropped.close();
        raw.close();

        int idx = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));

        frameMetas.add(meta);
        frameCount++;
        nextFrameMs += FRAME_MS;

        // Warn at 1 min
        if (frameCount == FPS * 60 && mc.player != null) {
            mc.player.sendMessage(Text.literal("⚠ 残り 1:00"), true);
        }

        // Submit async write
        ioExecutor.submit(() -> {
            try {
                frame.writeTo(outFile);
            } catch (IOException e) {
                Photographica.LOGGER.warn("[VideoRecorder] Frame write failed: {}", outFile, e);
            } finally {
                frame.close();
            }
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
            FrameMeta meta = metas.get(i);
            File inFile  = new File(rawDirIn, String.format("frame_%04d.png", meta.idx()));
            File outFile = new File(processedDir, String.format("frame_%04d.png", meta.idx()));

            if (!inFile.exists()) {
                ppProgress = (i + 1) * 80 / total;
                continue;
            }

            try (NativeImage img = NativeImage.read(inFile.toPath().toUri().toURL().openStream())) {
                NativeImage processed = applyVideoEffects(img, meta);
                processed.writeTo(outFile);
                processed.close();
            } catch (IOException e) {
                Photographica.LOGGER.warn("[VideoRecorder] Post-process failed frame {}", meta.idx(), e);
            }

            ppProgress = (i + 1) * 80 / total;
            ppMessage  = "エフェクト適用中... " + ppProgress + "%";
        }

        // ffmpeg encode
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
            // Move processed PNGs to videos dir as fallback
            File pngDir = new File(vidDir, sessionId);
            processedDir.renameTo(pngDir);
            ppMessage = "ffmpeg なし — PNG 保存: photographica/videos/" + sessionId + "/";
            Photographica.LOGGER.warn("[VideoRecorder] ffmpeg not found; PNGs at {}", pngDir);
        }

        // Clean up raw frames
        deleteDir(rawDirIn);
        if (ffmpegOk) deleteDir(processedDir);

        postProcessing = false;
        doneAtMs       = System.currentTimeMillis();
    }

    // ── Video effects ──────────────────────────────────────────────────────────

    /**
     * Applies exposure, vignette, and directional motion blur to a single frame.
     * Returns a NEW NativeImage; src is not modified or closed.
     */
    private static NativeImage applyVideoEffects(NativeImage src, FrameMeta meta) {
        int w  = src.getWidth();
        int h  = src.getHeight();
        float cx = w * 0.5f;
        float cy = h * 0.5f;

        // Exposure: auto ISO 400, shutter 1/48 (≈ 180° shutter rule at 24fps)
        // EV = log2(N² / t) - log2(ISO/100)
        // For aperture N, shutter t=1/48, ISO 400:
        //   EV_scene ≈ 12 (typical daylight)
        //   EV_camera = log2(N² * 48) - log2(4)  = log2(N²*48) - 2
        //   exposureMult = 2^(EV_scene - EV_camera) scaled to neutral at f/5.6
        float n   = meta.aperture();
        double evCamera = Math.log(n * n * 48.0) / Math.log(2.0) - 2.0;
        double evRef    = Math.log(5.6 * 5.6 * 48.0) / Math.log(2.0) - 2.0;
        float  expMult  = (float) Math.pow(2.0, evRef - evCamera);
        // clamp to sane range
        expMult = Math.max(0.1f, Math.min(8.0f, expMult));

        // Vignette strength (borrowed from PhotoCapture.apertureToVignette logic)
        float vig = apertureToVignette(n);

        // Directional motion blur in screen space
        // Project world-space velocity onto screen-right and screen-up axes.
        // In Minecraft, player facing direction: forward = (-sin(yaw), 0, cos(yaw))
        //                                        right   = ( cos(yaw), 0, sin(yaw))
        float yawRad   = (float) Math.toRadians(meta.yaw());
        float screenVX = (float)(  Math.cos(yawRad) * meta.velX()
                                 + Math.sin(yawRad) * meta.velZ());
        float screenVY = meta.velY();  // simplified: ignore pitch for vertical

        // Scale to pixels: 1 block/tick = 20 blocks/s; at 24fps each frame = 1/24 s
        // Approximate: 1 block moves ~32 pixels at typical Minecraft FOV
        float SCALE = 32.0f;
        float bDX   = -screenVX * SCALE;   // negative: moving right → blur to left
        float bDY   = -screenVY * SCALE;

        int blurLen = (int) Math.round(Math.sqrt(bDX * bDX + bDY * bDY));
        blurLen = Math.min(blurLen, w / 10);

        // Pass 1: exposure + vignette
        NativeImage pass1 = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c  = src.getColor(x, y);
                int a  = (c >>> 24) & 0xFF;
                int b  = (c >>> 16) & 0xFF;
                int g  = (c >>>  8) & 0xFF;
                int r  =  c         & 0xFF;

                r = applyExposure(r, expMult);
                g = applyExposure(g, expMult);
                b = applyExposure(b, expMult);

                float dx = (x - cx) / cx;
                float dy = (y - cy) / cy;
                float vf = Math.max(0f, 1f - vig * (dx * dx + dy * dy) * 0.5f);
                r = clamp((int)(r * vf));
                g = clamp((int)(g * vf));
                b = clamp((int)(b * vf));

                pass1.setColor(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        // Pass 2: directional motion blur (only if significant movement)
        if (blurLen < 2) return pass1;

        // Normalise direction
        float len = (float) Math.sqrt(bDX * bDX + bDY * bDY);
        float ndx = bDX / len;
        float ndy = bDY / len;

        NativeImage pass2 = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long ra = 0, ga = 0, ba = 0, aa = 0;
                int  n2  = 0;
                for (int s = 0; s <= blurLen; s++) {
                    int sx = Math.max(0, Math.min(w - 1, x + (int)(s * ndx)));
                    int sy = Math.max(0, Math.min(h - 1, y + (int)(s * ndy)));
                    int c  = pass1.getColor(sx, sy);
                    aa += (c >>> 24) & 0xFF;
                    ba += (c >>> 16) & 0xFF;
                    ga += (c >>>  8) & 0xFF;
                    ra +=  c         & 0xFF;
                    n2++;
                }
                pass2.setColor(x, y,
                        (((int)(aa / n2)) << 24) | (((int)(ba / n2)) << 16)
                        | (((int)(ga / n2)) << 8)  |  (int)(ra / n2));
            }
        }
        pass1.close();
        return pass2;
    }

    // ── ffmpeg ─────────────────────────────────────────────────────────────────

    private static boolean runFfmpeg(File processedDir, String outPath) {
        String[] candidates = {"ffmpeg", "/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg"};
        for (String ff : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        ff, "-y",
                        "-framerate", String.valueOf(FPS),
                        "-i", new File(processedDir, "frame_%04d.png").getAbsolutePath(),
                        "-c:v", "libx264",
                        "-crf",  "18",
                        "-pix_fmt", "yuv420p",
                        outPath);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
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

    // ── Image utilities ────────────────────────────────────────────────────────

    /** Crops a NativeImage to 16:9 aspect ratio (centred). */
    private static NativeImage cropTo16x9(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        float aspect = 16f / 9f;
        int targetW, targetH;
        if ((float) w / h > aspect) {
            targetH = h;
            targetW = Math.round(h * aspect);
        } else {
            targetW = w;
            targetH = Math.round(w / aspect);
        }
        if (targetW == w && targetH == h) return src;
        int offX = (w - targetW) / 2;
        int offY = (h - targetH) / 2;
        NativeImage dst = new NativeImage(targetW, targetH, false);
        for (int y = 0; y < targetH; y++) {
            for (int x = 0; x < targetW; x++) {
                dst.setColor(x, y, src.getColor(x + offX, y + offY));
            }
        }
        return dst;
    }

    /** Box downsample to maxWidth, preserving aspect ratio. */
    private static NativeImage boxDownsample(NativeImage src, int maxWidth) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw <= maxWidth) return src;
        int dw = maxWidth;
        int dh = Math.max(1, Math.round((float) sh * dw / sw));
        NativeImage dst = new NativeImage(dw, dh, false);
        float xS = (float) sw / dw;
        float yS = (float) sh / dh;
        for (int y = 0; y < dh; y++) {
            int sy0 = (int) Math.floor(y * yS);
            int sy1 = Math.min(sh, (int) Math.ceil((y + 1) * yS));
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int x = 0; x < dw; x++) {
                int sx0 = (int) Math.floor(x * xS);
                int sx1 = Math.min(sw, (int) Math.ceil((x + 1) * xS));
                if (sx1 <= sx0) sx1 = sx0 + 1;
                long ra = 0, ga = 0, ba = 0, aa = 0;
                int n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = src.getColor(sx, sy);
                        aa += (c >>> 24) & 0xFF;
                        ba += (c >>> 16) & 0xFF;
                        ga += (c >>>  8) & 0xFF;
                        ra +=  c         & 0xFF;
                        n++;
                    }
                }
                dst.setColor(x, y,
                        (((int)(aa / n)) << 24) | (((int)(ba / n)) << 16)
                        | (((int)(ga / n)) << 8) | (int)(ra / n));
            }
        }
        return dst;
    }

    private static int applyExposure(int v, float mult) {
        float f = v * mult;
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
