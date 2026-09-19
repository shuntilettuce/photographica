package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.CRC32;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The camera settings a shot was actually taken at, as EXIF — the metadata block every real
 * camera writes into every file it produces.
 *
 * <p>Not an effect, and the one thing on this list that a viewer, a photo library or a raw
 * developer reads rather than looks at: it is what makes a saved shot answer "what was this
 * taken at" the way a real one does, and what lets a developer's lens-correction and
 * exposure tools know what they are working on. Written to JPEG (as an APP1 segment) and to
 * DNG (as a proper Exif sub-IFD) and PNG (as an {@code eXIf} chunk, which the PNG spec has
 * carried since 2017 and which decoders predating it skip as an unknown ancillary chunk).
 *
 * <p>Values come from the same "ideal" fields the exposure maths reads rather than the dial
 * positions shown on the readout, so a shot taken in Av/Tv/P records the exposure it was
 * actually given rather than the nearest marked stop — see {@link SnapmaticaClient#autoShutterSecondsIdeal}.
 */
@Environment(EnvType.CLIENT)
final class PhotoExif {

    private final double shutterSeconds;
    private final double aperture;
    private final double iso;
    private final int    focalLengthMm;
    private final int    equivalentFocalMm;
    private final int    exposureProgram;
    private final boolean autoWhiteBalance;
    private final int    width, height;
    private final String captureTime;

    /** Snapshots the current camera state. Taken at save time, off the same values the
     *  exposure pipeline used, so the file cannot disagree with the picture in it. */
    static PhotoExif ofCurrentSettings(int width, int height) {
        return new PhotoExif(width, height);
    }

    private PhotoExif(int width, int height) {
        int em = SnapmaticaClient.exposureMode;
        boolean ssAuto = (em == 1 || em == 3);
        boolean apAuto = (em == 2 || em == 3);
        this.shutterSeconds = ssAuto
                ? SnapmaticaClient.autoShutterSecondsIdeal
                : SnapmaticaClient.SHUTTER_SECONDS[Math.max(0, Math.min(
                        SnapmaticaClient.SHUTTER_SECONDS.length - 1, SnapmaticaClient.shutterSpeedIdx))];
        this.aperture = apAuto ? SnapmaticaClient.autoApertureIdeal : SnapmaticaClient.aperture;
        this.iso = SnapmaticaClient.autoIsoIdeal;
        this.focalLengthMm = SnapmaticaClient.focalLengthMm;
        // What the same field of view would need on 35 mm full frame — the number a photographer
        // compares lenses by, and the reason a crop-body spec sheet always prints both.
        this.equivalentFocalMm = Math.round(
                SnapmaticaClient.focalLengthMm * SnapmaticaClient.sensorCropFactor);
        // EXIF's own enumeration: 1 manual, 2 program, 3 aperture priority, 4 shutter priority.
        // This mod's Av means "aperture is what you set, shutter follows", which is EXIF's
        // aperture priority; Tv is the mirror of it.
        this.exposureProgram = switch (em) {
            case 1 -> 3;
            case 2 -> 4;
            case 3 -> 2;
            default -> 1;
        };
        this.autoWhiteBalance = (SnapmaticaClient.wbKelvin == SnapmaticaClient.WB_AUTO);
        this.width = width;
        this.height = height;
        this.captureTime = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss").format(new Date());
    }

    String captureTime() { return captureTime; }

    /** The tags that belong in a file's main directory. */
    List<Tiff.Entry> ifd0Entries() {
        List<Tiff.Entry> e = new ArrayList<>();
        e.add(Tiff.asciiEntry(271, "Snapmatica"));                  // Make
        e.add(Tiff.asciiEntry(272, "Snapmatica Virtual Camera"));   // Model
        e.add(Tiff.shortEntry(274, 1));                             // Orientation = normal
        e.add(Tiff.asciiEntry(305, "Snapmatica"));                  // Software
        e.add(Tiff.asciiEntry(306, captureTime));                   // DateTime
        e.add(Tiff.longEntry(Tiff.TAG_EXIF_IFD, 0));                // patched by Tiff.build
        return e;
    }

    /** The tags that belong in the Exif sub-directory. */
    List<Tiff.Entry> exifEntries() {
        List<Tiff.Entry> e = new ArrayList<>();
        e.add(Tiff.bytesEntry(36864, Tiff.TYPE_UNDEFINED,
                "0231".getBytes(StandardCharsets.US_ASCII)));           // ExifVersion 2.31
        e.add(exposureTime(33434, shutterSeconds));                     // ExposureTime
        e.add(Tiff.rationalEntry(33437, aperture));                     // FNumber
        e.add(Tiff.shortEntry(34850, exposureProgram));                 // ExposureProgram
        e.add(Tiff.shortEntry(34855, (int) Math.round(iso)));           // ISOSpeedRatings
        e.add(Tiff.asciiEntry(36867, captureTime));                     // DateTimeOriginal
        e.add(Tiff.asciiEntry(36868, captureTime));                     // DateTimeDigitized
        // Metering mode 5 is EXIF's "Pattern" — a multi-zone evaluative meter, which is what
        // SnapmaticaClient.updateMetering's centre-weighted five-point sampling is.
        e.add(Tiff.shortEntry(37383, 5));                               // MeteringMode
        e.add(Tiff.rationalEntry(37386, focalLengthMm));                // FocalLength
        e.add(Tiff.longEntry(40962, width));                            // PixelXDimension
        e.add(Tiff.longEntry(40963, height));                           // PixelYDimension
        e.add(Tiff.shortEntry(41986, exposureProgram == 1 ? 1 : 0));    // ExposureMode
        e.add(Tiff.shortEntry(41987, autoWhiteBalance ? 0 : 1));        // WhiteBalance
        e.add(Tiff.shortEntry(41989, equivalentFocalMm));               // FocalLengthIn35mmFilm
        e.add(Tiff.asciiEntry(42036, lensName()));                      // LensModel
        return e;
    }

    private String lensName() {
        if (SnapmaticaClient.lensType == 0) return "No Lens";
        return CameraScrollHandler.focalMinMm() + "-" + CameraScrollHandler.focalMaxMm() + "mm";
    }

    /**
     * ExposureTime as the fraction a camera would print. EXIF's type is an unsigned rational
     * either way, but writing 1/250 rather than 4000/1000000 is what makes a viewer show
     * "1/250" instead of inventing its own rounding of a decimal.
     */
    private static Tiff.Entry exposureTime(int tag, double seconds) {
        if (seconds <= 0) return Tiff.rationalEntry(tag, 0, 1);
        if (seconds < 1.0) return Tiff.rationalEntry(tag, 1, Math.max(1, Math.round(1.0 / seconds)));
        return Tiff.rationalEntry(tag, Math.max(1, Math.round(seconds)), 1);
    }

    // ── JPEG ────────────────────────────────────────────────────────────────────

    /**
     * Splices an EXIF APP1 segment into an already-written JPEG file, in place.
     *
     * <p>Done as a post-pass on the encoder's output rather than through {@code ImageIO}'s
     * metadata API, which can only express EXIF for JPEG by building a
     * {@code javax_imageio_jpeg_image_1.0} DOM tree of an unknown-marker node holding the same
     * bytes assembled here — the same splice, several layers of indirection further from what
     * actually lands in the file. APP1 must be the first segment after the SOI marker for
     * readers that scan rather than parse, which is exactly where this puts it.
     *
     * <p>A failure here is logged and swallowed by the caller: the photo itself is already
     * saved and perfectly good, and losing its metadata is not worth losing the shot over.
     */
    void injectIntoJpeg(File jpeg) throws IOException {
        byte[] original = Files.readAllBytes(jpeg.toPath());
        if (original.length < 2 || (original[0] & 0xFF) != 0xFF || (original[1] & 0xFF) != 0xD8) {
            throw new IOException("not a JPEG (no SOI marker)");
        }

        byte[] tiff = Tiff.build(ifd0Entries(), exifEntries(), null, -1);
        // "Exif\0\0" then the TIFF stream, per the EXIF spec — TIFF offsets inside are relative
        // to the start of that stream, which is what Tiff.build already assumes.
        byte[] payload = new byte[6 + tiff.length];
        System.arraycopy("Exif".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 4);
        System.arraycopy(tiff, 0, payload, 6, tiff.length);

        // Segment length counts itself but not the two marker bytes, and is big-endian — JPEG
        // segment headers are, whatever byte order the TIFF inside chose.
        int segLen = payload.length + 2;
        if (segLen > 0xFFFF) throw new IOException("EXIF block too large for one APP1 segment");

        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + segLen + 2);
        out.write(0xFF); out.write(0xD8);                     // SOI
        out.write(0xFF); out.write(0xE1);                     // APP1
        out.write((segLen >> 8) & 0xFF); out.write(segLen & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(original, 2, original.length - 2);          // everything after the original SOI

        Files.write(jpeg.toPath(), out.toByteArray());
    }

    // -- PNG ---------------------------------------------------------------------

    /**
     * Splices an {@code eXIf} chunk into an already-written PNG, in place.
     *
     * <p>PNG's own EXIF chunk, holding the bare TIFF stream with no {@code Exif} identifier
     * prefix -- that prefix is JPEG's APP1 framing rather than part of EXIF itself. Inserted
     * straight after IHDR, which is the first place the spec allows it.
     *
     * <p>Safe for every reader: a decoder that does not know {@code eXIf} sees an ancillary
     * chunk (lowercase first letter) and is required to skip it, which is why stb -- and so the
     * gallery's own PNG path -- keeps loading these unchanged.
     */
    void injectIntoPng(File png) throws IOException {
        byte[] original = Files.readAllBytes(png.toPath());
        if (original.length < 8 || (original[0] & 0xFF) != 0x89 || original[1] != 'P'
                || original[2] != 'N' || original[3] != 'G') {
            throw new IOException("not a PNG");
        }
        // IHDR is required to be the first chunk: 8-byte signature, then its own 4-byte length
        // + 4-byte type + 13 bytes of data + 4-byte CRC.
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        if (afterIhdr > original.length) throw new IOException("PNG truncated before IHDR ends");

        byte[] chunk = pngChunk("eXIf", Tiff.build(ifd0Entries(), exifEntries(), null, -1));

        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + chunk.length);
        out.write(original, 0, afterIhdr);
        out.write(chunk, 0, chunk.length);
        out.write(original, afterIhdr, original.length - afterIhdr);
        Files.write(png.toPath(), out.toByteArray());
    }

    /** length (big-endian), type, data, CRC32 of type+data -- PNG's chunk framing. */
    private static byte[] pngChunk(String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        long c = crc.getValue();
        byte[] out = new byte[12 + data.length];
        int n = data.length;
        out[0] = (byte) (n >>> 24); out[1] = (byte) (n >>> 16);
        out[2] = (byte) (n >>> 8);  out[3] = (byte) n;
        System.arraycopy(t, 0, out, 4, 4);
        System.arraycopy(data, 0, out, 8, data.length);
        int p = 8 + data.length;
        out[p]     = (byte) (c >>> 24); out[p + 1] = (byte) (c >>> 16);
        out[p + 2] = (byte) (c >>> 8);  out[p + 3] = (byte) c;
        return out;
    }

    // -- Reading it back ---------------------------------------------------------

    /**
     * The settings a saved shot records, formatted the way a camera's own playback screen shows
     * them. Null fields are simply left out, so a file written before this mod carried EXIF --
     * or by something else entirely -- still shows whatever it does have.
     */
    record Info(String exposure, String lens, String mode, String taken) {}

    /**
     * Reads a saved shot's EXIF back, whichever container it is in, or null if it has none.
     *
     * <p>Never throws: this reads whatever happens to be sitting in the photos folder,
     * including files this mod did not write, and a malformed one should show no metadata
     * rather than take the gallery down with it.
     */
    static Info read(File file) {
        try {
            byte[] tiff = extractTiff(file);
            if (tiff == null) return null;
            Tiff.Dir d = Tiff.read(tiff);
            if (d == null) return null;

            StringBuilder exposure = new StringBuilder();
            long[] ss = d.ratio(33434);                       // ExposureTime
            if (ss != null && ss[0] != 0 && ss[1] != 0) {
                exposure.append(ss[1] > ss[0] ? (ss[0] + "/" + ss[1] + "s")
                                              : (fmt((double) ss[0] / ss[1]) + "s"));
            }
            double[] fn = d.rationals(33437);                 // FNumber
            if (fn.length > 0 && fn[0] > 0) {
                if (exposure.length() > 0) exposure.append("   ");
                exposure.append("f/").append(fmt(fn[0]));
            }
            long iso = d.num(34855, -1);                      // ISOSpeedRatings
            if (iso > 0) {
                if (exposure.length() > 0) exposure.append("   ");
                exposure.append("ISO ").append(iso);
            }

            StringBuilder lens = new StringBuilder();
            double[] fl = d.rationals(37386);                 // FocalLength
            if (fl.length > 0 && fl[0] > 0) lens.append(Math.round(fl[0])).append("mm");
            long eq = d.num(41989, -1);                       // FocalLengthIn35mmFilm
            if (eq > 0 && fl.length > 0 && Math.round(fl[0]) != eq) {
                lens.append(" (").append(eq).append("mm eq)");
            }
            String lensModel = d.text(42036);                 // LensModel
            if (lensModel != null && !lensModel.isBlank()) {
                if (lens.length() > 0) lens.append("   ");
                lens.append(lensModel);
            }

            StringBuilder mode = new StringBuilder();
            String progName = switch ((int) d.num(34850, -1)) {   // ExposureProgram
                case 1 -> "M"; case 2 -> "P"; case 3 -> "Av"; case 4 -> "Tv"; default -> null;
            };
            if (progName != null) mode.append(progName);
            if (d.has(41987)) {                               // WhiteBalance
                if (mode.length() > 0) mode.append(" | ");
                mode.append(d.num(41987, 0) == 0 ? "AWB" : "WB manual");
            }
            if (d.num(37383, -1) == 5) {                      // MeteringMode = Pattern
                if (mode.length() > 0) mode.append(" | ");
                mode.append("Pattern");
            }

            String taken = d.text(36867);                     // DateTimeOriginal
            if (taken == null) taken = d.text(306);           // DateTime

            if (exposure.length() == 0 && lens.length() == 0
                    && mode.length() == 0 && taken == null) {
                return null;
            }
            return new Info(nullIfEmpty(exposure), nullIfEmpty(lens), nullIfEmpty(mode), taken);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Locates the TIFF/EXIF stream inside a file -- JPEG APP1, PNG eXIf chunk, or a DNG, which
     * is a TIFF from its very first byte. Null if there is none.
     */
    private static byte[] extractTiff(File file) throws IOException {
        byte[] b = Files.readAllBytes(file.toPath());
        if (b.length < 8) return null;

        // DNG: already a TIFF.
        if (b[0] == 'I' && b[1] == 'I' && (b[2] & 0xFF) == 42 && b[3] == 0) return b;

        // JPEG: walk the segment chain looking for APP1 carrying the Exif identifier. Walking
        // rather than assuming it comes first, since another tool may have inserted its own
        // segment ahead of ours.
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            int p = 2;
            while (p + 4 <= b.length && (b[p] & 0xFF) == 0xFF) {
                int marker = b[p + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) break;   // start of scan / end of image
                int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                if (len < 2 || p + 2 + len > b.length) break;
                if (marker == 0xE1 && len >= 8 && b[p + 4] == 'E' && b[p + 5] == 'x'
                        && b[p + 6] == 'i' && b[p + 7] == 'f') {
                    int start = p + 10;                        // identifier, two NULs, then TIFF
                    int size = len - 8;
                    if (size > 0 && start + size <= b.length) {
                        byte[] tiff = new byte[size];
                        System.arraycopy(b, start, tiff, 0, size);
                        return tiff;
                    }
                }
                p += 2 + len;
            }
            return null;
        }

        // PNG: walk the chunk chain looking for eXIf.
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            int p = 8;
            while (p + 12 <= b.length) {
                int len = ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16)
                        | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                if (len < 0 || p + 12 + len > b.length) break;
                if (b[p + 4] == 'e' && b[p + 5] == 'X' && b[p + 6] == 'I' && b[p + 7] == 'f') {
                    byte[] tiff = new byte[len];
                    System.arraycopy(b, p + 8, tiff, 0, len);
                    return tiff;
                }
                if (b[p + 4] == 'I' && b[p + 5] == 'E' && b[p + 6] == 'N' && b[p + 7] == 'D') break;
                p += 12 + len;
            }
        }
        return null;
    }

    private static String nullIfEmpty(CharSequence cs) {
        return cs.length() == 0 ? null : cs.toString();
    }

    private static String fmt(double v) {
        return (v == Math.rint(v)) ? String.valueOf((long) v)
                                   : String.format(Locale.ROOT, "%.1f", v);
    }
}
