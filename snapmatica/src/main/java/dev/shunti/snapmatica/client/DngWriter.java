package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a minimal, valid "Linear DNG" — Adobe's TIFF/EXIF-based raw container, tagged
 * {@code PhotometricInterpretation = LinearRaw (34892)} rather than a Bayer CFA pattern.
 * Holds already-demosaiced, full-resolution 8-bit-per-channel RGB, uncompressed, one strip.
 *
 * <p><b>Why Linear DNG and not a simulated Bayer mosaic:</b> Minecraft's screenshot readback is
 * already full RGB per pixel — there is no sensor to demosaic, so faking a CFA pattern would
 * only throw away real per-pixel resolution and invent demosaic behaviour with no counterpart
 * in what this mod actually captured. Linear DNG is the DNG spec's own documented way to store
 * already-demosaiced raw-workflow data (in use since DNG 1.1), and every major raw developer —
 * Lightroom, darktable, RawTherapee — opens it as a genuine raw file: its exposure, white
 * balance and tone-mapping tools all operate on it normally, the way they would any raw file.
 *
 * <p><b>Honesty note</b> (see {@link PhotoCapture}'s DNG branch for the fuller account): this
 * does NOT recover any dynamic range Minecraft's own 8-bit RGBA8 framebuffer already discarded
 * before this mod ever reads it back — there is no true scene-referred data left to reconstruct.
 * What this buys is a capture with fewer of THIS MOD'S OWN destructive post-processing steps
 * baked irreversibly into the pixels (see PhotoCapture's rawCapture branches), delivered in a
 * container a raw developer's own tools can act on meaningfully — the least-processed 8-bit
 * image this mod can produce, not more information than was ever rendered.
 *
 * <p><b>ColorMatrix1</b> below is a NOMINAL, unmeasured XYZ(D65)-to-linear-sRGB matrix — the
 * standard textbook sRGB conversion matrix, not a photographed colour-chart characterisation
 * of anything (Minecraft has no camera sensor to measure). It exists purely so a raw developer
 * has some matrix to chain its white-balance/profile maths through rather than none at all;
 * {@code CalibrationIlluminant1} is set to the matching standard illuminant (D65) so no colour
 * adaptation step is silently assumed on top of it.
 *
 * <p>Structure comes from {@link Tiff} — a DNG is a TIFF, and a JPEG's EXIF block is the same
 * directory format, so both callers share the layout code rather than each hand-rolling it.
 */
@Environment(EnvType.CLIENT)
public final class DngWriter {
    private DngWriter() {}

    /** Standard XYZ(D65) → linear-sRGB matrix, row-major. See the class doc: nominal, not
     *  measured. Matches CalibrationIlluminant1 = 21 (D65) below, so no adaptation is implied. */
    private static final double[] NOMINAL_XYZ_TO_SRGB = {
             3.2404542, -1.5371385, -0.4985314,
            -0.9692660,  1.8760108,  0.0415560,
             0.0556434, -0.2040259,  1.0572252,
    };

    private static final int TAG_STRIP_OFFSETS = 273;

    /**
     * @param outFile  destination file (overwritten if present)
     * @param width    image width in pixels
     * @param height   image height in pixels
     * @param rgb      interleaved 8-bit RGB, row-major top-to-bottom, 3 bytes/pixel,
     *                 exactly {@code width*height*3} bytes — no alpha channel
     * @param baselineExposureStops EV to apply as metadata (BaselineExposure tag) rather than
     *                 bake into the pixels — see PhotoCapture's DNG branch, which passes
     *                 {@code log2(PhotoProcessor.exposureFactor())} here instead of multiplying
     *                 that factor destructively into the RGB the way the PNG/JPG path does.
     * @param wbGain   the per-channel white-balance gain the PNG/JPG path would have multiplied
     *                 in ({@link SnapmaticaClient#whiteBalanceGain}), recorded as AsShotNeutral
     *                 instead of applied — see {@link #asShotNeutral}
     * @param exif     camera settings for the Exif sub-IFD, or null to omit it
     */
    public static void write(File outFile, int width, int height, byte[] rgb,
                             double baselineExposureStops, float[] wbGain,
                             PhotoExif exif) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad dimensions");
        if (rgb.length != width * height * 3) {
            throw new IllegalArgumentException("rgb buffer size mismatch: expected "
                    + (width * height * 3) + ", got " + rgb.length);
        }

        List<Tiff.Entry> ifd0 = new ArrayList<>();
        ifd0.add(Tiff.longEntry(254, 0));                                     // NewSubFileType = primary image
        ifd0.add(Tiff.longEntry(256, width));                                 // ImageWidth
        ifd0.add(Tiff.longEntry(257, height));                                // ImageLength
        ifd0.add(Tiff.shortsEntry(258, (short) 8, (short) 8, (short) 8));     // BitsPerSample (R,G,B)
        ifd0.add(Tiff.shortEntry(259, 1));                                    // Compression = none
        ifd0.add(Tiff.shortEntry(262, 34892));                                // PhotometricInterpretation = LinearRaw
        ifd0.add(Tiff.longEntry(TAG_STRIP_OFFSETS, 0));                       // patched by Tiff.build
        ifd0.add(Tiff.shortEntry(277, 3));                                    // SamplesPerPixel
        ifd0.add(Tiff.longEntry(278, height));                                // RowsPerStrip = whole image
        ifd0.add(Tiff.longEntry(279, width * height * 3));                    // StripByteCounts
        ifd0.add(Tiff.shortEntry(284, 1));                                    // PlanarConfiguration = chunky
        ifd0.add(Tiff.rationalEntry(282, 72, 1));                             // XResolution (nominal)
        ifd0.add(Tiff.rationalEntry(283, 72, 1));                             // YResolution (nominal)
        ifd0.add(Tiff.shortEntry(296, 2));                                    // ResolutionUnit = inch
        ifd0.add(Tiff.bytesEntry(50706, Tiff.TYPE_BYTE, new byte[]{1, 4, 0, 0}));  // DNGVersion 1.4.0.0
        ifd0.add(Tiff.bytesEntry(50707, Tiff.TYPE_BYTE, new byte[]{1, 1, 0, 0}));  // DNGBackwardVersion 1.1.0.0 —
                                                                              // LinearRaw and the baseline tags
                                                                              // used here have been readable
                                                                              // since 1.1; keeping this low
                                                                              // maximises reader compatibility.
        ifd0.add(Tiff.asciiEntry(50708, "Snapmatica Virtual Camera"));        // UniqueCameraModel
        ifd0.add(Tiff.srationalsEntry(50721, NOMINAL_XYZ_TO_SRGB));           // ColorMatrix1 (nominal — see class doc)
        ifd0.add(Tiff.shortEntry(50778, 21));                                 // CalibrationIlluminant1 = D65
        ifd0.add(Tiff.shortEntry(50714, 0));                                  // BlackLevel
        ifd0.add(Tiff.shortEntry(50717, 255));                                // WhiteLevel (8-bit data)
        ifd0.add(Tiff.srationalEntry(50730, baselineExposureStops));          // BaselineExposure
        ifd0.add(Tiff.rationalsEntry(50728, asShotNeutral(wbGain)));          // AsShotNeutral

        List<Tiff.Entry> exifIfd;
        if (exif != null) {
            exifIfd = exif.exifEntries();
            // Make / Model / Software / DateTime, and the Exif pointer Tiff.build patches.
            for (Tiff.Entry e : exif.ifd0Entries()) {
                if (!hasTag(ifd0, e.tag())) ifd0.add(e);
            }
        } else {
            exifIfd = List.of();
            ifd0.add(Tiff.asciiEntry(271, "Snapmatica"));                     // Make
            ifd0.add(Tiff.asciiEntry(272, "Snapmatica Virtual Camera"));      // Model
            ifd0.add(Tiff.shortEntry(274, 1));                                // Orientation = normal
            ifd0.add(Tiff.asciiEntry(305, "Snapmatica"));                     // Software
        }

        byte[] file = Tiff.build(ifd0, exifIfd, rgb, TAG_STRIP_OFFSETS);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(file);
        }
    }

    /**
     * AsShotNeutral: the camera-space RGB a neutral subject actually recorded as, which is what
     * a raw developer divides by to white balance. It is therefore the RECIPROCAL of the gain
     * the PNG/JPG path multiplies in — that gain corrects the cast, so its inverse describes
     * the cast — normalised on green, the channel every reader treats as the reference.
     *
     * <p>Carrying the white balance here instead of in the pixels is the whole point of a raw
     * file: the developer opens the shot at the temperature the camera chose, and can then move
     * it anywhere with no loss, because nothing was ever multiplied away.
     */
    private static double[] asShotNeutral(float[] wbGain) {
        if (wbGain == null || wbGain.length != 3) return new double[]{1.0, 1.0, 1.0};
        double r = 1.0 / Math.max(wbGain[0], 1e-3f);
        double g = 1.0 / Math.max(wbGain[1], 1e-3f);
        double b = 1.0 / Math.max(wbGain[2], 1e-3f);
        return new double[]{r / g, 1.0, b / g};
    }

    private static boolean hasTag(List<Tiff.Entry> entries, int tag) {
        for (Tiff.Entry e : entries) if (e.tag() == tag) return true;
        return false;
    }
}
