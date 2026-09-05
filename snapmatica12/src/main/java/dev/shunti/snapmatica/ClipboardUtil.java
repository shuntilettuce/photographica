package dev.shunti.snapmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;

import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import javax.imageio.ImageIO;

/**
 * Puts the photograph on the system clipboard, so it can be pasted straight into whatever the
 * shot was taken for.
 *
 * <p>Not through AWT on Windows. Launchers start the JVM with {@code java.awt.headless=true},
 * and {@code Toolkit.getSystemClipboard()} throws under it — the property is read once, when
 * the toolkit first initialises, so it cannot be unset from here either. PowerShell is present
 * on every Windows this game runs on and does the copy out of process, where headlessness is
 * not its problem. Elsewhere AWT is used directly, with headless cleared before the toolkit is
 * touched.
 *
 * <p>Carried over from the 1.21.x version, which arrived at the same arrangement for the same
 * reason.
 */
public final class ClipboardUtil {
    private ClipboardUtil() {}

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("win");

    static {
        if (!IS_WINDOWS) {
            try { System.setProperty("java.awt.headless", "false"); } catch (Throwable ignored) {}
        }
    }

    /** Copy a saved PNG to the clipboard as image data. Runs on its own thread. */
    public static void copyImageAsync(final File pngFile) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (IS_WINDOWS) {
                        String path = pngFile.getAbsolutePath().replace("'", "''");
                        powershell(
                            "Add-Type -Assembly System.Windows.Forms;"
                          + "Add-Type -Assembly System.Drawing;"
                          + "$img=[System.Drawing.Image]::FromFile('" + path + "');"
                          + "[System.Windows.Forms.Clipboard]::SetImage($img);"
                          + "$img.Dispose()");
                    } else {
                        BufferedImage src = ImageIO.read(pngFile);
                        if (src == null) throw new IOException("could not decode " + pngFile.getName());
                        // Flattened to RGB: an alpha channel makes Windows' CF_DIB conversion
                        // produce a black or inverted paste in several applications.
                        BufferedImage rgb = new BufferedImage(
                                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = rgb.createGraphics();
                        g.drawImage(src, 0, 0, null);
                        g.dispose();
                        Toolkit.getDefaultToolkit().getSystemClipboard()
                               .setContents(new ImageTransferable(rgb), null);
                    }
                    actionBar("Snapmatica: copied to the clipboard");
                } catch (Throwable t) {
                    actionBar("Snapmatica: clipboard copy failed: " + t.getMessage());
                }
            }
        }, "snapmatica-clipboard").start();
    }

    private static void powershell(String script) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        // Drained so the process cannot block on a full output buffer.
        String out;
        InputStream is = proc.getInputStream();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) bos.write(b, 0, n);
            out = new String(bos.toByteArray(), Charset.forName("UTF-8")).trim();
        } finally {
            is.close();
        }
        int exit = proc.waitFor();
        if (exit != 0) throw new IOException("PowerShell exit " + exit
                + (out.isEmpty() ? "" : ": " + out));
    }

    private static void actionBar(final String msg) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(new Runnable() {
            @Override public void run() {
                if (mc.ingameGUI != null) {
                    mc.ingameGUI.setOverlayMessage(new TextComponentString(msg), false);
                }
            }
        });
    }

    private static final class ImageTransferable implements Transferable {
        private final BufferedImage image;
        ImageTransferable(BufferedImage image) { this.image = image; }

        @Override public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{ DataFlavor.imageFlavor };
        }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
}
