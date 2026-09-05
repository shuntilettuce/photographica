package dev.hitom.photographica.client.hud;

import dev.hitom.photographica.client.DronePilot;
import dev.hitom.photographica.entity.DroneEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Drone piloting HUD, styled to match {@link ViewfinderHud} instead of loose floating widgets —
 * a dimmed 16:9 "footage" frame with corner brackets and a thirds grid, channel/altitude readout,
 * signal bars, a bank scale, and a full-screen TV-static overlay that gets noisier as the link
 * weakens, all driven by {@link DronePilot}'s continuous signal monitoring.
 */
@Environment(EnvType.CLIENT)
public final class DroneSignalHud {
    private DroneSignalHud() {}

    private static final int BAR_COUNT = 5;
    private static final int BAR_WIDTH = 4;
    private static final int BAR_GAP = 2;
    private static final int BAR_MAX_HEIGHT = 14;
    private static final int COLOR_GOOD = 0xFF4CAF50; // green  — 4-5 bars
    private static final int COLOR_OK = 0xFFE08A3C;   // amber — 2-3 bars
    private static final int COLOR_BAD = 0xFFC2362B;  // red   — 1 bar (matches DronePilot's low-signal warning color)
    private static final int COLOR_EMPTY = 0x50FFFFFF;

    private static final int COLOR_BEZEL = 0xB8000000;
    private static final int COLOR_FRAME = 0xFFFFFFFF;
    private static final int COLOR_GRID = 0x60FFFFFF;
    private static final int COLOR_TEXT = 0xFFE8DCC4;     // CREAM, matches ViewfinderHud
    private static final int COLOR_TEXT_DIM = 0xFF9A8D72; // CREAM_DIM

    private static final int MAX_NOISE_DOTS = 700;

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        if (!DronePilot.isActive()) return;
        // Hide behind an open screen (the drone camera's own settings GUI, inventory, …) the
        // same way ViewfinderHud does. HudRenderCallback still fires with a screen open, so
        // without this the frame, the static overlay AND the DoF/digital-zoom shader passes all
        // keep drawing underneath the GUI.
        if (mc.currentScreen != null) return;

        int signal = DronePilot.getSignal();
        int filledBars = signal <= 0 ? 0 : Math.max(1, (int) Math.ceil(signal / 100.0 * BAR_COUNT));
        boolean lost = DronePilot.getNoSignalTicks() > 0;

        // Pinned to maximum for the whole "signal just flatlined, might still recover" grace
        // window (see DronePilot#getNoSignalTicks) rather than scaling off `signal` itself,
        // which is already 0 there and can't tell "just dipped" from "about to crash" apart.
        double noiseIntensity = lost ? 1.0 : 1.0 - signal / 100.0;
        renderNoise(ctx, noiseIntensity);

        int sw = ctx.getScaledWindowWidth();
        int sh = ctx.getScaledWindowHeight();

        // A drone's own footage reads as 16:9, not the 3:2 stills frame ViewfinderHud uses —
        // same "dimmed bezel + bracketed frame" language, different aspect, so the two feel like
        // one coherent camera-UI family instead of two unrelated overlays.
        float aspect = 16f / 9f;
        int frameH = (int) (sh * 0.90f);
        int frameW = (int) (frameH * aspect);
        if (frameW > sw * 0.96f) {
            frameW = (int) (sw * 0.96f);
            frameH = (int) (frameW / aspect);
        }
        int fx = (sw - frameW) / 2;
        int fy = (sh - frameH) / 2;
        int fx2 = fx + frameW;
        int fy2 = fy + frameH;

        ctx.fill(0, 0, sw, fy, COLOR_BEZEL);
        ctx.fill(0, fy2, sw, sh, COLOR_BEZEL);
        ctx.fill(0, fy, fx, fy2, COLOR_BEZEL);
        ctx.fill(fx2, fy, sw, fy2, COLOR_BEZEL);

        int bl = 20;
        int bt = 2;
        drawBracket(ctx, fx, fy, bl, bt, +1, +1, COLOR_FRAME);
        drawBracket(ctx, fx2, fy, bl, bt, -1, +1, COLOR_FRAME);
        drawBracket(ctx, fx, fy2, bl, bt, +1, -1, COLOR_FRAME);
        drawBracket(ctx, fx2, fy2, bl, bt, -1, -1, COLOR_FRAME);

