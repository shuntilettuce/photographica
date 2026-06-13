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

        int em = SnapmaticaClient.exposureMode;
        int shutterIdx = (em == 1 || em == 3) ? SnapmaticaClient.autoShutterIdx : SnapmaticaClient.shutterSpeedIdx;
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

        // Depth was already captured in onWorldRenderEnd() while the depth buffer was valid.
        final float[] capturedDepth = pendingLinearDepth;
        final int capturedFbW = pendingDepthFbW;
        final int capturedFbH = pendingDepthFbH;
        pendingLinearDepth = null;
        pendingDepthFbW = 0;
        pendingDepthFbH = 0;

        //? if >=1.21.11 {
        /*ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), raw -> processScreenshot(mc, raw, capturedDepth, capturedFbW, capturedFbH));*/
        //?} else {
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

    private static void processScreenshot(MinecraftClient mc, NativeImage raw, float[] linearDepth, int fbW, int fbH) {
        // ── Crop to 3:2 (landscape) or 2:3 (portrait) aspect ratio ──────────────
        int w = raw.getWidth();
        int h = raw.getHeight();
        float targetAspect = SnapmaticaClient.portraitOrientation ? 2f / 3f : 3f / 2f;
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
        // When the viewfinder is disabled, sneaking is just normal sneaking — skip the
        // depth capture and GL work entirely. Otherwise the per-frame depth-buffer copy /
        // FBO reads run without their renderBlur cleanup partner, leaving GL state dirty
        // and blacking out the hand and Distant Horizons passes.
        if (!SnapmaticaClient.viewfinderSneakEnabled && !capturePending) return;

        //? if >=1.21.11 {
        /*// In 1.21.11 glReadPixels(GL_DEPTH_COMPONENT) no longer reads the scene depth
        // because depth lives in a GpuTexture, not the legacy default FBO depth attachment.
        // mc.crosshairTarget is capped at interaction reach (~4.5 blocks), so it MISSes
        // for anything farther — leaving the focus plane and reticle frozen. Do our own
        // long-range raycast (blocks + entities) so focus tracks distant subjects too.
        final double maxDist = 1000.0;
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
        // The world raycast above is geometrically accurate for any loaded block out to
        // maxDist (1000), which covers every vanilla render distance. It is the PRIMARY
        // focus distance and is NOT overridden by the GPU depth reconstruction: that
        // reconstruction saturates at currentDepthFar (≈ rd*64) and was the root cause of
        // the "AF stuck at ~940m" bug.
        lastSceneDepthBlocks = (bestDist < maxDist) ? (float) bestDist : SnapmaticaClient.FOCUS_INFINITY;
        // Capture the depth texture (the EVF blur shader needs it every frame).
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW > 0 && vpH > 0) {
            int rd = mc.options.getViewDistance().getValue();
            EvfBlurRenderer.currentDepthFar = Math.max(rd * 64f, 256f);
            EvfBlurRenderer.captureDepth(vpW, vpH);
            // Only when the raycast missed (sky, or terrain beyond the loaded range) do we
            // fall back: first the GPU centre depth (rejecting saturated readings near the
            // far plane), then DH LOD terrain which may report km-scale distances.
            if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                float farPlane = EvfBlurRenderer.currentDepthFar;
                float gpuDepth = EvfBlurRenderer.readCenterLinearDepthBlocks();
                if (gpuDepth > 0.0f && gpuDepth < farPlane * 0.95f) {
                    lastSceneDepthBlocks = gpuDepth;
                }
                if (lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY) {
                    float dhDist = DhIntegration.queryLookDistance(mc);
                    if (dhDist > 0f) lastSceneDepthBlocks = dhDist;
                }
            }
            if (capturePending) {
                // Use the depth texture's own dimensions, not the GL viewport: shader mods
                // (Iris, etc.) can make those differ, causing readLinearDepthCpu to bail out
                // and the saved photo to have no DoF even though the EVF preview looked fine.
                float[] depth = EvfBlurRenderer.readLinearDepthCpu();
                if (depth != null) {
                    pendingLinearDepth = depth;
                    pendingDepthFbW    = EvfBlurRenderer.depthTexW;
                    pendingDepthFbH    = EvfBlurRenderer.depthTexH;
                }
            }
        }*/
        //?} else {
        // Read from the currently bound framebuffer without switching.
        GL11.glGetError(); // clear any pending GL error
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW <= 0 || vpH <= 0) return;

        // GPU-side depth copy for EVF DoF blur.
        int rd = mc.options.getViewDistance().getValue();
        EvfBlurRenderer.currentDepthFar = Math.max(rd * 64f, 256f);
        EvfBlurRenderer.captureDepth(vpW, vpH);
        if (capturePending) {
            float[] depth = EvfBlurRenderer.readLinearDepthCpu();
            if (depth != null) {
                pendingLinearDepth = depth;
                pendingDepthFbW    = EvfBlurRenderer.depthTexW;
                pendingDepthFbH    = EvfBlurRenderer.depthTexH;
            }
        }

        int cx = vpW / 2;
        int cy = vpH / 2;

        FloatBuffer depthBuf = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(cx, cy, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);
        float d = depthBuf.get(0);
        float ndc = 2.0f * d - 1.0f;

        // Reconstruct linear depth in world units (blocks ≈ metres)
        final float near = 0.05f;
        final float far  = EvfBlurRenderer.currentDepthFar;
        lastSceneDepthBlocks = 2.0f * near * far / (far + near - ndc * (far - near));
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
        if (linearDepth != null && SnapmaticaClient.aperture < 8.0f) {
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
        float maxBlurPx = Math.min(32.0f, 80.0f / (aperture * aperture));
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
                for (int dx = -r; dx <= r; dx++) {
                    int sx = Math.max(0, Math.min(iw - 1, ix + dx));
                    float gauss = (float) Math.exp(-(float)(dx * dx) / (2.0f * sigma * sigma));
                    float w = gauss * Math.min(1.0f, cocMap[iy * iw + sx] / coc);
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
                for (int dy = -r; dy <= r; dy++) {
                    int sy = Math.max(0, Math.min(ih - 1, iy + dy));
                    float gauss = (float) Math.exp(-(float)(dy * dy) / (2.0f * sigma * sigma));
                    float w = gauss * Math.min(1.0f, cocMap[sy * iw + ix] / coc);
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
