package dev.shunti.snapmatica.client;


import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Handles photo capture: screenshot + post-processing effects.
 * <p>
 * Ported from Photographica's PhotoCapture, stripped of all server networking,
 * film/digital workflows, and armour-stand logic.
 */
@Environment(EnvType.CLIENT)
public final class PhotoCapture {
    private PhotoCapture() {}

    // ── Timing / state ──────────────────────────────────────────────────────────
    public static long mirrorEndMs = 0L;
    public static long flashEndMs  = 0L;
    public static long secondClickAtMs = 0L;

    /** Depth at the centre of the screen (blocks), updated each frame. */
    public static float lastSceneDepthBlocks = 5.0f;

    private static long lastShotMs   = 0L;
    private static boolean capturePending = false;

    private static volatile float[] pendingLinearDepth = null;
    private static volatile int pendingDepthFbW = 0;
    private static volatile int pendingDepthFbH = 0;

    private static final long COOLDOWN_MS = 700L;

    // AF subject-distance query throttle. The long-range raycast (and especially the
    // Distant Horizons LOD raycast) is far too expensive to run every rendered frame —
    // focusing on distant terrain / sky froze the game. Sampling at ~10 Hz is plenty
    // since focus racks slowly; the depth texture for the blur is still copied each frame.
    private static final long AF_QUERY_INTERVAL_MS = 100L;
    private static long lastAfQueryMs = 0L;

    // ── Long exposure ───────────────────────────────────────────────────────────
    /**
     * Shutter speed used to change nothing but the brightness and the length of the mirror
     * blackout: 1/4000 and 1/15 produced an identical image. A real camera integrates light
     * for the whole time the shutter is open, so anything that moves — the subject, or the
     * camera in your hands — smears. The "WARN Blur" indicator has been pointing at an effect
     * that did not exist.
     *
     * <p>Ported from photographica, which does this by sampling the framebuffer repeatedly
     * across the exposure and averaging. The trails are therefore genuine: whatever actually
     * moved on screen during those milliseconds is what smears, camera shake included.
     *
     * <p>Only armed at 1/30 s or slower. Faster than that, a single frame IS the exposure.
     */
    private static final double ACCUM_MIN_SHUTTER_SEC = 1.0 / 30.0;
    /** Ceiling on samples, so a 30 s exposure costs the same as a 1 s one. */
    private static final int  ACCUM_MAX_SAMPLES = 120;
    /** Floor on the gap between samples, so a short exposure cannot spin the readback. */
    private static final long ACCUM_MIN_INTERVAL_MS = 8L;

    private static volatile boolean accumArmed   = false;
    private static volatile long    accumEndMs   = 0L;
    private static volatile long    accumNextMs  = 0L;
    private static volatile long    accumIntervalMs = ACCUM_MIN_INTERVAL_MS;
    private static volatile int     accumSamples = 0;
    private static volatile int     accumW = 0, accumH = 0;
    private static volatile float[] accumR = null, accumG = null, accumB = null;
    private static volatile float[] accumDepth = null;
    private static volatile int     accumDepthFbW = 0, accumDepthFbH = 0;

    // ── Public API ──────────────────────────────────────────────────────────────

    /**
     * True while a photo is being taken — including for the whole duration of a long exposure.
     *
     * <p>Callers use this to suppress the hand, the player model and block outlines, and to ask
     * EvfBlurRenderer for a FULL-frame blur rather than one scissored to the viewfinder. All of
     * those have to hold for every frame the shutter is open, not just the instant it opens, or
     * a long exposure would average one correctly-prepared frame together with a hundred that
     * still had the hand in them and only the viewfinder rectangle defocused.
     */
    public static boolean isCapturePending() {
        return capturePending || accumArmed;
    }

    /** True while a long exposure is integrating — used to decide whether to smear samples. */
    public static boolean isLongExposing() { return accumArmed; }

    public static boolean isBusy() {
        return System.currentTimeMillis() < mirrorEndMs;
    }

