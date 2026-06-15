package dev.hitom.photographica.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.core.UUIDUtil;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Metadata stored on a photo item. The actual PNG lives on disk at
 * <gameDir>/photographica/photos/<basename>.png.
 *
 * The on-disk basename is "&lt;date-time&gt;_&lt;uuid&gt;" (see {@link #fileBaseName()}): the
 * date/time prefix makes the photos folder human-browsable, while the embedded UUID lets
 * every lookup resolve the file by {@link #findPhotoFile} regardless of how the timestamp
 * was formatted — so the image always resolves. Legacy photos saved before {@code captureTime}
 * existed fall back to the plain UUID basename. {@link #captureDateTimeDisplay()} formats the
 * capture instant in local time for the preview.
 *
 * fogged: true when the film was exposed to light during loading/unloading or
 *         when developed under non-zero light level. The viewer renders a
 *         white-wash overlay instead of (or over) the normal image.
 */
public record PhotoData(
		UUID id,
		String photographer,
		long worldTime,
		long captureTime,
		String dimension,
		int x,
		int y,
		int z,
		CameraSettings cameraAtCapture,
		boolean fogged
) {
	/** Date/time prefix of the on-disk filename (local time — purely cosmetic, never parsed back). */
	private static final DateTimeFormatter FILE_FMT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(ZoneId.systemDefault());
	/** Human-readable capture date/time format (local timezone). */
	private static final DateTimeFormatter DISPLAY_FMT =
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());

	/** Convenience constructor — backwards-compat, fogged = false. */
	public PhotoData(UUID id, String photographer, long worldTime, long captureTime,
	                 String dimension, int x, int y, int z, CameraSettings cameraAtCapture) {
		this(id, photographer, worldTime, captureTime, dimension, x, y, z, cameraAtCapture, false);
	}

	public PhotoData withFogged(boolean f) {
		return new PhotoData(id, photographer, worldTime, captureTime, dimension, x, y, z, cameraAtCapture, f);
	}

	/** Bare UUID with hyphens removed — the stable suffix used to locate the PNG on disk. */
	private static String uuidKey(UUID id) {
		return id.toString().replace("-", "");
	}

	/**
	 * On-disk PNG basename (no extension): "&lt;date-time&gt;_&lt;uuid&gt;". The date/time prefix
	 * is human-readable; the UUID suffix lets {@link #findPhotoFile} resolve the file by id.
	 * Legacy photos (captureTime == 0) fall back to the plain UUID.
	 */
	public static String fileBaseName(long captureTime, UUID id) {
		if (captureTime <= 0L) return id.toString();
		return FILE_FMT.format(Instant.ofEpochMilli(captureTime)) + "_" + uuidKey(id);
	}

	public String fileBaseName() {
		return fileBaseName(captureTime, id);
	}

	/**
	 * Locates the PNG for a photo on disk. Matches the new "&lt;date-time&gt;_&lt;uuid&gt;.png"
	 * by its UUID suffix, then falls back to the legacy "&lt;uuid&gt;.png". Returns null if
	 * neither exists. Lookup is by the immutable UUID, so it never depends on how the
	 * timestamp was formatted — the image always resolves.
	 */
	public static File findPhotoFile(File dir, UUID id) {
		if (dir.isDirectory()) {
			String suffix = "_" + uuidKey(id) + ".png";
			File[] matches = dir.listFiles((d, name) -> name.endsWith(suffix));
			if (matches != null && matches.length > 0) return matches[0];
		}
		File legacy = new File(dir, id + ".png");
		return legacy.isFile() ? legacy : null;
	}

	/** Human-readable capture date/time (local time), or empty for legacy photos. */
	public String captureDateTimeDisplay() {
		if (captureTime <= 0L) return "";
		return DISPLAY_FMT.format(Instant.ofEpochMilli(captureTime));
	}

	public static final Codec<PhotoData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(PhotoData::id),
			Codec.STRING.fieldOf("photographer").forGetter(PhotoData::photographer),
			Codec.LONG.fieldOf("world_time").forGetter(PhotoData::worldTime),
			Codec.LONG.optionalFieldOf("capture_time", 0L).forGetter(PhotoData::captureTime),
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
			long captureTime = buf.readLong();
			int dimLen = buf.readInt();
			byte[] dimBytes = new byte[dimLen];
			buf.readBytes(dimBytes);
			String dimension = new String(dimBytes, java.nio.charset.StandardCharsets.UTF_8);
			int x = buf.readInt();
			int y = buf.readInt();
			int z = buf.readInt();
			CameraSettings camera = CameraSettings.PACKET_CODEC.decode(buf);
			boolean fogged = buf.readBoolean();
			return new PhotoData(id, photographer, worldTime, captureTime, dimension, x, y, z, camera, fogged);
		}

		@Override
		public void encode(ByteBuf buf, PhotoData v) {
			buf.writeLong(v.id.getMostSignificantBits());
			buf.writeLong(v.id.getLeastSignificantBits());
			byte[] name = v.photographer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			buf.writeInt(name.length);
			buf.writeBytes(name);
			buf.writeLong(v.worldTime);
			buf.writeLong(v.captureTime);
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
