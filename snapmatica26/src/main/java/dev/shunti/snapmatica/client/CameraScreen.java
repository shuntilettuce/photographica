package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
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
    private static final List<Integer> ISOS        = List.of(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
    private static final List<Float>   FOCUS_VALUES = List.of(
            0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f,
            5.0f, 6.0f, 7.0f, 8.0f, 10.0f, 12.0f, 15.0f, 20.0f, 25.0f, 30.0f,
            40.0f, 50.0f, 70.0f, 100.0f, 999.0f);
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
    protected void init() {
        int cx  = width / 2;
        int top = 60;
        int row = 0;
        int rowHeight = 26;
        int btnWidth  = 130;

        int exposureMode = SnapmaticaClient.exposureMode;

        boolean apAuto = exposureMode == EXP_TV || exposureMode == EXP_P;
        addRow2(cx, top + row++ * rowHeight, "Aperture",
                () -> apAuto ? "AUTO" : "F" + fmt(SnapmaticaClient.aperture),
                btnWidth,
                step -> { int idx = findClosest(APERTURES, SnapmaticaClient.aperture);
                    idx = clampStep(idx, step, APERTURES.size()); SnapmaticaClient.aperture = APERTURES.get(idx); },
                !apAuto);

        boolean ssAuto = exposureMode == EXP_AV || exposureMode == EXP_P;
        addRow2(cx, top + row++ * rowHeight, "Shutter",
                () -> ssAuto ? "AUTO" : SHUTTERS[clampIdx(SnapmaticaClient.shutterSpeedIdx, SHUTTERS.length)],
                btnWidth,
                step -> SnapmaticaClient.shutterSpeedIdx = clampStep(SnapmaticaClient.shutterSpeedIdx, step, SHUTTERS.length),
                !ssAuto);

        addRow2(cx, top + row++ * rowHeight, "ISO",
                () -> "ISO " + ISOS.get(clampIdx(findClosestInt(ISOS, SnapmaticaClient.iso), ISOS.size())),
                btnWidth,
                step -> { int idx = findClosestInt(ISOS, SnapmaticaClient.iso);
                    idx = clampStep(idx, step, ISOS.size()); SnapmaticaClient.iso = ISOS.get(idx); },
                true);

        boolean focusAuto = SnapmaticaClient.focusMode != FOCUS_MF;
        String focusAutoLabel = SnapmaticaClient.focusMode == FOCUS_MOB ? "MOB" : "AF";
        addRow2(cx, top + row++ * rowHeight, "Focus",
                () -> focusAuto ? focusAutoLabel : fmtFocus(SnapmaticaClient.focusDistance),
                btnWidth,
                step -> { int idx = findClosest(FOCUS_VALUES, SnapmaticaClient.focusDistance);
                    idx = clampStep(idx, step, FOCUS_VALUES.size()); SnapmaticaClient.focusDistance = FOCUS_VALUES.get(idx); },
                !focusAuto);

        addRow2(cx, top + row++ * rowHeight, "Exp. Mode",
                () -> EXP_MODE_LABELS[clampIdx(SnapmaticaClient.exposureMode, EXP_MODE_LABELS.length)],
                btnWidth,
                step -> SnapmaticaClient.exposureMode = clampStep(SnapmaticaClient.exposureMode, step, EXP_MODE_LABELS.length),
                true);

        addRow2(cx, top + row++ * rowHeight, "Focus Mode",
                () -> FOCUS_MODE_LABELS[clampIdx(SnapmaticaClient.focusMode, FOCUS_MODE_LABELS.length)],
                btnWidth,
                step -> SnapmaticaClient.focusMode = clampStep(SnapmaticaClient.focusMode, step, FOCUS_MODE_LABELS.length),
                true);

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(cx - 40, top + row * rowHeight + 16, 80, 20).build());
    }

    private void addRow2(int cx, int y, String label, java.util.function.Supplier<String> value,
                         int btnWidth, java.util.function.IntConsumer step, boolean editable) {
        int gap = 4;
        int halfTotal = 20 + gap + btnWidth + gap + 20;

        Button left = Button.builder(Component.literal("◀"),
                        b -> { step.accept(-1); rebuildWidgets(); })
                .bounds(cx - halfTotal, y, 20, 20).build();
        left.active = editable;
        addRenderableWidget(left);

        Button centre = Button.builder(
                        Component.literal(label + ": " + value.get()), b -> {})
                .bounds(cx - halfTotal + 20 + gap, y, btnWidth, 20).build();
        centre.active = false;
        addRenderableWidget(centre);

        Button right = Button.builder(Component.literal("▶"),
                        b -> { step.accept(1); rebuildWidgets(); })
                .bounds(cx + halfTotal - 20, y, 20, 20).build();
        right.active = editable;
        addRenderableWidget(right);
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredString(font, Component.literal("SNAPMATICA CAMERA"), width / 2, 10, 0xFFE8DCC4);
        super.render(ctx, mouseX, mouseY, delta);
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
        if (v >= 999.0f) return "inf";
        if (v < 1.0f) return String.format("%.1fm", v);
        return fmt(v) + "m";
    }
}
