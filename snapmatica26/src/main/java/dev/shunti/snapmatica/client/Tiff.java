package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a TIFF byte stream: one main IFD, an optional Exif sub-IFD, and an optional block of
 * trailing data (a DNG's image strip) whose final offset is patched back into a tag.
 *
 * <p>Shared by {@link DngWriter} — a DNG <i>is</i> a TIFF — and {@link PhotoExif}, whose JPEG
 * APP1 segment carries the identical structure with no image data after it. Both need the same
 * awkward part: TIFF stores anything longer than four bytes outside the directory entry and
 * refers to it by absolute file offset, so nothing can be written until the whole layout is
 * known, and the layout depends on how much of the data is short enough to sit inline.
 *
 * <p>Little-endian ("II"), which is what every camera in circulation writes and every reader
 * therefore handles first.
 *
 * <p>Hand-written rather than a library: this project has no TIFF or EXIF dependency and the
 * subset both callers need is a few hundred lines, most of it the entry builders below.
 */
@Environment(EnvType.CLIENT)
final class Tiff {
    private Tiff() {}

    static final int TYPE_BYTE      = 1;
    static final int TYPE_ASCII     = 2;
    static final int TYPE_SHORT     = 3;
    static final int TYPE_LONG      = 4;
    static final int TYPE_RATIONAL  = 5;
    static final int TYPE_UNDEFINED = 7;
    static final int TYPE_SRATIONAL = 10;

    /** IFD0 tag whose value is the offset of the Exif sub-IFD. */
    static final int TAG_EXIF_IFD = 34665;

    record Entry(int tag, int type, int count, byte[] data) {}

    /**
     * Serialises a complete TIFF stream.
     *
     * @param ifd0              main directory. Must already contain a placeholder entry for
     *                          {@link #TAG_EXIF_IFD} if {@code exifIfd} is non-empty, and one
     *                          for {@code trailingOffsetTag} if that is non-negative — both are
     *                          patched here with the offsets only the final layout knows.
     * @param exifIfd           Exif sub-directory, or empty/null for none
     * @param trailing          data appended after every directory (a DNG's uncompressed image
     *                          strip); empty for none
     * @param trailingOffsetTag IFD0 tag to patch with {@code trailing}'s absolute offset, or -1
     */
    static byte[] build(List<Entry> ifd0, List<Entry> exifIfd,
                        byte[] trailing, int trailingOffsetTag) {
        List<Entry> e0 = new ArrayList<>(ifd0);
        List<Entry> e1 = (exifIfd == null) ? new ArrayList<>() : new ArrayList<>(exifIfd);
        byte[] tail = (trailing == null) ? new byte[0] : trailing;

        // TIFF requires entries in ascending tag order, and readers are entitled to binary
        // search on it.
        e0.sort((a, b) -> Integer.compare(a.tag(), b.tag()));
        e1.sort((a, b) -> Integer.compare(a.tag(), b.tag()));

        final int ifd0Start = 8;                       // straight after the 8-byte header
        int ifd0Size = 2 + e0.size() * 12 + 4;
        int ext0Start = ifd0Start + ifd0Size;
        int[] off0 = new int[e0.size()];
        int cursor = layoutExternals(e0, ext0Start, off0);

        int exifStart = cursor;
        int[] off1 = new int[e1.size()];
        if (!e1.isEmpty()) {
            int ifd1Size = 2 + e1.size() * 12 + 4;
            cursor = layoutExternals(e1, exifStart + ifd1Size, off1);
        }
        int trailingStart = cursor;

        // Both offsets are four bytes stored inline in their own entry, so patching them
        // cannot change any size the layout above was computed from.
        if (!e1.isEmpty()) patchLong(e0, TAG_EXIF_IFD, exifStart);
        if (trailingOffsetTag >= 0) patchLong(e0, trailingOffsetTag, trailingStart);

        ByteArrayOutputStream out = new ByteArrayOutputStream(trailingStart + tail.length);

        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd0Start);
        writeAll(out, header.array());

        writeDirectory(out, e0, off0);
        writeExternals(out, e0);
        if (!e1.isEmpty()) {
            writeDirectory(out, e1, off1);
            writeExternals(out, e1);
        }
        // Defensive: the layout above should already have landed exactly here.
        while (out.size() < trailingStart) out.write(0);
        writeAll(out, tail);

        return out.toByteArray();
    }

    /** Assigns an absolute offset to every entry too long to sit inline; returns the end. */
    private static int layoutExternals(List<Entry> entries, int start, int[] offsets) {
        int cursor = start;
        for (int i = 0; i < entries.size(); i++) {
            byte[] d = entries.get(i).data();
            if (d.length > 4) {
                offsets[i] = cursor;
                cursor += d.length;
                if ((cursor & 1) != 0) cursor++;   // TIFF word-aligns offsets
            } else {
                offsets[i] = -1;                   // inline, in the entry's own value field
            }
        }
        return cursor;
    }

    private static void writeDirectory(ByteArrayOutputStream out, List<Entry> entries, int[] offsets) {
        ByteBuffer ifd = ByteBuffer.allocate(2 + entries.size() * 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        ifd.putShort((short) entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            ifd.putShort((short) e.tag());
            ifd.putShort((short) e.type());
            ifd.putInt(e.count());
            byte[] valueField = new byte[4];
            if (e.data().length <= 4) {
                System.arraycopy(e.data(), 0, valueField, 0, e.data().length);
            } else {
                ByteBuffer.wrap(valueField).order(ByteOrder.LITTLE_ENDIAN).putInt(offsets[i]);
            }
            ifd.put(valueField);
        }
        ifd.putInt(0);   // no next IFD
        writeAll(out, ifd.array());
    }

    private static void writeExternals(ByteArrayOutputStream out, List<Entry> entries) {
        for (Entry e : entries) {
            if (e.data().length > 4) {
                writeAll(out, e.data());
                if ((e.data().length & 1) != 0) out.write(0);
            }
        }
    }

    private static void patchLong(List<Entry> entries, int tag, int value) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).tag() == tag) { entries.set(i, longEntry(tag, value)); return; }
        }
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }

    // ── Reading ─────────────────────────────────────────────────────────────────

    /**
     * A parsed TIFF directory: every tag found in IFD0 and, if there is one, its Exif sub-IFD,
     * merged into a single lookup. Merging them is safe because the two use disjoint tag
     * numbers by design — that is exactly why EXIF was allocated its own high range.
     */
    static final class Dir {
        private final Map<Integer, Field> fields = new LinkedHashMap<>();
        private record Field(int type, int count, byte[] data) {}

        boolean has(int tag) { return fields.containsKey(tag); }

        /** First value as a long, for BYTE/SHORT/LONG tags. Returns {@code def} if absent. */
        long num(int tag, long def) {
            Field f = fields.get(tag);
            if (f == null || f.count() < 1) return def;
            ByteBuffer bb = ByteBuffer.wrap(f.data()).order(ByteOrder.LITTLE_ENDIAN);
            return switch (f.type()) {
                case TYPE_BYTE, TYPE_UNDEFINED -> f.data()[0] & 0xFF;
                case TYPE_SHORT -> bb.getShort(0) & 0xFFFF;
                case TYPE_LONG  -> bb.getInt(0) & 0xFFFFFFFFL;
                default -> def;
            };
        }

        /** All values of a RATIONAL/SRATIONAL tag as doubles; empty if absent or another type. */
        double[] rationals(int tag) {
            Field f = fields.get(tag);
            if (f == null || (f.type() != TYPE_RATIONAL && f.type() != TYPE_SRATIONAL)) {
                return new double[0];
            }
            boolean signed = f.type() == TYPE_SRATIONAL;
            ByteBuffer bb = ByteBuffer.wrap(f.data()).order(ByteOrder.LITTLE_ENDIAN);
            int n = Math.min(f.count(), f.data().length / 8);
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                long num = signed ? bb.getInt(i * 8) : (bb.getInt(i * 8) & 0xFFFFFFFFL);
                long den = signed ? bb.getInt(i * 8 + 4) : (bb.getInt(i * 8 + 4) & 0xFFFFFFFFL);
                out[i] = (den == 0) ? 0.0 : (double) num / den;
            }
            return out;
        }

        /** A RATIONAL as its literal numerator/denominator, which is how a shutter speed is
         *  meant to be shown — "1/250", not the decimal a reader would round its own way. */
        long[] ratio(int tag) {
            Field f = fields.get(tag);
            if (f == null || f.data().length < 8) return null;
            ByteBuffer bb = ByteBuffer.wrap(f.data()).order(ByteOrder.LITTLE_ENDIAN);
            return new long[]{bb.getInt(0), bb.getInt(4)};
        }

        String text(int tag) {
            Field f = fields.get(tag);
            if (f == null || f.type() != TYPE_ASCII) return null;
            int end = f.data().length;
            while (end > 0 && f.data()[end - 1] == 0) end--;
            return new String(f.data(), 0, end, StandardCharsets.US_ASCII);
        }
    }

    /**
     * Parses a TIFF stream. Returns null rather than throwing on anything malformed — this
     * reads files that may have been written by something else entirely, or truncated, and a
     * gallery tile is not worth crashing a screen over.
     *
     * <p>Little-endian only, which is what {@link #build} writes and what every camera writes.
     */
    static Dir read(byte[] b) {
        try {
            if (b.length < 8) return null;
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            if (bb.get(0) != 'I' || bb.get(1) != 'I' || bb.getShort(2) != 42) return null;
            Dir dir = new Dir();
            int exifPtr = readIfd(bb, bb.getInt(4), b.length, dir);
            if (exifPtr > 0) readIfd(bb, exifPtr, b.length, dir);
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads one directory into {@code out}; returns the Exif sub-IFD offset, or -1. */
    private static int readIfd(ByteBuffer bb, int off, int total, Dir out) {
        if (off < 8 || off + 2 > total) return -1;
        int n = bb.getShort(off) & 0xFFFF;
        if (off + 2 + n * 12 > total) return -1;
        int exifPtr = -1;
        for (int i = 0; i < n; i++) {
            int p = off + 2 + i * 12;
            int tag = bb.getShort(p) & 0xFFFF;
            int type = bb.getShort(p + 2) & 0xFFFF;
            int count = bb.getInt(p + 4);
            if (count < 0) continue;
            int size = typeSize(type) * count;
            if (size < 0 || size > total) continue;
            byte[] data = new byte[Math.max(0, size)];
            if (size <= 4) {
                for (int k = 0; k < size; k++) data[k] = bb.get(p + 8 + k);
            } else {
                int vo = bb.getInt(p + 8);
                if (vo < 0 || vo + size > total) continue;
                for (int k = 0; k < size; k++) data[k] = bb.get(vo + k);
            }
            if (tag == TAG_EXIF_IFD) exifPtr = bb.getInt(p + 8);
            out.fields.put(tag, new Dir.Field(type, count, data));
        }
        return exifPtr;
    }

    private static int typeSize(int type) {
        return switch (type) {
            case TYPE_BYTE, TYPE_ASCII, TYPE_UNDEFINED -> 1;
            case TYPE_SHORT -> 2;
            case TYPE_LONG -> 4;
            case TYPE_RATIONAL, TYPE_SRATIONAL -> 8;
            default -> 1;
        };
    }

    // ── Entry builders ──────────────────────────────────────────────────────────

    static Entry longEntry(int tag, int value) {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(value);
        return new Entry(tag, TYPE_LONG, 1, bb.array());
    }

    static Entry shortEntry(int tag, int value) {
        // A SHORT's value field is four bytes wide with the number in the low two; the high two
        // are padding, which zero-filling the buffer already handles.
        ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) value);
        return new Entry(tag, TYPE_SHORT, 1, bb.array());
    }

    static Entry shortsEntry(int tag, short... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : values) bb.putShort(v);
        return new Entry(tag, TYPE_SHORT, values.length, bb.array());
    }

    static Entry bytesEntry(int tag, int type, byte[] data) {
        return new Entry(tag, type, data.length, data);
    }

    static Entry asciiEntry(int tag, String s) {
        byte[] strBytes = s.getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[strBytes.length + 1];   // NUL terminator, per TIFF's ASCII type
        System.arraycopy(strBytes, 0, data, 0, strBytes.length);
        return new Entry(tag, TYPE_ASCII, data.length, data);
    }

    static Entry rationalEntry(int tag, long num, long den) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) num).putInt((int) den);
        return new Entry(tag, TYPE_RATIONAL, 1, bb.array());
    }

    /** Unsigned rational from a real number, over a denominator that keeps six decimal places. */
    static Entry rationalEntry(int tag, double value) {
        long denom = 1_000_000L;
        long num = Math.round(Math.max(0.0, value) * denom);
        long g = gcd(num, denom);
        if (g > 1) { num /= g; denom /= g; }
        return rationalEntry(tag, num, denom);
    }

    static Entry rationalsEntry(int tag, double... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            long denom = 1_000_000L;
            long num = Math.round(Math.max(0.0, v) * denom);
            long g = gcd(num, denom);
            if (g > 1) { num /= g; denom /= g; }
            bb.putInt((int) num).putInt((int) denom);
        }
        return new Entry(tag, TYPE_RATIONAL, values.length, bb.array());
    }

    static Entry srationalEntry(int tag, double value) {
        return new Entry(tag, TYPE_SRATIONAL, 1, srational(value));
    }

    static Entry srationalsEntry(int tag, double[] values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) bb.put(srational(v));
        return new Entry(tag, TYPE_SRATIONAL, values.length, bb.array());
    }

    /** Signed rational with a fixed 1e6 denominator (reduced by GCD) — plenty of precision for
     *  a colour-matrix coefficient, an exposure-stops value or an aperture. */
    private static byte[] srational(double v) {
        long denom = 1_000_000L;
        long num = Math.round(v * denom);
        long g = gcd(Math.abs(num), denom);
        if (g > 1) { num /= g; denom /= g; }
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) num).putInt((int) denom);
        return bb.array();
    }

    private static long gcd(long a, long b) {
        while (b != 0) { long t = b; b = a % b; a = t; }
        return a == 0 ? 1 : a;
    }
}
