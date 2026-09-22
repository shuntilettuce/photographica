package dev.shunti.snapmatica.client;


import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Video recording settings screen — vanilla Minecraft style.
 * Opens when R is pressed while idle; pressing R during recording stops directly.
 */

public class VideoRecorderScreen extends Screen {

    private static final List<Float>   APERTURES = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final List<Integer> FPS_LIST  = List.of(24, 30, 60);
    private static final List<Integer> WIDTH_LIST = List.of(
            VideoRecorder.WIDTH_720P, VideoRecorder.WIDTH_1080P, VideoRecorder.WIDTH_1440P);
    // Zoom stops (focal length, mm): wide → tele. Larger = more zoomed in.
    private static final List<Integer> FOCAL_LIST = List.of(14, 18, 24, 35, 50, 85, 135, 200);

    // Row layout constants
    private static final int ARROW_W = 20;
    private static final int LABEL_W = 160;
    private static final int GAP     = 4;
    private static final int ROW_W   = ARROW_W + GAP + LABEL_W + GAP + ARROW_W; // 208
    private static final int ROW_H   = 24;
    private static final int BTN_W   = 100;

    public VideoRecorderScreen() {
        super(Component.translatable("snapmatica.video.title"));
    }

    @Override
    protected void init() {
        int cx     = width  / 2;
        int cy     = height / 2;
        int rowX   = cx - ROW_W / 2;
        int top    = cy - 70;
        int row    = 0;

        // Aperture (F-value)
        addSettingRow(rowX, top + row++ * ROW_H, Component.translatable("snapmatica.video.aperture"),
                () -> "F" + fmt(SnapmaticaClient.aperture),
                step -> {
                    int idx = nearestIdx(APERTURES, SnapmaticaClient.aperture) + step;
                    SnapmaticaClient.aperture = APERTURES.get(clamp(idx, APERTURES.size()));
                }, true);

        // Zoom (focal length / angle of view)
        addSettingRow(rowX, top + row++ * ROW_H, Component.translatable("snapmatica.video.fov"),
                () -> SnapmaticaClient.focalLengthMm + "mm",
                step -> {
                    int idx = nearestIntIdx(FOCAL_LIST, SnapmaticaClient.focalLengthMm) + step;
                    SnapmaticaClient.focalLengthMm = FOCAL_LIST.get(clamp(idx, FOCAL_LIST.size()));
                }, true);

        // FPS — locked once recording
        addSettingRow(rowX, top + row++ * ROW_H, Component.literal("FPS"),
                () -> VideoRecorder.getCurrentFps() + " fps",
                step -> {
                    int idx = FPS_LIST.indexOf(VideoRecorder.getCurrentFps()) + step;
                    VideoRecorder.setFps(FPS_LIST.get(clamp(idx, FPS_LIST.size())));
                    SnapmaticaConfig.save();
                }, !VideoRecorder.isRecording());

        // Resolution — locked once recording, same reasoning as FPS. Higher costs more
        // crop/downsample/PNG-write time per frame, so it's a real trade against frame-drop
        // risk rather than a free upgrade — worth showing, not just defaulting silently.
        addSettingRow(rowX, top + row++ * ROW_H, Component.literal("Resolution"),
                () -> resolutionLabel(VideoRecorder.getCurrentWidth()),
                step -> {
                    int idx = WIDTH_LIST.indexOf(VideoRecorder.getCurrentWidth()) + step;
                    VideoRecorder.setWidth(WIDTH_LIST.get(clamp(idx, WIDTH_LIST.size())));
                    SnapmaticaConfig.save();
                }, !VideoRecorder.isRecording());

        // Motion blur (applied by ffmpeg frame-blending at encode time, no in-game cost)
        addSettingRow(rowX, top + row++ * ROW_H, Component.translatable("snapmatica.video.motion_blur"),
                () -> motionBlurLabel(VideoRecorder.getMotionBlur()),
                step -> VideoRecorder.setMotionBlur(VideoRecorder.getMotionBlur() + step), true);

        // Action buttons
        int btnY = top + row * ROW_H + 12;
        Component recLabel = Component.translatable(VideoRecorder.isRecording()
                ? "snapmatica.video.stop" : "snapmatica.video.rec");

        addRenderableWidget(Button.builder(recLabel, b -> {
                    if (VideoRecorder.isRecording()) VideoRecorder.stopRecording();
                    else VideoRecorder.startRecording();
                    onClose();
                }).bounds(cx - BTN_W - 2, btnY, BTN_W, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("snapmatica.common.close"), b -> onClose())
                .bounds(cx + 2, btnY, BTN_W, 20).build());
    }

    /**
     * Deliberately empty. Before 1.21.11, {@code Screen.render} opens by calling this, which
     * lands *after* this screen has drawn its own content — a second coat of the backdrop that
     * buried everything under it. The backdrop is painted from {@link #render} instead, where
     * the order is ours to control on every version.
     */
    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {}

    /** The dimmed backdrop this screen sits on. */
    private void drawBackdrop(GuiGraphics ctx) {
        ctx.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        drawBackdrop(ctx);
        ctx.drawCenteredString(font, Component.translatable("snapmatica.video.title"),
                width / 2, height / 2 - 80, 0xFFFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Row builder ───────────────────────────────────────────────────────────────

    private void addSettingRow(int rowX, int y, Component label,
                               Supplier<String> value, IntConsumer step, boolean editable) {
        Button left = Button.builder(Component.literal("◀"),
                        b -> { step.accept(-1); rebuildWidgets(); })
                .bounds(rowX, y, ARROW_W, 20).build();
        left.active = editable;
        addRenderableWidget(left);

        Button centre = Button.builder(
                        label.copy().append(": " + value.get()), b -> {})
                .bounds(rowX + ARROW_W + GAP, y, LABEL_W, 20).build();
        centre.active = false;
        addRenderableWidget(centre);

        Button right = Button.builder(Component.literal("▶"),
                        b -> { step.accept(1); rebuildWidgets(); })
                .bounds(rowX + ARROW_W + GAP + LABEL_W + GAP, y, ARROW_W, 20).build();
        right.active = editable;
        addRenderableWidget(right);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static String resolutionLabel(int width) {
        if (width >= VideoRecorder.WIDTH_1440P) return "1440p";
        if (width >= VideoRecorder.WIDTH_1080P) return "1080p";
        return "720p";
    }

    private static String motionBlurLabel(int v) {
        switch (v) {
            case 1:  return Component.translatable("snapmatica.video.mb.light").getString();
            case 2:  return Component.translatable("snapmatica.video.mb.strong").getString();
            default: return Component.translatable("snapmatica.video.mb.off").getString();
        }
    }

    private static int nearestIdx(List<Float> list, float v) {
        int best = 0; float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            float d = Math.abs(list.get(i) - v);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    private static int nearestIntIdx(List<Integer> list, int v) {
        int best = 0; int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            int d = Math.abs(list.get(i) - v);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    private static int clamp(int idx, int size) {
        return Math.max(0, Math.min(size - 1, idx));
    }
}
