package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.client.render.EvfBlurRenderer;
import dev.hitom.photographica.component.VideoSettings;
import dev.hitom.photographica.item.VideoCameraItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

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
 *
 * Pipeline: capture the already-EVF-blurred framebuffer each frame → async PNG
 * (writtenFrames AtomicInteger drives the progress bar) → runFfmpeg() with per-frame
 * duration (correct speed) + optional tmix motion-blur filter.
 * DoF comes from the GPU shader (EvfBlurRenderer) so video bokeh matches the live preview.
 */
public final class VideoRecorder {
    private VideoRecorder() {}

    // ── Constants ─────────────────────────────────────────────────────────────
    public static final int  FPS        = 24;
    public static final int  MAX_FRAMES = 30 * 120;   // 2 minutes @ 30 fps
    public static final float TRIPOD_FOV = 70.0f;

    private static int currentFps = FPS;

    private static final int   FOCUS_DWELL_FRAMES    = 20;
    private static final float FOCUS_TOL             = 0.25f;
    private static final float AF_OMEGA              = 0.16f;
    private static final float AF_ZETA               = 0.50f;
    private static final float AF_VEL_CAP            = 0.30f;
    private static final float AF_SETTLE             = 0.004f;
    private static final long  AF_QUERY_INTERVAL_MS  = 100L;
    private static final float BOKEH_FOCAL_BOOST     = 3.0f;

    // ── Recording state ────────────────────────────────────────────────────────
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
    private static ItemStack       recordingStack;
    private static int             motionBlurSetting = 1;
    private static boolean         prevSmoothCamera  = false;

    private static int recordingArmorStandEntityId = -1;

    // Autofocus spring-damper state
    private static float focusCandidateDepth  = 5.0f;
    private static int   focusCandidateFrames = 0;
    private static float currentFocusDepth    = 5.0f;
    private static float focusTargetDepth     = 5.0f;
    private static float focusVelocity        = 0.0f;
    private static long  lastAfQueryMs        = 0L;

    private static final AtomicInteger writtenFrames = new AtomicInteger(0);

