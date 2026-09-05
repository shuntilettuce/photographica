package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Camera settings screen with fixed-width rows so text never clips.
 */
@Environment(EnvType.CLIENT)
public class CameraScreen extends Screen {

    private static final List<Float> APERTURES = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
    private static final String[] SHUTTERS = {
            "30s", "15s", "8s", "4s", "2s", "1s",
            "1/2", "1/4", "1/8", "1/15", "1/30", "1/60",
            "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"
    };
    private static final List<Integer> ISOS = List.of(25, 50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
    private static final List<Float> FOCUS_VALUES = List.of(
            0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f,
            6.0f, 7.0f, 8.0f, 9.0f, 10.0f, 12.0f, 14.0f, 16.0f, 18.0f, 20.0f, 24.0f, 28.0f, 32.0f,
            36.0f, 40.0f, 45.0f, 50.0f, 60.0f, 70.0f, 80.0f, 90.0f, 100.0f, 115.0f, 130.0f, 150.0f,
            170.0f, 200.0f, 230.0f, 270.0f, 300.0f, 350.0f, 400.0f, 450.0f, 500.0f, 600.0f, 700.0f,
            850.0f, 1000.0f, 1200.0f, 1500.0f, 2000.0f, 3000.0f, 5000.0f, 8000.0f, 10000.0f,
            SnapmaticaClient.FOCUS_INFINITY);
    private static final String[] EXP_MODE_LABELS  = {"M", "Av", "Tv", "P"};
    private static final String[] FOCUS_MODE_LABELS = {"MF", "AF", "MOB"};
    private static final String[] PHOTO_FORMAT_LABELS = {"PNG", "JPG", "DNG"};

    /**
     * White-balance settings: AWB (0) plus the fixed temperatures a real body's Kelvin menu
     * offers, from tungsten to deep shade. 5600 K (daylight) is the no-correction point — see
     * {@link SnapmaticaClient#WB_DAYLIGHT_K}. Stops at 2500 K for the same reason real bodies
     * do: below it a diagonal gain can no longer express the correction.
     */
    private static final List<Integer> WB_KELVINS = List.of(
            SnapmaticaClient.WB_AUTO, 2500, 3200, 4000, 4500, 5000, 5600,
            6500, 7500, 9000, 12000);

    /**
     * Neutral-density filters, by the strength printed on the ring and the stops it removes.
     * The named steps real filters are actually sold in — see {@link SnapmaticaClient#ndStops}.
     */
    private static final int[]    ND_STOPS  = {0, 1, 2, 3, 4, 6, 10};
    private static final String[] ND_LABELS = {"OFF", "ND2", "ND4", "ND8", "ND16", "ND64", "ND1000"};

    /** The ambient mode's own aperture stops — see {@link SnapmaticaClient#ambientAperture}. */
    private static final List<Float> AMBIENT_APERTURES =
            List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f);

    private static final String[] AMBIENT_QUALITY_LABELS = {"PERF", "BALANCED", "HIGH"};

    /**
     * Sensor formats, as crop factors relative to 35 mm full frame. The labels are what a
     * photographer calls them; the number is the only thing the optics need — see
     * {@link SnapmaticaClient#sensorCropFactor}.
     */
    private static final float[]  SENSOR_CROPS  = {0.79f, 1.0f, 1.5f, 2.0f, 2.7f};
    private static final String[] SENSOR_LABELS = {"MEDIUM", "FULL FRAME", "APS-C", "M4/3", "1 INCH"};

    private static final int EXP_TV = 2;
    private static final int EXP_P  = 3;
    private static final int EXP_AV = 1;
    private static final int FOCUS_MF = 0;
    private static final int FOCUS_MOB = 2;

    // Two columns rather than one long list, once "Focus Peaking" pushed the list past what a
    // screen can comfortably show. Tried as a tab switch first, but that costs an extra click
    // to see the other half — a side-by-side split shows everything at once instead. Lens/
    // exposure controls on the left, everything about how the camera itself behaves (freecam
    // feel, viewfinder aids) on the right — same split the row comments already implied.
    private int headerY, colLeftX, colRightX;

