package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

@Environment(EnvType.CLIENT)
public class CameraScreen extends Screen {
	private static final List<Float> APERTURES = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
	private static final String[] SHUTTERS = {
			"30s", "15s", "8s", "4s", "2s", "1s",
			"1/2", "1/4", "1/8", "1/15", "1/30", "1/60",
			"1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"
	};
	private static final List<Integer> ISOS = List.of(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
	private static final List<Float> FOCUS_VALUES = List.of(0.3f, 0.5f, 1.0f, 2.0f, 3.0f, 5.0f, 10.0f, 20.0f, 50.0f, 999.0f);

	private static final String[] EXP_MODE_LABELS  = {"M", "Av", "Tv", "P"};
	private static final String[] FOCUS_MODE_LABELS = {"MF", "AF", "MOB"};

	private final ItemStack stack;
	private CameraSettings settings;
	private boolean dirty = false;

	public CameraScreen(ItemStack stack) {
		super(Text.translatable("screen.photographica.camera"));
		this.stack = stack;
		this.settings = CameraItem.getSettings(stack);
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int top = height / 2 - 100;
		int row = 0;

		// Aperture — disabled when auto controls it (Tv or P)
		boolean apAuto = settings.exposureMode() == CameraSettings.EXP_TV
				|| settings.exposureMode() == CameraSettings.EXP_P;
		addRow(cx, top + row++ * 22, "絞り",
				() -> apAuto ? "AUTO" : "F" + formatFloat(settings.aperture()),
				step -> {
					int idx = clampStep(APERTURES.indexOf(settings.aperture()), step, APERTURES.size());
					settings = withAperture(APERTURES.get(idx));
				}, !apAuto);

		// Shutter — disabled when auto controls it (Av or P)
		boolean ssAuto = settings.exposureMode() == CameraSettings.EXP_AV
				|| settings.exposureMode() == CameraSettings.EXP_P;
		addRow(cx, top + row++ * 22, "シャッター",
				() -> ssAuto ? "AUTO" : SHUTTERS[clampIdx(settings.shutterSpeedIdx(), SHUTTERS.length)],
				step -> settings = withShutter(clampStep(settings.shutterSpeedIdx(), step, SHUTTERS.length)),
				!ssAuto);

		addRow(cx, top + row++ * 22, "ISO感度",
				() -> "ISO " + ISOS.get(clampIdx(ISOS.indexOf(settings.iso()), ISOS.size())),
				step -> {
					int idx = clampStep(ISOS.indexOf(settings.iso()), step, ISOS.size());
					settings = withIso(ISOS.get(idx));
				}, true);

		// Focus distance — disabled when AF or MOB
		boolean focusAuto = settings.focusMode() != CameraSettings.FOCUS_MF;
		String focusAutoLabel = settings.focusMode() == CameraSettings.FOCUS_MOB ? "MOB" : "AF";
		addRow(cx, top + row++ * 22, "フォーカス",
				() -> focusAuto ? focusAutoLabel : formatFocus(settings.focusDistance()),
				step -> {
					int curIdx = nearestIdxFloat(FOCUS_VALUES, settings.focusDistance());
					int idx = clampStep(curIdx, step, FOCUS_VALUES.size());
					settings = withFocus(FOCUS_VALUES.get(idx));
				}, !focusAuto);

		// Focal length row
		boolean focalEditable = LensKind.isZoom(settings.lensType());
		addRow(cx, top + row++ * 22, "焦点距離",
				() -> {
					if (!LensKind.hasLens(settings.lensType())) return "—";
					return settings.focalLengthMm() + "mm";
				},
				step -> {
					if (!LensKind.isZoom(settings.lensType())) return;
					List<Integer> stops = LensKind.focalLengthStops(settings.lensType());
					int curIdx = stops.indexOf(settings.focalLengthMm());
					if (curIdx < 0) curIdx = 0;
					int newIdx = clampStep(curIdx, step, stops.size());
					settings = withFocalLength(stops.get(newIdx));
				}, focalEditable);

		addRow(cx, top + row++ * 22, "レンズ",
				() -> LensKind.displayName(settings.lensType()),
				step -> {
					int newLens = clampStep(settings.lensType(), step, LensKind.COUNT);
					int newFocal = LensKind.hasLens(newLens)
							? LensKind.clampFocalLength(newLens, settings.focalLengthMm())
							: LensKind.defaultFocalLength(newLens);
					settings = withLensAndFocal(newLens, newFocal);
				}, true);

		// Exposure mode row (M / Av / Tv / P)
		addRow(cx, top + row++ * 22, "露出モード",
				() -> EXP_MODE_LABELS[Math.max(0, Math.min(EXP_MODE_LABELS.length - 1, settings.exposureMode()))],
				step -> settings = withExposureMode(clampStep(settings.exposureMode(), step, EXP_MODE_LABELS.length)),
				true);

		// Focus mode row (MF / AF / MOB)
		addRow(cx, top + row++ * 22, "フォーカスモード",
				() -> FOCUS_MODE_LABELS[Math.max(0, Math.min(FOCUS_MODE_LABELS.length - 1, settings.focusMode()))],
				step -> settings = withFocusMode(clampStep(settings.focusMode(), step, FOCUS_MODE_LABELS.length)),
				true);

		addDrawableChild(ButtonWidget.builder(Text.literal("閉じる"), b -> close())
				.dimensions(cx - 50, top + row * 22 + 14, 100, 20)
				.build());
	}

	private void addRow(int cx, int y, String label, java.util.function.Supplier<String> value,
	                    java.util.function.IntConsumer step, boolean editable) {
		ButtonWidget left = ButtonWidget.builder(Text.literal("◀"),
						b -> { step.accept(-1); dirty = true; clearAndInit(); })
				.dimensions(cx - 30, y, 20, 20).build();
		left.active = editable;
		addDrawableChild(left);

		ButtonWidget center = ButtonWidget.builder(Text.literal(label + ": " + value.get()), b -> {})
				.dimensions(cx - 8, y, 140, 20).build();
		center.active = false;
		addDrawableChild(center);

		ButtonWidget right = ButtonWidget.builder(Text.literal("▶"),
						b -> { step.accept(1); dirty = true; clearAndInit(); })
				.dimensions(cx + 134, y, 20, 20).build();
		right.active = editable;
		addDrawableChild(right);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx, mouseX, mouseY, delta);
		super.render(ctx, mouseX, mouseY, delta);
		ctx.drawCenteredTextWithShadow(textRenderer, this.title, width / 2, height / 2 - 120, 0xFFFFFF);
	}

