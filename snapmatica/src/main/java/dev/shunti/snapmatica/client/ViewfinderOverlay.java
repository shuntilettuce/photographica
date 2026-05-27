package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

/**
 * Viewfinder overlay + blackout/flash effects + EVF live preview.
 * Ported from Photographica's ViewfinderHud.
 */
@Environment(EnvType.CLIENT)
public final class ViewfinderOverlay {
    private ViewfinderOverlay() {}

    private static final String[] SHUTTERS = {
            "30\"","15\"","8\"","4\"","2\"","1\"",
            "1/2","1/4","1/8","1/15","1/30","1/60",
            "1/125","1/250","1/500","1/1000","1/2000","1/4000"};
    private static final String[] LENS_NAMES =
            {"No Lens","50mm Prime","24-70mm Zoom","35mm Prime","85mm Prime","14mm UWA","70-200mm Zoom","100mm Macro"};

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();

        if (now < PhotoCapture.mirrorEndMs) { ctx.fill(0,0,sw,sh,0xFF000000); return; }
        if (now < PhotoCapture.flashEndMs) {
            long d = PhotoCapture.flashEndMs - PhotoCapture.mirrorEndMs;
            if (d > 0) { int a = (int)Math.min(200L,(PhotoCapture.flashEndMs-now)*200L/d); if (a>0) ctx.fill(0,0,sw,sh,(a<<24)|0x00FFFFFF); }
            return;
        }
        if (!SnapmaticaClient.viewfinderSneakEnabled || !mc.player.isSneaking()) return;
        if (mc.currentScreen != null) return;

        float aspect = 3f/2f;
        int fh = (int)(sh*0.86f), fw = (int)(fh*aspect);
        if (fw > sw*0.94f) { fw = (int)(sw*0.94f); fh = (int)(fw/aspect); }
        int fx = (sw-fw)/2, fy = (sh-fh)/2, fx2 = fx+fw, fy2 = fy+fh;

        // EVF real-time DoF blur (GPU shader, depth-aware) — rendered before bezels
        // so it only affects the scene inside the viewfinder frame.
        boolean hasLensForBlur = SnapmaticaClient.lensType != 0;
        if (hasLensForBlur && SnapmaticaClient.aperture < 8.0f
                && SnapmaticaClient.focusDistance < 999.0f) {
            EvfBlurRenderer.renderBlur(fx, fy, fx2, fy2,
                    SnapmaticaClient.focusDistance, SnapmaticaClient.aperture);
        }

        // Bezels
        ctx.fill(0,0,sw,fy,0xB8000000); ctx.fill(0,fy2,sw,sh,0xB8000000);
        ctx.fill(0,fy,fx,fy2,0xB8000000); ctx.fill(fx2,fy,sw,fy2,0xB8000000);

        // EVF preview overlays (exposure tint + ISO grain + vignette)
        renderEvfPreview(ctx, fx, fy, fx2, fy2);

        // Corner brackets
        drawBracket(ctx,fx,fy,20,2,1,1,0xFFFFFFFF);
        drawBracket(ctx,fx2,fy,20,2,-1,1,0xFFFFFFFF);
        drawBracket(ctx,fx,fy2,20,2,1,-1,0xFFFFFFFF);
        drawBracket(ctx,fx2,fy2,20,2,-1,-1,0xFFFFFFFF);

        // Rule-of-thirds guides
        int t1x=fx+fw/3,t2x=fx+(fw*2)/3,t1y=fy+fh/3,t2y=fy+(fh*2)/3;
        ctx.fill(t1x,fy+4,t1x+1,fy2-4,0x60FFFFFF);
        ctx.fill(t2x,fy+4,t2x+1,fy2-4,0x60FFFFFF);
        ctx.fill(fx+4,t1y,fx2-4,t1y+1,0x60FFFFFF);
        ctx.fill(fx+4,t2y,fx2-4,t2y+1,0x60FFFFFF);

        // Focus reticle (colour changes based on depth match)
        int cx=sw/2,cy=sh/2,rc=focusReticleColor();
        ctx.fill(cx-10,cy,cx-3,cy+1,rc);
        ctx.fill(cx+3,cy,cx+10,cy+1,rc);
        ctx.fill(cx,cy-10,cx+1,cy-3,rc);
        ctx.fill(cx,cy+3,cx+1,cy+10,rc);

        // Info text
        TextRenderer tr=mc.textRenderer;
        boolean hasLens=SnapmaticaClient.lensType!=0;
        String fp=hasLens?(SnapmaticaClient.focalLengthMm+"mm"):"No Lens";
        int si=Math.max(0,Math.min(SHUTTERS.length-1,SnapmaticaClient.shutterSpeedIdx));
        ctx.drawTextWithShadow(tr,Text.literal(String.format("F%s  %s  ISO%d  %s",
                fmt(SnapmaticaClient.aperture),SHUTTERS[si],SnapmaticaClient.iso,fp)),
                fx+6,fy2-tr.fontHeight-14,0xFFE8DCC4);

        // Exposure meter
        renderExposureMeter(ctx, fx, fx2, fy2);

        // Lens label
        ctx.drawTextWithShadow(tr,Text.literal(LENS_NAMES[
                Math.max(0,Math.min(LENS_NAMES.length-1,SnapmaticaClient.lensType))]),
                fx+6,fy+4,0xFF9A8D72);

        // Shake warning
        if (hasLens) {
            double safe=1.0/SnapmaticaClient.focalLengthMm;
            if (SnapmaticaClient.SHUTTER_SECONDS[si]>safe*1.5)
                ctx.drawTextWithShadow(tr,Text.literal("WARN Blur"),
                        fx+6,fy+4+tr.fontHeight+2,0xFFFF5555);
        }

