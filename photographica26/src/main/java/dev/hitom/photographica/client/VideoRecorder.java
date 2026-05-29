package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.VideoSettings;
import dev.hitom.photographica.item.VideoCameraItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.Screenshot;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
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

    private static final float CoC_K    = 100.0f;
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

    private static int recordingArmorStandEntityId = -1;
    private static boolean prevSmoothCamera = false;

    // ── Autofocus state ────────────────────────────────────────────────────────
    private static float focusCandidateDepth  = 5.0f;
    private static int   focusCandidateFrames = 0;
    private static float currentFocusDepth    = 5.0f;

    // ── Angular velocity tracking (for motion blur) ────────────────────────────
    private static float   prevFrameYaw      = 0f;
    private static float   prevFramePitch    = 0f;
    private static boolean prevFrameValid    = false;
    private static float   smoothedDeltaYaw  = 0f;
    private static float   smoothedDeltaPitch = 0f;

    // ── Depth-buffer read ──────────────────────────────────────────────────────
    private static float[]     pendingDepthGrid  = null;
    private static boolean     pendingDepthReady = false;
    private static FloatBuffer depthReadBuf      = null;
    private static int         depthReadBufCap   = 0;
    private static int pendingVpW = 0, pendingVpH = 0;
    private static int pendingCropOffX = 0, pendingCropOffY = 0;

    // ── DoF temp arrays (reused across frames in the background thread) ────────
    private static int[] dofTempR, dofTempG, dofTempB, dofTempA;
    private static int   dofTempCap = 0;

    private static float smoothedExpMult = 1.0f;

    private static final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "photographica-video-io");
                t.setDaemon(true);
                return t;
            });

    // ── Public accessors ───────────────────────────────────────────────────────
    public static boolean isRecording()                  { return recording; }
    public static boolean isPostProcessing()             { return postProcessing; }
    public static int     getPpProgress()                { return ppProgress; }
    public static String  getPpMessage()                 { return ppMessage; }
    public static int     getRecordingArmorStandEntityId() { return recordingArmorStandEntityId; }
    public static long    getDoneAtMs()      { return doneAtMs; }
    public static int     getFrameCount()    { return frameCount; }
    public static long    getRecordStartMs() { return recordStartMs; }
    public static int     getCurrentFps()    { return currentFps; }

    public static float videoFov = 70.0f;

    public static boolean willCaptureThisFrame() {
        return recording
                && System.currentTimeMillis() >= nextFrameMs
                && frameCount < MAX_FRAMES;
    }

    // ── FrameMeta ──────────────────────────────────────────────────────────────
    record FrameMeta(int   idx,
                     float velX,      float velY,       float velZ,
                     float yaw,       float pitch,
                     float deltaYaw,  float deltaPitch,
                     float fovDeg,
                     float aperture,
                     float focusDepth,
                     float[] depthGrid,
                     int vpW, int vpH,
                     int cropOffX, int cropOffY) {}

    // ── Start / Stop ───────────────────────────────────────────────────────────
    public static void toggle(ItemStack stack) {
        if (recording) stopRecording();
        else if (!postProcessing) startRecording(stack, -1);
    }

    /** Convenience overload for player-held camera (no armor stand). */
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

        currentFps      = VideoCameraItem.getSettings(stack).fps();
        smoothedExpMult = 1.0f;
        prevFrameValid    = false;
        prevFrameYaw      = 0f;
        prevFramePitch    = 0f;
        smoothedDeltaYaw  = 0f;
        smoothedDeltaPitch = 0f;
        frameCount    = 0;
        recordStartMs = System.currentTimeMillis();
        nextFrameMs   = recordStartMs;
        frameMetas    = new ArrayList<>(MAX_FRAMES);
        recordingStack = stack;

        // Reset AF
        currentFocusDepth    = 5.0f;
        focusCandidateDepth  = 5.0f;
        focusCandidateFrames = 0;

        rawDir = new File(mc.gameDirectory,
                "photographica/video_temp/" + sessionId + "/raw");
        if (!rawDir.mkdirs()) {
            Photographica.LOGGER.error("[VideoRecorder] Could not create raw dir: {}", rawDir);
            return;
        }

        // Switch to armor-stand perspective if recording from a tripod.
        recordingArmorStandEntityId = armorStandEntityId;
        if (armorStandEntityId >= 0 && mc.level != null) {
            net.minecraft.world.entity.Entity stand = mc.level.getEntity(armorStandEntityId);
            if (stand != null) mc.setCameraEntity(stand);
        }

        // Enable cinematic (smooth) camera for the duration of the recording.
        prevSmoothCamera = mc.options.smoothCamera;
        mc.options.smoothCamera = true;

        recording = true;
        if (mc.player != null)
            mc.player.displayClientMessage(Component.literal("● REC 開始"), true);
    }

    public static void stopRecording() {
        if (!recording) return;
        recording = false;
        Minecraft mc = Minecraft.getInstance();
        // Restore player perspective if we were recording from an armor stand.
        if (recordingArmorStandEntityId >= 0) {
            if (mc.player != null) mc.setCameraEntity(mc.player);
            recordingArmorStandEntityId = -1;
        }
        // Restore the smooth-camera setting the player had before recording.
        mc.options.smoothCamera = prevSmoothCamera;
        if (mc.player != null)
            mc.player.displayClientMessage(Component.literal("■ 録画停止 — 後処理中..."), true);

        final List<FrameMeta> metas    = new ArrayList<>(frameMetas);
        final File            rawSnap  = rawDir;
        final VideoSettings   vs       = VideoCameraItem.getSettings(recordingStack);
        final File            vidDir   = new File(mc.gameDirectory, "photographica/videos");

        postProcessing = true;
        ppProgress     = 0;
        ppMessage      = "後処理中...";

        Thread t = new Thread(() -> doPostProcess(metas, rawSnap, vs, vidDir),
                "photographica-video-pp");
        t.setDaemon(true);
        t.start();
    }

    // ── Render-thread hooks ────────────────────────────────────────────────────

    public static void onWorldRenderEnd() {
        if (!recording) return;
        if (pendingDepthReady) return;

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

        // Compute the 16:9 crop that cropTo16x9() will apply to the screenshot.
        float aspect = 16f / 9f;
        int cropW, cropH;
        if ((float) vpW / vpH > aspect) { cropH = vpH; cropW = Math.round(vpH * aspect); }
        else                             { cropW = vpW; cropH = Math.round(vpW / aspect); }
        pendingVpW     = vpW;
        pendingVpH     = vpH;
        pendingCropOffX = (vpW - cropW) / 2;
        pendingCropOffY = (vpH - cropH) / 2;
    }

    public static void captureFrameIfRecording() {
        if (!recording) return;
        long now = System.currentTimeMillis();
        if (now < nextFrameMs) return;
        if (frameCount >= MAX_FRAMES) { stopRecording(); return; }

        Minecraft mc = Minecraft.getInstance();
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
        float centreDepth = Math.max(
                depthGrid[(DEP_H / 2) * DEP_W + DEP_W / 2], 0.3f);

        if (Math.abs(centreDepth - focusCandidateDepth)
                / Math.max(focusCandidateDepth, 0.1f) <= FOCUS_TOL) {
            focusCandidateFrames++;
            if (focusCandidateFrames >= FOCUS_DWELL_FRAMES) {
                currentFocusDepth =
                        currentFocusDepth * 0.65f + focusCandidateDepth * 0.35f;
            }
        } else {
            focusCandidateDepth  = centreDepth;
            focusCandidateFrames = 0;
        }

        Vec3 vel = mc.player.getDeltaMovement();
        float ap  = VideoCameraItem.getSettings(recordingStack).aperture();

        Camera camera = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        float yaw   = (camera != null && camera.isInitialized()) ? camera.getYRot()   : mc.player.getYRot();
        float pitch = (camera != null && camera.isInitialized()) ? camera.getXRot() : mc.player.getXRot();

        float deltaYaw, deltaPitch;
        if (prevFrameValid) {
            float rawDeltaYaw = yaw - prevFrameYaw;
            if (rawDeltaYaw >  180f) rawDeltaYaw -= 360f;
            if (rawDeltaYaw < -180f) rawDeltaYaw += 360f;
            float rawDeltaPitch = pitch - prevFramePitch;
            final float A = 0.4f;
            smoothedDeltaYaw   = smoothedDeltaYaw   * (1 - A) + rawDeltaYaw   * A;
            smoothedDeltaPitch = smoothedDeltaPitch * (1 - A) + rawDeltaPitch * A;
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
                deltaYaw, deltaPitch, videoFov,
                ap,
                currentFocusDepth,
                depthGrid,
                pendingVpW, pendingVpH,
                pendingCropOffX, pendingCropOffY);

        // takeScreenshot is GPU-async in 1.21.11: claim the frame slot synchronously so
        // timing and meta ordering stay correct, then process pixels inside the callback.
        int idx     = frameCount;
        File outFile = new File(rawDir, String.format("frame_%04d.png", idx));
        frameMetas.add(meta);
        frameCount++;
        nextFrameMs = recordStartMs + (long)(frameCount * 1000.0 / currentFps);
        if (frameCount == currentFps * 60 && mc.player != null)
            mc.player.displayClientMessage(Component.literal("⚠ 残り 1:00"), true);
        Screenshot.grab(mc.getMainRenderTarget(), raw -> {
            if (raw == null) return;
            NativeImage cropped_ = cropTo16x9(raw);
            NativeImage frame_   = boxDownsample(cropped_, 1280);
            if (cropped_ != raw) cropped_.close();
            raw.close();
            ioExecutor.submit(() -> {
                try { frame_.writeToFile(outFile.toPath()); }
                catch (IOException e) {
                    Photographica.LOGGER.warn("[VideoRecorder] Frame write failed: {}", outFile, e);
                } finally { frame_.close(); }
            });
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
                processed.writeToFile(outFile.toPath());
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
        float alpha = rawExpMult > smoothedExpMult ? 0.04f : 0.15f;
        smoothedExpMult = smoothedExpMult * (1f - alpha) + rawExpMult * alpha;
        float expMult = smoothedExpMult;
        float vig     = apertureToVignette(ap);

        NativeImage pass1 = new NativeImage(w, h, false);
        for (int py = 0; py < h; py++) {
            float dy = (py - halfH) / halfH;
            float dy2 = dy * dy;
            for (int px = 0; px < w; px++) {
                int c = getPixelAbgr(src, px, py);
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

                setPixelAbgr(pass1, px, py, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        // ── Pass 2: depth-of-field bokeh ─────────────────────────────────────
        int vw = meta.vpW(), vh = meta.vpH();
        int cOffX = meta.cropOffX(), cOffY = meta.cropOffY();
        float aspect16_9 = 16f / 9f;
        int cropW, cropH;
        if (vw > 0 && vh > 0) {
            if ((float) vw / vh > aspect16_9) { cropH = vh; cropW = Math.round(vh * aspect16_9); }
            else                               { cropW = vw; cropH = Math.round(vw / aspect16_9); }
        } else {
            cropW = w; cropH = h;
        }

        float maxCoC = 40.0f;
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
                float coc = Math.abs(d - focus) * CoC_K / (ap * d * focus);
                cocMap[py * w + px] = Math.min(coc * 0.5f, maxCoC * 0.5f);
            }
        }
        float[] scaledCoc = new float[w * h];
        for (int i = 0; i < cocMap.length; i++) scaledCoc[i] = cocMap[i] * 0.58f;

        NativeImage dof1 = separableVariableBlur(pass1,  scaledCoc, w, h);
        NativeImage dof2 = separableVariableBlur(dof1,   scaledCoc, w, h);  dof1.close();
        NativeImage pass2 = separableVariableBlur(dof2,  scaledCoc, w, h);  dof2.close();
        pass1.close();

        // ── Pass 3: motion blur (angular + translational) ────────────────────
        float fovH     = meta.fovDeg();
        float fovV     = fovH * 9f / 16f;

        float rotSampleX =  meta.deltaYaw()   * w / fovH;
        float rotSampleY = -meta.deltaPitch() * h / fovV;

        float yawRad    = (float) Math.toRadians(meta.yaw());
        float strafeVel = ((float)(Math.cos(yawRad) * meta.velX()
                                 + Math.sin(yawRad) * meta.velZ()))
                        * (20.0f / currentFps);
        float transScale = strafeVel * FOCAL_PX;

        float fwdVel = ((float)(-Math.sin(yawRad) * meta.velX()
                               + Math.cos(yawRad) * meta.velZ()))
                     * (20.0f / currentFps);
        float cx = w * 0.5f, cy = h * 0.5f;

        float totalAtFocus = (float) Math.sqrt(
                (rotSampleX + transScale / focus) * (rotSampleX + transScale / focus)
              + rotSampleY * rotSampleY);
        float cornerFwdBlur = (float) Math.sqrt(cx * cx + cy * cy)
                            * Math.abs(fwdVel) / focus;
        if (totalAtFocus < 0.5f && cornerFwdBlur < 0.5f) return pass2;

        float maxBlurPx = w / 10.0f;

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
                    setPixelAbgr(pass3, px, py, getPixelAbgr(pass2, px, py));
                    continue;
                }

                float ndx = sampleX / blurMag;
                float ndy = sampleY / blurMag;

                float ra = 0, ga = 0, ba = 0, aa = 0, sumW = 0;
                for (int s = 0; s <= blurLen; s++) {
                    float wt = (blurLen - s + 1);
                    int sx = Math.max(0, Math.min(w - 1, px + (int)(s * ndx)));
                    int sy = Math.max(0, Math.min(h - 1, py + (int)(s * ndy)));
                    int c  = getPixelAbgr(pass2, sx, sy);
                    aa += ((c >>> 24) & 0xFF) * wt;
                    ba += ((c >>> 16) & 0xFF) * wt;
                    ga += ((c >>>  8) & 0xFF) * wt;
                    ra += ( c         & 0xFF) * wt;
                    sumW += wt;
                }
                setPixelAbgr(pass3, px, py,
                        (clamp((int)(aa / sumW)) << 24) | (clamp((int)(ba / sumW)) << 16)
                      | (clamp((int)(ga / sumW)) <<  8) |  clamp((int)(ra / sumW)));
            }
        }
        pass2.close();
        return pass3;
    }

    // ── Auto-exposure ──────────────────────────────────────────────────────────

    private static float computeAutoExposure(NativeImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] hist  = new int[256];
        int   count = 0;
        for (int py = 0; py < h; py += 8) {
            for (int px = 0; px < w; px += 8) {
                int c = getPixelAbgr(src, px, py);
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

    // ── Separable variable-radius box blur ────────────────────────────────────

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

        for (int py = 0; py < h; py++) {
            prefR[0] = prefG[0] = prefB[0] = prefA[0] = 0;
            for (int px = 0; px < w; px++) {
                int c = getPixelAbgr(src, px, py);
                prefA[px + 1] = prefA[px] + ((c >>> 24) & 0xFF);
                prefB[px + 1] = prefB[px] + ((c >>> 16) & 0xFF);
                prefG[px + 1] = prefG[px] + ((c >>>  8) & 0xFF);
                prefR[px + 1] = prefR[px] + ( c         & 0xFF);
            }
            for (int px = 0; px < w; px++) {
                int r = (int) radiusMap[py * w + px];
                int base = py * w + px;
                if (r < 1) {
                    int c = getPixelAbgr(src, px, py);
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
                    setPixelAbgr(dst, px, py,
                            (dofTempA[base] << 24) | (dofTempB[base] << 16)
                          | (dofTempG[base] <<  8) |  dofTempR[base]);
                } else {
                    int y0  = Math.max(0, py - r), y1 = Math.min(h - 1, py + r);
                    int cnt = y1 - y0 + 1;
                    int a   = (int)((vprefA[y1 + 1] - vprefA[y0]) / cnt);
                    int b   = (int)((vprefB[y1 + 1] - vprefB[y0]) / cnt);
                    int g   = (int)((vprefG[y1 + 1] - vprefG[y0]) / cnt);
                    int rv  = (int)((vprefR[y1 + 1] - vprefR[y0]) / cnt);
                    setPixelAbgr(dst, px, py, (a << 24) | (b << 16) | (g << 8) | rv);
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

    // In 1.21.11 NativeImage uses getPixelRGBA/setPixelRGBA.
    private static int getPixelAbgr(NativeImage img, int x, int y) {
        int argb = img.getPixelRGBA(x, y);
        int a=(argb>>>24)&0xFF; int r=(argb>>>16)&0xFF; int g=(argb>>>8)&0xFF; int b=argb&0xFF;
        return (a<<24)|(b<<16)|(g<<8)|r;
    }
    private static void setPixelAbgr(NativeImage img, int x, int y, int abgr) {
        int a=(abgr>>>24)&0xFF; int b=(abgr>>>16)&0xFF; int g=(abgr>>>8)&0xFF; int r=abgr&0xFF;
        img.setPixelRGBA(x, y, (a<<24)|(r<<16)|(g<<8)|b);
    }
}
