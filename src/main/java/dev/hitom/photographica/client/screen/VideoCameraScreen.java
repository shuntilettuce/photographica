package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.client.VideoRecorder;
import dev.hitom.photographica.component.VideoSettings;
import dev.hitom.photographica.item.VideoCameraItem;
import dev.hitom.photographica.network.UnequipCameraFromArmorStandPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

@Environment(EnvType.CLIENT)
public class VideoCameraScreen extends Screen {

    private static final List<Float>   APERTURES = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final List<Integer> FPS_LIST  = List.of(24, 30);

    private final ItemStack stack;
    private VideoSettings settings;
    private boolean dirty = false;
    private final int armorStandEntityId; // -1 = player's hand camera

    public VideoCameraScreen(ItemStack stack) {
        this(stack, -1);
    }

    public VideoCameraScreen(ItemStack stack, int armorStandEntityId) {
        super(Text.translatable("screen.photographica.video_camera"));
        this.stack              = stack;
        this.settings           = VideoCameraItem.getSettings(stack);
        this.armorStandEntityId = armorStandEntityId;
    }

    @Override
    protected void init() {
        int cx  = width / 2;
        int top = height / 2 - 60;
        int row = 0;

        // Aperture row
        addRow(cx, top + row++ * 22, "絞り",
                () -> "F" + formatFloat(settings.aperture()),
                step -> {
                    int idx = clampIdx(nearestIdx(APERTURES, settings.aperture()) + step, APERTURES.size());
                    settings = settings.withAperture(APERTURES.get(idx));
                    dirty = true;
                }, true);

        // FPS row
        addRow(cx, top + row++ * 22, "fps",
                () -> settings.fps() + " fps",
                step -> {
                    int idx = clampIdx(FPS_LIST.indexOf(settings.fps()) + step, FPS_LIST.size());
                    settings = settings.withFps(FPS_LIST.get(Math.max(0, idx)));
                    dirty = true;
                }, !VideoRecorder.isRecording());   // lock fps while recording

        int btnY = top + row * 22 + 12;

        // Record / Stop button
        if (VideoRecorder.isRecording()) {
            addDrawableChild(SafelightButton.primary(cx - 105, btnY, 100,
                    Text.literal("■ 停止"),
                    b -> {
                        flushSettings();
                        VideoRecorder.stopRecording();
                        close();
                    }));
        } else {
            addDrawableChild(SafelightButton.primary(cx - 105, btnY, 100,
                    Text.literal("● REC"),
                    b -> {
                        flushSettings();
                        VideoRecorder.startRecording(stack, armorStandEntityId);
                        close();
                    }));
        }

        if (armorStandEntityId >= 0 && !VideoRecorder.isRecording()) {
            // Armor stand mode: show "Remove camera" button below close
            addDrawableChild(SafelightButton.of(cx + 5, btnY, 100,
                    Text.literal("取り出す"),
                    b -> {
                        ClientPlayNetworking.send(new UnequipCameraFromArmorStandPayload(armorStandEntityId));
                        close();
                    }));
            addDrawableChild(SafelightButton.ghost(cx + 5, btnY + 24, 100,
                    Text.literal("閉じる"), b -> close()));
        } else {
            addDrawableChild(SafelightButton.ghost(cx + 5, btnY, 100,
                    Text.literal("閉じる"), b -> close()));
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xFF101010);

        int cx     = width / 2;
        int top    = height / 2 - 60;
        int panelW = 320;
        int panelH = 130;
        int px     = cx - panelW / 2;
        int py     = top - 16;

        GuiHelper.drawPanel(ctx, px, py, panelW, panelH);
        GuiHelper.drawNameplate(ctx, px + 6, py + 5, panelW - 12);
        GuiHelper.drawRule(ctx, px + 6, py + 17, panelW - 12);

        super.render(ctx, mouseX, mouseY, delta);

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("CAMCORDER"), cx, py + 6, GuiHelper.CREAM);
    }

    private void addRow(int cx, int y, String label,
                        java.util.function.Supplier<String> value,
                        java.util.function.IntConsumer step, boolean editable) {
        SafelightButton left = SafelightButton.of(cx - 30, y, 20, Text.literal("◀"),
                b -> { step.accept(-1); clearAndInit(); });
        left.active = editable;
        addDrawableChild(left);

        SafelightButton center = SafelightButton.ghost(cx - 8, y, 140,
                Text.literal(label + ": " + value.get()), b -> {});
        center.active = false;
        addDrawableChild(center);

        SafelightButton right = SafelightButton.of(cx + 134, y, 20, Text.literal("▶"),
                b -> { step.accept(1); clearAndInit(); });
        right.active = editable;
        addDrawableChild(right);
    }

    private void flushSettings() {
        if (dirty) {
            VideoCameraItem.setSettings(stack, settings);
            dirty = false;
        }
    }

    @Override
    public void close() {
        flushSettings();
        super.close();
    }

    private static String formatFloat(float v) {
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.valueOf(v);
    }

    private static int clampIdx(int idx, int size) {
        return Math.max(0, Math.min(size - 1, idx));
    }

    /** Find nearest index in the list to the given value. */
    private static int nearestIdx(List<Float> list, float v) {
        int best = 0; float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            float d = Math.abs(list.get(i) - v);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }
}
