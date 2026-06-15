package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Copies the most recently captured photo / video to the system clipboard.
 *
 * On Windows, launchers typically start the JVM with -Djava.awt.headless=true,
 * so we shell out to PowerShell to do the copy out-of-process. On other
 * platforms, AWT is used directly with headless forced to false.
 *
 *  - Photos  → clipboard image (pasteable into Discord, chat apps, image editors)
 *  - Videos  → clipboard file reference (pasteable into Explorer / Discord upload)
 */
@Environment(EnvType.CLIENT)
public final class ClipboardUtil {
    private ClipboardUtil() {}

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().startsWith("win");

    static {
        if (!IS_WINDOWS) {
            try { System.setProperty("java.awt.headless", "false"); } catch (Throwable ignored) {}
        }
    }

    /** Copy a saved PNG to the clipboard as image data (async). */
    public static void copyImageAsync(File pngFile) {
        run("photographica-clipboard-image", () -> {
            if (IS_WINDOWS) {
                String path = pngFile.getAbsolutePath().replace("'", "''");
                powershell(
                    "Add-Type -Assembly System.Windows.Forms;" +
                    "Add-Type -Assembly System.Drawing;" +
                    "$img=[System.Drawing.Image]::FromFile('" + path + "');" +
                    "[System.Windows.Forms.Clipboard]::SetImage($img);" +
                    "$img.Dispose()");
            } else {
                BufferedImage src = ImageIO.read(pngFile);
                if (src == null) throw new IllegalStateException("画像デコード失敗: " + pngFile.getName());
                BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.drawImage(src, 0, 0, null);
                g.dispose();
                clipboard().setContents(new ImageTransferable(rgb), null);
            }
        }, "📋 写真をクリップボードにコピーしました");
    }

    /** Copy a saved file (e.g. an MP4) to the clipboard as a file reference (async). */
    public static void copyFileAsync(File file) {
        run("photographica-clipboard-file", () -> {
            if (IS_WINDOWS) {
                String path = file.getAbsolutePath().replace("'", "''");
                powershell(
                    "Add-Type -Assembly System.Windows.Forms;" +
                    "$sc=New-Object System.Collections.Specialized.StringCollection;" +
                    "$sc.Add('" + path + "');" +
                    "[System.Windows.Forms.Clipboard]::SetFileDropList($sc)");
            } else {
                clipboard().setContents(new FileTransferable(List.of(file)), null);
            }
        }, "📋 動画をクリップボードにコピーしました");
    }

    private interface ClipboardTask { void run() throws Exception; }

    private static void run(String name, ClipboardTask task, String successMsg) {
        Thread t = new Thread(() -> {
            try {
                task.run();
                actionBar(successMsg);
            } catch (Throwable e) {
                Photographica.LOGGER.error("クリップボードへのコピーに失敗", e);
                actionBar("⚠ クリップボードへのコピーに失敗 (" + e.getClass().getSimpleName() + ")");
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    private static void powershell(String script) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out;
        try (InputStream is = proc.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int exit = proc.waitFor();
        if (exit != 0) throw new IOException("PowerShell exit " + exit + (out.isEmpty() ? "" : ": " + out));
    }

    private static void actionBar(String msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setOverlayMessage(Component.literal(msg), false));
    }

    private static Clipboard clipboard() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    private record ImageTransferable(Image image) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.imageFlavor.equals(f); }
        @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(f)) throw new UnsupportedFlavorException(f);
            return image;
        }
    }

    private record FileTransferable(List<File> files) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.javaFileListFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor f) { return DataFlavor.javaFileListFlavor.equals(f); }
        @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
            if (!DataFlavor.javaFileListFlavor.equals(f)) throw new UnsupportedFlavorException(f);
            return files;
        }
    }
}
