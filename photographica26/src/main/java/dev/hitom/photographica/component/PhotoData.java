package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.UUIDUtil;

import java.util.UUID;

/**
 * Metadata stored on a photo item. The actual PNG lives on disk at
 * <gameDir>/photographica/photos/<id>.png — keyed by {@link #id()}.
 *
 * fogged: true when the film was exposed to light during loading/unloading or
 *         when developed under non-zero light level. The viewer renders a
 *         white-wash overlay instead of (or over) the normal image.
 */
public record PhotoData(
		UUID id,
		String photographer,
		long worldTime,
		String dimension,
		int x,
		int y,
		int z,
		CameraSettings cameraAtCapture,
		boolean fogged
) {
	/** Convenience constructor — backwards-compat, fogged = false. */
	public PhotoData(UUID id, String photographer, long worldTime, String dimension,
	                 int x, int y, int z, CameraSettings cameraAtCapture) {
		this(id, photographer, worldTime, dimension, x, y, z, cameraAtCapture, false);
	}

	public PhotoData withFogged(boolean f) {
		return new PhotoData(id, photographer, worldTime, dimension, x, y, z, cameraAtCapture, f);
	}

	public static final Codec<PhotoData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(PhotoData::id),
			Codec.STRING.fieldOf("photographer").forGetter(PhotoData::photographer),
			Codec.LONG.fieldOf("world_time").forGetter(PhotoData::worldTime),
			Codec.STRING.fieldOf("dimension").forGetter(PhotoData::dimension),
			Codec.INT.fieldOf("x").forGetter(PhotoData::x),
			Codec.INT.fieldOf("y").forGetter(PhotoData::y),
			Codec.INT.fieldOf("z").forGetter(PhotoData::z),
			CameraSettings.CODEC.fieldOf("camera").forGetter(PhotoData::cameraAtCapture),
			Codec.BOOL.optionalFieldOf("fogged", false).forGetter(PhotoData::fogged)
	).apply(instance, PhotoData::new));

	public static final StreamCodec<ByteBuf, PhotoData> PACKET_CODEC = new StreamCodec<>() {
		@Override
		public PhotoData decode(ByteBuf buf) {
			long hi = buf.readLong();
			long lo = buf.readLong();
			UUID id = new UUID(hi, lo);
			int nameLen = buf.readInt();
			byte[] nameBytes = new byte[nameLen];
			buf.readBytes(nameBytes);
			String photographer = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
			long worldTime = buf.readLong();
			int dimLen = buf.readInt();
			byte[] dimBytes = new byte[dimLen];
			buf.readBytes(dimBytes);
			String dimension = new String(dimBytes, java.nio.charset.StandardCharsets.UTF_8);
			int x = buf.readInt();
			int y = buf.readInt();
			int z = buf.readInt();
			CameraSettings camera = CameraSettings.PACKET_CODEC.decode(buf);
			boolean fogged = buf.readBoolean();
			return new PhotoData(id, photographer, worldTime, dimension, x, y, z, camera, fogged);
		}

		@Override
		public void encode(ByteBuf buf, PhotoData v) {
			buf.writeLong(v.id.getMostSignificantBits());
			buf.writeLong(v.id.getLeastSignificantBits());
			byte[] name = v.photographer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			buf.writeInt(name.length);
			buf.writeBytes(name);
			buf.writeLong(v.worldTime);
			byte[] dim = v.dimension.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			buf.writeInt(dim.length);
			buf.writeBytes(dim);
			buf.writeInt(v.x);
			buf.writeInt(v.y);
			buf.writeInt(v.z);
			CameraSettings.PACKET_CODEC.encode(buf, v.cameraAtCapture);
			buf.writeBoolean(v.fogged);
		}
	};
}