    private static final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "photographica-video-io");
                t.setDaemon(true);
                return t;
            });

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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        sessionId = ts;
        if (new File(mc.runDirectory, "photographica/video_temp/" + sessionId).exists())
            sessionId = ts + "_" + (System.currentTimeMillis() % 1000);

        VideoSettings startSettings = VideoCameraItem.getSettings(stack);
        currentFps        = startSettings.fps();
        motionBlurSetting = startSettings.motionBlur();
        frameCount        = 0;
        virtualFrameCount = 0;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);
        recordingStack = stack;
        writtenFrames.set(0);

        recordingArmorStandEntityId = armorStandEntityId;

        // Probe scene focus immediately so the first frame has correct DoF.
        float initDepth = computeSceneFocusDepth(mc, armorStandEntityId);
        currentFocusDepth    = (initDepth > 0.3f && initDepth < 999.0f) ? initDepth : 5.0f;
        focusTargetDepth     = currentFocusDepth;
        focusVelocity        = 0.0f;
        focusCandidateDepth  = currentFocusDepth;
        focusCandidateFrames = 0;
        lastAfQueryMs        = System.currentTimeMillis();

        rawDir = new File(mc.runDirectory,
                "photographica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            Photographica.LOGGER.error("[VideoRecorder] Could not create raw dir: {}", rawDir);
            return;
        }

        if (armorStandEntityId < 0) {
            prevSmoothCamera = mc.options.smoothCameraEnabled;
            mc.options.smoothCameraEnabled = true;
        }

        recording = true;
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("● REC 開始"), true);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;
        boolean wasTripod = recordingArmorStandEntityId >= 0;
        recordingArmorStandEntityId = -1;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (!wasTripod) mc.options.smoothCameraEnabled = prevSmoothCamera;
        if (mc.player != null)
            mc.player.sendMessage(Text.literal("■ 録画停止 — エンコード中..."), true);

        final List<FrameMeta> metas   = new ArrayList<>(frameMetas);
        final File            rawSnap = rawDir;
        final File            vidDir  = new File(mc.runDirectory, "photographica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = "フレーム書き込み中...";

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vidDir),
                "photographica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    // ── Render-thread hooks ────────────────────────────────────────────────────

    /**
     * Called every frame at WorldRenderEvents.LAST while the scene depth buffer is valid.
     * Copies depth into the GPU texture used by the DoF shader.
     */
    public static void onWorldRenderEnd() {
        if (!recording) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        if (mainFb == null) return;
        int fbW = mainFb.textureWidth, fbH = mainFb.textureHeight;
        if (fbW > 0 && fbH > 0) {
            if (mc.gameRenderer != null) {
                EvfBlurRenderer.updateDepthFar(mc.gameRenderer.getBasicProjectionMatrix(70.0f),
                        Math.max(mc.options.getViewDistance().getValue() * 64f, 256f));
            }
            EvfBlurRenderer.captureDepth(fbW, fbH);
        }
    }

    public static void captureFrameIfRecording() {
        if (!recording) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // AF + DoF run every render frame (not just on capture frames) so the preview
        // stays steady instead of flickering between sharp/blurred each render cycle.
        updateAutofocus(mc);
        applyVideoBlur(mc);

        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (virtualFrameCount >= MAX_FRAMES) { stopRecording(); return; }

        long overdue      = now - nextFrameMs;
        int  slotsConsumed = 1 + (int)(overdue * currentFps / 1000L);
        slotsConsumed = Math.min(slotsConsumed, currentFps);
        float durationSec = (float) slotsConsumed / currentFps;

        virtualFrameCount += slotsConsumed;
        nextFrameMs = recordStartMs + (long)(virtualFrameCount * 1000.0 / currentFps);

        int  idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        frameMetas.add(new FrameMeta(idx, durationSec));
        frameCount++;

        if (virtualFrameCount >= currentFps * 60
                && virtualFrameCount - slotsConsumed < currentFps * 60 && mc.player != null)
            mc.player.sendMessage(Text.literal("⚠ 残り 1:00"), true);

        // The framebuffer is already DoF-blurred. Screenshot it, crop+downsample on the
        // I/O thread so the render thread isn't stalled.
        //? if >=1.21.11 {
        /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> {
            if (raw == null) return;
            ioExecutor.submit(() -> {
                NativeImage cropped = null, frame = null;
                try {
                    cropped = cropTo16x9(raw);
                    frame   = boxDownsample(cropped, 1280);
                    frame.writeTo(outFile);
                } catch (IOException e) {
                    Photographica.LOGGER.warn("[VideoRecorder] Frame write failed: {}", outFile, e);
                } finally {
                    if (frame != null && frame != cropped) frame.close();
                    if (cropped != null && cropped != raw) cropped.close();
                    raw.close();
                    writtenFrames.incrementAndGet();
                }
            });
        });*/
        //?} else {
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            Photographica.LOGGER.warn("[VideoRecorder] Screenshot failed frame {}", idx, e);
            return;
        }
        ioExecutor.submit(() -> {
            NativeImage cropped = null, frame = null;
            try {
                cropped = cropTo16x9(raw);
                frame   = boxDownsample(cropped, 1280);
                frame.writeTo(outFile);
            } catch (IOException e) {
                Photographica.LOGGER.warn("[VideoRecorder] Frame write failed: {}", outFile, e);
            } finally {
                if (frame != null && frame != cropped) frame.close();
                if (cropped != null && cropped != raw) cropped.close();
                raw.close();
                writtenFrames.incrementAndGet();
            }
        });
        //?}
    }

    // ── Autofocus ─────────────────────────────────────────────────────────────

    private static void updateAutofocus(MinecraftClient mc) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastAfQueryMs >= AF_QUERY_INTERVAL_MS) {
            lastAfQueryMs = nowMs;
            float sceneDepth = computeSceneFocusDepth(mc, recordingArmorStandEntityId);
            float centreDepth = Math.max(sceneDepth, 0.3f);
            if (Math.abs(centreDepth - focusCandidateDepth)
                    / Math.max(focusCandidateDepth, 0.1f) <= FOCUS_TOL) {
                focusCandidateFrames++;
                if (focusCandidateFrames >= FOCUS_DWELL_FRAMES)
                    focusTargetDepth = focusCandidateDepth;
            } else {
                focusCandidateDepth  = centreDepth;
                focusCandidateFrames = 0;
            }
        }
        stepFocusSpring();
    }

    private static void stepFocusSpring() {
        float logCur = (float) Math.log(Math.max(0.01f, currentFocusDepth));
        float logTar = (float) Math.log(Math.max(0.01f, focusTargetDepth));
        float disp = logTar - logCur;
        if (Math.abs(disp) < AF_SETTLE && Math.abs(focusVelocity) < AF_SETTLE) {
            currentFocusDepth = focusTargetDepth; focusVelocity = 0.0f; return;
        }
        focusVelocity += AF_OMEGA * AF_OMEGA * disp - 2.0f * AF_ZETA * AF_OMEGA * focusVelocity;
        if (focusVelocity >  AF_VEL_CAP) focusVelocity =  AF_VEL_CAP;
        if (focusVelocity < -AF_VEL_CAP) focusVelocity = -AF_VEL_CAP;
        currentFocusDepth = (float) Math.exp(logCur + focusVelocity);
    }

    private static float computeSceneFocusDepth(MinecraftClient mc, int armorStandId) {
        if (mc.world == null || mc.player == null) return currentFocusDepth;
        net.minecraft.util.math.Vec3d eye = mc.player.getCameraPosVec(1.0f);
        net.minecraft.util.math.Vec3d look = mc.player.getRotationVec(1.0f);
        final double maxDist = 1000.0;
        net.minecraft.util.math.Vec3d end = eye.add(look.multiply(maxDist));
        net.minecraft.util.hit.BlockHitResult blockHit = mc.world.raycast(
                new net.minecraft.world.RaycastContext(eye, end,
                        net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
        double bestDist = (blockHit != null
                && blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS)
                ? eye.distanceTo(blockHit.getPos()) : maxDist;
        final double entityDist = Math.min(bestDist, 60.0);
        net.minecraft.util.math.Vec3d entityEnd = eye.add(look.multiply(entityDist));
        net.minecraft.util.math.Box searchBox =
                mc.player.getBoundingBox().stretch(look.multiply(entityDist)).expand(1.0);
        final int excludeId = armorStandId;
        net.minecraft.util.hit.EntityHitResult entityHit =
                net.minecraft.entity.projectile.ProjectileUtil.raycast(mc.player, eye, entityEnd,
                        searchBox,
                        e -> !e.isSpectator() && e.isAlive()
                                && e != mc.player && e.getId() != excludeId,
                        entityDist * entityDist);
        if (entityHit != null) {
            double eDist = eye.distanceTo(entityHit.getPos());
            if (eDist < bestDist) bestDist = eDist;
        }
        return (float) Math.min(bestDist, 999.0);
    }

    private static void applyVideoBlur(MinecraftClient mc) {
        if (recordingStack == null) return;
        VideoSettings vs = VideoCameraItem.getSettings(recordingStack);
        float aperture = vs.aperture();
        if (aperture >= 8.0f) return;
        float fovDeg = recordingArmorStandEntityId >= 0 ? TRIPOD_FOV : videoFov;
        // The render FOV gives a wide lens (~17 mm) with near-infinite depth of field.
        // Boost the bokeh focal length so the video has pleasing subject separation
        // while keeping the wide framing unchanged.
        float realFocalMm  = (float)(12.0 / Math.tan(Math.toRadians(fovDeg / 2.0)));
        float bokehFocalMm = realFocalMm * BOKEH_FOCAL_BOOST;
        EvfBlurRenderer.applyVideoBlur(currentFocusDepth, aperture, bokehFocalMm,
                EvfBlurRenderer.DOF_SCALE_VIDEO);
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

        // Wait for async I/O executor to finish writing all PNGs.
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
            ppMessage = "✓ 保存: photographica/videos/" + sessionId + ".mp4";
            Photographica.LOGGER.info("[VideoRecorder] Video saved: {}", outMp4);
            deleteDir(rawDirIn);
        } else {
            File pngDir = new File(vidDir, sessionId);
            rawDirIn.renameTo(pngDir);
            ppMessage = "ffmpeg なし — PNG 保存: photographica/videos/" + sessionId + "/";
            Photographica.LOGGER.warn("[VideoRecorder] ffmpeg not found; PNGs at {}", pngDir);
        }

        postProcessing = false;
        doneAtMs       = System.currentTimeMillis();
    }

    // ── ffmpeg ─────────────────────────────────────────────────────────────────

    private static boolean runFfmpeg(File rawDirIn, List<FrameMeta> metas, String outPath) {
        File concatFile = new File(rawDirIn.getParentFile(), "concat.txt");
        try (PrintWriter pw = new PrintWriter(concatFile, java.nio.charset.StandardCharsets.UTF_8)) {
            for (FrameMeta m : metas) {
                String fname = String.format("frame_%04d.png", m.idx());
                pw.println("file '" + fname.replace("'", "\\'") + "'");
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
                    // Light: 75/25 blend — subtle shutter trail. Strong: 50/50 blend.
                    String mxFilter = motionBlurSetting >= 2
                            ? "tmix=frames=2"
                            : "tmix=frames=2:weights='3 1'";
                    cmd.addAll(Arrays.asList("-vf", mxFilter));
                }
                cmd.addAll(Arrays.asList("-c:v", "libx264", "-crf", "18",
                        "-pix_fmt", "yuv420p", outPath));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                // Concat demuxer needs the working dir set to the raw frame directory so
                // relative filenames resolve correctly.
                pb.directory(rawDirIn);
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
                niSet(dst, x, y, niGet(src, x + offX, y + offY));
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
                        int c = niGet(src, sx, sy);
                        aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
                        ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
                        n++;
                    }
                niSet(dst, x, y,
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

    //? if >=1.21.4 {
    /*private static int niGet(NativeImage img, int x, int y) {
        int argb = img.getColorArgb(x, y);
        int a=(argb>>>24)&0xFF; int r=(argb>>>16)&0xFF; int g=(argb>>>8)&0xFF; int b=argb&0xFF;
        return (a<<24)|(b<<16)|(g<<8)|r;
    }
    private static void niSet(NativeImage img, int x, int y, int abgr) {
        int a=(abgr>>>24)&0xFF; int b=(abgr>>>16)&0xFF; int g=(abgr>>>8)&0xFF; int r=abgr&0xFF;
        img.setColorArgb(x, y, (a<<24)|(r<<16)|(g<<8)|b);
    }*/
    //?} else {
    private static int niGet(NativeImage img, int x, int y) { return img.getColor(x, y); }
    private static void niSet(NativeImage img, int x, int y, int abgr) { img.setColor(x, y, abgr); }
    //?}
}
