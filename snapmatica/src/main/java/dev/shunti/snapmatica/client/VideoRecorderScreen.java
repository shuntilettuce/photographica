package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Pre-recording settings screen: aperture (F-value), FPS, and REC/Stop button.
 * Matches the layout and safelight aesthetic of photographica's VideoCameraScreen.
 */
@Environment(EnvType.CLIENT)
public class VideoRecorderScreen extends Screen {

    private static final List<Float>   APERTURES = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final List<Integer> FPS_LIST  = List.of(24, 30);

    // ── Safelight palette (ARGB 0xAARRGGBB) ─────────────────────────────────────
    static final int PANEL_SHADOW  = 0xFF15110D;
    static final int PANEL         = 0xFF221D18;
    static final int PANEL_2       = 0xFF2C2620;
    static final int PANEL_LIGHT   = 0xFF3A3128;
    static final int BRASS_DIM     = 0xFF5C3F18;
    static final int BRASS         = 0xFF9B6F30;
    static final int BRASS_BRIGHT  = 0xFFD4A052;
    static final int CREAM_DIM     = 0xFF9A8D72;
    static final int CREAM         = 0xFFE8DCC4;
    static final int SAFELIGHT_DIM = 0xFF7A1F17;
    static final int SAFELIGHT     = 0xFFC2362B;
    static final int FRAME_HI      = 0xFF4A3F30;
    static final int FRAME_LO      = 0xFF14100C;

    enum Style { DEFAULT, PRIMARY, GHOST }

    // ── Custom button: renders the safelight aesthetic instead of vanilla button ──

    static class SnapButton extends ButtonWidget {
        final Style style;

        // Use String to avoid ButtonWidget$Text shadowing net.minecraft.text.Text
        SnapButton(int x, int y, int w, String label, Style style, PressAction action) {
            super(x, y, w, 20, net.minecraft.text.Text.literal(label), action, DEFAULT_NARRATION_SUPPLIER);
            this.style = style;
        }

        //? if >=1.21.11 {
        /*@Override
        protected void drawIcon(DrawContext ctx, int mx, int my, float delta) {
            paintCustom(ctx);
        }*/
        //?} else {
        @Override
        protected void renderWidget(DrawContext ctx, int mx, int my, float delta) {
            paintCustom(ctx);
        }
        //?}

        private void paintCustom(DrawContext ctx) {
            int bx = getX(), by = getY(), bw = getWidth();
            ctx.fill(bx, by, bx + bw, by + 20, FRAME_LO);
            switch (style) {
                case PRIMARY -> {
                    ctx.fill(bx+1, by+1,  bx+bw-1, by+7,  0xFFD35A3A);
                    ctx.fill(bx+1, by+7,  bx+bw-1, by+14, SAFELIGHT);
                    ctx.fill(bx+1, by+14, bx+bw-1, by+19, SAFELIGHT_DIM);
                }
                case GHOST -> {
                    ctx.fill(bx+1, by+1,  bx+bw-1, by+7,  PANEL_2);
                    ctx.fill(bx+1, by+7,  bx+bw-1, by+14, PANEL);
                    ctx.fill(bx+1, by+14, bx+bw-1, by+19, PANEL_SHADOW);
                }
                default -> {
                    ctx.fill(bx+1, by+1,  bx+bw-1, by+7,  PANEL_LIGHT);
                    ctx.fill(bx+1, by+7,  bx+bw-1, by+14, PANEL);
                    ctx.fill(bx+1, by+14, bx+bw-1, by+19, PANEL_SHADOW);
                }
            }
            ctx.fill(bx+1, by+1, bx+bw-1, by+2, FRAME_HI);
            int textColor = switch (style) {
                case PRIMARY -> 0xFFFFF5E8;
                case GHOST   -> CREAM_DIM;
                default      -> CREAM;
            };
            var tr = MinecraftClient.getInstance().textRenderer;
            ctx.drawCenteredTextWithShadow(tr, getMessage(), bx + bw / 2, by + 6, textColor);
        }
    }

    // ── Screen ────────────────────────────────────────────────────────────────────

    public VideoRecorderScreen() {
        super(Text.literal("CAMCORDER"));
    }

