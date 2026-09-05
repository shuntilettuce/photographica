package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.texture.NativeImage;

import java.io.File;
import java.nio.file.Files;

/**
 * Decodes the Linear DNGs {@link DngWriter} produces, so the in-game roll can show them.
 *
 * <p>Only this mod's own subset: uncompressed, single strip, 8 bits on three channels. That is
 * not a general raw decoder and is not trying to be — a DNG from a real camera is a Bayer
 * mosaic behind one of several compression schemes, and none of that is what this reads back.
 * What it reads is exactly what was written a few files over, which keeps the check cheap and
 * the failure mode honest: anything that does not match returns null and the tile says so.
 *
 * <p><b>Rendered "as shot", not as stored.</b> The whole point of the DNG path is that exposure
 * and white balance are NOT in the pixels — they travel as {@code BaselineExposure} and {@code
 * AsShotNeutral} instead (see {@link DngWriter}). Showing the stored pixels directly would
 * therefore show a flat, wrongly-lit frame that matches neither the viewfinder nor what any raw
 * developer opens the file to. Applying both tags on the way out is precisely what a developer's
 * default rendering does, and it is what makes the roll's DNG tile look like the photograph that
 * was taken.
 */
@Environment(EnvType.CLIENT)
final class DngReader {
    private DngReader() {}

    private static final int TAG_IMAGE_WIDTH        = 256;
    private static final int TAG_IMAGE_LENGTH       = 257;
    private static final int TAG_BITS_PER_SAMPLE    = 258;
    private static final int TAG_COMPRESSION        = 259;
    private static final int TAG_STRIP_OFFSETS      = 273;
    private static final int TAG_SAMPLES_PER_PIXEL  = 277;
    private static final int TAG_STRIP_BYTE_COUNTS  = 279;
    private static final int TAG_AS_SHOT_NEUTRAL    = 50728;
    private static final int TAG_BASELINE_EXPOSURE  = 50730;

    /** Returns the decoded image, or null if the file is not one of ours. */
    static NativeImage read(File file) throws Exception {
        byte[] raw = Files.readAllBytes(file.toPath());
        Tiff.Dir dir = Tiff.read(raw);
        if (dir == null) return null;

        int w = (int) dir.num(TAG_IMAGE_WIDTH, 0);
        int h = (int) dir.num(TAG_IMAGE_LENGTH, 0);
        int bits = (int) dir.num(TAG_BITS_PER_SAMPLE, 0);
        int samples = (int) dir.num(TAG_SAMPLES_PER_PIXEL, 0);
        int compression = (int) dir.num(TAG_COMPRESSION, 0);
        int stripOff = (int) dir.num(TAG_STRIP_OFFSETS, 0);
        int stripLen = (int) dir.num(TAG_STRIP_BYTE_COUNTS, 0);

        if (w <= 0 || h <= 0 || bits != 8 || samples != 3 || compression != 1) return null;
        if (stripOff <= 0 || stripLen < w * h * 3 || stripOff + w * h * 3 > raw.length) return null;

        // BaselineExposure is in stops; AsShotNeutral is what a neutral subject RECORDED as, so
        // the correction is its reciprocal — the same relationship, read back the other way,
        // that DngWriter.asShotNeutral documents on the way in.
        double[] be = dir.rationals(TAG_BASELINE_EXPOSURE);
        double gain = (be.length > 0) ? Math.pow(2.0, be[0]) : 1.0;
        double[] asn = dir.rationals(TAG_AS_SHOT_NEUTRAL);
        double gr = 1.0, gg = 1.0, gb = 1.0;
        if (asn.length == 3 && asn[0] > 1e-4 && asn[1] > 1e-4 && asn[2] > 1e-4) {
            gr = asn[1] / asn[0];   // normalised on green, which is the reference channel
            gb = asn[1] / asn[2];
        }

        NativeImage img = new NativeImage(w, h, false);
        int idx = stripOff;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = clamp255((raw[idx]     & 0xFF) * gain * gr);
                int g = clamp255((raw[idx + 1] & 0xFF) * gain * gg);
                int b = clamp255((raw[idx + 2] & 0xFF) * gain * gb);
                idx += 3;
                Pixels.setAbgr(img, x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }
        return img;
    }

    private static int clamp255(double v) {
        return v < 0 ? 0 : (v > 255 ? 255 : (int) (v + 0.5));
    }
}
