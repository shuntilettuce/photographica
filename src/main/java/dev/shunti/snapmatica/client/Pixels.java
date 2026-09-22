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

    static int getAbgr(NativeImage img, int x, int y) { return img.getPixelRGBA(x, y); }
    static void setAbgr(NativeImage img, int x, int y, int abgr) { img.setPixelRGBA(x, y, abgr); }
}