    @Override
    protected void init() {
        int cx  = width / 2;
        int top = height / 2 - 60;
        int row = 0;

        // Aperture (F-value) row
        addSettingRow(cx, top + row++ * 22, "絞り",
                () -> "F" + fmtAperture(SnapmaticaClient.aperture),
                step -> {
                    int idx = nearestIdx(APERTURES, SnapmaticaClient.aperture) + step;
                    SnapmaticaClient.aperture = APERTURES.get(Math.max(0, Math.min(APERTURES.size() - 1, idx)));
                }, true);

        // FPS row — locked while recording
        addSettingRow(cx, top + row++ * 22, "fps",
                () -> VideoRecorder.getCurrentFps() + " fps",
                step -> {
                    int idx = FPS_LIST.indexOf(VideoRecorder.getCurrentFps()) + step;
                    VideoRecorder.setFps(FPS_LIST.get(Math.max(0, Math.min(FPS_LIST.size() - 1, idx))));
                }, !VideoRecorder.isRecording());

        int btnY = top + row * 22 + 12;

        if (VideoRecorder.isRecording()) {
            addDrawableChild(new SnapButton(cx - 105, btnY, 100,
                    "■ 停止", Style.PRIMARY,
                    b -> { VideoRecorder.stopRecording(); close(); }));
        } else {
            addDrawableChild(new SnapButton(cx - 105, btnY, 100,
                    "● REC", Style.PRIMARY,
                    b -> { VideoRecorder.startRecording(); close(); }));
        }

        addDrawableChild(new SnapButton(cx + 5, btnY, 100,
                "閉じる", Style.GHOST, b -> close()));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xFF101010);

        int cx     = width / 2;
        int top    = height / 2 - 60;
        int panelW = 320;
        int panelH = 130;
        int px     = cx - panelW / 2;
        int py     = top - 16;

        drawPanel(ctx, px, py, panelW, panelH);
        drawNameplate(ctx, px + 6, py + 5, panelW - 12);
        drawRule(ctx, px + 6, py + 17, panelW - 12);

        super.render(ctx, mouseX, mouseY, delta);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("CAMCORDER"), cx, py + 6, CREAM);
    }

    @Override
    public boolean shouldPause() { return false; }

    // ── Setting row ───────────────────────────────────────────────────────────────

    private void addSettingRow(int cx, int y, String label,
                               Supplier<String> value, IntConsumer step, boolean editable) {
        SnapButton left = new SnapButton(cx - 30, y, 20, "◀", Style.DEFAULT,
                b -> { step.accept(-1); clearAndInit(); });
        left.active = editable;
        addDrawableChild(left);

        SnapButton centre = new SnapButton(cx - 8, y, 140,
                label + ": " + value.get(), Style.GHOST, b -> {});
        centre.active = false;
        addDrawableChild(centre);

        SnapButton right = new SnapButton(cx + 134, y, 20, "▶", Style.DEFAULT,
                b -> { step.accept(1); clearAndInit(); });
        right.active = editable;
        addDrawableChild(right);
    }

    // ── Panel drawing ─────────────────────────────────────────────────────────────

    private static void drawPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL);
        ctx.fill(x,         y,         x + w,     y + 1,     BRASS_DIM);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     BRASS_DIM);
        ctx.fill(x,         y,         x + 1,     y + h,     BRASS_DIM);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     BRASS_DIM);
        ctx.fill(x + 1,     y + 1,     x + w - 1, y + 2,     PANEL_LIGHT);
        ctx.fill(x + 1,     y + 1,     x + 2,     y + h - 1, PANEL_LIGHT);
        ctx.fill(x + 1,     y + h - 2, x + w - 1, y + h - 1, PANEL_SHADOW);
        ctx.fill(x + w - 2, y + 1,     x + w - 1, y + h - 1, PANEL_SHADOW);
    }

    private static void drawNameplate(DrawContext ctx, int x, int y, int w) {
        ctx.fill(x, y,     x + w, y + 1, 0xFFEFC88A);
        ctx.fill(x, y + 1, x + w, y + 3, BRASS_BRIGHT);
        ctx.fill(x, y + 3, x + w, y + 6, BRASS);
        ctx.fill(x, y + 6, x + w, y + 8, BRASS_DIM);
        ctx.fill(x, y + 8, x + w, y + 9, 0xFF3A2812);
        ctx.fill(x, y + 9, x + w, y + 10, PANEL_SHADOW);
    }

    private static void drawRule(DrawContext ctx, int x, int y, int w) {
        ctx.fill(x, y,     x + w, y + 1, PANEL_SHADOW);
        ctx.fill(x, y + 1, x + w, y + 2, PANEL_LIGHT);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static String fmtAperture(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static int nearestIdx(List<Float> list, float v) {
        int best = 0; float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            float d = Math.abs(list.get(i) - v);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }
}
