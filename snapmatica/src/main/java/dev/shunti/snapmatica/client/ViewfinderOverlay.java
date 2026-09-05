package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
//? if >=1.21 {
import net.minecraft.client.render.RenderTickCounter;
//?}
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

    // Fabric's HudRenderCallback passes a RenderTickCounter from 1.21 on; 1.20.1's still
    // passes the plain tickDelta float directly. Unused either way — the parameter only
    // needs to satisfy the functional interface's shape.
    //? if >=1.21 {
    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
    //?} else {
    /*public static void render(DrawContext ctx, float tickDelta) {
    *///?}
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        long now = System.currentTimeMillis();
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();

        // Exactly the region the capture will crop to — see PhotoCapture.frameRect. The frame
        // is deliberately not inset any further: an inset box would be showing less than the
        // photo records, which is both a lie about the framing and a lie about the focal
        // length, since the angle you judge is the angle the box spans.
        int[] fr = PhotoCapture.frameRect(sw, sh, SnapmaticaClient.portraitOrientation);
        int fx = fr[0], fy = fr[1], fw = fr[2], fh = fr[3];
        int fx2 = fx + fw, fy2 = fy + fh;

        // While the aperture is being integrated, cover the finder.
        //
        // The burst really does move the viewpoint across the entrance pupil — a metre of it,
        // at a diorama world scale — and every one of those sub-frames is presented, so the
        // world visibly lurches for as long as the shutter is open. Nothing is wrong when that
        // happens; it is the parallax that makes the defocus real. But a photographer has no
        // business watching it, and no camera shows it: the finder blacks out while the shutter
        // is open and comes back when it closes.
        //
        // Safe to draw because of WHERE this runs. The readback that feeds the sum happens in
        // GameRendererMixin immediately after renderWorld, and the HUD — this — is drawn after
        // that. The cover is on the photographer's screen and never in the photograph.
        if (ApertureIntegration.isActive()) {
            ctx.fill(0, 0, sw, sh, 0xF00A0A0A);
            // Two phases, and they are different things: the shutter is open for the exposure
            // while EntityExposure records it tick by tick, and only then does the burst walk
            // the pupil. A thirty-second photograph spends thirty seconds in the first and a
            // second and a half in the second, so saying which is which is worth a word.
            boolean rec = EntityExposure.isRecording();
            String msg = rec ? "SHUTTER OPEN" : "EXPOSING";
            int tw = mc.textRenderer.getWidth(msg);
            ctx.drawText(mc.textRenderer, msg, (sw - tw) / 2, sh / 2 - 12, 0xFFB0B0B0, false);
            int barW = Math.min(fw, 180), barX = (sw - barW) / 2, barY = sh / 2 + 4;
            ctx.fill(barX, barY, barX + barW, barY + 2, 0xFF3A3A3A);
            float prog = rec ? EntityExposure.recordProgress() : ApertureIntegration.progress();
            ctx.fill(barX, barY, barX + (int) (barW * prog), barY + 2, 0xFFD0D0D0);
            return;
        }

        // The depth-of-field pass is not driven from here at all — EvfBlurRenderer.applyBlur()
        // decides for itself, straight after renderWorld, so its optics match the frame they
        // are applied to. Queuing it from the HUD put it one frame behind the field of view.
        if (now < PhotoCapture.mirrorEndMs) { ctx.fill(0,0,sw,sh,0xFF000000); return; }
        if (now < PhotoCapture.flashEndMs) {
            long d = PhotoCapture.flashEndMs - PhotoCapture.mirrorEndMs;
            if (d > 0) { int a = (int)Math.min(200L,(PhotoCapture.flashEndMs-now)*200L/d); if (a>0) ctx.fill(0,0,sw,sh,(a<<24)|0x00FFFFFF); }
            return;
        }
        if (!SnapmaticaClient.viewfinderActive(mc)) return;
        if (mc.currentScreen != null) return;

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

        // Focus reticle: SPOT keeps the original crosshair (it was never a problem on its
        // own — only ZONE needed something that reads as an AREA rather than a single point).
        // ZONE draws a 3x3 grid of small open AF-point boxes instead, the same multi-point
        // display a real mirrorless body draws over its metering/AF area — see
        // SnapmaticaClient.focusAreaWide. Colour changes based on depth match either way.
        int cx=sw/2,cy=sh/2,rc=focusReticleColor();
        if (SnapmaticaClient.focusAreaWide) {
            drawAfPointGrid(ctx,cx,cy,rc);
        } else {
            ctx.fill(cx-10,cy,cx-3,cy+1,rc);
            ctx.fill(cx+3,cy,cx+10,cy+1,rc);
            ctx.fill(cx,cy-10,cx+1,cy-3,rc);
            ctx.fill(cx,cy+3,cx+1,cy+10,rc);
        }

        // Info text
        TextRenderer tr=mc.textRenderer;
        boolean hasLens=SnapmaticaClient.lensType!=0;
        String fp=hasLens?(SnapmaticaClient.focalLengthMm+"mm")
                :Text.translatable("snapmatica.vf.no_lens").getString();
        int em = SnapmaticaClient.exposureMode;
        int si = clampIdx((em == 1 || em == 3) ? SnapmaticaClient.autoShutterIdx : SnapmaticaClient.shutterSpeedIdx, SHUTTERS.length);
        float dispAp = (em == 2 || em == 3) ? SnapmaticaClient.autoAperture : SnapmaticaClient.aperture;
        ctx.drawTextWithShadow(tr, String.format("F%s  %s  ISO%d  %s",
                fmt(dispAp),SHUTTERS[si],SnapmaticaClient.iso,fp),
                fx+6,fy2-tr.fontHeight-14,0xFFE8DCC4);
        if (SnapmaticaClient.lensType != 0) {
            // Same predicate the DoF shader is driven from, so the readout cannot claim
            // infinity while the blur is still working off a finite focus distance.
            boolean atInf = AutoFocus.atInfinity();
            String fd = atInf ? "inf"
                    : fmtFocusDist(SnapmaticaClient.focusDistance, SnapmaticaClient.dofScaleMm);
            ctx.drawTextWithShadow(tr, fd, fx2 - tr.getWidth(fd) - 6, fy + 4, rc);
        }

        // Exposure meter
        renderExposureMeter(ctx, fx, fx2, fy2);

        // Lens label. snapmatica has one lens and it zooms the whole range, so it is named
        // after that range rather than the fixed focal lengths photographica's kit had.
        String lens = hasLens
                ? CameraScrollHandler.focalMinMm() + "-" + CameraScrollHandler.focalMaxMm() + "mm"
                : Text.translatable("snapmatica.vf.no_lens").getString();
        ctx.drawTextWithShadow(tr, Text.literal(lens), fx+6, fy+4, 0xFF9A8D72);

        // Mode indicator. Sits directly under the lens label — there used to be a shake
        // warning line between them, but a handheld-shake simulation is a photographica
        // concept that snapmatica never carried, so the check (and the gap it left) is gone.
        String[] el={"M","Av","Tv","P"};
        String[] fl2={"MF","AF","MOB"};
        ctx.drawTextWithShadow(tr,Text.literal(
                el[clampIdx(SnapmaticaClient.exposureMode,4)]
                +" | "+fl2[clampIdx(SnapmaticaClient.focusMode,3)]
                +" | "+(SnapmaticaClient.portraitOrientation?"3:2 V":"3:2 H")),
                fx+6,fy+4+tr.fontHeight+2,0xFFCCCCFF);

    }

    // ── EVF live preview ────────────────────────────────────────────────────────

    private static void renderEvfPreview(DrawContext ctx, int fx, int fy, int fx2, int fy2) {
        // A flat whole-frame alpha wash used to stand in for exposure preview here, on the
        // dial's deviation from neutral alone. It was never anything more than an
        // approximation, and now that EvfBlurRenderer's own exposure gain and DynamicRangeSim
        // crush/rolloff are baked directly into the SAME framebuffer this finder is already
        // reading (real per-pixel processing, not a preview of it), a second, cruder, whole-
        // frame darken/brighten layered on top just diverged from what actually got captured
        // — most visibly once dynamic range simulation started giving the real thing a lot
        // more range to move in than this flat approximation ever modelled. Removed outright
        // rather than re-tuned: the finder already shows the real result without it.

        // ISO grain — same Auto-ISO assist target the saved photo's own noise pass reads,
        // so the finder actually shows the grain a shot leaning on it would come out with.
        float sig = isoToNoiseSigma((int) Math.round(SnapmaticaClient.autoIsoIdeal));
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
                // Some of the specks are coloured, matching the chroma component the saved
                // photo's own noise pass adds (see PhotoCapture.chromaNoiseRatio) — a high-ISO
                // frame is mottled with colour, not merely grainy, and the finder saying
                // otherwise would understate exactly the reason to avoid the ISO.
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int spread = (int)(eff * 0.9f);
                int cr = clampByte(gr + (int)((rng >>> 33) % (2L * spread + 1)) - spread);
                rng = rng * 6364136223846793005L + 1442695040888963407L;
                int cb = clampByte(gr + (int)((rng >>> 33) % (2L * spread + 1)) - spread);
                // Coloured specks are drawn as small BLOCKS rather than single pixels, for the
                // same reason the saved photo's chroma noise is generated coarse: colour noise
                // is correlated over several pixels by demosaicing, so it mottles rather than
                // shimmers. A one-pixel coloured dot is the wrong scale even when it is the
                // right amplitude.
                int blob = (cr == cb) ? 1 : 2;
                ctx.fill(gx, gy, gx + blob, gy + blob,
                        (da << 24) | (cr << 16) | (gr << 8) | cb);
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

    /**
     * Same continuous-vs-rounded distinction as {@link PhotoProcessor#exposureFactor}: an auto
     * axis reads its exact target, not the stop it rounds to for the F/shutter readout, or the
     * needle would swing a full stop on a boundary crossing the photo itself never sees.
     *
     * <p>Deviation from the fixed neutral reference (f/5.6, 1/60, ISO 400) — how far the DIAL
     * itself sits from that point, nothing about what the lens is actually pointed at. This is
     * what the exposure TINT ({@link #renderEvfPreview}) reads: it approximates underexposure
     * as a flat alpha-black wash over the whole frame, which is only a fair stand-in for real
     * exposure preview across the few stops a dial realistically sits away from neutral — real
     * metering can swing eight stops between a cave and its own doorway, and at that range the
     * same wash saturates its opacity and flattens bright and dark content to the same near-
     * black alike, reading as the whole frame dimming rather than as a properly exposed subject
     * against a blown-out sky. {@link #computeEvDeviation} is the version that includes real
     * metering, for the needle only.
     */
    private static double dialDeviation() {
        int em = SnapmaticaClient.exposureMode;
        double ss = (em == 1 || em == 3)
                ? SnapmaticaClient.autoShutterSecondsIdeal
                : SnapmaticaClient.SHUTTER_SECONDS[clampIdx(SnapmaticaClient.shutterSpeedIdx, SHUTTERS.length)];
        double ap = (em == 2 || em == 3) ? SnapmaticaClient.autoApertureIdeal : SnapmaticaClient.aperture;
        return Math.log(ss * 60.0 * Math.pow(5.6 / ap, 2)
                * (SnapmaticaClient.iso / 400.0)) / Math.log(2.0);
    }

    /**
     * {@link #dialDeviation} plus {@link SnapmaticaClient#getMeteredExtraStops} — how far the
     * dial sits from what THIS scene actually needs, 0 with metering off (the getter is 0
     * there, same as before dynamic range simulation existed). Feeds the exposure meter needle
     * ({@link #renderExposureMeter}) only; the tint reads {@link #dialDeviation} instead — see
     * its doc for why the two must not be the same call.
     */
    private static double computeEvDeviation() {
        // Both terms are stops the dial has to make UP: what the meter read off the scene, and
        // what an ND filter is holding back. The finder shows the result of both directly now,
        // so the needle reports the same thing the picture does rather than standing in for it.
        return dialDeviation() - SnapmaticaClient.getMeteredExtraStops() - SnapmaticaClient.ndStops;
    }

    // ── Focus reticle ───────────────────────────────────────────────────────────

    private static int focusReticleColor() {
        if (SnapmaticaClient.lensType == 0)
            return 0xFFFFFFFF;
        // Infinity focus — the same predicate the label and the DoF shader use. Testing
        // focusDistance directly (as this did) never fires in AF: AutoFocus clamps the
        // value to its finite far anchor, so the reticle fell through to the next test,
        // saw the scene depth reading infinity, and went red on a correctly focused sky.
        if (AutoFocus.atInfinity())
            return 0xFFFFFFFF;
        if (PhotoCapture.lastSceneDepthBlocks >= SnapmaticaClient.FOCUS_INFINITY)
            return 0xFFE04040;

        float tol = SnapmaticaClient.focusDistance * SnapmaticaClient.aperture * 0.08f;
        float diff = Math.abs(PhotoCapture.lastSceneDepthBlocks - SnapmaticaClient.focusDistance);
        if (diff <= tol) return 0xFF7CE67C;
        if (diff <= tol * 2.5f) return 0xFFFFCC44;
        return 0xFFE04040;
    }

    /** A 3x3 grid of AF-point boxes for ZONE mode — a real body's multi-point AF display,
     *  not a literal picture of the five sample rays {@link
     *  PhotoCapture#nearestSubjectDistance} actually casts (a diamond, not a grid); the grid
     *  reads as "AF area" at a glance the way the true sample pattern would not. Only called
     *  for ZONE — SPOT keeps the plain crosshair. */
    private static void drawAfPointGrid(DrawContext ctx, int cx, int cy, int color) {
        final int SPACING = 16; // px between box centres
        for (int gy = -1; gy <= 1; gy++) {
            for (int gx = -1; gx <= 1; gx++) {
                drawAfBox(ctx, cx + gx * SPACING, cy + gy * SPACING, color);
            }
        }
    }

    /** One open (unfilled) AF-point box — the shape a real EVF draws over each candidate
     *  point, rather than a crosshair. */
    private static void drawAfBox(DrawContext ctx, int x, int y, int color) {
        final int H = 4; // half-size, px
        ctx.fill(x - H, y - H, x + H, y - H + 1, color);   // top
        ctx.fill(x - H, y + H - 1, x + H, y + H, color);   // bottom
        ctx.fill(x - H, y - H, x - H + 1, y + H, color);   // left
        ctx.fill(x + H - 1, y - H, x + H, y + H, color);   // right
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static float isoToNoiseSigma(int iso) {
        if (iso <= 100) return 0f; if (iso <= 200) return 1.5f;
        if (iso <= 400) return 3f; if (iso <= 800) return 6f;
        if (iso <= 1600) return 11f; if (iso <= 3200) return 18f;
        if (iso <= 6400) return 28f; if (iso <= 12800) return 42f;
        return 60f;
    }

    private static int clampByte(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

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

    /**
     * Blocks are what the ring is actually marked in and what a builder thinks in; metres are
     * what the depth of field is computed from and what tells you how far the focus plane
     * really sits. Showing only blocks understated an aggressively scaled-down world (10 blocks
     * read as "10m" when the lens saw well under half that); showing only metres hid the number
     * the focus ring itself moves in. Both together cost one line of screen space and settle
     * either question.
     */
    private static String fmtFocusDist(float blocks, float dofScaleMm) {
        if (blocks >= SnapmaticaClient.FOCUS_INFINITY) return "inf";
        String blk = (blocks == (int) blocks) ? String.valueOf((int) blocks)
                                              : String.format("%.1f", blocks);
        float m = blocks * dofScaleMm / 1000.0f;
        String metres = (m < 10.0f) ? String.format("%.1fm", m) : Math.round(m) + "m";
        return blk + "blk (" + metres + ")";
    }
}
