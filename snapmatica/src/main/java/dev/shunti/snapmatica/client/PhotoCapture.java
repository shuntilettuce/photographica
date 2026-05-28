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

    private static final long COOLDOWN_MS = 700L;

    // ── Public API ──────────────────────────────────────────────────────────────

    public static boolean isCapturePending() {
        return capturePending;
    }

    public static boolean isBusy() {
        return System.currentTimeMillis() < mirrorEndMs;
    }

    public static void take() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastShotMs < COOLDOWN_MS) return;

        int shutterIdx = SnapmaticaClient.shutterSpeedIdx;
        double shutterSec = SnapmaticaClient.SHUTTER_SECONDS[
                Math.max(0, Math.min(SnapmaticaClient.SHUTTER_SECONDS.length - 1, shutterIdx))];
        long shutterMs = Math.min(1500, (long)(shutterSec * 1000));

        mirrorEndMs = now + 100 + shutterMs + 100;
        flashEndMs = mirrorEndMs + Math.min(200, 20 + shutterMs / 2);
        secondClickAtMs = now + 100 + shutterMs;

        capturePending = true;
        lastShotMs = now;
    }

    public static void captureIfPending() {
        if (!capturePending) return;
        capturePending = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        //? if >=1.21.11 {
        /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> processScreenshot(mc, raw));*/
        //?} else {
        NativeImage raw;
        try {
            raw = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer());
        } catch (Exception e) {
            System.err.println("[Snapmatica] Screenshot failed: " + e.getMessage());
            return;
        }
        processScreenshot(mc, raw);
        //?}
    }

    private static void processScreenshot(MinecraftClient mc, NativeImage raw) {
        // ── Crop to 3:2 aspect ratio ────────────────────────────────────────────
        int w = raw.getWidth();
        int h = raw.getHeight();
        float targetAspect = 3f / 2f;
        int cropW, cropH;
        if ((float) w / h > targetAspect) {
            cropH = h;
            cropW = Math.round(h * targetAspect);
        } else {
            cropW = w;
            cropH = Math.round(w / targetAspect);
        }
        int offX = (w - cropW) / 2;
        int offY = (h - cropH) / 2;
        NativeImage cropped = new NativeImage(cropW, cropH, false);
        for (int y = 0; y < cropH; y++) {
            for (int x = 0; x < cropW; x++) {
                setPixelAbgr(cropped, x, y, getPixelAbgr(raw, x + offX, y + offY));
            }
        }
        raw.close();

        // ── Apply photo effects ─────────────────────────────────────────────────
        NativeImage processed = applyPhotoEffects(cropped);
        cropped.close();

        // ── Save to disk ────────────────────────────────────────────────────────
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File snapDir = new File(mc.runDirectory, "snapmatica/photos");
        snapDir.mkdirs();
        File outFile = new File(snapDir, timestamp + ".png");

        try {
            processed.writeTo(outFile);
            System.out.println("[Snapmatica] Photo saved: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Snapmatica] Failed to save photo: " + e.getMessage());
        } finally {
            processed.close();
        }
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
        if (mc.player == null || !mc.player.isSneaking()) return;

        //? if >=1.21.11 {
        /*// In 1.21.11 glReadPixels(GL_DEPTH_COMPONENT) no longer reads the scene depth
        // because depth lives in a GpuTexture, not the legacy default FBO depth attachment.
        // mc.crosshairTarget is capped at interaction reach (~4.5 blocks), so it MISSes
        // for anything farther — leaving the focus plane and reticle frozen. Do our own
        // long-range raycast (blocks + entities) so focus tracks distant subjects too.
        final double maxDist = 256.0;
        net.minecraft.util.math.Vec3d eye = mc.player.getCameraPosVec(1.0f);
        net.minecraft.util.math.Vec3d look = mc.player.getRotationVec(1.0f);
        net.minecraft.util.math.Vec3d end = eye.add(look.multiply(maxDist));
        net.minecraft.util.hit.BlockHitResult blockHit = mc.world.raycast(
                new net.minecraft.world.RaycastContext(eye, end,
                        net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
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
        // Nothing within range (sky / far horizon) → treat as infinity so AF can
        // reach the 999 stop, matching the old glReadPixels behaviour at the sky.
        lastSceneDepthBlocks = (bestDist < maxDist) ? (float) bestDist : 999.0f;
        // Still capture depth texture for the EVF blur shader.
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW > 0 && vpH > 0) EvfBlurRenderer.captureDepth(vpW, vpH);*/
        //?} else {
        // Read from the currently bound framebuffer without switching.
        GL11.glGetError(); // clear any pending GL error
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) return;

        // GPU-side depth copy for EVF DoF blur (no CPU readback).
        EvfBlurRenderer.captureDepth(vpW, vpH);

        int cx = vpW / 2;
        int cy = vpH / 2;

        FloatBuffer depthBuf = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(cx, cy, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);
        float d = depthBuf.get(0);
        float ndc = 2.0f * d - 1.0f;

        // Reconstruct linear depth in world units (blocks ≈ metres)
        final float near = 0.05f;
        final float far  = 512.0f;
        lastSceneDepthBlocks = 2.0f * near * far / (far + near - ndc * (far - near));
        //?}
    }

    // ── Photo effects pipeline ──────────────────────────────────────────────────

    /**
     * Applies photographic effects to the cropped screenshot:
     * exposure compensation, vignetting, ISO noise, tone curve,
     * highlight rolloff, and depth‑of‑field blur.
     */
    private static NativeImage applyPhotoEffects(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        // Exposure compensation
        double expFactor = PhotoProcessor.exposureFactor();

        // DOF parameters
        float focusDist = SnapmaticaClient.focusDistance;
        float depthCenter = lastSceneDepthBlocks;          // blocks at centre
        float depthMeters = depthCenter;                    // 1 block ≈ 1 metre (approx)

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

        // ── Depth‑of‑field blur ────────────────────────────────────────────────
        // Simple separable box blur, repeated. Full implementation would use
        // a proper Gaussian or bokeh kernel with depth awareness.
        if (SnapmaticaClient.aperture < 8.0f && focusDist < 999.0f) {
            float dofStrength = (8.0f - SnapmaticaClient.aperture) / 6.6f; // 0..1
            if (dofStrength > 0.01f) {
                int blurPasses = (int)(dofStrength * 3);   // 1 … 3
                int radius = 1 + (int)(dofStrength * 4);   // 1 … 5 px
                for (int pass = 0; pass < blurPasses; pass++) {
                    dst = boxBlur(dst, radius, focusDist, depthCenter);
                }
            }
        }

        return dst;
    }

    // ── Separable box blur with simple depth weighting ──────────────────────────

    private static NativeImage boxBlur(NativeImage src, int radius,
                                       float focusDist, float depthCenter) {
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage tmp = new NativeImage(w, h, false);

        // Horizontal pass
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float weight = depthWeight(y, h, radius, focusDist, depthCenter);
                if (weight <= 0f) {
                    setPixelAbgr(tmp, x, y, getPixelAbgr(src, x, y));
                    continue;
                }
                int ar = 0, ag = 0, ab = 0, aa = 0;
                int count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = x + k;
                    if (sx < 0 || sx >= w) continue;
                    int c = getPixelAbgr(src, sx, y);
                    ar += (c >>> 24) & 0xFF;
                    ag += (c >>> 16) & 0xFF;
                    ab += (c >>>  8) & 0xFF;
                    aa += c & 0xFF;
                    count++;
                }
                if (count == 0) { setPixelAbgr(tmp, x, y, getPixelAbgr(src, x, y)); continue; }
                int nc = ( (ar / count) << 24 ) | ( (ag / count) << 16 ) | ( (ab / count) << 8 ) | ( aa / count );
                setPixelAbgr(tmp, x, y, blendWithOriginal(getPixelAbgr(src, x, y), nc, weight));
            }
        }

        // Vertical pass
        NativeImage dst = new NativeImage(w, h, false);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float weight = depthWeight(y, h, radius, focusDist, depthCenter);
                if (weight <= 0f) {
                    setPixelAbgr(dst, x, y, getPixelAbgr(tmp, x, y));
                    continue;
                }
                int ar = 0, ag = 0, ab = 0, aa = 0;
                int count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = y + k;
                    if (sy < 0 || sy >= h) continue;
                    int c = getPixelAbgr(tmp, x, sy);
                    ar += (c >>> 24) & 0xFF;
                    ag += (c >>> 16) & 0xFF;
                    ab += (c >>>  8) & 0xFF;
                    aa += c & 0xFF;
                    count++;
                }
                if (count == 0) { setPixelAbgr(dst, x, y, getPixelAbgr(tmp, x, y)); continue; }
                int nc = ( (ar / count) << 24 ) | ( (ag / count) << 16 ) | ( (ab / count) << 8 ) | ( aa / count );
                setPixelAbgr(dst, x, y, blendWithOriginal(getPixelAbgr(tmp, x, y), nc, weight));
            }
        }
        tmp.close();
        return dst;
    }

    /**
     * Simple depth weight based on screen Y-coordinate.
     * Assumes the centre of the screen is at focus distance,
     * top/bottom are farther away (ground/sky).
     */
    private static float depthWeight(int y, int h, int radius,
                                     float focusDist, float depthCenter) {
        // Normalised Y: 0 = top, 0.5 = centre, 1 = bottom
        float ny = (float) y / h;
        // Simple parabolic: blur more at top/bottom, less at centre
        float distFromCenter = Math.abs(ny - 0.5f) * 2f; // 0..1
        float strength = Math.min(1f, distFromCenter * 1.5f);
        // Reduce strength when aperture is small (f/8+)
        float ap = SnapmaticaClient.aperture;
        float apFactor = Math.min(1f, (8f - ap) / 6.6f);
        return strength * apFactor * 0.7f;
    }

    private static int blendWithOriginal(int orig, int blurred, float weight) {
        int origR = (orig >>> 24) & 0xFF;
        int origG = (orig >>> 16) & 0xFF;
        int origB = (orig >>>  8) & 0xFF;
        int origA =  orig        & 0xFF;

        int blrR = (blurred >>> 24) & 0xFF;
        int blrG = (blurred >>> 16) & 0xFF;
        int blrB = (blurred >>>  8) & 0xFF;
        int blrA =  blurred        & 0xFF;

        float inv = 1f - weight;
        int r = clamp((int)(origR * inv + blrR * weight));
        int g = clamp((int)(origG * inv + blrG * weight));
        int b = clamp((int)(origB * inv + blrB * weight));
        int a = clamp((int)(origA * inv + blrA * weight));

        return (r << 24) | (g << 16) | (b << 8) | a;
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
    }*/
    //?} else {
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
