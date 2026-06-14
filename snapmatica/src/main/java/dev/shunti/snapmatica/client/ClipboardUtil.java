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
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Copies the most recently captured photo / video to the system clipboard.
 *
 * Uses AWT, which Minecraft already pulls in (java.awt.Desktop is used by the
 * vanilla launcher to open files / URLs). All work runs on a short-lived daemon
 * thread; success and failure are both reported to the in-game action bar so the
 * user gets immediate feedback either way.
 *
 *  - Photos  → the decoded image is placed on the clipboard (DataFlavor.imageFlavor).
 *              The image is flattened to TYPE_INT_RGB first: on Windows an
 *              alpha-bearing image produces a malformed CF_DIB that most apps
 *              paste as nothing / black, so stripping alpha is what actually makes
 *              the paste work.
 *  - Videos  → a file reference is placed on the clipboard (javaFileListFlavor),
 *              since video bytes cannot be pasted inline; this lets the file be
 *              pasted into a file manager or a Discord/upload field.
 */
@Environment(EnvType.CLIENT)
public final class ClipboardUtil {
    private ClipboardUtil() {}

    static {
        // Ensure AWT is not headless before its toolkit is first initialised; a
        // headless toolkit throws on getSystemClipboard(). Harmless if AWT already
        // initialised non-headless.
        try { System.setProperty("java.awt.headless", "false"); } catch (Throwable ignored) {}
    }

    /** Copy a saved PNG to the clipboard as image data (async, off the render thread). */
    public static void copyImageAsync(File pngFile) {
        run("snapmatica-clipboard-image", () -> {
            BufferedImage src = ImageIO.read(pngFile);
            if (src == null) throw new IllegalStateException("画像をデコードできません: " + pngFile.getName());
            // Flatten to opaque RGB for Windows CF_DIB compatibility.
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
            clipboard().setContents(new ImageTransferable(rgb), null);
        }, "📋 写真をクリップボードにコピーしました");
    }

    /** Copy a saved file (e.g. an MP4) to the clipboard as a file reference (async). */
    public static void copyFileAsync(File file) {
        run("snapmatica-clipboard-file", () -> {
            clipboard().setContents(new FileTransferable(List.of(file)), null);
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
                // Headless environments, sandboxed clipboards, macOS AWT quirks, etc.
                System.err.println("[Snapmatica] クリップボードへのコピーに失敗:");
                e.printStackTrace();
                actionBar("⚠ クリップボードへのコピーに失敗 (" + e.getClass().getSimpleName() + ")");
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /** Post an action-bar message on the client thread (safe from a worker thread). */
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
        @Override public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }

    private record FileTransferable(List<File> files) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.javaFileListFlavor.equals(flavor)) throw new UnsupportedFlavorException(flavor);
            return files;
        }
    }
}
