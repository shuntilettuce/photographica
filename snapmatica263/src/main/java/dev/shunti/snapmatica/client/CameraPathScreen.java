package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A dedicated menu for camera-path recording, opened by a single key while freecam is active.
 *
 * <p>Add/Play/Clear and the total-duration setting used to each be their own keybind, but none
 * of them are the kind of thing you need mid-motion the way freecam's own flight keys are —
 * setting up a path is a pause-and-decide action, not a reflex, so they collapsed into one
 * menu behind one key instead of piling up the keyboard.
 */
@Environment(EnvType.CLIENT)
public class CameraPathScreen extends Screen {

    private static final double[] PATH_DURATIONS = {
            2, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90, 120 };
    // Same list VideoRecorderScreen offers — Play + Record starts the recorder itself, so its
    // fps belongs here too rather than making that trip to the other screen just to set it.
    private static final int[] FPS_LIST = { 24, 30, 60 };

    public CameraPathScreen() {
        super(Component.translatable("snapmatica.path.title"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int rowHeight = 22;
        int btnWidth = 130;
        // Same rhythm CameraScreen's own rows use: a fixed row height, a little breathing
        // room before the action row, and a status line reserved above it all.
        int top = Math.max(40, (height - (5 * rowHeight + 16 + 20)) / 2);
        int row = 0;

        addRow(cx, top + row++ * rowHeight, "snapmatica.camera.path_duration",
                () -> fmtSeconds(Freecam.pathDurationSec),
                btnWidth,
                step -> { int idx = findClosestD(PATH_DURATIONS, Freecam.pathDurationSec);
                    idx = clampStep(idx, step, PATH_DURATIONS.length);
                    Freecam.pathDurationSec = PATH_DURATIONS[idx]; });
        addRow(cx, top + row++ * rowHeight, "snapmatica.path.fps",
                () -> VideoRecorder.getCurrentFps() + " fps",
                btnWidth,
                step -> { int idx = findClosestI(FPS_LIST, VideoRecorder.getCurrentFps());
                    idx = clampStep(idx, step, FPS_LIST.length);
                    VideoRecorder.setFps(FPS_LIST[idx]); });
        row++; // breathing room before the action buttons

        int gap = 4;
        int wAdd = 90, wPlay = 60, wPlayRec = 96, wClear = 70;
        int total = wAdd + wPlay + wPlayRec + wClear + gap * 3;
        int x = cx - total / 2;
        int by = top + row++ * rowHeight;

        // Every button here closes the menu right after acting — one press, done, back to
        // whatever you opened this to check on. None of these are the kind of thing you'd want
        // to fire twice in a row anyway (Play mid-flight would just restart from keyframe one).
        addRenderableWidget(CameraUi.SnapButton.primary(x, by, wAdd,
                Component.translatable("snapmatica.path.add"),
                b -> { Minecraft mc = Minecraft.getInstance();
                    if (mc != null) Freecam.addPathKeyframe(mc); onClose(); }));
        x += wAdd + gap;
        addRenderableWidget(CameraUi.SnapButton.of(x, by, wPlay,
                Component.translatable(Freecam.isPathPlaying() ? "snapmatica.path.stop" : "snapmatica.path.play"),
                b -> { Minecraft mc = Minecraft.getInstance();
                    if (mc != null) Freecam.togglePathPlayback(mc); onClose(); }));
        x += wPlay + gap;
        // Starts the path and the recorder together, and stops the recorder the instant the
        // path ends (naturally or via Stop above) — see Freecam.playPathWithRecording.
        addRenderableWidget(CameraUi.SnapButton.of(x, by, wPlayRec,
                Component.translatable("snapmatica.path.play_record"),
                b -> { Minecraft mc = Minecraft.getInstance();
                    if (mc != null) Freecam.playPathWithRecording(mc); onClose(); }));
        x += wPlayRec + gap;
        addRenderableWidget(CameraUi.SnapButton.ghost(x, by, wClear,
                Component.translatable("snapmatica.path.clear_btn"),
                b -> { Minecraft mc = Minecraft.getInstance();
                    if (mc != null) Freecam.clearPath(mc); onClose(); }));

        addRenderableWidget(CameraUi.SnapButton.ghost(cx - 40, top + row * rowHeight + 16, 80,
                Component.translatable("snapmatica.common.close"), b -> onClose()));
    }

    private static String fmtSeconds(double s) {
        return (s == (long) s) ? (long) s + "s" : s + "s";
    }

    private static int findClosestD(double[] arr, double v) {
        int best = 0; double bd = Double.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            double d = Math.abs(arr[i] - v);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static int findClosestI(int[] arr, int v) {
        int best = 0, bd = Integer.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            int d = Math.abs(arr[i] - v);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static int clampStep(int idx, int step, int len) { return Math.max(0, Math.min(len - 1, idx + step)); }

    private void addRow(int cx, int y, String labelKey, java.util.function.Supplier<String> value,
                        int btnWidth, java.util.function.IntConsumer step) {
        int gap = 4;
        int totalRowWidth = 20 + gap + btnWidth + gap + 20;
        int rowLeft = cx - totalRowWidth / 2;

        addRenderableWidget(Button.builder(Component.literal("◀"),
                        b -> { step.accept(-1); rebuildWidgets(); })
                .bounds(rowLeft, y, 20, 20).build());

        String label = Component.translatable(labelKey).getString();
        Button centre = Button.builder(
                        Component.literal(label + ": " + value.get()), b -> {})
                .bounds(rowLeft + 20 + gap, y, btnWidth, 20).build();
        centre.active = false;
        addRenderableWidget(centre);

        addRenderableWidget(Button.builder(Component.literal("▶"),
                        b -> { step.accept(1); rebuildWidgets(); })
                .bounds(rowLeft + totalRowWidth - 20, y, 20, 20).build());
    }

    /** Same reasoning as CameraScreen: paint the backdrop from extractRenderState so its order
     *  is ours to control. */
    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0101010);
        ctx.centeredText(font, Component.translatable("snapmatica.path.title"),
                width / 2, 10, CameraUi.CREAM);

        int n = Freecam.pathKeyframeCount();
        Component status = (n == 0)
                ? Component.translatable("snapmatica.freecam.hud_no_keyframes")
                : Component.translatable("snapmatica.freecam.hud_status", n,
                        String.format("%.1f", Freecam.distanceFromLastKeyframe()));
        ctx.centeredText(font, status, width / 2, 26, CameraUi.CREAM_DIM);

        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