        int t1x = fx + frameW / 3;
        int t2x = fx + (frameW * 2) / 3;
        int t1y = fy + frameH / 3;
        int t2y = fy + (frameH * 2) / 3;
        ctx.fill(t1x, fy + 4, t1x + 1, fy2 - 4, COLOR_GRID);
        ctx.fill(t2x, fy + 4, t2x + 1, fy2 - 4, COLOR_GRID);
        ctx.fill(fx + 4, t1y, fx2 - 4, t1y + 1, COLOR_GRID);
        ctx.fill(fx + 4, t2y, fx2 - 4, t2y + 1, COLOR_GRID);

        int cx = sw / 2;
        int cy = sh / 2;
        int reticleColor = lost ? COLOR_BAD : COLOR_FRAME;
        ctx.fill(cx - 8, cy, cx - 3, cy + 1, reticleColor);
        ctx.fill(cx + 3, cy, cx + 8, cy + 1, reticleColor);
        ctx.fill(cx, cy - 8, cx + 1, cy - 3, reticleColor);
        ctx.fill(cx, cy + 3, cx + 1, cy + 8, reticleColor);

        TextRenderer tr = mc.textRenderer;

        // Channel / altitude — top-left, same corner ViewfinderHud puts its lens label in.
        int frequency = -1;
        double altitude = Double.NaN;
        dev.hitom.photographica.component.CameraSettings camSettings = null;
        boolean hasCamera = false;
        if (mc.world != null && mc.world.getEntityById(DronePilot.droneEntityId()) instanceof DroneEntity drone) {
            frequency = drone.getFrequency();
            //? if >=1.21.11 {
            /*altitude = drone.getEntityPos().y;
            *///?} else {
            altitude = drone.getPos().y;
            //?}
            net.minecraft.item.ItemStack mounted = drone.getEquippedCamera();
            hasCamera = !mounted.isEmpty();
            if (hasCamera) {
                camSettings = mounted.getItem() instanceof dev.hitom.photographica.item.FilmCameraItem
                        ? dev.hitom.photographica.item.FilmCameraItem.getSettings(mounted)
                        : dev.hitom.photographica.item.CameraItem.getSettings(mounted);
            }
        }
        String chLabel = frequency >= 0 ? ("📡 CH " + frequency) : "📡 CH --";
        ctx.drawTextWithShadow(tr, Text.literal(chLabel), fx + 6, fy + 4, COLOR_TEXT_DIM);
        String altLabel = Double.isNaN(altitude) ? "ALT --" : String.format("ALT %.1fm", altitude);
        ctx.drawTextWithShadow(tr, Text.literal(altLabel), fx + 6, fy + 4 + tr.fontHeight + 2, COLOR_TEXT);

