package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

@Environment(EnvType.CLIENT)
public class CameraScreen extends Screen {

    private static final List<Float>   APERTURES   = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final String[]      SHUTTERS    = {
            "30s","15s","8s","4s","2s","1s",
            "1/2","1/4","1/8","1/15","1/30","1/60",
            "1/125","1/250","1/500","1/1000","1/2000","1/4000"};
    private static final List<Integer> ISOS        = List.of(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
    private static final List<Float>   FOCUS_VALUES = List.of(
            0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f,
            5.0f, 6.0f, 7.0f, 8.0f, 10.0f, 12.0f, 15.0f, 20.0f, 25.0f, 30.0f,
            40.0f, 50.0f, 70.0f, 100.0f, 150.0f, 200.0f,
            300.0f, 350.0f, 400.0f, 450.0f, 500.0f, 550.0f, 600.0f, 650.0f, 700.0f, 1000.0f, 1500.0f, 2000.0f, SnapmaticaClient.FOCUS_INFINITY);
    private static final String[] EXP_MODE_LABELS  = {"M", "Av", "Tv", "P"};
    private static final String[] FOCUS_MODE_LABELS = {"MF", "AF", "MOB"};

    private static final int EXP_TV  = 2;
    private static final int EXP_P   = 3;
    private static final int EXP_AV  = 1;
    private static final int FOCUS_MF  = 0;
    private static final int FOCUS_MOB = 2;

    public CameraScreen() {
        super(Component.literal("Camera Settings"));
    }

    @Override
    public void removed() {
        SnapmaticaConfig.save();   // persist camera state when the settings screen closes
        super.removed();
    }

    // Two columns rather than one long list, once "Focus Peaking" pushed the list past what a
    // screen can comfortably show. Lens/exposure controls on the left, everything about how
    // the camera itself behaves (freecam feel, viewfinder aids) on the right.
    private int headerY, colLeftX, colRightX;

    @Override
    protected void init() {
        int cx  = width / 2;
        int rowHeight = 22;
        int btnWidth  = 130;
        int gap = 4;
        int totalRowWidth = 20 + gap + btnWidth + gap + 20;
        int colGap = 24;
        int leftRows = 6, rightRows = 4;
        int maxRows = Math.max(leftRows, rightRows);
        int headerH = 14;
        int contentH = headerH + maxRows * rowHeight + 16 + 20;
        int top = Math.max(24, (height - contentH) / 2);

        colLeftX  = cx - (colGap / 2 + totalRowWidth / 2);
        colRightX = cx + (colGap / 2 + totalRowWidth / 2);
        headerY = top;
        int rowsTop = top + headerH;

        int exposureMode = SnapmaticaClient.exposureMode;

        // Left column: lens / exposure settings.
        int rL = 0;

        boolean apAuto = exposureMode == EXP_TV || exposureMode == EXP_P;
        addRow2(colLeftX, rowsTop + rL++ * rowHeight, "snapmatica.camera.aperture",
                () -> apAuto ? "AUTO" : "F" + fmt(SnapmaticaClient.aperture),
                btnWidth,
                step -> { int idx = findClosest(APERTURES, SnapmaticaClient.aperture);
                    idx = clampStep(idx, step, APERTURES.size()); SnapmaticaClient.aperture = APERTURES.get(idx); SnapmaticaClient.updateAutoValues(); },
                !apAuto);

        boolean ssAuto = exposureMode == EXP_AV || exposureMode == EXP_P;
        addRow2(colLeftX, rowsTop + rL++ * rowHeight, "snapmatica.camera.shutter",
                () -> ssAuto ? "AUTO" : SHUTTERS[clampIdx(SnapmaticaClient.shutterSpeedIdx, SHUTTERS.length)],
                btnWidth,
                step -> SnapmaticaClient.shutterSpeedIdx = clampStep(SnapmaticaClient.shutterSpeedIdx, step, SHUTTERS.length),
                !ssAuto);

        addRow2(colLeftX, rowsTop + rL++ * rowHeight, "snapmatica.camera.iso",
                () -> "ISO " + ISOS.get(clampIdx(findClosestInt(ISOS, SnapmaticaClient.iso), ISOS.size())),
                btnWidth,
                step -> { int idx = findClosestInt(ISOS, SnapmaticaClient.iso);
                    idx = clampStep(idx, step, ISOS.size()); SnapmaticaClient.iso = ISOS.get(idx); SnapmaticaClient.updateAutoValues(); },
                true);

        boolean focusAuto = SnapmaticaClient.focusMode != FOCUS_MF;
        String focusAutoLabel = SnapmaticaClient.focusMode == FOCUS_MOB ? "MOB" : "AF";
        addRow2(colLeftX, rowsTop + rL++ * rowHeight, "snapmatica.camera.focus",
                () -> focusAuto ? focusAutoLabel : fmtFocus(SnapmaticaClient.focusDistance),
                btnWidth,
                step -> { int idx = findClosest(FOCUS_VALUES, SnapmaticaClient.focusDistance);
                    idx = clampStep(idx, step, FOCUS_VALUES.size()); SnapmaticaClient.focusDistance = FOCUS_VALUES.get(idx); },
                !focusAuto);

        addRow2(colLeftX, rowsTop + rL++ * rowHeight, "snapmatica.camera.exp_mode",
                () -> EXP_MODE_LABELS[clampIdx(SnapmaticaClient.exposureMode, EXP_MODE_LABELS.length)],
                btnWidth,
                step -> { SnapmaticaClient.exposureMode = clampStep(SnapmaticaClient.exposureMode, step, EXP_MODE_LABELS.length); SnapmaticaClient.updateAutoValues(); },
                true);

        addRow2(colLeftX, rowsTop + rL++ * rowHeight, "snapmatica.camera.focus_mode",
                () -> FOCUS_MODE_LABELS[clampIdx(SnapmaticaClient.focusMode, FOCUS_MODE_LABELS.length)],
                btnWidth,
                step -> SnapmaticaClient.focusMode = clampStep(SnapmaticaClient.focusMode, step, FOCUS_MODE_LABELS.length),
                true);

        // Right column: how the camera itself behaves — not optical settings.
        int rR = 0;

        // Viewfinder-only aid, never in the photo — see EvfBlurRenderer.applyBlur.
        addRow2(colRightX, rowsTop + rR++ * rowHeight, "snapmatica.camera.focus_peaking",
                () -> SnapmaticaClient.focusPeaking ? "ON" : "OFF",
                btnWidth,
                step -> { SnapmaticaClient.focusPeaking = !SnapmaticaClient.focusPeaking;
                    SnapmaticaConfig.save(); },
                true);

        // World scale. Not an optical setting — a statement about the build, and the only
        // number here the lens cannot infer for itself. Grouped with the camera-feel settings
        // rather than the exposure triangle, since it isn't one either.
        addRow2(colRightX, rowsTop + rR++ * rowHeight, "snapmatica.camera.scale",
                () -> fmtScale(SnapmaticaClient.dofScaleMm),
                btnWidth,
                step -> { int idx = findClosestF(SCALE_MM, SnapmaticaClient.dofScaleMm);
                    idx = clampStep(idx, step, SCALE_MM.length);
                    SnapmaticaClient.dofScaleMm = SCALE_MM[idx]; },
                true);

        // Drone mode — freecam's control feel, not an optical setting.
        addRow2(colRightX, rowsTop + rR++ * rowHeight, "snapmatica.camera.drone_mode",
                () -> SnapmaticaClient.droneMode ? "ON" : "OFF",
                btnWidth,
                step -> { SnapmaticaClient.droneMode = !SnapmaticaClient.droneMode; SnapmaticaConfig.save(); },
                true);

        // Freecam-only, same reasoning as Drone Mode.
        addRow2(colRightX, rowsTop + rR++ * rowHeight, "snapmatica.camera.hide_player",
                () -> SnapmaticaClient.freecamHidePlayer ? "ON" : "OFF",
                btnWidth,
                step -> { SnapmaticaClient.freecamHidePlayer = !SnapmaticaClient.freecamHidePlayer;
                    SnapmaticaConfig.save(); },
                true);

        // Camera roll and freecam, side by side. Neither has a key of its own — a photography
        // mod already asks for enough of the keyboard. A dedicated close button is redundant
        // with Esc, which already closes any screen, so it stands in for freecam instead.
        int by = rowsTop + maxRows * rowHeight + 16;
        addRenderableWidget(CameraUi.SnapButton.primary(cx - 84, by, 80,
                Component.translatable("snapmatica.gallery.open_roll"),
                b -> { if (minecraft != null) minecraft.setScreen(new GalleryScreen()); }));
        addRenderableWidget(CameraUi.SnapButton.ghost(cx + 4, by, 80,
                Component.translatable(Freecam.isActive()
                        ? "snapmatica.common.exit_freecam" : "snapmatica.common.freecam"),
                b -> { if (minecraft != null) { Freecam.toggle(minecraft); onClose(); } }));
    }

    /**
     * How big a block is, in millimetres. Stops rather than a slider, chosen to be the scales
     * people actually build at: a metre for the usual convention, down to 1 cm — the same
     * optics an actual diorama lens has, for a tilt-shift miniature look.
     */
    private static final float[] SCALE_MM = {
            10, 20, 30, 50, 75, 100, 150, 200, 250, 300, 375, 500, 625, 750, 1000, 1500, 2000
    };

    private static String fmtScale(float mm) {
        if (mm >= 1000f) return String.format("1blk = %.1fm", mm / 1000f);
        float cm = mm / 10f;
        // One decimal below 10 cm — the miniature stops sit close enough together (1, 2, 3 cm)
        // that rounding to a whole centimetre would show the same label for two different ones.
        return (cm < 10f) ? String.format("1blk = %.1fcm", cm)
                          : String.format("1blk = %.0fcm", cm);
    }

    private static int findClosestF(float[] arr, float v) {
        int best = 0; float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            float d = Math.abs(arr[i] - v);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    private void addRow2(int cx, int y, String labelKey, java.util.function.Supplier<String> value,
                         int btnWidth, java.util.function.IntConsumer step, boolean editable) {
        int gap = 4;
        int totalRowWidth = 20 + gap + btnWidth + gap + 20;
        int rowLeft = cx - totalRowWidth / 2;

        Button left = Button.builder(Component.literal("◀"),
                        b -> { step.accept(-1); rebuildWidgets(); })
                .bounds(rowLeft, y, 20, 20).build();
        left.active = editable;
        addRenderableWidget(left);

        String label = Component.translatable(labelKey).getString();
        Button centre = Button.builder(
                        Component.literal(label + ": " + value.get()), b -> {})
                .bounds(rowLeft + 20 + gap, y, btnWidth, 20).build();
        centre.active = false;
        addRenderableWidget(centre);

        Button right = Button.builder(Component.literal("▶"),
                        b -> { step.accept(1); rebuildWidgets(); })
                .bounds(rowLeft + totalRowWidth - 20, y, 20, 20).build();
        right.active = editable;
        addRenderableWidget(right);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        extractBackground(ctx, mouseX, mouseY, delta);
        ctx.centeredText(font, Component.literal("SNAPMATICA CAMERA"), width / 2, 10, 0xFFE8DCC4);
        ctx.centeredText(font, Component.translatable("snapmatica.camera.tab_photo"),
                colLeftX, headerY, 0xFF9A8D72);
        ctx.centeredText(font, Component.translatable("snapmatica.camera.tab_camera"),
                colRightX, headerY, 0xFF9A8D72);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private static int clampIdx(int idx, int len) { return Math.max(0, Math.min(len-1, idx)); }
    private static int clampStep(int idx, int step, int len) { return Math.max(0, Math.min(len-1, idx+step)); }

    private static int findClosest(List<Float> list, float v) {
        int best = 0; float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) { float d = Math.abs(list.get(i)-v); if (d<bestDiff){bestDiff=d;best=i;} }
        return best;
    }

    private static int findClosestInt(List<Integer> list, int v) {
        int best = 0; int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) { int d = Math.abs(list.get(i)-v); if (d<bestDiff){bestDiff=d;best=i;} }
        return best;
    }

    private static String fmt(float v) { return v==(int)v ? String.valueOf((int)v) : String.format("%.1f",v); }
    private static String fmtFocus(float v) {
        if (v >= SnapmaticaClient.FOCUS_INFINITY) return "inf";
        if (v < 1.0f) return String.format("%.1fm", v);
        return fmt(v) + "m";
    }
}
