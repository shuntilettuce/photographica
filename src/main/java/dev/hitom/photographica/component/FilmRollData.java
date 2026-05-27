package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Data carried by a roll of film. Same shape is used for three life stages:
 *
 *   Fresh roll    : usedExposures = 0, exposures = []
 *   Loaded camera : usedExposures rises with each shot, exposures grows
 *   Exposed film  : usedExposures = totalExposures, exposures fully populated
 *
 * Each entry in {@link #exposures} corresponds to one latent (undeveloped)
 * frame; on development, each entry is materialised as a viewable photo item.
 */
public record FilmRollData(
		int filmType,
		int totalExposures,
		int usedExposures,
		boolean wound,
		List<PhotoData> exposures
) {
	public static final FilmRollData EMPTY = new FilmRollData(
			FilmKind.COLOR_400, FilmKind.defaultExposures(FilmKind.COLOR_400),
			0, false, List.of());

	public static FilmRollData freshRoll(int filmType) {
		return new FilmRollData(filmType, FilmKind.defaultExposures(filmType),
				0, false, List.of());
	}

	public boolean isEmpty()     { return usedExposures == 0; }
	public boolean isExposed()   { return usedExposures >= totalExposures; }
	public int     remaining()   { return Math.max(0, totalExposures - usedExposures); }

	/** Returns a new instance with the given exposure appended and used count bumped, wound→false. */
	public FilmRollData withNewExposure(PhotoData shot) {
		List<PhotoData> next = new ArrayList<>(exposures);
		next.add(shot);
		return new FilmRollData(filmType, totalExposures,
				Math.min(totalExposures, usedExposures + 1), false,
				Collections.unmodifiableList(next));
	}

	public FilmRollData withWound(boolean w) {
		return new FilmRollData(filmType, totalExposures, usedExposures, w, exposures);
	}

	/** Returns a new instance with every existing exposure marked as fogged (light leak). */
	public FilmRollData withFoggedExposures() {
		if (exposures.isEmpty()) return this;
		List<PhotoData> fogged = new ArrayList<>(exposures.size());
		for (PhotoData e : exposures) fogged.add(e.withFogged(true));
		return new FilmRollData(filmType, totalExposures, usedExposures, wound,
				Collections.unmodifiableList(fogged));
	}

	public static final Codec<FilmRollData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("film_type").forGetter(FilmRollData::filmType),
			Codec.INT.fieldOf("total_exposures").forGetter(FilmRollData::totalExposures),
			Codec.INT.fieldOf("used_exposures").forGetter(FilmRollData::usedExposures),
			Codec.BOOL.fieldOf("wound").forGetter(FilmRollData::wound),
			PhotoData.CODEC.listOf().fieldOf("exposures").forGetter(FilmRollData::exposures)
	).apply(instance, FilmRollData::new));

	public static final PacketCodec<ByteBuf, FilmRollData> PACKET_CODEC = new PacketCodec<>() {
		@Override
		public FilmRollData decode(ByteBuf buf) {
			int filmType = buf.readInt();
			int total    = buf.readInt();
			int used     = buf.readInt();
			boolean w    = buf.readBoolean();
			int n        = buf.readInt();
			List<PhotoData> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) list.add(PhotoData.PACKET_CODEC.decode(buf));
			return new FilmRollData(filmType, total, used, w, Collections.unmodifiableList(list));
		}

		@Override
		public void encode(ByteBuf buf, FilmRollData v) {
			buf.writeInt(v.filmType);
			buf.writeInt(v.totalExposures);
			buf.writeInt(v.usedExposures);
			buf.writeBoolean(v.wound);
			buf.writeInt(v.exposures.size());
			for (PhotoData e : v.exposures) PhotoData.PACKET_CODEC.encode(buf, e);
		}
	};
}
