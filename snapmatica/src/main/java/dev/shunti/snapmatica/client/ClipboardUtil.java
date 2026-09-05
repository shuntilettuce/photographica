package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Copies the most recently captured photo / video to the system clipboard.
 *
 * On Windows, Minecraft launchers typically start the JVM with
 * -Djava.awt.headless=true, making AWT's Toolkit.getSystemClipboard()
 * throw at runtime. We therefore shell out to PowerShell (always present on
 * Windows 7+) to do the copy out-of-process. On other platforms, AWT is used
 * directly with the headless flag forced to false before the toolkit is loaded.
 *
 *  - Photos and videos both go on the clipboard as a FILE reference (pasteable into
 *    Explorer, Discord, chat apps — a pasted file reference attaches the same way a
 *    pasted bitmap would, but keeps the real file's actual bytes and format).
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

    /**
     * Copy a saved photo to the clipboard (async) — as a FILE reference, not a decoded bitmap.
     *
     * <p>This used to convert the file to an in-memory bitmap first ({@code
     * System.Drawing.Image}/AWT {@code BufferedImage}) and put THAT on the clipboard
     * ({@code Clipboard.SetImage}/{@code DataFlavor.imageFlavor}). Windows' image clipboard
     * format (CF_DIB) is an uncompressed bitmap with no record of what file it came from, so a
     * shot saved as JPG — chosen specifically for its smaller, lossy-compressed size — pasted
     * out the other end as whatever raw bitmap the receiving app chose to re-save it as
     * (typically PNG), the actual JPEG bytes never having survived the round trip at all. A
     * file reference (the same clipboard mechanism {@link #copyFileAsync} already uses for
     * video) carries the real file — real bytes, real format, real size — and modern chat/
     * image apps (Discord, Slack, browsers) accept a pasted file reference as an attachment
     * exactly the way they accept a pasted bitmap, so nothing about the paste experience
     * changes; only what the far end actually receives does.
     */
    public static void copyImageAsync(File imageFile) {
        copyFileReferenceAsync(imageFile, Text.translatable("snapmatica.clip.photo"));
    }

    /** Copy a saved file (e.g. an MP4) to the clipboard as a file reference (async). */
    public static void copyFileAsync(File file) {
        copyFileReferenceAsync(file, Text.translatable("snapmatica.clip.video"));
    }

    private static void copyFileReferenceAsync(File file, Text successMsg) {
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
        }, successMsg);
    }

    // ── internals ─────────────────────────────────────────────────────────────────

    private interface ClipboardTask { void run() throws Exception; }

    private static void run(String name, ClipboardTask task, Text successMsg) {
        Thread t = new Thread(() -> {
            try {
                task.run();
                actionBar(successMsg);
            } catch (Throwable e) {
                System.err.println("[Snapmatica] Clipboard copy failed:");
                e.printStackTrace();
                actionBar(Text.translatable("snapmatica.clip.failed", e.getClass().getSimpleName()));
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

    private static void actionBar(Text msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(msg, true);
        });
    }

    private static Clipboard clipboard() {
        return Toolkit.getDefaultToolkit().getSystemClipboard();
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
