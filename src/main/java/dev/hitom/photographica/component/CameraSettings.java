package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;

/**
 * Camera state held on the ItemStack as a data component.
 *
 * aperture         F値 (F1.4 .. F22)
 * shutterSpeedIdx  0..N index into a fixed list (1/4000 .. 30s)
 * iso              ISO感度 (100 .. 25600)
 * focusDistance    ピント距離 (m)
 * focalLengthMm    焦点距離 (mm) — 24mm相当〜200mm相当
 * lensType         装着レンズ種別 (0=なし, 1=単焦点50mm, 2=ズーム24-70mm)
 * filmType         フィルム種別 (0=デジタル) ※将来拡張用、現状デジタルのみ
 * remainingShots   残枚数 (フィルム時のみ意味あり、現状未使用)
 * exposureMode     露出モード (0=M, 1=Av, 2=Tv, 3=P)
 * focusMode        フォーカスモード (0=MF, 1=AF, 2=MOB)
 */
public record CameraSettings(
		float aperture,
		int shutterSpeedIdx,
		int iso,
		float focusDistance,
		int focalLengthMm,
		int lensType,
		int filmType,
		int remainingShots,
		int exposureMode,
		int focusMode
) {
	// Exposure mode constants
	public static final int EXP_M  = 0;
	public static final int EXP_AV = 1;
	public static final int EXP_TV = 2;
	public static final int EXP_P  = 3;

	// Focus mode constants
	public static final int FOCUS_MF  = 0;
	public static final int FOCUS_AF  = 1;
	public static final int FOCUS_MOB = 2;

	public static final CameraSettings DEFAULT = new CameraSettings(
			5.6f, 10, 400, 5.0f, 50, LensKind.PRIME_50MM, 0, 0, EXP_M, FOCUS_MF
	);

	public static final Codec<CameraSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.fieldOf("aperture").forGetter(CameraSettings::aperture),
			Codec.INT.fieldOf("shutter_speed_idx").forGetter(CameraSettings::shutterSpeedIdx),
			Codec.INT.fieldOf("iso").forGetter(CameraSettings::iso),
			Codec.FLOAT.fieldOf("focus_distance").forGetter(CameraSettings::focusDistance),
			Codec.INT.fieldOf("focal_length_mm").forGetter(CameraSettings::focalLengthMm),
			Codec.INT.fieldOf("lens_type").forGetter(CameraSettings::lensType),
			Codec.INT.fieldOf("film_type").forGetter(CameraSettings::filmType),
			Codec.INT.fieldOf("remaining_shots").forGetter(CameraSettings::remainingShots),
			Codec.INT.optionalFieldOf("exposure_mode", EXP_M).forGetter(CameraSettings::exposureMode),
			Codec.INT.optionalFieldOf("focus_mode", FOCUS_MF).forGetter(CameraSettings::focusMode)
	).apply(instance, CameraSettings::new));

	public static final PacketCodec<ByteBuf, CameraSettings> PACKET_CODEC = new PacketCodec<>() {
		@Override
		public CameraSettings decode(ByteBuf buf) {
			return new CameraSettings(
					buf.readFloat(),
					buf.readInt(),
					buf.readInt(),
					buf.readFloat(),
					buf.readInt(),
					buf.readInt(),
					buf.readInt(),
					buf.readInt(),
					buf.readInt(),
					buf.readInt()
			);
		}

		@Override
		public void encode(ByteBuf buf, CameraSettings v) {
			buf.writeFloat(v.aperture());
			buf.writeInt(v.shutterSpeedIdx());
			buf.writeInt(v.iso());
			buf.writeFloat(v.focusDistance());
			buf.writeInt(v.focalLengthMm());
			buf.writeInt(v.lensType());
			buf.writeInt(v.filmType());
			buf.writeInt(v.remainingShots());
			buf.writeInt(v.exposureMode());
			buf.writeInt(v.focusMode());
		}
	};

	public boolean isFilm() {
		return filmType != 0;
	}

	private static final double[] SHUTTER_SECONDS = {
			30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
			0.5, 0.25, 0.125, 1.0 / 15, 1.0 / 30, 1.0 / 60,
			1.0 / 125, 1.0 / 250, 1.0 / 500, 1.0 / 1000, 1.0 / 2000, 1.0 / 4000
	};

	/** Shutter open time in seconds. */
	public double shutterSeconds() {
		return SHUTTER_SECONDS[Math.max(0, Math.min(SHUTTER_SECONDS.length - 1, shutterSpeedIdx))];
	}

	/**
	 * EV deviation from the reference exposure (F5.6 · 1/60 · ISO 400).
	 * Positive = overexposed, negative = underexposed.
	 */
	public double evDeviation() {
		double mult = shutterSeconds() * 60.0
				* ((5.6 / aperture) * (5.6 / aperture))
				* (iso / 400.0);
		return Math.log(mult) / Math.log(2.0);
	}

	// Convenience mutators used by AutoCamera (avoid repeating all 10 fields).

	public CameraSettings withExposureMode(int mode) {
		return new CameraSettings(aperture, shutterSpeedIdx, iso, focusDistance,
				focalLengthMm, lensType, filmType, remainingShots, mode, focusMode);
	}

	public CameraSettings withFocusMode(int mode) {
		return new CameraSettings(aperture, shutterSpeedIdx, iso, focusDistance,
				focalLengthMm, lensType, filmType, remainingShots, exposureMode, mode);
	}

	public CameraSettings withApertureAndShutter(float ap, int ss) {
		return new CameraSettings(ap, ss, iso, focusDistance,
				focalLengthMm, lensType, filmType, remainingShots, exposureMode, focusMode);
	}

	public CameraSettings withShutterIdx(int ss) {
		return new CameraSettings(aperture, ss, iso, focusDistance,
				focalLengthMm, lensType, filmType, remainingShots, exposureMode, focusMode);
	}

	public CameraSettings withApertureVal(float ap) {
		return new CameraSettings(ap, shutterSpeedIdx, iso, focusDistance,
				focalLengthMm, lensType, filmType, remainingShots, exposureMode, focusMode);
	}

	public CameraSettings withFocusDistance(float fd) {
		return new CameraSettings(aperture, shutterSpeedIdx, iso, fd,
				focalLengthMm, lensType, filmType, remainingShots, exposureMode, focusMode);
	}
}