	@Override
	public void close() {
		if (dirty) {
			CameraItem.setSettings(stack, settings);
			ClientPlayNetworking.send(new UpdateCameraSettingsPayload(settings));
		}
		super.close();
	}

	// ---------- helpers ----------

	private static int clampIdx(int idx, int len) {
		if (idx < 0) return 0;
		if (idx >= len) return len - 1;
		return idx;
	}

	private static int clampStep(int idx, int step, int len) {
		int next = idx + step;
		if (next < 0) return 0;
		if (next >= len) return len - 1;
		return next;
	}

	private static int nearestIdxFloat(List<Float> values, float target) {
		int best = 0;
		float bestDiff = Float.MAX_VALUE;
		for (int i = 0; i < values.size(); i++) {
			float d = Math.abs(values.get(i) - target);
			if (d < bestDiff) { bestDiff = d; best = i; }
		}
		return best;
	}

	private static String formatFloat(float v) {
		if (v == (int) v) return String.valueOf((int) v);
		return String.format("%.1f", v);
	}

	private static String formatFocus(float v) {
		if (v >= 999.0f) return "∞";
		if (v < 1.0f) return String.format("%.1fm", v);
		return formatFloat(v) + "m";
	}

	private CameraSettings withAperture(float v) {
		return new CameraSettings(v, settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind());
	}
	private CameraSettings withShutter(int v) {
		return new CameraSettings(settings.aperture(), v, settings.iso(),
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind());
	}
	private CameraSettings withIso(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), v,
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind());
	}
	private CameraSettings withFocus(float v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				v, settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind());
	}
	private CameraSettings withFocalLength(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), v, settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind());
	}
	private CameraSettings withLensAndFocal(int lens, int focal) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), focal, lens,
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind());
	}
	private CameraSettings withExposureMode(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				v, settings.focusMode(), settings.autoWind());
	}
	private CameraSettings withFocusMode(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), v, settings.autoWind());
	}
}
