package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public final class ViewfinderOverlay {
    private ViewfinderOverlay() {}

    private static final String[] SHUTTERS = {
            "30\"","15\"","8\"","4\"","2\"","1\"",
            "1/2","1/4","1/8","1/15","1/30","1/60",
            "1/125","1/250","1/500","1/1000","1/2000","1/4000"};

    public static void extractRenderState(GuiGraphicsExtractor ctx, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();

        if (now < PhotoCapture.mirrorEndMs) { ctx.fill(0,0,sw,sh,0xFF000000); return; }
        if (now < PhotoCapture.flashEndMs) {
            long d = PhotoCapture.flashEndMs - PhotoCapture.mirrorEndMs;
            if (d > 0) {
                int a = (int) Math.min(200L, (PhotoCapture.flashEndMs - now) * 200L / d);
                if (a > 0) ctx.fill(0,0,sw,sh,(a<<24)|0x00FFFFFF);
            }
            return;
        }
        if (!SnapmaticaClient.viewfinderSneakEnabled || !mc.player.isShiftKeyDown()) return;
        if (mc.screen != null) return;

        // Exactly the region the capture will crop to — see PhotoCapture.frameRect. The frame
        // is deliberately not inset any further: an inset box would be showing less than the
        // photo records, which is both a lie about the framing and a lie about the focal
        // length, since the angle you judge is the angle the box spans. It also has to match
        // what EvfBlurRenderer.applyBlur() blurs, or the strip between the two reads as a
        // hazy band along the edge of the screen — blurred, but outside the bezel.
        int[] fr = PhotoCapture.frameRect(sw, sh, SnapmaticaClient.portraitOrientation);
        int fx = fr[0], fy = fr[1], fw = fr[2], fh = fr[3];
        int fx2 = fx + fw, fy2 = fy + fh;

        ctx.fill(0,0,sw,fy,0xB8000000); ctx.fill(0,fy2,sw,sh,0xB8000000);
        ctx.fill(0,fy,fx,fy2,0xB8000000); ctx.fill(fx2,fy,sw,fy2,0xB8000000);

        renderEvfPreview(ctx, fx, fy, fx2, fy2);

        drawBracket(ctx,fx,fy,20,2,1,1,0xFFFFFFFF);
        drawBracket(ctx,fx2,fy,20,2,-1,1,0xFFFFFFFF);
        drawBracket(ctx,fx,fy2,20,2,1,-1,0xFFFFFFFF);
        drawBracket(ctx,fx2,fy2,20,2,-1,-1,0xFFFFFFFF);

        int t1x=fx+fw/3, t2x=fx+(fw*2)/3, t1y=fy+fh/3, t2y=fy+(fh*2)/3;
        ctx.fill(t1x,fy+4,t1x+1,fy2-4,0x60FFFFFF);
        ctx.fill(t2x,fy+4,t2x+1,fy2-4,0x60FFFFFF);
        ctx.fill(fx+4,t1y,fx2-4,t1y+1,0x60FFFFFF);
        ctx.fill(fx+4,t2y,fx2-4,t2y+1,0x60FFFFFF);

        int cx=sw/2, cy=sh/2, rc=focusReticleColor();
        ctx.fill(cx-10,cy,cx-3,cy+1,rc);
        ctx.fill(cx+3,cy,cx+10,cy+1,rc);
        ctx.fill(cx,cy-10,cx+1,cy-3,rc);
        ctx.fill(cx,cy+3,cx+1,cy+10,rc);

        Font font = mc.font;
        boolean hasLens = SnapmaticaClient.lensType != 0;
        String fp = hasLens ? (SnapmaticaClient.focalLengthMm+"mm") : "No Lens";
        int em = SnapmaticaClient.exposureMode;
        int si = clampIdx((em == 1 || em == 3) ? SnapmaticaClient.autoShutterIdx : SnapmaticaClient.shutterSpeedIdx, SHUTTERS.length);
        float dispAp = (em == 2 || em == 3) ? SnapmaticaClient.autoAperture : SnapmaticaClient.aperture;
        ctx.text(font, String.format("F%s  %s  ISO%d  %s",
                fmt(dispAp), SHUTTERS[si], SnapmaticaClient.iso, fp),
                fx+6, fy2-font.lineHeight-14, 0xFFE8DCC4, true);
        if (SnapmaticaClient.lensType != 0) {
            // Same predicate the DoF shader is driven from, so the readout cannot claim
            // infinity while the blur works off a finite focus distance.
            boolean atInf = AutoFocus.atInfinity();
            String fd = atInf ? "inf" : fmtFocusDist(SnapmaticaClient.focusDistance);
            ctx.text(font, fd, fx2 - font.width(fd) - 6, fy + 4, rc, true);
        }

        renderExposureMeter(ctx, fx, fx2, fy2);

        // Lens label. snapmatica has one lens and it zooms the whole range, so it is named
        // after that range rather than the fixed focal lengths photographica's kit had.
        String lens = hasLens
                ? CameraScrollHandler.focalMinMm() + "-" + CameraScrollHandler.focalMaxMm() + "mm"
                : Component.translatable("snapmatica.vf.no_lens").getString();
        ctx.text(font, lens, fx+6, fy+4, 0xFF9A8D72, true);

        if (hasLens) {
            double safe = 1.0 / SnapmaticaClient.focalLengthMm;
            if (SnapmaticaClient.SHUTTER_SECONDS[si] > safe * 1.5)
                ctx.text(font, "WARN Blur", fx+6, fy+4+font.lineHeight+2, 0xFFFF5555, true);
        }

        String[] el  = {"M","Av","Tv","P"};
        String[] fl2 = {"MF","AF","MOB"};
        ctx.text(font,
                el[clampIdx(SnapmaticaClient.exposureMode,4)]
                + " | " + fl2[clampIdx(SnapmaticaClient.focusMode,3)]
                +" | "+(SnapmaticaClient.portraitOrientation ? "3:2 V" : "3:2 H"),
                fx+6, fy+4+font.lineHeight*2+4, 0xFFCCCCFF, true);
    }

    private static void renderEvfPreview(GuiGraphicsExtractor ctx, int fx, int fy, int fx2, int fy2) {
        double ev = computeEvDeviation();
        double ae = Math.abs(ev);
        if (ae > 0.3) {
            double fr = Math.min(1, ae / 4);
            int a = Math.min(230, (int)(fr * fr * 230));
            ctx.fill(fx, fy, fx2, fy2, ev > 0 ? ((a<<24)|0x00FFFFFF) : (a<<24));
        }

        float sig = isoToNoiseSigma(SnapmaticaClient.iso);
        float eff = Math.max(0f, sig - 8f);
        if (eff > 0f) {
            int fw_ = fx2-fx, fh_ = fy2-fy;
            int nd = Math.min(400, (int)(eff * 7));
            int da = Math.min(80, (int)(eff * 2.5f));
            long rng = System.currentTimeMillis() / 150L * 2654435761L;
            for (int i = 0; i < nd; i++) {
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int gx = fx + (int)((rng >>> 33) % fw_);
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int gy = fy + (int)((rng >>> 33) % fh_);
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int gr = (int)((rng >>> 33) % 256);
                ctx.fill(gx, gy, gx+1, gy+1, (da<<24)|(gr<<16)|(gr<<8)|gr);
            }
        }

        float vs = evfVignetteStrength(SnapmaticaClient.aperture);
        if (vs > 0.01f) {
            int fw_ = fx2-fx, fh_ = fy2-fy;
            for (int b = 0; b < 6; b++) {
                float t = (float)(6-b)/6f;
                int a = (int)(vs * 80 * t * t);
                if (a < 2) continue;
                int bw = (6-b)*fw_/24, bh = (6-b)*fh_/24;
                int vc = (a<<24);
                ctx.fill(fx, fy, fx+bw, fy2, vc); ctx.fill(fx2-bw, fy, fx2, fy2, vc);
                ctx.fill(fx, fy, fx2, fy+bh, vc); ctx.fill(fx, fy2-bh, fx2, fy2, vc);
            }
        }
    }

    private static void renderExposureMeter(GuiGraphicsExtractor ctx, int fx, int fx2, int fy2) {
        final int MW = 120;
        int mx  = (fx + fx2 - MW) / 2;
        int mcx = mx + MW / 2;
        int by  = fy2 - 5;
        double ev = computeEvDeviation();
        float pp = MW / 6f;

        ctx.fill(mx, by, mx+MW, by+1, 0x80FFFFFF);
        for (int e = -3; e <= 3; e++) {
            int tx = mcx + (int)(e * pp);
            ctx.fill(tx, by-(e==0?6:3), tx+1, by+1, 0xC0FFFFFF);
        }
        float cl = (float) Math.max(-3.5, Math.min(3.5, ev));
        int px = mcx + (int)(cl * pp);
        ctx.fill(px-1, by-7, px+2, by+2,
                Math.abs(ev) <= 2.0 ? 0xFFE08A3C : 0xFFC2362B);
    }

    private static double computeEvDeviation() {
        int em = SnapmaticaClient.exposureMode;
        int si = (em == 1 || em == 3) ? SnapmaticaClient.autoShutterIdx : SnapmaticaClient.shutterSpeedIdx;
        float ap = (em == 2 || em == 3) ? SnapmaticaClient.autoAperture : SnapmaticaClient.aperture;
        double ss = SnapmaticaClient.SHUTTER_SECONDS[clampIdx(si, SHUTTERS.length)];
        return Math.log(ss * 60.0 * Math.pow(5.6 / ap, 2)
                * (SnapmaticaClient.iso / 400.0)) / Math.log(2.0);
    }

    private static int focusReticleColor() {
        if (SnapmaticaClient.lensType == 0 || SnapmaticaClient.aperture >= 8f)
            return 0xFFFFFFFF;
        // Testing focusDistance directly never fires in AF: AutoFocus clamps the value to
        // its finite far anchor, so the reticle fell through to the next test, saw the scene
        // depth reading infinity, and went red on a correctly focused sky.
        if (AutoFocus.atInfinity())
            return 0xFFFFFFFF;
        if (PhotoCapture.lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY)
            return 0xFFE04040;  // sky / beyond range — always out of focus
        float tol  = SnapmaticaClient.focusDistance * SnapmaticaClient.aperture * 0.08f;
        float diff = Math.abs(PhotoCapture.lastSceneDepthBlocks - SnapmaticaClient.focusDistance);
        if (diff <= tol)       return 0xFF7CE67C;
        if (diff <= tol*2.5f)  return 0xFFFFCC44;
        return 0xFFE04040;
    }

    private static float isoToNoiseSigma(int iso) {
        if (iso <= 100) return 0f; if (iso <= 200) return 1.5f;
        if (iso <= 400) return 3f; if (iso <= 800) return 6f;
        if (iso <= 1600) return 11f; if (iso <= 3200) return 18f;
        if (iso <= 6400) return 28f; if (iso <= 12800) return 42f;
        return 60f;
    }

    private static float evfVignetteStrength(float ap) {
        if (ap <= 1.4f) return 0.90f; if (ap <= 2.0f) return 0.72f;
        if (ap <= 2.8f) return 0.55f; if (ap <= 4.0f) return 0.38f;
        if (ap <= 5.6f) return 0.22f; if (ap <= 8.0f) return 0.11f;
        if (ap <= 11f)  return 0.05f; return 0.02f;
    }

    private static void drawBracket(GuiGraphicsExtractor ctx, int ax, int ay, int len, int t,
                                    int dx, int dy, int color) {
        ctx.fill(dx>0?ax:ax-len, dy>0?ay:ay-t, dx>0?ax+len:ax, dy>0?ay+t:ay, color);
        ctx.fill(dx>0?ax:ax-t, dy>0?ay:ay-len, dx>0?ax+t:ax, dy>0?ay+len:ay, color);
    }

    private static int clampIdx(int idx, int len) { return Math.max(0, Math.min(len-1, idx)); }
    private static String fmt(float v) {
        return v == (int)v ? String.valueOf((int)v) : String.format("%.1f", v);
    }

    private static String fmtFocusDist(float v) {
        if (v >= SnapmaticaClient.FOCUS_INFINITY) return "∞";
        if (v < 10.0f) return String.format("%.1fm", v);
        return Math.round(v) + "m";
    }
}