    public CameraScreen() {
        super(Component.literal("Camera Settings"));
    }

    @Override
    public void removed() {
        // Persist the camera state (exposure/focus mode, aperture, ISO, focal length, …)
        // whenever the settings screen closes, so it survives restarts.
        SnapmaticaConfig.save();
        super.removed();
    }

    /**
     * One line of the settings list: either a group heading, or a row with its own value and
     * stepper.
     *
     * <p>Built as data first and placed second, because the placement depends on how much of
     * the list fits — which cannot be known until the whole list exists.
     */
    private record Item(String key, java.util.function.Supplier<String> value,
                        java.util.function.IntConsumer step, boolean editable) {
        static Item section(String key) { return new Item(key, null, null, false); }
        boolean isSection() { return value == null; }
    }

    /** A heading recorded during {@link #init} and drawn in {@link #render}. */
    private record Heading(String key, int x, int y) {}
    private final java.util.List<Heading> headings = new java.util.ArrayList<>();

    private static final int ROW_H     = 22;   // never below BUTTON_H, or the rows overlap
    private static final int BUTTON_H  = 20;
    private static final int SECTION_H = 15;

    /**
     * How far the list is scrolled, in pixels, always a whole multiple of {@link #ROW_H}.
     *
     * <p>Quantised so no row is ever half-cut at the top or bottom edge: a row that does not
     * fit entirely is simply not placed, and whole-row steps mean that never happens mid-row.
     * Static so it survives the {@code clearAndInit} that every stepper press triggers —
     * otherwise changing any setting would throw you back to the top of the list.
     */
    private static int scrollPx = 0;

    @Override
    protected void init() {
        headings.clear();
        int cx = width / 2;
        int gap = 4;
        int colGap = 24;
        int headerH = 14;

        // Button width follows the window rather than being fixed at 130. A row's label and its
        // value share one button, and the longest of them ("Dynamic Range Width: 8 stops", and
        // more so its Japanese translation) ran off the end at 130. Widening where there is room
        // fixes that; the floor keeps two columns on screen on a small window.
        int btnWidth = Math.max(96, Math.min(160, (width - colGap) / 2 - (20 + gap) * 2));
        int totalRowWidth = 20 + gap + btnWidth + gap + 20;

        colLeftX  = cx - (colGap / 2 + totalRowWidth / 2);
        colRightX = cx + (colGap / 2 + totalRowWidth / 2);

        // The action buttons sit a fixed distance off the bottom of the screen, so they stay in
        // the same findable place however long the settings list grows.
        int buttonsY = height - 28;
        int top = 14;
        headerY = top;
        int visTop = top + headerH;
        int visBottom = buttonsY - 10;

        java.util.List<Item> left = buildLeftColumn();
        java.util.List<Item> right = buildRightColumn();

        // Scroll only as far as the taller column actually needs, quantised to whole rows.
        int contentH = Math.max(columnHeight(left), columnHeight(right));
        int visH = Math.max(ROW_H, visBottom - visTop);
        int maxScroll = Math.max(0, contentH - visH);
        maxScroll = ((maxScroll + ROW_H - 1) / ROW_H) * ROW_H;
        scrollPx = Math.max(0, Math.min(maxScroll, scrollPx));
        scrollable = maxScroll > 0;
        scrollContentH = contentH; scrollVisH = visH;
        scrollVisTop = visTop; scrollVisBottom = visBottom; scrollMax = maxScroll;

        place(left, colLeftX, visTop - scrollPx, visTop, visBottom, btnWidth);
        place(right, colRightX, visTop - scrollPx, visTop, visBottom, btnWidth);

        // Camera roll and freecam, side by side. Neither has a key of its own — a photography
        // mod already asks for enough of the keyboard, and this is where you would look for
        // either. A dedicated close button is redundant with Esc, which already closes any
        // screen, so it stands in for freecam instead.
        int by = buttonsY;
        addRenderableWidget(CameraUi.SnapButton.primary(cx - 84, by, 80,
                Component.translatable("snapmatica.gallery.open_roll"),
                b -> { if (minecraft != null) minecraft.setScreen(new GalleryScreen()); }));
        addRenderableWidget(CameraUi.SnapButton.ghost(cx + 4, by, 80,
                Component.translatable(Freecam.isActive()
                        ? "snapmatica.common.exit_freecam" : "snapmatica.common.freecam"),
                b -> { if (minecraft != null) { Freecam.toggle(minecraft); onClose(); } }));
    }

