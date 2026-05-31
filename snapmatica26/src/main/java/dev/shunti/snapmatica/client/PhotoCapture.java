package dev.shunti.snapmatica.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

@Environment(EnvType.CLIENT)
public final class PhotoCapture {
    private PhotoCapture() {}

    public static long mirrorEndMs  = 0L;
    public static long flashEndMs   = 0L;
    public static long secondClickAtMs = 0L;

    public static float lastSceneDepthBlocks = 5.0f;

    private static long    lastShotMs      = 0L;
    private static boolean capturePending  = false;

    private static volatile float[] pendingLinearDepth = null;
    private static volatile int pendingDepthFbW = 0;
    private static volatile int pendingDepthFbH = 0;

    private static final long COOLDOWN_MS = 700L;

    public static boolean isCapturePending() { return capturePending; }
    public static boolean isBusy() { return System.currentTimeMillis() < mirrorEndMs; }

    public static void take() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastShotMs < COOLDOWN_MS) return;

        int    shutterIdx = SnapmaticaClient.shutterSpeedIdx;
        double shutterSec = SnapmaticaClient.SHUTTER_SECONDS[
                Math.max(0, Math.min(SnapmaticaClient.SHUTTER_SECONDS.length - 1, shutterIdx))];
        long shutterMs = Math.min(1500, (long)(shutterSec * 1000));

        mirrorEndMs     = now + 100 + shutterMs + 100;
        flashEndMs      = mirrorEndMs + Math.min(200, 20 + shutterMs / 2);
        secondClickAtMs = now + 100 + shutterMs;

        capturePending = true;
        lastShotMs     = now;
    }

    public static void captureIfPending() {
        if (!capturePending) return;
        capturePending = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Capture depth for photo DoF (GPU→CPU stall, once per shutter press).
        com.mojang.blaze3d.pipeline.RenderTarget mainFb = mc.getMainRenderTarget();
        int fbW = mainFb.width;
        int fbH = mainFb.height;
        if (fbW > 0 && fbH > 0) {
            EvfBlurRenderer.captureDepth(fbW, fbH);
            float[] depth = EvfBlurRenderer.readLinearDepthCpu(fbW, fbH);
            if (depth != null) {
                pendingLinearDepth = depth;
                pendingDepthFbW    = fbW;
                pendingDepthFbH    = fbH;
            }
        }

        final float[] capturedDepth = pendingLinearDepth;
        final int capturedFbW = pendingDepthFbW;
        final int capturedFbH = pendingDepthFbH;
        pendingLinearDepth = null;
        pendingDepthFbW = 0;
        pendingDepthFbH = 0;

        Screenshot.takeScreenshot(mainFb, raw -> processScreenshot(mc, raw, capturedDepth, capturedFbW, capturedFbH));
    }

    private static void processScreenshot(Minecraft mc, NativeImage raw, float[] linearDepth, int fbW, int fbH) {
        int   w    = raw.getWidth();
        int   h    = raw.getHeight();
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
                setPixel(cropped, x, y, getPixel(raw, x + offX, y + offY));
            }
        }
        raw.close();

        NativeImage processed = applyPhotoEffects(cropped, linearDepth, fbW, fbH);
        cropped.close();

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        File snapDir = new File(mc.gameDirectory, "snapmatica/photos");
        snapDir.mkdirs();
        File outFile = new File(snapDir, timestamp + ".png");

        try {
            processed.writeToFile(outFile.toPath());
            System.out.println("[Snapmatica] Photo saved: " + outFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[Snapmatica] Failed to save photo: " + e.getMessage());
        } finally {
            processed.close();
        }
    }

    public static void onWorldRenderEnd() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isShiftKeyDown()) return;

        final double maxDist = 1000.0;
        Vec3 eye  = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);
        Vec3 end  = eye.add(look.scale(maxDist));

        BlockHitResult blockHit = mc.level.clip(
                new ClipContext(eye, end,
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, mc.player));
        double bestDist = (blockHit != null
                && blockHit.getType() != HitResult.Type.MISS)
                ? eye.distanceTo(blockHit.getLocation()) : maxDist;

        AABB searchBox = mc.player.getBoundingBox()
                .expandTowards(look.scale(maxDist)).inflate(1.0);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                mc.player, eye, end, searchBox,
                e -> !e.isSpectator() && e.isAlive(), bestDist * bestDist);
        if (entityHit != null) {
            double eDist = eye.distanceTo(entityHit.getLocation());
            if (eDist < bestDist) bestDist = eDist;
        }

        lastSceneDepthBlocks = (bestDist < maxDist) ? (float) bestDist : 999.0f;

        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int vpW = viewport[2];
        int vpH = viewport[3];
        if (vpW > 0 && vpH > 0) {
            EvfBlurRenderer.currentDepthFar = Math.max(
                    mc.options.renderDistance().get() * 64f, 256f);
            EvfBlurRenderer.captureDepth(vpW, vpH);
        }
    }

    private static NativeImage applyPhotoEffects(NativeImage src, float[] linearDepth, int fbW, int fbH) {
        int   w     = src.getWidth();
        int   h     = src.getHeight();
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        double expFactor  = PhotoProcessor.exposureFactor();
        float  focusDist  = SnapmaticaClient.focusDistance;
        float  depthCenter = lastSceneDepthBlocks;

        NativeImage dst = new NativeImage(w, h, false);

        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int c = getPixel(src, px, py);
                int a = (c >>> 24) & 0xFF;
                int b = (c >>> 16) & 0xFF;
                int g = (c >>>  8) & 0xFF;
                int r =  c         & 0xFF;

                r = clamp((int)(r * expFactor));
                g = clamp((int)(g * expFactor));
                b = clamp((int)(b * expFactor));

                float dx  = (px - halfW) / halfW;
                float dy  = (py - halfH) / halfH;
                float vig = vignetteStrength(SnapmaticaClient.aperture);
                float vf  = Math.max(0f, 1f - vig * (dx * dx + dy * dy) * 0.5f);
                r = clamp((int)(r * vf));
                g = clamp((int)(g * vf));
                b = clamp((int)(b * vf));

                float noiseSigma = isoToNoiseSigma(SnapmaticaClient.iso);
                if (noiseSigma > 0.5f) {
                    float noise = (float)(Math.random() - 0.5) * noiseSigma * 1.5f;
                    r = clamp((int)(r + noise));
                    g = clamp((int)(g + noise));
                    b = clamp((int)(b + noise));
                }

                r = applyToneCurve(r);
                g = applyToneCurve(g);
                b = applyToneCurve(b);

                r = softClip(r);
                g = softClip(g);
                b = softClip(b);

                setPixel(dst, px, py, (a << 24) | (b << 16) | (g << 8) | r);
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

        int croppedW, croppedH, cropOffX, cropOffY;
        if ((float) fbW / fbH > 1.5f) {
            croppedH = fbH; croppedW = Math.round(fbH * 1.5f);
            cropOffX = (fbW - croppedW) / 2; cropOffY = 0;
        } else {
            croppedW = fbW; croppedH = Math.round(fbW / 1.5f);
            cropOffX = 0; cropOffY = (fbH - croppedH) / 2;
        }

        boolean infinityFocus = (focusDist >= 999.0f);
        float nearLimit = infinityFocus ? (10.0f / aperture) : 0.0f;
        float[] cocMap = new float[iw * ih];
        for (int iy = 0; iy < ih; iy++) {
            for (int ix = 0; ix < iw; ix++) {
                int fx    = Math.max(0, Math.min(fbW - 1, cropOffX + ix * croppedW / iw));
                int fy_gl = Math.max(0, Math.min(fbH - 1, fbH - 1 - (cropOffY + iy * croppedH / ih)));
                float depth = linearDepth[fy_gl * fbW + fx];
                float coc;
                if (infinityFocus) {
                    coc = Math.min(maxBlurPx, maxBlurPx * nearLimit / Math.max(depth, 0.05f));
                } else {
                    float r = depth / focusDist;
                    coc = (depth <= focusDist) ? (1.0f - r) * maxBlurPx : ((r - 1.0f) / r) * maxBlurPx;
                }
                cocMap[iy * iw + ix] = Math.min(coc, maxBlurPx);
            }
        }

        int[] hBuf = new int[iw * ih];
        for (int iy = 0; iy < ih; iy++) {
            for (int ix = 0; ix < iw; ix++) {
                float coc = cocMap[iy * iw + ix];
                if (coc < 0.5f) { hBuf[iy * iw + ix] = getPixel(src, ix, iy); continue; }
                int r = Math.min(maxR, (int) Math.ceil(coc));
                float sigma = Math.max(coc * 0.5f, 1.0f);
                float ra = 0, ga = 0, ba = 0, aa = 0, tw = 0;
                for (int dx = -r; dx <= r; dx++) {
                    int sx = Math.max(0, Math.min(iw - 1, ix + dx));
                    float gauss = (float) Math.exp(-(float)(dx * dx) / (2.0f * sigma * sigma));
                    float w = gauss * Math.min(1.0f, cocMap[iy * iw + sx] / coc);
                    if (w < 0.001f) continue;
                    int c = getPixel(src, sx, iy);
                    aa += ((c >>> 24) & 0xFF) * w; ba += ((c >>> 16) & 0xFF) * w;
                    ga += ((c >>>  8) & 0xFF) * w; ra += ( c         & 0xFF) * w;
                    tw += w;
                }
                hBuf[iy * iw + ix] = (tw < 0.001f) ? getPixel(src, ix, iy)
                        : ((clamp(Math.round(aa / tw)) << 24) | (clamp(Math.round(ba / tw)) << 16)
                         | (clamp(Math.round(ga / tw)) <<  8) |  clamp(Math.round(ra / tw)));
            }
        }

        NativeImage result = new NativeImage(iw, ih, false);
        for (int ix = 0; ix < iw; ix++) {
            for (int iy = 0; iy < ih; iy++) {
                float coc = cocMap[iy * iw + ix];
                if (coc < 0.5f) { setPixel(result, ix, iy, getPixel(src, ix, iy)); continue; }
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
                setPixel(result, ix, iy, (tw < 0.001f) ? hBuf[iy * iw + ix]
                        : ((clamp(Math.round(aa / tw)) << 24) | (clamp(Math.round(ba / tw)) << 16)
                         | (clamp(Math.round(ga / tw)) <<  8) |  clamp(Math.round(ra / tw))));
            }
        }
        return result;
    }

    private static int getPixel(NativeImage img, int x, int y) { return img.getPixel(x, y); }
    private static void setPixel(NativeImage img, int x, int y, int abgr) { img.setPixel(x, y, abgr); }

    private static float vignetteStrength(float aperture) {
        if (aperture <= 1.4f) return 0.70f; if (aperture <= 2.0f) return 0.55f;
        if (aperture <= 2.8f) return 0.40f; if (aperture <= 4.0f) return 0.25f;
        if (aperture <= 5.6f) return 0.15f; if (aperture <= 8.0f) return 0.08f;
        return 0.03f;
    }

    private static float isoToNoiseSigma(int iso) {
        if (iso <=   100) return  0.0f; if (iso <=   200) return  1.5f;
        if (iso <=   400) return  3.0f; if (iso <=   800) return  6.0f;
        if (iso <=  1600) return 11.0f; if (iso <=  3200) return 18.0f;
        if (iso <=  6400) return 28.0f; if (iso <= 12800) return 42.0f;
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
        return clamp((int)(200 + 55f * (1f - (float) Math.exp(-excess / 55f))));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
