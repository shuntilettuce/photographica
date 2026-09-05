package dev.shunti.snapmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * The viewfinder: the frame the photograph will actually be, and the readings that decide it.
 *
 * <p>The frame is not inset from what gets recorded. An inset box would be a lie twice over —
 * about the framing, and about the focal length, since the angle you judge is the angle the box
 * spans. {@link #frameRect} is the single definition, and anything that crops, blurs or draws
 * a border has to come through it.
 *
 * <p>The defocus itself runs over the whole screen rather than being scissored to this frame.
 * That costs a little work outside the bezels and buys the guarantee that the blurred region
 * and the frame can never disagree — a mismatch of even a pixel shows up as a band of
 * differently-blurred image just inside the border, which is a difficult thing to see and an
 * easy thing to avoid.
 */
public final class ViewfinderOverlay {
    private ViewfinderOverlay() {}

    /** 1 block = 37.5 cm, matching the scale the thin-lens maths uses. */
    private static final float BLOCKS_TO_M = 0.375f;

    private static final int BEZEL   = 0xB8000000;
    private static final int BRACKET = 0xFFFFFFFF;
    private static final int THIRDS  = 0x60FFFFFF;
    private static final int TEXT    = 0xFFE8DCC4;
    private static final int RETICLE = 0xFF7FE07F;

    /**
     * The 3:2 rectangle, centred, in whatever coordinate space is passed in — GUI units for
     * drawing, framebuffer pixels for cropping. Returns x, y, w, h.
     */
    public static int[] frameRect(int w, int h, boolean portrait) {
        float target = portrait ? 2f / 3f : 3f / 2f;
        int fw, fh;
        if ((float) w / h > target) { fh = h; fw = Math.round(h * target); }
        else                        { fw = w; fh = Math.round(w / target); }
        return new int[]{ (w - fw) / 2, (h - fh) / 2, fw, fh };
    }

    public static void render(float focusBlocks, float aperture, float focalLenMm,
                              boolean autoFocus) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.gameSettings.hideGUI) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        int[] fr = frameRect(sw, sh, CameraState.portrait);
        int fx = fr[0], fy = fr[1], fw = fr[2], fh = fr[3];
        int fx2 = fx + fw, fy2 = fy + fh;

        // Stated rather than assumed. The overlay is drawn after the HUD, and the HUD leaves
        // whatever the last element it drew needed.
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        // Bezels — everything the photograph will not contain.
        Gui.drawRect(0, 0, sw, fy, BEZEL);
        Gui.drawRect(0, fy2, sw, sh, BEZEL);
        Gui.drawRect(0, fy, fx, fy2, BEZEL);
        Gui.drawRect(fx2, fy, sw, fy2, BEZEL);

        // Corner brackets
        bracket(fx,  fy,  20, 2,  1,  1);
        bracket(fx2, fy,  20, 2, -1,  1);
        bracket(fx,  fy2, 20, 2,  1, -1);
        bracket(fx2, fy2, 20, 2, -1, -1);

        // Rule-of-thirds guides
        int t1x = fx + fw / 3, t2x = fx + (fw * 2) / 3;
        int t1y = fy + fh / 3, t2y = fy + (fh * 2) / 3;
        Gui.drawRect(t1x, fy + 4, t1x + 1, fy2 - 4, THIRDS);
        Gui.drawRect(t2x, fy + 4, t2x + 1, fy2 - 4, THIRDS);
        Gui.drawRect(fx + 4, t1y, fx2 - 4, t1y + 1, THIRDS);
        Gui.drawRect(fx + 4, t2y, fx2 - 4, t2y + 1, THIRDS);

        // Focus reticle
        int cx = sw / 2, cy = sh / 2;
        Gui.drawRect(cx - 10, cy, cx - 3, cy + 1, RETICLE);
        Gui.drawRect(cx + 3, cy, cx + 10, cy + 1, RETICLE);
        Gui.drawRect(cx, cy - 10, cx + 1, cy - 3, RETICLE);
        Gui.drawRect(cx, cy + 3, cx + 1, cy + 10, RETICLE);

        // Readings. Distances are shown in metres rather than blocks because that is the unit
        // the optics are actually computed in — a focus ring marked in blocks would not tell
        // you anything about the depth of field you are going to get.
        String left = String.format("F%s  %dmm", fmtAperture(aperture), Math.round(focalLenMm));
        mc.fontRenderer.drawStringWithShadow(left, fx + 6, fy2 - mc.fontRenderer.FONT_HEIGHT - 6, TEXT);
        String fd = (focusBlocks >= CameraState.FOCUS_INFINITY)
                  ? "inf" : fmtDistance(focusBlocks * BLOCKS_TO_M);
        if (autoFocus) fd = "AF " + fd;
        mc.fontRenderer.drawStringWithShadow(fd,
                fx2 - mc.fontRenderer.getStringWidth(fd) - 6, fy + 5, RETICLE);

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private static void bracket(int x, int y, int len, int thick, int dx, int dy) {
        int x2 = x + len * dx, y2 = y + len * dy;
        Gui.drawRect(Math.min(x, x2), Math.min(y, y + thick * dy),
                     Math.max(x, x2), Math.max(y, y + thick * dy), BRACKET);
        Gui.drawRect(Math.min(x, x + thick * dx), Math.min(y, y2),
                     Math.max(x, x + thick * dx), Math.max(y, y2), BRACKET);
    }

    private static String fmtAperture(float a) {
        return (a < 10f) ? String.format("%.1f", a) : String.format("%.0f", a);
    }

    private static String fmtDistance(float metres) {
        if (metres >= 100f) return String.format("%.0fm", metres);
        if (metres >= 10f)  return String.format("%.1fm", metres);
        return String.format("%.2fm", metres);
    }
}