        // Camera readout (bottom-left) — F2.8 fixed, focal length + magnification relative to
        // the 24mm wide end (the way a compact/phone camera states its zoom ratio), and the
        // live AF distance. Mirrors ViewfinderHud's exposure-readout placement/style.
        if (hasCamera && camSettings != null) {
            int opticalMax = dev.hitom.photographica.component.LensKind.DRONE_FOCAL_OPTICAL_MAX;
            float mag = camSettings.focalLengthMm()
                    / (float) dev.hitom.photographica.component.LensKind.DRONE_FOCAL_MIN;
            boolean tele = camSettings.focalLengthMm() >= opticalMax;
            String camLine = String.format("F2.8 · %dmm (%.1fx · %s)",
                    camSettings.focalLengthMm(), mag, tele ? "TELE" : "WIDE");
            ctx.drawTextWithShadow(tr, Text.literal(camLine), fx + 6, fy2 - tr.fontHeight * 2 - 14, COLOR_TEXT);
            boolean atInf = camSettings.focusDistance() >= dev.hitom.photographica.component.CameraSettings.FOCUS_INFINITY;
            String afLine = "AF " + (atInf ? "∞" : String.format("%.1fm", camSettings.focusDistance()));
            ctx.drawTextWithShadow(tr, Text.literal(afLine), fx + 6, fy2 - tr.fontHeight - 12, COLOR_TEXT_DIM);

            // Live depth-of-field, ported from ViewfinderHud's mirrorless EVF preview — f/2.8
            // is always wide open on this lens (see DroneEntity#createBuiltInCamera), so bokeh
            // is never optional here the way it is for a handheld camera someone might stop
            // down. Depth capture itself is gated in PhotoCapture#isEvfActive (piloting a drone
            // counts as EVF-active every frame, same as sneaking with a mirrorless in hand).
            // Bokeh always runs at the wide end's focal length regardless of zoom — see
            // LensKind#bokehFocalLengthMm for why honest f² scaling is unusable here.
            int bokehFocal = dev.hitom.photographica.component.LensKind.bokehFocalLengthMm(
                    camSettings.lensType(), camSettings.focalLengthMm());
            //? if >=1.21.11 {
            /*// On >=1.21.11 this scheduleBlur() call is also what bakes bokeh into the SAVED
            // PHOTO (applyScheduledBlur() runs in GameRendererMixin right before the capture
            // screenshot) — unlike the live preview, that must never be skipped.
            dev.hitom.photographica.client.render.EvfBlurRenderer.scheduleBlur(
                    fx, fy, fx2, fy2, camSettings.focusDistance(), camSettings.aperture(), bokehFocal);
            *///?} else {
            // <1.21.11 bakes bokeh into the saved photo separately (CPU-side, see
            // PhotoCapture#applyDepthOfField) — this call is live-preview only.
            dev.hitom.photographica.client.render.EvfBlurRenderer.renderBlur(fx, fy, fx2, fy2,
                    camSettings.focusDistance(), camSettings.aperture(), bokehFocal,
                    dev.hitom.photographica.client.render.EvfBlurRenderer.DOF_SCALE_STILL);
            //?}

            // Live reproduction of the digital-zoom detail loss (see
            // PhotoCapture#applyDigitalSoftening for the saved-photo side). Modeled as a
            // dual-camera switch (see LensKind#digitalZoomSoftenPx): the WIDE sensor goes
            // progressively softer approaching 70mm, snaps back to fully sharp the instant the
            // airframe switches to the TELE sensor at exactly 70mm, then softens again
            // approaching 200mm.
            float blockPx = dev.hitom.photographica.component.LensKind.digitalZoomSoftenPx(camSettings.focalLengthMm());
            if (blockPx > 1.0f) {
                //? if >=1.21.11 {
                /*dev.hitom.photographica.client.render.EvfBlurRenderer.scheduleDigitalZoom(fx, fy, fx2, fy2, blockPx);
                *///?} else {
                dev.hitom.photographica.client.render.EvfBlurRenderer.applyDigitalZoom(fx, fy, fx2, fy2, blockPx);
                //?}
            }
        } else {
            ctx.drawTextWithShadow(tr, Text.literal("📷 カメラ未装備"), fx + 6, fy2 - tr.fontHeight - 12, COLOR_TEXT_DIM);
        }

        // Key hints (bottom-right) — the drone's own fast-path controls, distinct from the
        // handheld camera's Ctrl/Alt+Scroll combos (there's nothing to modify here: no
        // aperture, no shutter dial, just zoom and shutter).
        String hint = "Scroll ズーム  ✕ 撮影";
        int hintW = tr.getWidth(hint);
        ctx.drawTextWithShadow(tr, Text.literal(hint), fx2 - hintW - 6, fy2 - tr.fontHeight - 12, 0x80FFFFFF);

        // Signal bars — top-right, inside the frame instead of floating loose in screen space.
        renderBars(ctx, fx2, fy, filledBars);

        // Bank scale — bottom-center, same visual language as ViewfinderHud's exposure meter.
        renderBankMeter(ctx, fx, fx2, fy2);

