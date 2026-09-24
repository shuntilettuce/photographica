package dev.hitom.photographica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Writes captured photos as baseline JPEG carrying an Exif APP1 block.
 *
 * <p>Photos used to be saved as PNG with no metadata at all: every bit of shooting
 * information lived on the item's data component, so a file copied out of the game
 * directory lost its aperture, shutter speed, ISO, lens and capture time. Writing
 * Exif means an ordinary photo viewer shows the same numbers the SD card browser
 * does, and sorts the photos by when they were actually taken.
 *
 * <p>JPEG also suits the image far better than PNG did. ISO grain is high-frequency
 * noise, which is the worst case for lossless compression — an ISO 1600 frame ran
 * to roughly 2.8 MB as PNG against 0.6 MB at this quality.
 *
 * <p>Pixels come in as packed RGB ints rather than a {@code NativeImage} so this
 * class stays free of the per-version texture API differences, and so the alpha
 * channel is dropped on the way in: the previous PNG path copied whatever alpha the
 * framebuffer readback happened to contain.
 */
@Environment(EnvType.CLIENT)
public final class PhotoWriter {
	private PhotoWriter() {}

	/** Extension photos are written with. Readers still accept the older {@code .png} files. */
	public static final String EXTENSION = ".jpg";

	private static final float JPEG_QUALITY = 0.92f;

	/** Exif ASCII fields are 7-bit only, so these names stay out of Japanese. */
	private static final String MAKE = "Photographica";

	private static final DateTimeFormatter EXIF_DATE =
			DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
	private static final DateTimeFormatter EXIF_OFFSET =
			DateTimeFormatter.ofPattern("xxx");

	// TIFF field types.
	private static final int TYPE_ASCII     = 2;
	private static final int TYPE_SHORT     = 3;
	private static final int TYPE_LONG      = 4;
	private static final int TYPE_RATIONAL  = 5;
	private static final int TYPE_UNDEFINED = 7;

	// Exif ExposureProgram values.
	public static final int PROGRAM_MANUAL            = 1;
	public static final int PROGRAM_NORMAL            = 2;
	public static final int PROGRAM_APERTURE_PRIORITY = 3;
	public static final int PROGRAM_SHUTTER_PRIORITY  = 4;

	/**
	 * Shooting information written into the Exif block.
	 *
	 * @param model           camera body, ASCII (e.g. {@code "Mirrorless Digital"})
	 * @param software        writing mod and version
	 * @param lens            lens name, ASCII; may be empty when no lens is attached
	 * @param artist          photographer name
	 * @param description     free text — dimension and coordinates
	 * @param userComment     free text — lens, film stock and exposure mode
	 * @param exposureSeconds shutter open time
	 * @param aperture        f-number
	 * @param iso             ISO sensitivity
	 * @param focalLengthMm   focal length; the mod's focal lengths are 35 mm equivalents
	 * @param exposureProgram one of the {@code PROGRAM_*} constants
	 * @param takenAt         capture time, with the offset the player's clock is in
	 */
	public record Shot(
			String model,
			String lens,
			String software,
			String artist,
			String description,
			String userComment,
			double exposureSeconds,
			float aperture,
			int iso,
			int focalLengthMm,
			int exposureProgram,
			OffsetDateTime takenAt
	) {}

