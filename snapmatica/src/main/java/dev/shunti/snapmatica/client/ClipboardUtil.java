package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

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
 * On Windows, Minecraft launchers typically start the JVM with
 * -Djava.awt.headless=true, making AWT's Toolkit.getSystemClipboard()
 * throw at runtime. We therefore shell out to PowerShell (always present on
 * Windows 7+) to do the copy out-of-process. On other platforms, AWT is used
 * directly with the headless flag forced to false before the toolkit is loaded.
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
        // On non-Windows systems try to un-set headless before the AWT toolkit
        // is first initialised. Has no effect once headless is already cached
        // (which is why we use PowerShell on Windows instead).
        if (!IS_WINDOWS) {
            try { System.setProperty("java.awt.headless", "false"); } catch (Throwable ignored) {}
        }
    }

    /** Copy a saved PNG to the clipboard as image data (async). */
    public static void copyImageAsync(File pngFile) {
        run("snapmatica-clipboard-image", () -> {
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
                // Flatten alpha: AWT's imageFlavor with alpha causes CF_DIB corruption on Windows.
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
        run("snapmatica-clipboard-file", () -> {
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

    // ── internals ─────────────────────────────────────────────────────────────────

    private interface ClipboardTask { void run() throws Exception; }

    private static void run(String name, ClipboardTask task, String successMsg) {
        Thread t = new Thread(() -> {
            try {
                task.run();
                actionBar(successMsg);
            } catch (Throwable e) {
                System.err.println("[Snapmatica] クリップボードへのコピーに失敗:");
                e.printStackTrace();
                actionBar("⚠ クリップボードへのコピーに失敗 (" + e.getClass().getSimpleName() + ")");
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /** Run a PowerShell command and throw if it exits non-zero. */
    private static void powershell(String script) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        // Drain stdout/stderr so the process doesn't block on full output buffer.
        String out;
        try (InputStream is = proc.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int exit = proc.waitFor();
        if (exit != 0) throw new IOException("PowerShell exit " + exit + (out.isEmpty() ? "" : ": " + out));
    }

    private static void actionBar(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal(msg), true);
        });
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
