package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

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
        super(Text.translatable("snapmatica.path.title"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int rowHeight = 22;
        int btnWidth = 130;
        // Same rhythm CameraScreen's own rows use: a fixed row height, a little breathing
        // room before the action row, and a status line reserved above it all.
        int top = Math.max(40, (height - (6 * rowHeight + 16 + 20)) / 2);
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
        // Arrows just flip it either direction — a boolean has no "next stop" to step toward,
        // it's the same toggle addRow's neighbours use for drone mode / hide player.
        addRow(cx, top + row++ * rowHeight, "snapmatica.path.focus_lock",
                () -> Freecam.isPathFocusLockEnabled() ? "ON" : "OFF",
                btnWidth,
                step -> { MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) Freecam.togglePathFocusLock(mc); });
        row++; // breathing room before the action buttons

        int gap = 4;
        int wAdd = 90, wPlay = 60, wPlayRec = 96, wClear = 70;
        int total = wAdd + wPlay + wPlayRec + wClear + gap * 3;
        int x = cx - total / 2;
        int by = top + row++ * rowHeight;

        // Every button here closes the menu right after acting — one press, done, back to
        // whatever you opened this to check on. None of these are the kind of thing you'd want
        // to fire twice in a row anyway (Play mid-flight would just restart from keyframe one).
        addDrawableChild(CameraUi.Button.primary(x, by, wAdd,
                Text.translatable("snapmatica.path.add"),
                b -> { MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) Freecam.addPathKeyframe(mc); close(); }));
        x += wAdd + gap;
        addDrawableChild(CameraUi.Button.of(x, by, wPlay,
                Text.translatable(Freecam.isPathPlaying() ? "snapmatica.path.stop" : "snapmatica.path.play"),
                b -> { MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) Freecam.togglePathPlayback(mc); close(); }));
        x += wPlay + gap;
        // Starts the path and the recorder together, and stops the recorder the instant the
        // path ends (naturally or via Stop above) — see Freecam.playPathWithRecording.
        addDrawableChild(CameraUi.Button.of(x, by, wPlayRec,
                Text.translatable("snapmatica.path.play_record"),
                b -> { MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) Freecam.playPathWithRecording(mc); close(); }));
        x += wPlayRec + gap;
        addDrawableChild(CameraUi.Button.ghost(x, by, wClear,
                Text.translatable("snapmatica.path.clear_btn"),
                b -> { MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) Freecam.clearPath(mc); close(); }));

        addDrawableChild(CameraUi.Button.ghost(cx - 40, top + row * rowHeight + 16, 80,
                Text.translatable("snapmatica.common.close"), b -> close()));
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

        addDrawableChild(ButtonWidget.builder(Text.literal("◀"),
                        b -> { step.accept(-1); clearAndInit(); })
                .dimensions(rowLeft, y, 20, 20).build());

        String label = Text.translatable(labelKey).getString();
        ButtonWidget centre = ButtonWidget.builder(
                        Text.literal(label + ": " + value.get()), b -> {})
                .dimensions(rowLeft + 20 + gap, y, btnWidth, 20).build();
        centre.active = false;
        addDrawableChild(centre);

        addDrawableChild(ButtonWidget.builder(Text.literal("▶"),
                        b -> { step.accept(1); clearAndInit(); })
                .dimensions(rowLeft + totalRowWidth - 20, y, 20, 20).build());
    }

    /** Same reasoning as CameraScreen: paint the backdrop from render() so its order is ours
     *  to control on every version, rather than vanilla's own pre-content pass. */
    //? if >=1.21 {
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}
    //?} else {
    /*@Override
    public void renderBackground(DrawContext ctx) {}
    *///?}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xC0101010);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("snapmatica.path.title"),
                width / 2, 10, CameraUi.CREAM);

        int n = Freecam.pathKeyframeCount();
        Text status = (n == 0)
                ? Text.translatable("snapmatica.freecam.hud_no_keyframes")
                : Text.translatable("snapmatica.freecam.hud_status", n,
                        String.format("%.1f", Freecam.distanceFromLastKeyframe()));
        ctx.drawCenteredTextWithShadow(textRenderer, status, width / 2, 26, CameraUi.CREAM_DIM);

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() { return false; }
}
