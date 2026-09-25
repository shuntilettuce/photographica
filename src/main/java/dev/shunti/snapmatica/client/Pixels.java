package dev.shunti.snapmatica.client;


import com.mojang.blaze3d.platform.NativeImage;

/**
 * Reading and writing single pixels of a {@link NativeImage}, in one place.
 *
 * <p>NativeImage stopped exposing its raw pixel array in 1.21.2 and swapped the byte order its
 * accessors speak at the same time, so every caller that touches a pixel needs the same pair of
 * version branches. Keeping them here means there is exactly one of each to get right.
 *
 * <p>ABGR throughout — the packing NativeImage's own buffer uses, and what the rest of this mod
 * has always passed around.
 */

final class Pixels {
    private Pixels() {}

    static int getAbgr(NativeImage img, int x, int y) {
        int argb = img.getPixel(x, y);
        int a = (argb >>> 24) & 0xFF; int r = (argb >>> 16) & 0xFF;
        int g = (argb >>>  8) & 0xFF; int b =  argb         & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
    static void setAbgr(NativeImage img, int x, int y, int abgr) {
        int a = (abgr >>> 24) & 0xFF; int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>>  8) & 0xFF; int r =  abgr         & 0xFF;
        img.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
    }
}