    private boolean scrollable = false;
    /** Scroll geometry from the last {@link #init}, for the bar {@link #render} draws. */
    private int scrollContentH, scrollVisH, scrollVisTop, scrollVisBottom, scrollMax;

    private static int columnHeight(java.util.List<Item> items) {
        int h = 0;
        for (Item it : items) h += it.isSection() ? SECTION_H : ROW_H;
        return h;
    }

    /**
     * Lays a column out from {@code y}, adding only what fits entirely between {@code visTop}
     * and {@code visBottom}. Anything scrolled past is skipped rather than clipped — Minecraft's
     * widgets do not clip to a parent, so a half-placed row would draw straight over the header
     * and the buttons below.
     */
    private void place(java.util.List<Item> items, int colX, int y,
                       int visTop, int visBottom, int btnWidth) {
        for (Item it : items) {
            int h = it.isSection() ? SECTION_H : ROW_H;
            boolean visible = y >= visTop && y + (it.isSection() ? 10 : BUTTON_H) <= visBottom;
            if (visible) {
                if (it.isSection()) headings.add(new Heading(it.key(), colX, y));
                else addRow2(colX, y, it.key(), it.value(), btnWidth, it.step(), it.editable());
            }
            y += h;
        }
    }

    // ── The lists themselves ────────────────────────────────────────────────