        // Mode indicator
        String[] el={"M","Av","Tv","P"};
        String[] fl2={"MF","AF","MOB"};
        ctx.drawTextWithShadow(tr,Text.literal(
                el[clampIdx(SnapmaticaClient.exposureMode,4)]
                +" | "+fl2[clampIdx(SnapmaticaClient.focusMode,3)]),
                fx+6,fy+4+tr.fontHeight*2+4,0xFFCCCCFF);

    }

    // ── EVF live preview ────────────────────────────────────────────────────────

    private static void renderEvfPreview(DrawContext ctx, int fx, int fy, int fx2, int fy2) {
        // 1. Exposure tint
        double ev = computeEvDeviation();
        double ae = Math.abs(ev);
        if (ae > 0.3) {
            double fr = Math.min(1, ae / 4);
            int a = Math.min(230, (int)(fr * fr * 230));
            ctx.fill(fx, fy, fx2, fy2,
                    ev > 0 ? ((a << 24) | 0x00FFFFFF) : (a << 24));
        }

        // 2. ISO grain
        float sig = isoToNoiseSigma(SnapmaticaClient.iso);
        float eff = Math.max(0f, sig - 8f);
        if (eff > 0f) {
            int fw = fx2 - fx, fh = fy2 - fy;
            int nd = Math.min(400, (int)(eff * 7));
            int da = Math.min(80, (int)(eff * 2.5f));
            long rng = System.currentTimeMillis() / 150L * 2654435761L;
            for (int i = 0; i < nd; i++) {
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int gx = fx + (int)((rng >>> 33) % fw);
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int gy = fy + (int)((rng >>> 33) % fh);
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int gr = (int)((rng >>> 33) % 256);
                ctx.fill(gx, gy, gx + 1, gy + 1,
                        (da << 24) | (gr << 16) | (gr << 8) | gr);
            }
        }

        // 3. Vignette
        float vs = evfVignetteStrength(SnapmaticaClient.aperture);
        if (vs > 0.01f) {
            int fw = fx2 - fx, fh = fy2 - fy;
            for (int b = 0; b < 6; b++) {
                float t = (float)(6 - b) / 6f;
                int a = (int)(vs * 80 * t * t);
                if (a < 2) continue;
                int bw = (6 - b) * fw / 24;
                int bh = (6 - b) * fh / 24;
                int vc = (a << 24);
                ctx.fill(fx, fy, fx + bw, fy2, vc);
                ctx.fill(fx2 - bw, fy, fx2, fy2, vc);
                ctx.fill(fx, fy, fx2, fy + bh, vc);
                ctx.fill(fx, fy2 - bh, fx2, fy2, vc);
            }
        }
    }

    // ── Exposure meter ──────────────────────────────────────────────────────────

    private static void renderExposureMeter(DrawContext ctx, int fx, int fx2, int fy2) {
        final int MW = 120;
        int mx = (fx + fx2 - MW) / 2;
        int mcx = mx + MW / 2;
        int by = fy2 - 5;
        double ev = computeEvDeviation();
        float pp = MW / 6f;

        ctx.fill(mx, by, mx + MW, by + 1, 0x80FFFFFF);
        for (int e = -3; e <= 3; e++) {
            int tx = mcx + (int)(e * pp);
            ctx.fill(tx, by - (e == 0 ? 6 : 3), tx + 1, by + 1, 0xC0FFFFFF);
        }
        float cl = (float) Math.max(-3.5, Math.min(3.5, ev));
        int px = mcx + (int)(cl * pp);
        ctx.fill(px - 1, by - 7, px + 2, by + 2,
                Math.abs(ev) <= 2.0 ? 0xFFE08A3C : 0xFFC2362B);
    }

    private static double computeEvDeviation() {
        double ss = SnapmaticaClient.SHUTTER_SECONDS[
                clampIdx(SnapmaticaClient.shutterSpeedIdx, SHUTTERS.length)];
        return Math.log(ss * 60.0 * Math.pow(5.6 / SnapmaticaClient.aperture, 2)
                * (SnapmaticaClient.iso / 400.0)) / Math.log(2.0);
    }

    // ── Focus reticle ───────────────────────────────────────────────────────────

    private static int focusReticleColor() {
        if (SnapmaticaClient.lensType == 0 || SnapmaticaClient.aperture >= 8f)
            return 0xFFFFFFFF;
        if (SnapmaticaClient.focusDistance >= 999f)
            return 0xFFFFFFFF;

        float tol = SnapmaticaClient.focusDistance * SnapmaticaClient.aperture * 0.08f;
        float diff = Math.abs(PhotoCapture.lastSceneDepthBlocks - SnapmaticaClient.focusDistance);
        if (diff <= tol) return 0xFF7CE67C;
        if (diff <= tol * 2.5f) return 0xFFFFCC44;
        return 0xFFE04040;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

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
        if (ap <= 11f) return 0.05f; return 0.02f;
    }

    private static void drawBracket(DrawContext ctx, int ax, int ay, int len, int t,
                                    int dx, int dy, int color) {
        ctx.fill(dx>0?ax:ax-len, dy>0?ay:ay-t, dx>0?ax+len:ax, dy>0?ay+t:ay, color);
        ctx.fill(dx>0?ax:ax-t, dy>0?ay:ay-len, dx>0?ax+t:ax, dy>0?ay+len:ay, color);
    }

    private static int clampIdx(int idx, int len) {
        return Math.max(0, Math.min(len - 1, idx));
    }

    private static String fmt(float v) {
        return v == (int)v ? String.valueOf((int)v) : String.format("%.1f", v);
    }
}
