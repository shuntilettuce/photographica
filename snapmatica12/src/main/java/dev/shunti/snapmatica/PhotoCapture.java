package dev.shunti.snapmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Taking the picture.
 *
 * <p>The read happens at the one instant in the frame where the framebuffer holds exactly the
 * photograph: after the defocus has been composited and before the HUD is drawn. Nothing has
 * to be hidden, restored or re-rendered for it — the viewfinder, the hotbar and the chat are
 * simply not there yet. That is what makes this the stable way to do it rather than the
 * obvious one of screenshotting and cropping, which has to contend with everything the
 * interface draws and with whatever the interface happened to be doing that frame.
 *
 * <p>Only the frame rectangle is read back, not the whole screen, so the crop costs nothing and
 * the bytes that cross the bus are the bytes that get saved. {@link ViewfinderOverlay#frameRect}
 * is the single definition of that rectangle, shared with the border you composed against.
 */
public final class PhotoCapture {
    private PhotoCapture() {}

    private static volatile boolean pending = false;

    /** Ask for a photograph on the next frame that has one to give. */
    public static void request() { pending = true; }

    public static boolean isPending() { return pending; }

    /**
     * Called with the defocused world in the bound framebuffer and no interface over it.
     * OpenGL's origin is the bottom left and an image's is the top left, so the rows come back
     * upside down and are written in reverse.
     */
    public static void captureNow(int fbW, int fbH) {
        if (!pending) return;
        pending = false;
        if (fbW <= 0 || fbH <= 0) return;

        int[] fr = ViewfinderOverlay.frameRect(fbW, fbH, CameraState.portrait);
        final int x = fr[0], w = fr[2], h = fr[3];
        final int glY = fbH - fr[1] - h;

        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(x, glY, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < h; row++) {
            int src = (h - 1 - row) * w * 4;
            for (int col = 0; col < w; col++) {
                int i = src + col * 4;
                int r = buf.get(i)     & 0xFF;
                int g = buf.get(i + 1) & 0xFF;
                int b = buf.get(i + 2) & 0xFF;
                img.setRGB(col, row, (r << 16) | (g << 8) | b);
            }
        }

        final File dir = new File(Minecraft.getMinecraft().gameDir, "snapmatica/photos");
        final String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + ".png";

        // Encoding and disk are not the render thread's business. ImageIO is fine under
        // java.awt.headless -- it is the window and Desktop classes that are not.
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    dir.mkdirs();
                    File out = new File(dir, name);
                    ImageIO.write(img, "png", out);
                    tell("Snapmatica: saved " + out.getName() + "  (" + w + "x" + h + ")");
                    // Straight onto the clipboard: a screenshot you have to go and find is a
                    // screenshot, and the point of this one is to be pasted somewhere.
                    ClipboardUtil.copyImageAsync(out);
                } catch (Throwable t) {
                    tell("Snapmatica: could not save the photo: " + t.getMessage());
                }
            }
        }, "snapmatica-photo").start();
    }

    private static void tell(final String msg) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(new Runnable() {
            @Override public void run() {
                if (mc.player != null) mc.player.sendMessage(new TextComponentString(msg));
            }
        });
    }
}