    private java.util.List<Item> buildLeftColumn() {
        int exposureMode = SnapmaticaClient.exposureMode;
        boolean apAuto = exposureMode == EXP_TV || exposureMode == EXP_P;
        boolean ssAuto = exposureMode == EXP_AV || exposureMode == EXP_P;
        boolean focusAuto = SnapmaticaClient.focusMode != FOCUS_MF;
        String focusAutoLabel = SnapmaticaClient.focusMode == FOCUS_MOB ? "MOB" : "AF";

        java.util.List<Item> out = new java.util.ArrayList<>();
        out.add(Item.section("snapmatica.camera.sec_exposure"));

        out.add(new Item("snapmatica.camera.aperture",
                () -> apAuto ? "AUTO" : "F" + fmt(SnapmaticaClient.aperture),
                step -> { int idx = findClosest(APERTURES, SnapmaticaClient.aperture);
                    idx = clampStep(idx, step, APERTURES.size()); SnapmaticaClient.aperture = APERTURES.get(idx);
                    SnapmaticaClient.syncApertureDiameter(); SnapmaticaClient.updateAutoValues(); },
                !apAuto));

        out.add(new Item("snapmatica.camera.shutter",
                () -> ssAuto ? "AUTO" : SHUTTERS[clampIdx(SnapmaticaClient.shutterSpeedIdx, SHUTTERS.length)],
                step -> SnapmaticaClient.shutterSpeedIdx =
                        clampStep(SnapmaticaClient.shutterSpeedIdx, step, SHUTTERS.length),
                !ssAuto));

        out.add(new Item("snapmatica.camera.iso",
                () -> "ISO " + ISOS.get(clampIdx(findClosestInt(ISOS, SnapmaticaClient.iso), ISOS.size())),
                step -> { int idx = findClosestInt(ISOS, SnapmaticaClient.iso);
                    idx = clampStep(idx, step, ISOS.size()); SnapmaticaClient.iso = ISOS.get(idx);
                    SnapmaticaClient.updateAutoValues(); },
                true));

        out.add(new Item("snapmatica.camera.exp_mode",
                () -> EXP_MODE_LABELS[clampIdx(SnapmaticaClient.exposureMode, EXP_MODE_LABELS.length)],
                step -> { SnapmaticaClient.exposureMode =
                        clampStep(SnapmaticaClient.exposureMode, step, EXP_MODE_LABELS.length);
                    SnapmaticaClient.updateAutoValues(); },
                true));

        // Shows the stops as well as the ring marking, because the stops are what the exposure
        // actually does — see SnapmaticaClient.ndStops.
        out.add(new Item("snapmatica.camera.nd_filter",
                () -> { int i = findClosestI(ND_STOPS, SnapmaticaClient.ndStops);
                    return i == 0 ? ND_LABELS[0] : ND_LABELS[i] + " (-" + ND_STOPS[i] + "EV)"; },
                step -> { int idx = findClosestI(ND_STOPS, SnapmaticaClient.ndStops);
                    idx = clampStep(idx, step, ND_STOPS.length);
                    SnapmaticaClient.ndStops = ND_STOPS[idx];
                    SnapmaticaClient.updateAutoValues(); SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.white_balance",
                () -> SnapmaticaClient.wbKelvin == SnapmaticaClient.WB_AUTO
                        ? "AWB " + Math.round(SnapmaticaClient.effectiveWbKelvin() / 50.0) * 50 + "K"
                        : SnapmaticaClient.wbKelvin + "K",
                step -> { int idx = findClosestInt(WB_KELVINS, SnapmaticaClient.wbKelvin);
                    idx = clampStep(idx, step, WB_KELVINS.size());
                    SnapmaticaClient.wbKelvin = WB_KELVINS.get(idx); SnapmaticaConfig.save(); },
                true));

        out.add(Item.section("snapmatica.camera.sec_lens"));

        out.add(new Item("snapmatica.camera.focus",
                () -> focusAuto ? focusAutoLabel : fmtFocus(SnapmaticaClient.focusTarget),
                step -> { int idx = findClosest(FOCUS_VALUES, SnapmaticaClient.focusTarget);
                    idx = clampStep(idx, step, FOCUS_VALUES.size());
                    SnapmaticaClient.focusTarget = FOCUS_VALUES.get(idx); },
                !focusAuto));

        out.add(new Item("snapmatica.camera.focus_mode",
                () -> FOCUS_MODE_LABELS[clampIdx(SnapmaticaClient.focusMode, FOCUS_MODE_LABELS.length)],
                step -> SnapmaticaClient.focusMode =
                        clampStep(SnapmaticaClient.focusMode, step, FOCUS_MODE_LABELS.length),
                true));

        out.add(new Item("snapmatica.camera.focus_area",
                () -> SnapmaticaClient.focusAreaWide ? "ZONE" : "SPOT",
                step -> { SnapmaticaClient.focusAreaWide = !SnapmaticaClient.focusAreaWide;
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.sensor",
                () -> SENSOR_LABELS[findClosestF(SENSOR_CROPS, SnapmaticaClient.sensorCropFactor)],
                step -> { int idx = findClosestF(SENSOR_CROPS, SnapmaticaClient.sensorCropFactor);
                    idx = clampStep(idx, step, SENSOR_CROPS.length);
                    SnapmaticaClient.sensorCropFactor = SENSOR_CROPS[idx]; SnapmaticaConfig.save(); },
                true));
        return out;
    }

    private java.util.List<Item> buildRightColumn() {
        java.util.List<Item> out = new java.util.ArrayList<>();
        out.add(Item.section("snapmatica.camera.sec_image"));

        out.add(new Item("snapmatica.camera.chromatic_aberration",
                () -> SnapmaticaClient.chromaticAberration ? "ON" : "OFF",
                step -> { SnapmaticaClient.chromaticAberration = !SnapmaticaClient.chromaticAberration;
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.focus_breathing",
                () -> SnapmaticaClient.focusBreathing ? "ON" : "OFF",
                step -> { SnapmaticaClient.focusBreathing = !SnapmaticaClient.focusBreathing;
                    SnapmaticaConfig.save(); },
                true));

        // A look, not an optical measurement — see SnapmaticaClient.dynamicRangeSim for why it
        // stays a separate toggle instead of something the lens model derives on its own.
        out.add(new Item("snapmatica.camera.dynamic_range",
                () -> SnapmaticaClient.dynamicRangeSim ? "ON" : "OFF",
                step -> { SnapmaticaClient.dynamicRangeSim = !SnapmaticaClient.dynamicRangeSim;
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.dynamic_range_stops",
                () -> fmtStops(SnapmaticaClient.dynamicRangeStops),
                step -> { SnapmaticaClient.dynamicRangeStops = Math.max(3f, Math.min(16f,
                        SnapmaticaClient.dynamicRangeStops + step));
                    SnapmaticaConfig.save(); },
                true));

        // World scale. Not an optical setting — a statement about the build, and the only
        // number here the lens cannot infer for itself.
        out.add(new Item("snapmatica.camera.scale",
                () -> fmtScale(SnapmaticaClient.dofScaleMm),
                step -> { int idx = findClosestF(SCALE_MM, SnapmaticaClient.dofScaleMm);
                    idx = clampStep(idx, step, SCALE_MM.length);
                    SnapmaticaClient.dofScaleMm = SCALE_MM[idx]; },
                true));

        out.add(Item.section("snapmatica.camera.sec_viewfinder"));

        // Viewfinder-only aid, never in the photo — see EvfBlurRenderer.applyBlur.
        out.add(new Item("snapmatica.camera.focus_peaking",
                () -> SnapmaticaClient.focusPeaking ? "ON" : "OFF",
                step -> { SnapmaticaClient.focusPeaking = !SnapmaticaClient.focusPeaking;
                    SnapmaticaConfig.save(); },
                true));

        out.add(Item.section("snapmatica.camera.sec_output"));

        out.add(new Item("snapmatica.camera.photo_format",
                () -> PHOTO_FORMAT_LABELS[clampIdx(SnapmaticaClient.photoFormat, PHOTO_FORMAT_LABELS.length)],
                step -> { SnapmaticaClient.photoFormat =
                        clampStep(SnapmaticaClient.photoFormat, step, PHOTO_FORMAT_LABELS.length);
                    SnapmaticaConfig.save(); },
                true));

        out.add(Item.section("snapmatica.camera.sec_freecam"));

        out.add(new Item("snapmatica.camera.drone_mode",
                () -> SnapmaticaClient.droneMode ? "ON" : "OFF",
                step -> { SnapmaticaClient.droneMode = !SnapmaticaClient.droneMode;
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.hide_player",
                () -> SnapmaticaClient.freecamHidePlayer ? "ON" : "OFF",
                step -> { SnapmaticaClient.freecamHidePlayer = !SnapmaticaClient.freecamHidePlayer;
                    SnapmaticaConfig.save(); },
                true));

        // Not a quality setting for the defocus — a different way of arriving at it. See
        // ApertureIntegration: the shutter spends a burst of frames looking from points across
        // the entrance pupil, and the photograph is their sum.
        out.add(Item.section("snapmatica.camera.sec_aperture_int"));

        out.add(new Item("snapmatica.camera.aperture_integration",
                () -> SnapmaticaClient.apertureIntegration ? "ON" : "OFF",
                step -> { SnapmaticaClient.apertureIntegration = !SnapmaticaClient.apertureIntegration;
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.live_temporal",
                () -> SnapmaticaClient.liveTemporalIntegration ? "ON" : "OFF",
                step -> { SnapmaticaClient.liveTemporalIntegration = !SnapmaticaClient.liveTemporalIntegration;
                          LiveAperture.reset(); SnapmaticaConfig.save(); },
                true));
        out.add(new Item("snapmatica.camera.aperture_debug",
                () -> SnapmaticaClient.apertureDebugSamples ? "ON" : "OFF",
                step -> { SnapmaticaClient.apertureDebugSamples = !SnapmaticaClient.apertureDebugSamples;
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.aperture_samples",
                () -> Integer.toString(SnapmaticaClient.apertureSamples),
                step -> { int idx = findClosestI(APERTURE_SAMPLES, SnapmaticaClient.apertureSamples);
                    idx = clampStep(idx, step, APERTURE_SAMPLES.length);
                    SnapmaticaClient.apertureSamples = APERTURE_SAMPLES[idx];
                    SnapmaticaConfig.save(); },
                true));

        // The always-on lens. Deliberately its OWN aperture and scale rather than the camera's
        // — see SnapmaticaClient.ambientDof for why sharing them would be wrong.
        out.add(Item.section("snapmatica.camera.sec_ambient"));

        out.add(new Item("snapmatica.camera.ambient_dof",
                () -> SnapmaticaClient.ambientDof ? "ON" : "OFF",
                step -> { SnapmaticaClient.ambientDof = !SnapmaticaClient.ambientDof;
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.ambient_aperture",
                () -> "F" + fmt(SnapmaticaClient.ambientAperture),
                step -> { int idx = findClosest(AMBIENT_APERTURES, SnapmaticaClient.ambientAperture);
                    idx = clampStep(idx, step, AMBIENT_APERTURES.size());
                    SnapmaticaClient.ambientAperture = AMBIENT_APERTURES.get(idx);
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.ambient_scale",
                () -> fmtScale(SnapmaticaClient.ambientDofScaleMm),
                step -> { int idx = findClosestF(SCALE_MM, SnapmaticaClient.ambientDofScaleMm);
                    idx = clampStep(idx, step, SCALE_MM.length);
                    SnapmaticaClient.ambientDofScaleMm = SCALE_MM[idx];
                    SnapmaticaConfig.save(); },
                true));

        out.add(new Item("snapmatica.camera.ambient_quality",
                () -> AMBIENT_QUALITY_LABELS[clampIdx(SnapmaticaClient.ambientQuality,
                        AMBIENT_QUALITY_LABELS.length)],
                step -> { SnapmaticaClient.ambientQuality = clampStep(SnapmaticaClient.ambientQuality,
                        step, AMBIENT_QUALITY_LABELS.length);
                    SnapmaticaConfig.save(); },
                true));
        return out;
    }

    /** Scrolls by whole rows. Returns false when there is nothing to scroll, so the event
     *  falls through rather than being silently swallowed. */
    private boolean onScroll(double dy) {
        if (!scrollable || dy == 0) return false;
        scrollPx = Math.max(0, scrollPx - (int) Math.signum(dy) * ROW_H);
        rebuildWidgets();
        return true;
    }

    //? if >=1.21.10 {
    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }
    //?} elif >=1.21 {
    /*@Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }
    *///?} else {
    /*@Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        return onScroll(amount) || super.mouseScrolled(mx, my, amount);
    }
    *///?}

    /**
     * How big a block is, in millimetres.
     *
     * <p>Stops rather than a slider, and chosen to be the scales people actually build at: a
     * metre for the usual convention, a half or a third of one for a city or a railway, down to
     * 20 cm where a room-sized build reads as a model. The default sits at 37.5 cm.
     *
     * <p>Below that, the same thin-lens formula stops describing "a smaller world" and starts
     * describing a toy of one — a block scaled to 1 cm makes the whole build optically the size
     * of an actual diorama, which is what throws the depth of field this shallow at a normal
     * shooting distance. That is the tilt-shift miniature look, on request rather than as the
     * side effect it was at 20 cm.
     */
    private static final float[] SCALE_MM = {
            10f, 20f, 30f, 50f, 75f, 100f, 150f,
            200f, 250f, 300f, 375f, 500f, 625f, 750f, 1000f, 1500f, 2000f };

    /**
     * Pupil samples offered for {@link SnapmaticaClient#apertureSamples}.
     *
     * <p>Powers of two up to the class ceiling, because the disc's sample spacing goes as
     * 1/sqrt(N) — halving the gap between ghosts costs four times the frames, and offering
     * intermediate stops would suggest a finer control than the eye actually gets.
     */
    private static final int[] APERTURE_SAMPLES = { 8, 16, 32, 64, 128, 256 };

    private static String fmtScale(float mm) {
        if (mm >= 1000f) return String.format("1blk = %.1fm", mm / 1000f);
        float cm = mm / 10f;
        // One decimal below 10 cm — the miniature stops sit close enough together (1, 2, 3 cm)
        // that rounding to a whole centimetre would show the same label for two different ones.
        return (cm < 10f) ? String.format("1blk = %.1fcm", cm)
                          : String.format("1blk = %.0fcm", cm);
    }

    private static String fmtStops(float stops) {
        return String.format("%.0f stops", stops);
    }

    private static int findClosestF(float[] arr, float v) {
        int best = 0; float bd = Float.MAX_VALUE;
        for (int i = 0; i < arr.length; i++) {
            float d = Math.abs(arr[i] - v);
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

    /**
     * Deliberately empty. Before 1.21.11, {@code Screen.render} opens by calling this, which
     * lands *after* this screen has drawn its own content — a second coat of the backdrop that
     * buried everything under it. The backdrop is painted from {@link #render} instead, where
     * the order is ours to control on every version.
     */
    //? if >=1.21 {
    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {}
    //?} else {
    /*@Override
    public void renderBackground(GuiGraphicsExtractor ctx) {}
    *///?}

    /** The dimmed backdrop this screen sits on. */
    private void drawBackdrop(GuiGraphicsExtractor ctx) {
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        drawBackdrop(ctx);
        ctx.centeredText(font, Component.literal("SNAPMATICA CAMERA"),
                width / 2, 10, CameraUi.CREAM);
        ctx.centeredText(font, Component.translatable("snapmatica.camera.tab_photo"),
                colLeftX, headerY, CameraUi.CREAM_DIM);
        ctx.centeredText(font, Component.translatable("snapmatica.camera.tab_camera"),
                colRightX, headerY, CameraUi.CREAM_DIM);
        // Group headings, with a rule running out to either side so a run of rows reads as one
        // block rather than as an undifferentiated list.
        for (Heading h : headings) {
            String label = Component.translatable(h.key()).getString();
            int halfW = font.width(label) / 2;
            int ruleY = h.y() + 4;
            ctx.fill(h.x() - 84, ruleY, h.x() - halfW - 5, ruleY + 1, 0x40E8DCC4);
            ctx.fill(h.x() + halfW + 5, ruleY, h.x() + 84, ruleY + 1, 0x40E8DCC4);
            ctx.centeredText(font, Component.literal(label),
                    h.x(), h.y(), 0xFF8A8A95);
        }
        if (scrollable) {
            // A real bar rather than a pair of arrows. Half this list is off-screen at a large
            // GUI scale, and an arrow glyph says "there is more" without saying how much more
            // or where in it you are — which is exactly what someone hunting for a setting
            // needs to know. Track and thumb sit just outside the right-hand column.
            int barX = Math.min(width - 6, colRightX + 96);
            int trackTop = scrollVisTop, trackH = Math.max(1, scrollVisBottom - scrollVisTop);
            ctx.fill(barX, trackTop, barX + 3, trackTop + trackH, 0x30E8DCC4);
            int thumbH = Math.max(12, trackH * scrollVisH / Math.max(1, scrollContentH));
            int thumbY = trackTop + (trackH - thumbH) * scrollPx / Math.max(1, scrollMax);
            ctx.fill(barX, thumbY, barX + 3, thumbY + thumbH, 0xC0E8DCC4);
        }
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static int clampIdx(int idx, int len) { return Math.max(0, Math.min(len - 1, idx)); }
    private static int clampStep(int idx, int step, int len) { return Math.max(0, Math.min(len - 1, idx + step)); }

    private static int findClosest(List<Float> list, float v) {
        int best = 0; float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) { float d = Math.abs(list.get(i) - v); if (d < bestDiff) { bestDiff = d; best = i; } }
        return best;
    }

    private static int findClosestInt(List<Integer> list, int v) {
        int best = 0; int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) { int d = Math.abs(list.get(i) - v); if (d < bestDiff) { bestDiff = d; best = i; } }
        return best;
    }

    private static String fmt(float v) { return v == (int) v ? String.valueOf((int) v) : String.format("%.1f", v); }
    private static String fmtFocus(float v) {
        if (v >= SnapmaticaClient.FOCUS_INFINITY) return "inf";
        if (v < 1.0f) return String.format("%.1fm", v);
        return fmt(v) + "m";
    }
}
