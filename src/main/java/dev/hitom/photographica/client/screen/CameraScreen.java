package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.SdCardData;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.LensItem;
import dev.hitom.photographica.network.UpdateArmorStandCameraPayload;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

@Environment(EnvType.CLIENT)
public class CameraScreen extends Screen {
	private static final List<Float> APERTURES = List.of(1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
	private static final String[] SHUTTERS = {
			"30s", "15s", "8s", "4s", "2s", "1s",
			"1/2", "1/4", "1/8", "1/15", "1/30", "1/60",
			"1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"
	};
	private static final List<Integer> ISOS = List.of(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600);
	private static final List<Float> FOCUS_VALUES = List.of(
			0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f,
			5.0f, 6.0f, 7.0f, 8.0f, 10.0f, 12.0f, 15.0f, 20.0f, 25.0f, 30.0f,
			40.0f, 50.0f, 70.0f, 100.0f, 999.0f);

	private static final String[] EXP_MODE_LABELS  = {"M", "Av", "Tv", "P"};
	private static final String[] FOCUS_MODE_LABELS = {"MF", "AF", "MOB"};

	private final ItemStack stack;
	private final int armorStandEntityId; // -1 = player's hand camera
	private CameraSettings settings;
	private boolean dirty = false;

	public CameraScreen(ItemStack stack) {
		this(stack, -1);
	}

	public CameraScreen(ItemStack stack, int armorStandEntityId) {
		super(Text.translatable("screen.photographica.camera"));
		this.stack = stack;
		this.armorStandEntityId = armorStandEntityId;
		this.settings = CameraItem.getSettings(stack);
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int top = height / 2 - 112;
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
					List<Integer> available = availableLensKinds();
					int curIdx = available.indexOf(settings.lensType());
					if (curIdx < 0) curIdx = 0;
					int newLens = available.get(clampStep(curIdx, step, available.size()));
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

		// Self-timer
		addRow(cx, top + row++ * 22, "タイマー",
				() -> {
					int t = settings.timerSeconds();
					return t == 0 ? "なし" : t + "秒";
				},
				step -> {
					int[] tv = {0, 2, 5, 10};
					int cur = settings.timerSeconds();
					int idx = 0;
					for (int i = 0; i < tv.length; i++) if (tv[i] == cur) { idx = i; break; }
					idx = Math.floorMod(idx + step, tv.length);
					settings = settings.withTimerSeconds(tv[idx]);
					dirty = true;
				}, true);

		int btnY = top + row * 22 + 14;
		if (armorStandEntityId >= 0) {
			// Armor stand mode: show "Shoot" + "Close"
			addDrawableChild(SafelightButton.primary(cx - 105, btnY, 100,
					Text.literal("撮影"),
					b -> {
						flushSettings();
						PhotoCapture.triggerArmorStandCapture(armorStandEntityId, stack);
						close();
					}));
			addDrawableChild(SafelightButton.ghost(cx + 5, btnY, 100,
					Text.literal("閉じる"), b -> close()));
		} else {
			SdCardData sdData = stack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
			if (!sdData.isEmpty()) {
				addDrawableChild(SafelightButton.primary(cx - 105, btnY, 100,
						Text.literal("SDカード (" + sdData.photos().size() + ")"),
						b -> {
							if (dirty) {
								CameraItem.setSettings(stack, settings);
								ClientPlayNetworking.send(new UpdateCameraSettingsPayload(settings));
								dirty = false;
							}
							client.setScreen(new SdCardBrowserScreen(stack, sdData, this));
						}));
				addDrawableChild(SafelightButton.ghost(cx + 5, btnY, 100,
						Text.literal("閉じる"), b -> close()));
			} else {
				addDrawableChild(SafelightButton.ghost(cx - 50, btnY, 100,
						Text.literal("閉じる"), b -> close()));
			}
		}
	}

	private void addRow(int cx, int y, String label, java.util.function.Supplier<String> value,
	                    java.util.function.IntConsumer step, boolean editable) {
		SafelightButton left = SafelightButton.of(cx - 30, y, 20, Text.literal("◀"),
				b -> { step.accept(-1); dirty = true; clearAndInit(); });
		left.active = editable;
		addDrawableChild(left);

		SafelightButton center = SafelightButton.ghost(cx - 8, y, 140,
				Text.literal(label + ": " + value.get()), b -> {});
		center.active = false;
		addDrawableChild(center);

		SafelightButton right = SafelightButton.of(cx + 134, y, 20, Text.literal("▶"),
				b -> { step.accept(1); dirty = true; clearAndInit(); });
		right.active = editable;
		addDrawableChild(right);
	}

	@Override
	public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, 0xFF101010);

		// Draw dark panel background
		int cx = width / 2;
		int top = height / 2 - 112;
		int panelW = 320;
		int panelH = 256;
		int px = cx - panelW / 2;
		int py = top - 16;
		GuiHelper.drawPanel(ctx, px, py, panelW, panelH);

		// Nameplate at top of panel
		GuiHelper.drawNameplate(ctx, px + 6, py + 5, panelW - 12);

		// Rule below nameplate
		GuiHelper.drawRule(ctx, px + 6, py + 17, panelW - 12);

		super.render(ctx, mouseX, mouseY, delta);

		// Title text
		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("CAMERA SETTINGS"), cx, py + 6, GuiHelper.CREAM);
	}

	private void flushSettings() {
		if (dirty) {
			CameraItem.setSettings(stack, settings);
			if (armorStandEntityId >= 0) {
				ClientPlayNetworking.send(new UpdateArmorStandCameraPayload(armorStandEntityId, settings));
			} else {
				ClientPlayNetworking.send(new UpdateCameraSettingsPayload(settings));
			}
			dirty = false;
		}
	}

	@Override
	public void close() {
		flushSettings();
		super.close();
	}

	/** Returns lens kind IDs available to the player: always NONE + current + any LensItem in inventory. */
	private List<Integer> availableLensKinds() {
		TreeSet<Integer> kinds = new TreeSet<>();
		kinds.add(LensKind.NONE);
		kinds.add(settings.lensType());
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			for (ItemStack s : mc.player.getInventory().main) {
				if (s.getItem() instanceof LensItem lens) kinds.add(lens.lensKind);
			}
			for (ItemStack s : mc.player.getInventory().offHand) {
				if (s.getItem() instanceof LensItem lens) kinds.add(lens.lensKind);
			}
		}
		return new ArrayList<>(kinds);
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
				settings.exposureMode(), settings.focusMode(), settings.autoWind(), settings.timerSeconds());
	}
	private CameraSettings withShutter(int v) {
		return new CameraSettings(settings.aperture(), v, settings.iso(),
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind(), settings.timerSeconds());
	}
	private CameraSettings withIso(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), v,
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind(), settings.timerSeconds());
	}
	private CameraSettings withFocus(float v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				v, settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind(), settings.timerSeconds());
	}
	private CameraSettings withFocalLength(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), v, settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind(), settings.timerSeconds());
	}
	private CameraSettings withLensAndFocal(int lens, int focal) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), focal, lens,
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), settings.focusMode(), settings.autoWind(), settings.timerSeconds());
	}
	private CameraSettings withExposureMode(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				v, settings.focusMode(), settings.autoWind(), settings.timerSeconds());
	}
	private CameraSettings withFocusMode(int v) {
		return new CameraSettings(settings.aperture(), settings.shutterSpeedIdx(), settings.iso(),
				settings.focusDistance(), settings.focalLengthMm(), settings.lensType(),
				settings.filmType(), settings.remainingShots(),
				settings.exposureMode(), v, settings.autoWind(), settings.timerSeconds());
	}
}
