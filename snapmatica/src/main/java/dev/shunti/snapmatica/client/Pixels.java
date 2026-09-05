package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.texture.NativeImage;

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
@Environment(EnvType.CLIENT)
final class Pixels {
    private Pixels() {}

    //? if >=1.21.2 {
    static int getAbgr(NativeImage img, int x, int y) {
        int argb = img.getColorArgb(x, y);
        int a = (argb >>> 24) & 0xFF; int r = (argb >>> 16) & 0xFF;
        int g = (argb >>>  8) & 0xFF; int b =  argb         & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
    static void setAbgr(NativeImage img, int x, int y, int abgr) {
        int a = (abgr >>> 24) & 0xFF; int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>>  8) & 0xFF; int r =  abgr         & 0xFF;
        img.setColorArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
    }
    //?} else {
    /*static int getAbgr(NativeImage img, int x, int y) { return img.getColor(x, y); }
    static void setAbgr(NativeImage img, int x, int y, int abgr) { img.setColor(x, y, abgr); }
    *///?}
}
