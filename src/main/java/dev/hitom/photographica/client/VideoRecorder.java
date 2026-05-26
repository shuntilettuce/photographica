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
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side video recording engine for the Camcorder item.
 *
 * Flow:
 *   1. startRecording()           – called from VideoCameraItem right-click
 *   2. onWorldRenderEnd()         – WorldRenderEvents.LAST; reads full viewport depth
 *                                   and downsamples to DEP_W×DEP_H grid
 *   3. captureFrameIfRecording()  – after GameRenderer.renderWorld(); takes screenshot
 *   4. stopRecording()            – stops capture, starts background post-process thread
 *   5. doPostProcess()            – background: applies effects, encodes with ffmpeg
 *
 * Depth-based motion blur:
 *   The full viewport depth buffer is read once per frame (one glReadPixels call),
 *   downsampled to a 32×18 float grid stored in FrameMeta. During post-processing,
 *   each output pixel samples its depth via bilinear interpolation and scales its
 *   blur radius by (refDepth / pixDepth) — near objects blur more, far ones less.
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ──────────────────────────────────────────────────────────────
    public static final int  FPS        = 24;
    public static final int  MAX_FRAMES = FPS * 120; // 2 minutes
    private static final long FRAME_MS  = 1000L / FPS;

    /** Depth grid dimensions (32×18 = same 16:9 ratio as 1280×720, at 1/40 scale). */
    private static final int DEP_W = 32;
    private static final int DEP_H = 18;

    private static final float NEAR = 0.05f;
    private static final float FAR  = 512.0f;

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

    // Depth grid sampled by onWorldRenderEnd(), consumed by captureFrameIfRecording()
    // Allocated lazily and reused across frames to avoid GC pressure.
    private static float[]      pendingDepthGrid  = null;
    private static boolean      pendingDepthReady = false;
    private static FloatBuffer  depthReadBuf      = null; // reusable GL buffer
    private static int          depthReadBufCap   = 0;

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
    /**
     * Per-frame metadata captured on the render thread.
     * depthGrid is a DEP_W×DEP_H array of linear-depth values (metres from camera).
     * Row 0 = bottom of viewport (OpenGL convention); row (DEP_H-1) = top.
     */
    record FrameMeta(int idx,
                     float velX, float velY, float velZ,
                     float yaw,  float pitch,
                     float aperture,
                     float[] depthGrid) {}

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

        // Use datetime as session ID so files sort naturally
        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        // Guard against two recordings starting in the same second
        sessionId = ts;
        File candidateDir = new File(mc.runDirectory,
                "photographica/video_temp/" + sessionId);
        if (candidateDir.exists()) {
            sessionId = ts + "_" + (System.currentTimeMillis() % 1000);
        }

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

        final List<FrameMeta> metas     = new ArrayList<>(frameMetas);
        final File            rawDirSnap = rawDir;
        final VideoSettings   vs        = VideoCameraItem.getSettings(recordingStack);
        final File            vidDir    = new File(mc.runDirectory, "photographica/videos");

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
     * Reads the full viewport depth buffer with one glReadPixels call,
     * then downsamples it to a DEP_W×DEP_H float grid.
     * Must be called BEFORE captureFrameIfRecording().
     */
    public static void onWorldRenderEnd() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;

        GL11.glGetError(); // clear pending errors
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) { pendingDepthReady = false; return; }

        // Ensure read buffer is large enough; reallocate only when viewport grows.
        int needed = vpW * vpH;
        if (depthReadBuf == null || depthReadBufCap < needed) {
            depthReadBuf    = BufferUtils.createFloatBuffer(needed);
            depthReadBufCap = needed;
        }
        depthReadBuf.clear();

        // One glReadPixels for the entire viewport depth — single GPU-CPU sync point.
        GL11.glReadPixels(0, 0, vpW, vpH, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthReadBuf);

        // Downsample to DEP_W×DEP_H grid on CPU.
        pendingDepthGrid  = downsampleDepth(depthReadBuf, vpW, vpH, DEP_W, DEP_H);
        pendingDepthReady = true;
    }

    /**
     * Called from GameRendererMixin after renderWorld() (render thread).
     * Takes a screenshot and queues it for async disk write.
     */
    public static void captureFrameIfRecording() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;

        if (frameCount >= MAX_FRAMES) {
            stopRecording();
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Consume pending depth grid (or use a flat fallback if not ready)
        float[] depthGrid;
        if (pendingDepthReady && pendingDepthGrid != null) {
            depthGrid = pendingDepthGrid;
            pendingDepthGrid  = null;
            pendingDepthReady = false;
        } else {
            depthGrid = flatDepthGrid(5.0f);
        }

        Vec3d vel   = mc.player.getVelocity();
        float yaw   = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        float ap    = VideoCameraItem.getSettings(recordingStack).aperture();

        FrameMeta meta = new FrameMeta(
                frameCount,
                (float) vel.x, (float) vel.y, (float) vel.z,
                yaw, pitch, ap, depthGrid);

        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            Photographica.LOGGER.warn("[VideoRecorder] Screenshot failed frame {}", frameCount, e);
            nextFrameMs += FRAME_MS;
            return;
        }

        NativeImage cropped = cropTo16x9(raw);
        NativeImage frame   = boxDownsample(cropped, 1280);
        if (cropped != raw)  cropped.close();
        raw.close();

        int  idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));

        frameMetas.add(meta);
        frameCount++;
        nextFrameMs += FRAME_MS;

        if (frameCount == FPS * 60 && mc.player != null) {
            mc.player.sendMessage(Text.literal("⚠ 残り 1:00"), true);
        }

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
            FrameMeta meta   = metas.get(i);
            File inFile  = new File(rawDirIn,    String.format("frame_%04d.png", meta.idx()));
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
     * Applies exposure, vignette, and depth-scaled directional motion blur.
     *
     * Blur model:
     *   baseBlurLen  = |screenSpaceVelocity| × SCALE  (pixels)
     *   pixBlurLen   = baseBlurLen × clamp(refDepth / pixDepth, 0, 4)
     *   → near pixels (small depth) get multiplied blur
     *   → far  pixels (large depth) get divided   blur → near zero
     *
     * Depth is looked up from FrameMeta.depthGrid (32×18) via bilinear interpolation.
     * refDepth = centre depth pixel (the "focus" reference).
     */
    private static NativeImage applyVideoEffects(NativeImage src, FrameMeta meta) {
        int w  = src.getWidth();
        int h  = src.getHeight();
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        // ── Exposure ──
        float n   = meta.aperture();
        double evCam = Math.log(n * n * 48.0) / Math.log(2.0) - 2.0;
        double evRef = Math.log(5.6 * 5.6 * 48.0) / Math.log(2.0) - 2.0;
        float  expMult = Math.max(0.1f, Math.min(8.0f, (float) Math.pow(2.0, evRef - evCam)));

        // ── Vignette ──
        float vig = apertureToVignette(n);

        // ── Screen-space velocity → blur direction & base length ──
        float yawRad  = (float) Math.toRadians(meta.yaw());
        // Project world-space horizontal velocity onto screen-right axis
        float screenVX = (float)( Math.cos(yawRad) * meta.velX()
                                + Math.sin(yawRad) * meta.velZ());
        // Pitch only affects vertical to second order; use raw Y as screen-up
        float screenVY = meta.velY();

        // 1 block/tick × 20 ticks/s ÷ 24 fps × ~32 px/block at default FOV
        final float SCALE = (20.0f / FPS) * 32.0f;
        float bDX = -screenVX * SCALE;  // negative: moving right → trail to left
        float bDY = -screenVY * SCALE;

        float baseBlur = (float) Math.sqrt(bDX * bDX + bDY * bDY);
        // Hard cap: never blur more than 1/8 of frame width
        float maxBlur = w / 8.0f;
        baseBlur = Math.min(baseBlur, maxBlur);

        // Normalised blur direction
        float ndx = 0f, ndy = 0f;
        if (baseBlur >= 0.5f) {
            float inv = 1.0f / baseBlur;
            ndx = bDX * inv;
            ndy = bDY * inv;
        }

        // ── Reference depth = centre of depth grid ──
        float[] depthGrid = meta.depthGrid();
        float refDepth = (depthGrid != null)
                ? depthGrid[(DEP_H / 2) * DEP_W + DEP_W / 2]
                : 5.0f;
        refDepth = Math.max(refDepth, 0.5f);

        // ── Pass 1: exposure + vignette ──
        NativeImage pass1 = new NativeImage(w, h, false);
        for (int py = 0; py < h; py++) {
            float dy = (py - halfH) / halfH;
            for (int px = 0; px < w; px++) {
                int c  = src.getColor(px, py);
                int a  = (c >>> 24) & 0xFF;
                int b  = (c >>> 16) & 0xFF;
                int g  = (c >>>  8) & 0xFF;
                int r  =  c         & 0xFF;

                r = applyExposure(r, expMult);
                g = applyExposure(g, expMult);
                b = applyExposure(b, expMult);

                float dx = (px - halfW) / halfW;
                float vf = Math.max(0f, 1f - vig * (dx * dx + dy * dy) * 0.5f);
                r = clamp((int)(r * vf));
                g = clamp((int)(g * vf));
                b = clamp((int)(b * vf));

                pass1.setColor(px, py, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        if (baseBlur < 0.5f) return pass1;  // no movement — skip blur pass

        // ── Pass 2: depth-scaled directional motion blur ──
        NativeImage pass2 = new NativeImage(w, h, false);

        for (int py = 0; py < h; py++) {
            // Normalised texture coordinate (0 = top in image space, but depth grid
            // rows go bottom-up in GL convention — flip fy for the grid lookup)
            float fy = 1.0f - (py + 0.5f) / h;   // flip Y: image top → depth grid bottom
            float fx = 0f;

            for (int px = 0; px < w; px++) {
                fx = (px + 0.5f) / w;

                // Look up linear depth at this pixel via bilinear interpolation
                float pixDepth = (depthGrid != null)
                        ? bilinearDepth(depthGrid, DEP_W, DEP_H, fx, fy)
                        : refDepth;
                pixDepth = Math.max(pixDepth, 0.2f);

                // Depth scale: near → large blur, far → small blur
                // depthScale > 1 for near objects, < 1 for far objects
                float depthScale = Math.min(refDepth / pixDepth, 4.0f);
                int blurLen = (int)(baseBlur * depthScale);
                blurLen = Math.min(blurLen, (int) maxBlur);

                if (blurLen < 1) {
                    pass2.setColor(px, py, pass1.getColor(px, py));
                    continue;
                }

                // 1-D box blur along (ndx, ndy) direction, length blurLen
                long ra = 0, ga = 0, ba = 0, aa = 0;
                int  samples = blurLen + 1;
                for (int s = 0; s <= blurLen; s++) {
                    int sx = Math.max(0, Math.min(w - 1, px + (int)(s * ndx)));
                    int sy = Math.max(0, Math.min(h - 1, py + (int)(s * ndy)));
                    int c  = pass1.getColor(sx, sy);
                    aa += (c >>> 24) & 0xFF;
                    ba += (c >>> 16) & 0xFF;
                    ga += (c >>>  8) & 0xFF;
                    ra +=  c         & 0xFF;
                }
                pass2.setColor(px, py,
                        ((int)(aa / samples) << 24) | ((int)(ba / samples) << 16)
                      | ((int)(ga / samples) <<  8) |  (int)(ra / samples));
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
                // Discard stdout/stderr so the OS pipe buffer never fills up.
                // Without this, proc.waitFor() deadlocks once the 64KB pipe buffer
                // fills with ffmpeg's progress output.
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process proc = pb.start();

                // Poll isAlive() so we can animate ppProgress 80→98 while ffmpeg runs.
                long startMs = System.currentTimeMillis();
                while (proc.isAlive()) {
                    try { Thread.sleep(200); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                    long elapsed = System.currentTimeMillis() - startMs;
                    ppProgress = 80 + (int) Math.min(18, elapsed / 1000); // +1%/s, cap 98
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
     * Downsamples a raw GL depth buffer (non-linear, [0,1]) to a dW×dH grid
     * of linear depths in metres. Averages raw depth values within each cell
     * before linearising (more accurate than linearising then averaging).
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

                float rawD  = (float)(sum / cnt);
                float ndc   = 2.0f * rawD - 1.0f;
                // Linearise: depth in metres from camera
                float linD  = 2.0f * NEAR * FAR / (FAR + NEAR - ndc * (FAR - NEAR));
                grid[dy * dW + dx] = linD;
            }
        }
        return grid;
    }

    /**
     * Bilinear interpolation over a dW×dH depth grid at normalised coordinates
     * (fx, fy) ∈ [0, 1]. Row 0 = bottom (GL convention).
     */
    private static float bilinearDepth(float[] grid, int dW, int dH,
                                       float fx, float fy) {
        float gx = fx * (dW - 1);
        float gy = fy * (dH - 1);
        int   x0 = (int) gx,  y0 = (int) gy;
        int   x1 = Math.min(x0 + 1, dW - 1);
        int   y1 = Math.min(y0 + 1, dH - 1);
        float tx = gx - x0,   ty = gy - y0;

        float d00 = grid[y0 * dW + x0], d10 = grid[y0 * dW + x1];
        float d01 = grid[y1 * dW + x0], d11 = grid[y1 * dW + x1];
        return (d00 * (1 - tx) + d10 * tx) * (1 - ty)
             + (d01 * (1 - tx) + d11 * tx) *      ty;
    }

    /** Returns a flat DEP_W×DEP_H depth grid filled with a single value. */
    private static float[] flatDepthGrid(float depth) {
        float[] g = new float[DEP_W * DEP_H];
        java.util.Arrays.fill(g, depth);
        return g;
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
                int  n  = 0;
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
                        ((int)(aa / n) << 24) | ((int)(ba / n) << 16)
                      | ((int)(ga / n) <<  8) |  (int)(ra / n));
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
