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

        Screenshot.takeScreenshot(mc.getMainRenderTarget(), raw -> processScreenshot(mc, raw));
    }

    private static void processScreenshot(Minecraft mc, NativeImage raw) {
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

        NativeImage processed = applyPhotoEffects(cropped);
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

        final double maxDist = 256.0;
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
        if (vpW > 0 && vpH > 0) EvfBlurRenderer.captureDepth(vpW, vpH);
    }

    private static NativeImage applyPhotoEffects(NativeImage src) {
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

        if (SnapmaticaClient.aperture < 8.0f && focusDist < 999.0f) {
            float dofStrength = (8.0f - SnapmaticaClient.aperture) / 6.6f;
            if (dofStrength > 0.01f) {
                int blurPasses = (int)(dofStrength * 3);
                int radius     = 1 + (int)(dofStrength * 4);
                for (int pass = 0; pass < blurPasses; pass++) {
                    dst = boxBlur(dst, radius, focusDist, depthCenter);
                }
            }
        }

        return dst;
    }

    private static NativeImage boxBlur(NativeImage src, int radius,
                                       float focusDist, float depthCenter) {
        int w = src.getWidth();
        int h = src.getHeight();
        NativeImage tmp = new NativeImage(w, h, false);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float weight = depthWeight(y, h, radius, focusDist, depthCenter);
                if (weight <= 0f) { setPixel(tmp, x, y, getPixel(src, x, y)); continue; }
                int ar = 0, ag = 0, ab = 0, aa = 0, count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sx = x + k;
                    if (sx < 0 || sx >= w) continue;
                    int c = getPixel(src, sx, y);
                    ar += (c >>> 24) & 0xFF; ag += (c >>> 16) & 0xFF;
                    ab += (c >>>  8) & 0xFF; aa +=  c         & 0xFF;
                    count++;
                }
                if (count == 0) { setPixel(tmp, x, y, getPixel(src, x, y)); continue; }
                int nc = ((ar/count)<<24)|((ag/count)<<16)|((ab/count)<<8)|(aa/count);
                setPixel(tmp, x, y, blend(getPixel(src, x, y), nc, weight));
            }
        }

        NativeImage dst = new NativeImage(w, h, false);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float weight = depthWeight(y, h, radius, focusDist, depthCenter);
                if (weight <= 0f) { setPixel(dst, x, y, getPixel(tmp, x, y)); continue; }
                int ar = 0, ag = 0, ab = 0, aa = 0, count = 0;
                for (int k = -radius; k <= radius; k++) {
                    int sy = y + k;
                    if (sy < 0 || sy >= h) continue;
                    int c = getPixel(tmp, x, sy);
                    ar += (c >>> 24) & 0xFF; ag += (c >>> 16) & 0xFF;
                    ab += (c >>>  8) & 0xFF; aa +=  c         & 0xFF;
                    count++;
                }
                if (count == 0) { setPixel(dst, x, y, getPixel(tmp, x, y)); continue; }
                int nc = ((ar/count)<<24)|((ag/count)<<16)|((ab/count)<<8)|(aa/count);
                setPixel(dst, x, y, blend(getPixel(tmp, x, y), nc, weight));
            }
        }
        tmp.close();
        return dst;
    }

    private static float depthWeight(int y, int h, int radius, float focusDist, float depthCenter) {
        float ny = (float) y / h;
        float distFromCenter = Math.abs(ny - 0.5f) * 2f;
        float strength = Math.min(1f, distFromCenter * 1.5f);
        float ap = SnapmaticaClient.aperture;
        float apFactor = Math.min(1f, (8f - ap) / 6.6f);
        return strength * apFactor * 0.7f;
    }

    private static int blend(int orig, int blurred, float weight) {
        int origR = (orig >>> 24) & 0xFF; int origG = (orig >>> 16) & 0xFF;
        int origB = (orig >>>  8) & 0xFF; int origA =  orig         & 0xFF;
        int blrR  = (blurred >>> 24) & 0xFF; int blrG = (blurred >>> 16) & 0xFF;
        int blrB  = (blurred >>>  8) & 0xFF; int blrA =  blurred         & 0xFF;
        float inv = 1f - weight;
        return (clamp((int)(origR*inv+blrR*weight))<<24)
             | (clamp((int)(origG*inv+blrG*weight))<<16)
             | (clamp((int)(origB*inv+blrB*weight))<<8)
             |  clamp((int)(origA*inv+blrA*weight));
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
