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
 */
public record CameraSettings(
		float aperture,
		int shutterSpeedIdx,
		int iso,
		float focusDistance,
		int focalLengthMm,
		int lensType,
		int filmType,
		int remainingShots
) {
	public static final CameraSettings DEFAULT = new CameraSettings(
			5.6f, 10, 400, 5.0f, 50, LensKind.PRIME_50MM, 0, 0
	);

	public static final Codec<CameraSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.fieldOf("aperture").forGetter(CameraSettings::aperture),
			Codec.INT.fieldOf("shutter_speed_idx").forGetter(CameraSettings::shutterSpeedIdx),
			Codec.INT.fieldOf("iso").forGetter(CameraSettings::iso),
			Codec.FLOAT.fieldOf("focus_distance").forGetter(CameraSettings::focusDistance),
			Codec.INT.fieldOf("focal_length_mm").forGetter(CameraSettings::focalLengthMm),
			Codec.INT.fieldOf("lens_type").forGetter(CameraSettings::lensType),
			Codec.INT.fieldOf("film_type").forGetter(CameraSettings::filmType),
			Codec.INT.fieldOf("remaining_shots").forGetter(CameraSettings::remainingShots)
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
}