	/**
	 * Encodes {@code rgb} as JPEG and writes it to {@code outFile} with an Exif block.
	 *
	 * @param rgb    packed pixels, one int per pixel, row-major. Alpha is ignored.
	 * @param width  image width in pixels
	 * @param height image height in pixels
	 */
	public static void write(int[] rgb, int width, int height, File outFile, Shot shot) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, width, height, rgb, 0, width);

		byte[] jpeg = encodeJpeg(image);
		byte[] exif = buildExifPayload(shot, width, height);
		byte[] out = exif != null ? spliceExif(jpeg, exif) : jpeg;

		try (OutputStream os = new FileOutputStream(outFile)) {
			os.write(out);
		}
	}

	/** The mod version, for the Exif Software field. */
	public static String softwareTag(String modId) {
		String version = FabricLoader.getInstance().getModContainer(modId)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("");
		String name = MAKE;
		return version.isEmpty() ? name : name + " " + version;
	}

	// ── JPEG ───────────────────────────────────────────────────────────────────

	private static byte[] encodeJpeg(BufferedImage image) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) throw new IOException("No JPEG encoder available");
		ImageWriter writer = writers.next();
		// Estimate 1 byte per pixel: comfortably above q92 output, so no regrowth.
		ByteArrayOutputStream baos = new ByteArrayOutputStream(image.getWidth() * image.getHeight());
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			// MemoryCache rather than the ImageIO disk cache: no temp files, and no
			// dependency on the global ImageIO.setUseCache() setting other mods may change.
			try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
				writer.setOutput(ios);
				writer.write(null, new IIOImage(image, null, null), param);
			}
		} finally {
			writer.dispose();
		}
		return baos.toByteArray();
	}

	/**
	 * Inserts an Exif APP1 segment directly after the SOI marker, dropping the JFIF
	 * APP0 segment ImageIO emits. Exif requires APP1 to be the first marker in the
	 * file; leaving APP0 in front of it produces a file some readers reject.
	 */
	private static byte[] spliceExif(byte[] jpeg, byte[] exifPayload) {
		if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
			return jpeg; // Not a JPEG we recognise — leave it alone rather than corrupt it.
		}
		int pos = 2;
		// Skip any leading APP0 (JFIF) segments.
		while (pos + 4 <= jpeg.length
				&& (jpeg[pos] & 0xFF) == 0xFF && (jpeg[pos + 1] & 0xFF) == 0xE0) {
			int segLen = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
			if (segLen < 2 || pos + 2 + segLen > jpeg.length) break;
			pos += 2 + segLen;
		}
		int segmentLength = exifPayload.length + 2;
		if (segmentLength > 0xFFFF) return jpeg; // Would not fit in one segment.

		byte[] out = new byte[2 + 4 + exifPayload.length + (jpeg.length - pos)];
		int w = 0;
		out[w++] = (byte) 0xFF;
		out[w++] = (byte) 0xD8;
		out[w++] = (byte) 0xFF;
		out[w++] = (byte) 0xE1;
		out[w++] = (byte) ((segmentLength >> 8) & 0xFF);
		out[w++] = (byte) (segmentLength & 0xFF);
		System.arraycopy(exifPayload, 0, out, w, exifPayload.length);
		w += exifPayload.length;
		System.arraycopy(jpeg, pos, out, w, jpeg.length - pos);
		return out;
	}

	// ── Exif ───────────────────────────────────────────────────────────────────

	/** One TIFF IFD entry. Values of four bytes or fewer sit inline in the entry. */
	private static final class Entry {
		final int tag;
		final int type;
		final int count;
		final byte[] value;
		/** Offset into the TIFF block, assigned once the IFD sizes are known. */
		int valueOffset;

		Entry(int tag, int type, int count, byte[] value) {
			this.tag = tag;
			this.type = type;
			this.count = count;
			this.value = value;
		}

		boolean inline() {
			return value.length <= 4;
		}
	}

	/** Returns null when the block would not fit in one APP1 segment. */
	private static byte[] buildExifPayload(Shot s, int width, int height) {
		String when = s.takenAt().format(EXIF_DATE);
		String offset = s.takenAt().format(EXIF_OFFSET);

		List<Entry> ifd0 = new ArrayList<>();
		ifd0.add(ascii(0x010E, s.description()));                       // ImageDescription
		ifd0.add(ascii(0x010F, MAKE));                                  // Make
		ifd0.add(ascii(0x0110, s.model()));                             // Model
		ifd0.add(shortValue(0x0112, 1));                                // Orientation: normal
		ifd0.add(rational(0x011A, 72, 1));                              // XResolution
		ifd0.add(rational(0x011B, 72, 1));                              // YResolution
		ifd0.add(shortValue(0x0128, 2));                                // ResolutionUnit: inch
		ifd0.add(ascii(0x0131, s.software()));                          // Software
		ifd0.add(ascii(0x0132, when));                                  // DateTime
		ifd0.add(ascii(0x013B, s.artist()));                            // Artist
		Entry exifPointer = longValue(0x8769, 0);                       // ExifIFDPointer, patched below
		ifd0.add(exifPointer);

		List<Entry> sub = new ArrayList<>();
		long[] exposure = toRational(s.exposureSeconds());
		sub.add(rational(0x829A, exposure[0], exposure[1]));            // ExposureTime
		sub.add(rational(0x829D, Math.round(s.aperture() * 100.0f), 100)); // FNumber
		sub.add(shortValue(0x8822, s.exposureProgram()));               // ExposureProgram
		sub.add(shortValue(0x8827, Math.min(65535, Math.max(0, s.iso())))); // ISOSpeedRatings
		sub.add(undefined(0x9000, "0232".getBytes(StandardCharsets.US_ASCII))); // ExifVersion
		sub.add(ascii(0x9003, when));                                   // DateTimeOriginal
		sub.add(ascii(0x9004, when));                                   // DateTimeDigitized
		sub.add(ascii(0x9011, offset));                                 // OffsetTimeOriginal
		sub.add(ascii(0x9012, offset));                                 // OffsetTimeDigitized
		sub.add(rational(0x920A, s.focalLengthMm(), 1));                // FocalLength
		sub.add(userComment(0x9286, s.userComment()));                  // UserComment
		sub.add(undefined(0xA000, "0100".getBytes(StandardCharsets.US_ASCII))); // FlashpixVersion
		sub.add(shortValue(0xA001, 1));                                 // ColorSpace: sRGB
		sub.add(longValue(0xA002, width));                              // PixelXDimension
		sub.add(longValue(0xA003, height));                             // PixelYDimension
		sub.add(shortValue(0xA405, s.focalLengthMm()));                 // FocalLengthIn35mmFilm
		if (!s.lens().isEmpty()) {
			sub.add(ascii(0xA434, s.lens()));                           // LensModel
		}

		ifd0.sort(Comparator.comparingInt(e -> e.tag));
		sub.sort(Comparator.comparingInt(e -> e.tag));

		// TIFF header is 8 bytes; IFD0 starts right after it.
		int ifd0Offset = 8;
		int ifd0Size = 2 + 12 * ifd0.size() + 4;
		int subOffset = ifd0Offset + ifd0Size;
		int subSize = 2 + 12 * sub.size() + 4;
		int dataOffset = subOffset + subSize;

		exifPointer.value[0] = (byte) (subOffset & 0xFF);
		exifPointer.value[1] = (byte) ((subOffset >> 8) & 0xFF);
		exifPointer.value[2] = (byte) ((subOffset >> 16) & 0xFF);
		exifPointer.value[3] = (byte) ((subOffset >> 24) & 0xFF);

		ByteArrayOutputStream data = new ByteArrayOutputStream();
		for (List<Entry> entries : List.of(ifd0, sub)) {
			for (Entry e : entries) {
				if (e.inline()) continue;
				e.valueOffset = dataOffset + data.size();
				data.writeBytes(e.value);
				if ((e.value.length & 1) == 1) data.write(0); // TIFF values are word-aligned.
			}
		}

		ByteArrayOutputStream tiff = new ByteArrayOutputStream();
		tiff.write('I');
		tiff.write('I');
		writeShort(tiff, 42);
		writeLong(tiff, ifd0Offset);
		writeIfd(tiff, ifd0, subOffset);
		writeIfd(tiff, sub, 0);
		tiff.writeBytes(data.toByteArray());

		byte[] tiffBytes = tiff.toByteArray();
		if (tiffBytes.length + 6 + 2 > 0xFFFF) return null; // Cannot fit; write a plain JPEG.

		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.writeBytes("Exif".getBytes(StandardCharsets.US_ASCII));
		payload.write(0);
		payload.write(0);
		payload.writeBytes(tiffBytes);
		return payload.toByteArray();
	}

	/** Writes one IFD. {@code nextIfd} is the offset of the following IFD, or 0 for none. */
	private static void writeIfd(ByteArrayOutputStream out, List<Entry> entries, int nextIfd) {
		writeShort(out, entries.size());
		for (Entry e : entries) {
			writeShort(out, e.tag);
			writeShort(out, e.type);
			writeLong(out, e.count);
			if (e.inline()) {
				for (int i = 0; i < 4; i++) {
					out.write(i < e.value.length ? e.value[i] : 0);
				}
			} else {
				writeLong(out, e.valueOffset);
			}
		}
		// IFD0 points at no successor: the Exif sub-IFD is reached through tag 0x8769,
		// not through the next-IFD chain.
		writeLong(out, 0);
	}

	private static void writeShort(ByteArrayOutputStream out, int v) {
		out.write(v & 0xFF);
		out.write((v >> 8) & 0xFF);
	}

	private static void writeLong(ByteArrayOutputStream out, long v) {
		out.write((int) (v & 0xFF));
		out.write((int) ((v >> 8) & 0xFF));
		out.write((int) ((v >> 16) & 0xFF));
		out.write((int) ((v >> 24) & 0xFF));
	}

	private static Entry ascii(int tag, String text) {
		byte[] raw = toAscii(text);
		byte[] value = new byte[raw.length + 1]; // NUL terminator counts toward the field length.
		System.arraycopy(raw, 0, value, 0, raw.length);
		return new Entry(tag, TYPE_ASCII, value.length, value);
	}

	private static Entry undefined(int tag, byte[] raw) {
		return new Entry(tag, TYPE_UNDEFINED, raw.length, raw);
	}

	/** UserComment carries an 8-byte character-set code before the text itself. */
	private static Entry userComment(int tag, String text) {
		byte[] raw = toAscii(text);
		byte[] value = new byte[8 + raw.length];
		byte[] charset = "ASCII".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(charset, 0, value, 0, charset.length);
		System.arraycopy(raw, 0, value, 8, raw.length);
		return new Entry(tag, TYPE_UNDEFINED, value.length, value);
	}

	private static Entry shortValue(int tag, int v) {
		byte[] value = { (byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF) };
		return new Entry(tag, TYPE_SHORT, 1, value);
	}

	private static Entry longValue(int tag, long v) {
		byte[] value = {
				(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
				(byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)
		};
		return new Entry(tag, TYPE_LONG, 1, value);
	}

	private static Entry rational(int tag, long numerator, long denominator) {
		ByteArrayOutputStream b = new ByteArrayOutputStream(8);
		writeLong(b, numerator);
		writeLong(b, denominator);
		return new Entry(tag, TYPE_RATIONAL, 1, b.toByteArray());
	}

	/** Exif ASCII fields are 7-bit; anything else is replaced so viewers do not see mojibake. */
	private static byte[] toAscii(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			sb.append(c >= 0x20 && c < 0x7F ? c : '?');
		}
		return sb.toString().getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * Shutter time as an Exif rational. Times of a second or more are written whole,
	 * faster ones as 1/N, which is how a camera records them.
	 */
	private static long[] toRational(double seconds) {
		if (seconds <= 0) return new long[] { 1, 1 };
		if (seconds >= 1.0) return new long[] { Math.round(seconds), 1 };
		return new long[] { 1, Math.max(1, Math.round(1.0 / seconds)) };
	}
}