    public static void take() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastShotMs < COOLDOWN_MS) return;

        int em = SnapmaticaClient.exposureMode;
        int shutterIdx = (em == 1 || em == 3) ? SnapmaticaClient.autoShutterIdx : SnapmaticaClient.shutterSpeedIdx;
        double shutterSec = SnapmaticaClient.SHUTTER_SECONDS[
                Math.max(0, Math.min(SnapmaticaClient.SHUTTER_SECONDS.length - 1, shutterIdx))];
        long shutterMs = Math.min(1500, (long)(shutterSec * 1000));

        // Electronic shutter — no mirror, so no blackout. The blackout was modelling an SLR's
        // mirror swinging up, which is exactly the thing a mirrorless body does not have; it
        // also hid the live view for the whole of a long exposure, when watching the trails
        // build is the point. A brief exposure flash is all that marks the frame.
        mirrorEndMs = now;
        secondClickAtMs = 0L;
        flashEndMs = now + Math.min(200, 20 + shutterMs / 2);

        // Arm the long exposure for slow shutters. capturePending stays false in that case:
        // the accumulator owns the capture from here, and finalises it when the shutter closes.
        if (shutterSec >= ACCUM_MIN_SHUTTER_SEC) {
            long durationMs = Math.max((long) (shutterSec * 1000), 1L);
            accumArmed   = true;
            accumEndMs   = now + durationMs;
            accumIntervalMs = Math.max(ACCUM_MIN_INTERVAL_MS, durationMs / ACCUM_MAX_SAMPLES);
            accumNextMs  = now;
            accumSamples = 0;
            accumR = null; accumG = null; accumB = null;
            accumDepth = null;
            capturePending = false;
        } else {
            capturePending = true;
        }
        lastShotMs = now;
    }

    public static void captureIfPending() {
        // A long exposure in progress owns the capture path until the shutter closes.
        if (accumArmed) { tickAccumulation(); return; }
        if (!capturePending) return;
        capturePending = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // Depth was already captured in onWorldRenderEnd() while the depth buffer was valid.
        final float[] capturedDepth = pendingLinearDepth;
        final int capturedFbW = pendingDepthFbW;
        final int capturedFbH = pendingDepthFbH;
        pendingLinearDepth = null;
        pendingDepthFbW = 0;
        pendingDepthFbH = 0;

        //? if >=1.21.11 {
        /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> processScreenshot(mc, raw, capturedDepth, capturedFbW, capturedFbH));
        *///?} else {
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            System.err.println("[Snapmatica] Screenshot failed: " + e.getMessage());
            return;
        }
        processScreenshot(mc, raw, capturedDepth, capturedFbW, capturedFbH);
        //?}
    }

    /** Samples the framebuffer across the open shutter, then hands the average to the normal
     *  photo pipeline. Runs once per rendered frame while a long exposure is armed. */
    private static void tickAccumulation() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) { resetAccumulation(); return; }
        long now = System.currentTimeMillis();

        // First tick: take over the depth pre-read the viewfinder left for us. The depth is
        // sampled once, at the start of the exposure — a moving scene has no single depth, and
        // the subject you focused on is the one that should drive the defocus.
        if (accumSamples == 0 && accumDepth == null) {
            accumDepth    = pendingLinearDepth;
            accumDepthFbW = pendingDepthFbW;
            accumDepthFbH = pendingDepthFbH;
            pendingLinearDepth = null;
            pendingDepthFbW = 0;
            pendingDepthFbH = 0;
        }

        if (now >= accumNextMs && accumSamples < ACCUM_MAX_SAMPLES) {
            //? if >=1.21.11 {
            /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), PhotoCapture::accumulateFrame);
            *///?} else {
            try {
                accumulateFrame(ScreenshotRecorder.takeScreenshot(mc.getFramebuffer()));
            } catch (Exception e) {
                System.err.println("[Snapmatica] Long-exposure sample failed: " + e.getMessage());
            }
            //?}
            accumNextMs = now + accumIntervalMs;
            // Reset the smear reference so the next one spans this gap, not one frame of it.
            EvfBlurRenderer.markMotionSampled();
        }

        if (now >= accumEndMs || accumSamples >= ACCUM_MAX_SAMPLES) finalizeAccumulation(mc);
    }

    /** Adds one framebuffer sample to the running per-channel sums, and closes {@code frame}. */
    private static void accumulateFrame(NativeImage frame) {
        if (frame == null) return;
        try {
            int w = frame.getWidth(), h = frame.getHeight();
            if (accumR == null) {
                accumW = w; accumH = h;
                accumR = new float[w * h];
                accumG = new float[w * h];
                accumB = new float[w * h];
            }
            // A resize mid-exposure changes the buffer dimensions; drop the odd frame rather
            // than corrupt the sums.
            if (w != accumW || h != accumH) return;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c   = getPixelAbgr(frame, x, y);
                    int idx = y * w + x;
                    accumR[idx] +=  c        & 0xFF;
                    accumG[idx] += (c >>> 8) & 0xFF;
                    accumB[idx] += (c >>> 16) & 0xFF;
                }
            }
            accumSamples++;
        } finally {
            frame.close();
        }
    }

    /** Averages the exposure and pushes it through the normal crop / effects / save path. */
    private static void finalizeAccumulation(MinecraftClient mc) {
        int w = accumW, h = accumH, n = accumSamples;
        float[] r = accumR, g = accumG, b = accumB;
        float[] depth = accumDepth;
        int dFbW = accumDepthFbW, dFbH = accumDepthFbH;
        resetAccumulation();

        if (n == 0 || r == null) {
            System.err.println("[Snapmatica] Long exposure: no frames accumulated, discarding");
            return;
        }

        NativeImage averaged = new NativeImage(w, h, false);
        float inv = 1.0f / n;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int rr = Math.min(255, (int) (r[idx] * inv + 0.5f));
                int gg = Math.min(255, (int) (g[idx] * inv + 0.5f));
                int bb = Math.min(255, (int) (b[idx] * inv + 0.5f));
                setPixelAbgr(averaged, x, y, 0xFF000000 | (bb << 16) | (gg << 8) | rr);
            }
        }
        processScreenshot(mc, averaged, depth, dFbW, dFbH);
    }

    private static void resetAccumulation() {
        accumArmed   = false;
        accumEndMs   = 0L;
        accumSamples = 0;
        accumR = null; accumG = null; accumB = null;
        accumDepth = null;
        accumDepthFbW = 0; accumDepthFbH = 0;
    }

    /**
     * The photo's frame: the largest centred rectangle of the current aspect that fits.
     * Returned as {x, y, w, h}.
     *
     * <p>The single definition of what the camera sees, used both to crop the capture and to
     * lay out the viewfinder. They used to compute it separately, and disagreed: the viewfinder
     * drew a box 86% of the screen height while the capture kept the FULL height, so roughly
     * 16% more scene ended up in the photo than was ever framed. Beyond breaking
     * what-you-see-is-what-you-get, it made every focal length read about a stop longer than it
     * was — a 24 mm framed like a 35 mm, because the box was showing 46 degrees of a 53 degree
     * field.
     */
    public static int[] frameRect(int w, int h, boolean portrait) {
        float target = portrait ? 2f / 3f : 3f / 2f;
        int fw, fh;
        if ((float) w / h > target) { fh = h; fw = Math.round(h * target); }
        else                        { fw = w; fh = Math.round(w / target); }
        return new int[]{ (w - fw) / 2, (h - fh) / 2, fw, fh };
    }

    private static void processScreenshot(MinecraftClient mc, NativeImage raw, float[] linearDepth, int fbW, int fbH) {
        // ── Crop to 3:2 (landscape) or 2:3 (portrait) aspect ratio ──────────────
        int w = raw.getWidth();
        int h = raw.getHeight();
        int[] fr = frameRect(w, h, SnapmaticaClient.portraitOrientation);
        int offX = fr[0], offY = fr[1], cropW = fr[2], cropH = fr[3];
        NativeImage cropped = new NativeImage(cropW, cropH, false);
        for (int y = 0; y < cropH; y++) {
            for (int x = 0; x < cropW; x++) {
                setPixelAbgr(cropped, x, y, getPixelAbgr(raw, x + offX, y + offY));
            }
        }
        raw.close();

        // ── Apply photo effects ─────────────────────────────────────────────────
        NativeImage processed = applyPhotoEffects(cropped, linearDepth, fbW, fbH);
        cropped.close();

        // ── Save to disk ────────────────────────────────────────────────────────
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File snapDir = new File(mc.runDirectory, "snapmatica/photos");
        snapDir.mkdirs();
        File outFile = new File(snapDir, timestamp + ".png");

        try {
            processed.writeTo(outFile);
            System.out.println("[Snapmatica] Photo saved: " + outFile.getAbsolutePath());
            // Auto-copy the freshly saved photo to the system clipboard.
            // ClipboardUtil reports success/failure to the action bar itself.
            ClipboardUtil.copyImageAsync(outFile);
        } catch (IOException e) {
            System.err.println("[Snapmatica] Failed to save photo: " + e.getMessage());
        } finally {
            processed.close();
        }
    }

    /**
     * The projection matrix the world was ACTUALLY rendered with — the one that produced
     * the depth buffer we are about to linearise.
     *
     * <p>On 1.21.11 this must be {@code getProjectionMatrix}, not {@code getBasicProjection-
     * Matrix}: the latter builds a fresh matrix out of vanilla parameters, so a LOD mod
     * (Voxy, DH) that extends the far plane elsewhere in the pipeline is invisible to it and
     * distant terrain gets linearised against a far plane that is far too small. Older
     * versions have no separate accessor — there {@code getBasicProjectionMatrix} IS what
     * {@code renderWorld} uses, so it is the correct source.
     *
     * <p>Far/near are fov-independent, so the 70° argument is irrelevant to what we read.
     * The per-version target selection lives in the invoker, so there is none here.
     */
    private static org.joml.Matrix4f worldProjection(MinecraftClient mc) {
        return ((dev.shunti.snapmatica.client.mixin.GameRendererInvoker) mc.gameRenderer)
                .snapmatica$worldProjection(70.0f);
    }

    /**
     * Copies the scene depth for the EVF blur, BEFORE translucent geometry is drawn.
     *
     * <p>Glass writes depth at its own surface while the pixel shows what is behind it. Taken
     * at the end of the world render, the buffer therefore said "glass pane, two blocks away"
     * for a pixel displaying a building far beyond it — so the blur treated the view through
     * a window as near-field, and the pane's own rectangle appeared as a hard-edged shape in
     * the defocus however heavily blurred it was. Every translucent surface has the problem;
     * glass is only the one you notice.
     *
     * <p>Sampling before the translucent pass leaves the depth of whatever is actually behind
     * the glass, which is what the camera is looking at. Solid geometry and entities are
     * already drawn by this point, so nothing that should be focusable is missed.
     */
    public static void onBeforeTranslucent() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        boolean sneakViewfinder = mc.player.isSneaking()
                && (SnapmaticaClient.viewfinderSneakEnabled || capturePending);
        if (!sneakViewfinder && !capturePending && !VideoRecorder.isRecording()) return;

        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) return;

        int rd = mc.options.getViewDistance().getValue();
        EvfBlurRenderer.updateDepthFar(worldProjection(mc),
                mc.gameRenderer.getFarPlaneDistance(), Math.max(rd * 64f, 256f));
        EvfBlurRenderer.captureDepth(vpW, vpH);
    }

    /**
     * Samples the centre pixel of the currently bound depth buffer and stores the
     * linear depth in {@link #lastSceneDepthBlocks} for the viewfinder focus reticle.
     * Called from WorldRenderEvents.LAST (fires inside renderWorld).
     *
     * Mirrors Photographica's updateCenterDepth() exactly, including the
     * viewport query via glGetIntegerv(GL_VIEWPORT) and GL error clearing.
     */
    public static void onWorldRenderEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        // Run when the viewfinder is active (sneaking + mode enabled), when a photo is
        // pending, OR when video is recording (needs depth every frame regardless of sneak).
        boolean sneakViewfinder = mc.player.isSneaking()
                && (SnapmaticaClient.viewfinderSneakEnabled || capturePending);
        if (!sneakViewfinder && !capturePending && !VideoRecorder.isRecording()) return;

        //? if >=1.21.11 {
        /*// Depth is captured earlier now — see onBeforeTranslucent().

        // AF subject distance — THROTTLED. The 1000-block vanilla raycast plus the
        // Distant Horizons LOD raycast are far too costly to run every frame; focusing
        // on far terrain / sky froze the game (DH traverses many thousands of blocks).
        // Sample at ~10 Hz and reuse lastSceneDepthBlocks in between; focus racks slowly
        // so this is imperceptible.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastAfQueryMs >= AF_QUERY_INTERVAL_MS) {
            lastAfQueryMs = nowMs;
            final double maxDist = 1000.0;
            net.minecraft.util.math.Vec3d eye = mc.player.getCameraPosVec(1.0f);
            net.minecraft.util.math.Vec3d look = mc.player.getRotationVec(1.0f);
            net.minecraft.util.math.Vec3d end = eye.add(look.multiply(maxDist));
            net.minecraft.util.hit.BlockHitResult blockHit =
                    AutoFocus.raycastThroughGlass(mc, eye, look, maxDist);
            double bestDist = (blockHit != null
                    && blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS)
                    ? eye.distanceTo(blockHit.getPos()) : maxDist;
            net.minecraft.util.math.Box searchBox = mc.player.getBoundingBox()
                    .stretch(look.multiply(maxDist)).expand(1.0);
            net.minecraft.util.hit.EntityHitResult entityHit =
                    net.minecraft.entity.projectile.ProjectileUtil.raycast(mc.player, eye, end,
                            searchBox, e -> !e.isSpectator() && e.isAlive(), bestDist * bestDist);
            if (entityHit != null) {
                double eDist = eye.distanceTo(entityHit.getPos());
                if (eDist < bestDist) bestDist = eDist;
            }
            lastSceneDepthBlocks = (bestDist < maxDist) ? (float) bestDist : SnapmaticaClient.FOCUS_INFINITY;
            // Raycast missed (sky / beyond loaded range). The old GPU centre-depth readback
            // (readCenterLinearDepthBlocks -> glReadPixels on a depth FBO) crashed the NVIDIA
            // driver on hybrid-GPU laptops whenever a LOD mod (Voxy / Distant Horizons) was
            // drawing the distance — a hard EXCEPTION_ACCESS_VIOLATION inside nvoglv64.dll —
            // so it is removed. Fall back to the DH LOD raycast; without it the focus simply
            // stays at infinity, which reads distant terrain as far (sharp) — correct anyway.
            if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                float dhDist = DhIntegration.queryLookDistance(mc);
                if (dhDist > 0f) lastSceneDepthBlocks = dhDist;
            }
        }

        // Depth readback for CPU DoF is no longer needed in 1.21.11: the EVF blur
        // (GPU bokeh) is applied to mainTex in GameRendererMixin before captureIfPending(),
        // so the screenshot already contains the correct DoF. Eliminating readLinearDepthCpu()
        // removes the GPU→CPU sync stall that caused the freeze on photo capture.
        *///?} else {
        // Read from the currently bound framebuffer without switching.
        GL11.glGetError(); // clear any pending GL error
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) return;

        // NO depth copy here. This hook fires after the translucent pass, so the buffer now
        // holds the glass surface rather than what is behind it; copying it would overwrite
        // the good pre-translucent copy onBeforeTranslucent() just made and blur every scene
        // seen through a window. The viewport query above is kept — the centre-depth fallback
        // further down still needs it.

        // AF subject distance — THROTTLED. The world raycast is the PRIMARY focus
        // distance (good to 1000 m, covers all vanilla render distances). The GPU
        // centre-depth reconstruction saturates at currentDepthFar (≈ rd*64), pinning
        // every distant reading to ~940 m, so it (and the DH LOD raycast) is consulted
        // ONLY when the raycast misses (sky / beyond loaded range). Without this the
        // focus was stuck near the far plane — the "950 m cap" fixed in 172eca8.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastAfQueryMs >= AF_QUERY_INTERVAL_MS) {
            lastAfQueryMs = nowMs;
            final double maxDist = 1000.0;
            net.minecraft.util.math.Vec3d eye = mc.player.getCameraPosVec(1.0f);
            net.minecraft.util.math.Vec3d look = mc.player.getRotationVec(1.0f);
            net.minecraft.util.math.Vec3d end = eye.add(look.multiply(maxDist));
            net.minecraft.util.hit.BlockHitResult blockHit =
                    AutoFocus.raycastThroughGlass(mc, eye, look, maxDist);
            double bestDist = (blockHit != null
                    && blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS)
                    ? eye.distanceTo(blockHit.getPos()) : maxDist;
            net.minecraft.util.math.Box searchBox = mc.player.getBoundingBox()
                    .stretch(look.multiply(maxDist)).expand(1.0);
            net.minecraft.util.hit.EntityHitResult entityHit =
                    net.minecraft.entity.projectile.ProjectileUtil.raycast(mc.player, eye, end,
                            searchBox, e -> !e.isSpectator() && e.isAlive(), bestDist * bestDist);
            if (entityHit != null) {
                double eDist = eye.distanceTo(entityHit.getPos());
                if (eDist < bestDist) bestDist = eDist;
            }
            lastSceneDepthBlocks = (bestDist < maxDist) ? (float) bestDist : SnapmaticaClient.FOCUS_INFINITY;

            if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                // Raycast missed: reconstruct the GPU centre depth, rejecting saturated
                // far-plane readings, then fall back to the DH LOD raycast.
                int cx = vpW / 2;
                int cy = vpH / 2;
                FloatBuffer depthBuf = BufferUtils.createFloatBuffer(1);
                GL11.glReadPixels(cx, cy, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);
                float rawD = depthBuf.get(0);
                // Mirror the sky threshold used by readCenterLinearDepthBlocks (>=1.21.11):
                // reject only depth values at/beyond the far plane (rawD >= 0.999999).
                // The old farPlane*0.95 check on the linearised result rejected valid terrain
                // near the far plane (e.g. 974m with farPlane=1024 gives ~975m > 972.8).
                if (rawD >= 0.001f && rawD < 0.999999f) {
                    float ndc = 2.0f * rawD - 1.0f;
                    final float near     = 0.05f;
                    final float farPlane = EvfBlurRenderer.currentDepthFar;
                    float gpuDepth = 2.0f * near * farPlane / (farPlane + near - ndc * (farPlane - near));
                    if (gpuDepth > 0.0f) lastSceneDepthBlocks = gpuDepth;
                }
                if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                    float dhDist = DhIntegration.queryLookDistance(mc);
                    if (dhDist > 0f) lastSceneDepthBlocks = dhDist;
                }
            }
        }
        //?}
    }

    // ── Photo effects pipeline ──────────────────────────────────────────────────

    /**
     * Applies photographic effects to the cropped screenshot:
     * exposure compensation, vignetting, ISO noise, tone curve,
     * highlight rolloff, and depth‑of‑field blur.
     */
    private static NativeImage applyPhotoEffects(NativeImage src, float[] linearDepth, int fbW, int fbH) {
        int w = src.getWidth();
        int h = src.getHeight();

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        // Exposure compensation
        double expFactor = PhotoProcessor.exposureFactor();

        // DOF parameters
        float focusDist = SnapmaticaClient.focusDistance;
        float depthCenter = lastSceneDepthBlocks;          // blocks at centre

        NativeImage dst = new NativeImage(w, h, false);

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int c = getPixelAbgr(src, px, py);
                int a = (c >>> 24) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int g = (c >>>  8) & 0xFF;
                int r =  c         & 0xFF;

                // 1. Exposure compensation
                r = clamp((int)(r * expFactor));
                g = clamp((int)(g * expFactor));
                b = clamp((int)(b * expFactor));

                // 2. Lens vignetting
                float dx = (px - halfW) / halfW;
                float dy = (py - halfH) / halfH;
                float vig = vignetteStrength(SnapmaticaClient.aperture);
                float vf = Math.max(0f, 1f - vig * (dx * dx + dy * dy) * 0.5f);
                r = clamp((int)(r * vf));
                g = clamp((int)(g * vf));
                b = clamp((int)(b * vf));

                // 3. ISO noise
                float noiseSigma = isoToNoiseSigma(SnapmaticaClient.iso);
                if (noiseSigma > 0.5f) {
                    float noise = (float)(Math.random() - 0.5) * noiseSigma * 1.5f;
                    r = clamp((int)(r + noise));
                    g = clamp((int)(g + noise));
                    b = clamp((int)(b + noise));
                }

                // 4. Tone curve
                r = applyToneCurve(r);
                g = applyToneCurve(g);
                b = applyToneCurve(b);

                // 5. Highlight rolloff
                r = softClip(r);
                g = softClip(g);
                b = softClip(b);

                setPixelAbgr(dst, px, py, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        // Pass 2: Depth-of-field blur
        NativeImage pass2;
        if (linearDepth != null) {
            pass2 = applyDepthOfField(dst, SnapmaticaClient.aperture, focusDist,
                                       linearDepth, w, h, fbW, fbH);
            dst.close();
        } else {
            pass2 = dst;
        }
        return pass2;
    }

    private static NativeImage applyDepthOfField(NativeImage src,
                                                  float aperture, float focusDist,
                                                  float[] linearDepth,
                                                  int iw, int ih, int fbW, int fbH) {
        // Ceiling for the CPU photo path. The old 80 / N^2 collapsed to 1.25 px at f/8 and
        // essentially nothing beyond, which is where the "no bokeh past f/8" behaviour came
        // from on this path. The per-pixel CoC below is already physical; this only bounds the
        // kernel, so bound it by what the optics can produce rather than by the f-number.
        float maxBlurPx = Math.min(32.0f,
                EvfBlurRenderer.maxCocPx(focusDist, aperture, SnapmaticaClient.focalLengthMm,
                        EvfBlurRenderer.DOF_SCALE_STILL, ih / 24.0f));
        int   maxR      = Math.max(1, (int) Math.ceil(maxBlurPx));

        // Match the depth-buffer crop to the output image aspect (iw/ih), so the same
        // mapping works for both 3:2 landscape and 2:3 portrait framing.
        float targetA = (float) iw / ih;
        int croppedW, croppedH, cropOffX, cropOffY;
        if ((float) fbW / fbH > targetA) {
            croppedH = fbH; croppedW = Math.round(fbH * targetA);
            cropOffX = (fbW - croppedW) / 2; cropOffY = 0;
        } else {
            croppedW = fbW; croppedH = Math.round(fbW / targetA);
            cropOffX = 0; cropOffY = (fbH - croppedH) / 2;
        }

        // Physical thin-lens circle of confusion (same model as the EVF shader):
        //   coc_mm = f^2 / (N * (S1 - f)) * |S2 - S1| / S2
        // This keeps deep depth-of-field for wide/normal lenses so distant terrain stays
        // sharp, instead of saturating to max blur a short distance past the focus plane.
        boolean infinityFocus = (focusDist >= SnapmaticaClient.FOCUS_INFINITY);
        float fmm = SnapmaticaClient.focalLengthMm;
        float pxPerMm = (float) ih / 24.0f;  // 24mm sensor height maps to ih pixels
        float[] cocMap = new float[iw * ih];
        boolean[] isFgMap = new boolean[iw * ih];  // true = closer than focus plane
        for (int iy = 0; iy < ih; iy++) {
            for (int ix = 0; ix < iw; ix++) {
                int fx    = Math.max(0, Math.min(fbW - 1, cropOffX + ix * croppedW / iw));
                int fy_gl = Math.max(0, Math.min(fbH - 1, fbH - 1 - (cropOffY + iy * croppedH / ih)));
                float depthM = Math.max(linearDepth[fy_gl * fbW + fx], 0.05f);
                float cocMM;
                if (infinityFocus) {
                    cocMM = (fmm * fmm) / (aperture * depthM * 200f);
                } else {
                    float s1mm = focusDist * 200f;
                    cocMM = (fmm * fmm) * Math.abs(depthM - focusDist)
                            / (depthM * aperture * Math.max(s1mm - fmm, 1.0f));
                    isFgMap[iy * iw + ix] = (depthM < focusDist);
                }
                cocMap[iy * iw + ix] = Math.min(cocMM * pxPerMm, maxBlurPx);
            }
        }

        int[] hBuf = new int[iw * ih];
        for (int iy = 0; iy < ih; iy++) {
            for (int ix = 0; ix < iw; ix++) {
                float coc = cocMap[iy * iw + ix];
                if (coc < 0.5f) { hBuf[iy * iw + ix] = getPixelAbgr(src, ix, iy); continue; }
                int r = Math.min(maxR, (int) Math.ceil(coc));
                float sigma = Math.max(coc * 0.5f, 1.0f);
                float ra = 0, ga = 0, ba = 0, aa = 0, tw = 0;
                boolean fg = isFgMap[iy * iw + ix];
                for (int dx = -r; dx <= r; dx++) {
                    int sx = ix + dx;
                    if (sx < 0 || sx >= iw) continue;
                    float gauss = (float) Math.exp(-(float)(dx * dx) / (2.0f * sigma * sigma));
                    float cocW = fg ? 1.0f : Math.max(0.10f, Math.min(1.0f, cocMap[iy * iw + sx] / coc));
                    float w = gauss * cocW;
                    if (w < 0.001f) continue;
                    int c = getPixelAbgr(src, sx, iy);
                    aa += ((c >>> 24) & 0xFF) * w; ba += ((c >>> 16) & 0xFF) * w;
                    ga += ((c >>>  8) & 0xFF) * w; ra += ( c         & 0xFF) * w;
                    tw += w;
                }
                hBuf[iy * iw + ix] = (tw < 0.001f) ? getPixelAbgr(src, ix, iy)
                        : ((clamp(Math.round(aa / tw)) << 24) | (clamp(Math.round(ba / tw)) << 16)
                         | (clamp(Math.round(ga / tw)) <<  8) |  clamp(Math.round(ra / tw)));
            }
        }

        NativeImage result = new NativeImage(iw, ih, false);
        for (int ix = 0; ix < iw; ix++) {
            for (int iy = 0; iy < ih; iy++) {
                float coc = cocMap[iy * iw + ix];
                if (coc < 0.5f) { setPixelAbgr(result, ix, iy, getPixelAbgr(src, ix, iy)); continue; }
                int r = Math.min(maxR, (int) Math.ceil(coc));
                float sigma = Math.max(coc * 0.5f, 1.0f);
                float ra = 0, ga = 0, ba = 0, aa = 0, tw = 0;
                boolean fg = isFgMap[iy * iw + ix];
                for (int dy = -r; dy <= r; dy++) {
                    int sy = iy + dy;
                    if (sy < 0 || sy >= ih) continue;
                    float gauss = (float) Math.exp(-(float)(dy * dy) / (2.0f * sigma * sigma));
                    float cocW = fg ? 1.0f : Math.max(0.10f, Math.min(1.0f, cocMap[sy * iw + ix] / coc));
                    float w = gauss * cocW;
                    if (w < 0.001f) continue;
                    int c = hBuf[sy * iw + ix];
                    aa += ((c >>> 24) & 0xFF) * w; ba += ((c >>> 16) & 0xFF) * w;
                    ga += ((c >>>  8) & 0xFF) * w; ra += ( c         & 0xFF) * w;
                    tw += w;
                }
                setPixelAbgr(result, ix, iy, (tw < 0.001f) ? hBuf[iy * iw + ix]
                        : ((clamp(Math.round(aa / tw)) << 24) | (clamp(Math.round(ba / tw)) << 16)
                         | (clamp(Math.round(ga / tw)) <<  8) |  clamp(Math.round(ra / tw))));
            }
        }
        return result;
    }

    // ── Pixel access (NativeImage format changed in 1.21.4) ─────────────────────

    //? if >=1.21.4 {
    /*private static int getPixelAbgr(NativeImage img, int x, int y) {
        int argb = img.getColorArgb(x, y);
        int a = (argb >>> 24) & 0xFF; int r = (argb >>> 16) & 0xFF;
        int g = (argb >>>  8) & 0xFF; int b =  argb         & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
    private static void setPixelAbgr(NativeImage img, int x, int y, int abgr) {
        int a = (abgr >>> 24) & 0xFF; int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>>  8) & 0xFF; int r =  abgr         & 0xFF;
        img.setColorArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
    }
    *///?} else {
    private static int getPixelAbgr(NativeImage img, int x, int y) { return img.getColor(x, y); }
    private static void setPixelAbgr(NativeImage img, int x, int y, int abgr) { img.setColor(x, y, abgr); }
    //?}

    // ── Effect helpers ──────────────────────────────────────────────────────────

    private static float vignetteStrength(float aperture) {
        if (aperture <= 1.4f) return 0.70f;
        if (aperture <= 2.0f) return 0.55f;
        if (aperture <= 2.8f) return 0.40f;
        if (aperture <= 4.0f) return 0.25f;
        if (aperture <= 5.6f) return 0.15f;
        if (aperture <= 8.0f) return 0.08f;
        return 0.03f;
    }

    private static float isoToNoiseSigma(int iso) {
        if (iso <=   100) return  0.0f;
        if (iso <=   200) return  1.5f;
        if (iso <=   400) return  3.0f;
        if (iso <=   800) return  6.0f;
        if (iso <=  1600) return 11.0f;
        if (iso <=  3200) return 18.0f;
        if (iso <=  6400) return 28.0f;
        if (iso <= 12800) return 42.0f;
        return 60.0f;
    }

    private static int applyToneCurve(int v) {
        float f = v / 255.0f;
        f = f * (1.0f + 0.15f * (1.0f - Math.abs(f - 0.5f) * 2.0f));
        if (f < 0.0f) f = 0.0f;
        return clamp((int)(f * 255.0f));
    }

    private static int softClip(int v) {
        if (v <= 200) return v;
        float excess = v - 200;
        float softened = 200 + 55f * (1f - (float) Math.exp(-excess / 55f));
        return clamp((int) softened);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
