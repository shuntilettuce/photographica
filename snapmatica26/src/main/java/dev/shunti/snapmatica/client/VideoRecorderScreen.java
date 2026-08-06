package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Video recording settings screen (MC 26.1.2).
 * Opens when R is pressed while idle; pressing R during recording stops directly.
 */
@Environment(EnvType.CLIENT)
public class VideoRecorderScreen extends Screen {

    private static final List<Float>   APERTURES  = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final List<Integer> FPS_LIST   = List.of(24, 30);
    private static final List<Integer> FOCAL_LIST = List.of(14, 18, 24, 35, 50, 85, 135, 200);

    private static final int ARROW_W = 20;
    private static final int LABEL_W = 160;
    private static final int GAP     = 4;
    private static final int ROW_W   = ARROW_W + GAP + LABEL_W + GAP + ARROW_W;
    private static final int ROW_H   = 24;
    private static final int BTN_W   = 100;

    public VideoRecorderScreen() {
        super(Component.literal("動画設定"));
    }

    @Override
    protected void init() {
        int cx   = width  / 2;
        int cy   = height / 2;
        int rowX = cx - ROW_W / 2;
        int top  = cy - 58;
        int row  = 0;

        // Aperture (F-value)
        addSettingRow(rowX, top + row++ * ROW_H, "絞り",
                () -> "F" + fmt(SnapmaticaClient.aperture),
                step -> {
                    int idx = nearestIdx(APERTURES, SnapmaticaClient.aperture) + step;
                    SnapmaticaClient.aperture = APERTURES.get(clamp(idx, APERTURES.size()));
                }, true);

        // Zoom (focal length)
        addSettingRow(rowX, top + row++ * ROW_H, "画角",
                () -> SnapmaticaClient.focalLengthMm + "mm",
                step -> {
                    int idx = nearestIntIdx(FOCAL_LIST, SnapmaticaClient.focalLengthMm) + step;
                    SnapmaticaClient.focalLengthMm = FOCAL_LIST.get(clamp(idx, FOCAL_LIST.size()));
                }, true);

        // FPS — locked once recording
        addSettingRow(rowX, top + row++ * ROW_H, "FPS",
                () -> VideoRecorder.getCurrentFps() + " fps",
                step -> {
                    int idx = FPS_LIST.indexOf(VideoRecorder.getCurrentFps()) + step;
                    VideoRecorder.setFps(FPS_LIST.get(clamp(idx, FPS_LIST.size())));
                    SnapmaticaConfig.save();
                }, !VideoRecorder.isRecording());

        // Motion blur
        addSettingRow(rowX, top + row++ * ROW_H, "モーションブラー",
                () -> motionBlurLabel(VideoRecorder.getMotionBlur()),
                step -> VideoRecorder.setMotionBlur(VideoRecorder.getMotionBlur() + step), true);

        // Action buttons
        int btnY = top + row * ROW_H + 12;
        String recLabel = VideoRecorder.isRecording() ? "■ 停止" : "● REC";

        addRenderableWidget(Button.builder(Component.literal(recLabel), b -> {
                    if (VideoRecorder.isRecording()) VideoRecorder.stopRecording();
                    else VideoRecorder.startRecording();
                    onClose();
                }).bounds(cx - BTN_W - 2, btnY, BTN_W, 20).build());

        addRenderableWidget(Button.builder(Component.literal("閉じる"), b -> onClose())
                .bounds(cx + 2, btnY, BTN_W, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        extractBackground(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font, Component.literal("動画設定"), width / 2, height / 2 - 72, 0xFFFFFFFF);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void addSettingRow(int rowX, int y, String label,
                               Supplier<String> value, IntConsumer step, boolean editable) {
        Button left = Button.builder(Component.literal("◀"),
                        b -> { step.accept(-1); rebuildWidgets(); })
                .bounds(rowX, y, ARROW_W, 20).build();
        left.active = editable;
        addRenderableWidget(left);

        Button centre = Button.builder(
                        Component.literal(label + ": " + value.get()), b -> {})
                .bounds(rowX + ARROW_W + GAP, y, LABEL_W, 20).build();
        centre.active = false;
        addRenderableWidget(centre);

        Button right = Button.builder(Component.literal("▶"),
                        b -> { step.accept(1); rebuildWidgets(); })
                .bounds(rowX + ARROW_W + GAP + LABEL_W + GAP, y, ARROW_W, 20).build();
        right.active = editable;
        addRenderableWidget(right);
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static String motionBlurLabel(int v) {
        switch (v) {
            case 1:  return "弱";
            case 2:  return "強";
            default: return "オフ";
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