        if (lost) {
            String warn = "⚠ LOST SIGNAL";
            int ww = tr.getWidth(warn);
            // Blink at ~2 Hz so it reads as an active alarm, not a static label.
            if ((System.currentTimeMillis() / 250L) % 2 == 0) {
                ctx.drawTextWithShadow(tr, Text.literal(warn), (fx + fx2 - ww) / 2, fy + 6, COLOR_BAD);
            }
        } else if (signal <= 50) {
            String warn = "⚠ 電波が弱くなっています";
            int ww = tr.getWidth(warn);
            ctx.drawTextWithShadow(tr, Text.literal(warn), (fx + fx2 - ww) / 2, fy + 6, COLOR_OK);
        }
    }

    private static void renderBars(DrawContext ctx, int frameRight, int frameTop, int filledBars) {
        int color = switch (filledBars) {
            case 0, 1 -> COLOR_BAD;
            case 2, 3 -> COLOR_OK;
            default -> COLOR_GOOD; // 4-5
        };

        int totalWidth = BAR_COUNT * BAR_WIDTH + (BAR_COUNT - 1) * BAR_GAP;
        int x0 = frameRight - totalWidth - 6;
        int y0 = frameTop + 4;

        for (int i = 0; i < BAR_COUNT; i++) {
            int barHeight = BAR_MAX_HEIGHT * (i + 1) / BAR_COUNT;
            int x = x0 + i * (BAR_WIDTH + BAR_GAP);
            int y = y0 + (BAR_MAX_HEIGHT - barHeight);
            ctx.fill(x, y, x + BAR_WIDTH, y0 + BAR_MAX_HEIGHT, i < filledBars ? color : COLOR_EMPTY);
        }
    }

    /** Bank-angle scale, same "baseline + ticks + coloured pointer" shape as ViewfinderHud's
     *  exposure meter, so the two camera HUDs share one visual grammar for "deviation from
     *  centre" readouts. Range is ±25°, matching DronePilot's own max bank clamp. */
    private static void renderBankMeter(DrawContext ctx, int fx, int fx2, int fy2) {
        final int METER_W = 100;
        final float MAX_BANK = 25f;
        int meterX = (fx + fx2 - METER_W) / 2;
        int meterCx = meterX + METER_W / 2;
        int baseY = fy2 - 5;
        float pixPerDeg = METER_W / (2f * MAX_BANK);

        ctx.fill(meterX, baseY, meterX + METER_W, baseY + 1, 0x80FFFFFF);
        for (int deg = -20; deg <= 20; deg += 10) {
            int tx = meterCx + (int) (deg * pixPerDeg);
            int th = (deg == 0) ? 6 : 3;
            ctx.fill(tx, baseY - th, tx + 1, baseY + 1, 0xC0FFFFFF);
        }

        float bank = clamp(DronePilot.getBank(), -MAX_BANK, MAX_BANK);
        int ptrX = meterCx + (int) (bank * pixPerDeg);
        int ptrColor = Math.abs(bank) <= MAX_BANK * 0.4f ? 0xFFE08A3C : 0xFFC2362B;
        ctx.fill(ptrX - 1, baseY - 7, ptrX + 2, baseY + 2, ptrColor);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    private static void drawBracket(DrawContext ctx, int ax, int ay, int len, int thick,
                                     int dx, int dy, int color) {
        int hx1 = dx > 0 ? ax : ax - len;
        int hx2 = dx > 0 ? ax + len : ax;
        int hy1 = dy > 0 ? ay : ay - thick;
        int hy2 = dy > 0 ? ay + thick : ay;
        ctx.fill(hx1, hy1, hx2, hy2, color);
        int vx1 = dx > 0 ? ax : ax - thick;
        int vx2 = dx > 0 ? ax + thick : ax;
        int vy1 = dy > 0 ? ay : ay - len;
        int vy2 = dy > 0 ? ay + len : ay;
        ctx.fill(vx1, vy1, vx2, vy2, color);
    }

    /** Cheap "TV static" — a fresh scatter of 1px dots every frame, count and alpha both
     *  scaling with {@code intensity}. Not a shader (nothing in this mod's render pipeline
     *  hooks the world-composite step at HUD time), but a HUD-space dot scatter reads as
     *  static well enough and costs nothing to wire up. */
    private static void renderNoise(DrawContext ctx, double intensity) {
        if (intensity <= 0) return;
        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        int count = (int) (MAX_NOISE_DOTS * intensity);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int x = rng.nextInt(w);
            int y = rng.nextInt(h);
            int gray = rng.nextInt(256);
            int alpha = (int) (rng.nextInt(120, 220) * intensity);
            int color = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
            ctx.fill(x, y, x + 1, y + 1, color);
        }
    }
}
