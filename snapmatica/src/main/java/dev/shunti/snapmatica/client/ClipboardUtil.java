package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

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
 * thread and every failure mode is swallowed: a clipboard that refuses the copy
 * must never interrupt the capture pipeline.
 *
 *  - Photos  → the decoded image is placed on the clipboard (DataFlavor.imageFlavor),
 *              so it can be pasted straight into chat / image editors.
 *  - Videos  → a file reference is placed on the clipboard (javaFileListFlavor),
 *              since video bytes cannot be pasted inline; this lets the file be
 *              pasted into a file manager or a Discord/upload field.
 */
@Environment(EnvType.CLIENT)
public final class ClipboardUtil {
    private ClipboardUtil() {}

    /** Copy a saved PNG to the clipboard as image data (async, off the render thread). */
    public static void copyImageAsync(File pngFile) {
        run("snapmatica-clipboard-image", () -> {
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null) return;
            clipboard().setContents(new ImageTransferable(img), null);
            System.out.println("[Snapmatica] 写真をクリップボードにコピー: " + pngFile.getName());
        });
    }

    /** Copy a saved file (e.g. an MP4) to the clipboard as a file reference (async). */
    public static void copyFileAsync(File file) {
        run("snapmatica-clipboard-file", () -> {
            clipboard().setContents(new FileTransferable(List.of(file)), null);
            System.out.println("[Snapmatica] 動画をクリップボードにコピー: " + file.getName());
        });
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private interface ClipboardTask { void run() throws Exception; }

    private static void run(String name, ClipboardTask task) {
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                // Headless environments, sandboxed clipboards, macOS AWT quirks, etc.
                System.err.println("[Snapmatica] クリップボードへのコピーに失敗: " + e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
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
