package dev.shunti.snapmatica.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the gallery needs: what has been shot, and the GPU textures to show it with.
 *
 * <p>Photos are PNGs the mod wrote itself. Videos are mp4, which nothing in Minecraft can
 * decode, so each gets a poster frame extracted by ffmpeg once and cached beside it; playback
 * is handed to whatever the desktop uses for video.
 */
@Environment(EnvType.CLIENT)
public final class MediaLibrary {
    private MediaLibrary() {}

    /** One item in the roll. */
    public record Entry(File file, boolean video, long modified) {
        public String displayName() { return file.getName(); }
    }

    /**
     * Textures are uploaded to the GPU and stay there until evicted, so the cache is bounded
     * rather than growing with the size of the roll — a few hundred full-resolution photos
     * would otherwise be several gigabytes of VRAM. Insertion-ordered so eviction is oldest
     * first; the grid only ever needs the page it is showing plus whatever the viewer holds.
     */
    private static final int MAX_TEXTURES = 48;
    private static final Map<String, Identifier> loaded = new LinkedHashMap<>();
    private static final Set<String> failed = new HashSet<>();
    private static final Map<String, boolean[]> pending = new HashMap<>();

    /**
     * Aspect ratio per source file, learned when the image is decoded. The gallery needs it to
     * letterbox rather than stretch, and a portrait shot is not merely a rotated landscape one.
     * Kept outside the texture cache so an eviction does not cost a re-measure.
     */
    private static final Map<String, Float> aspects = new HashMap<>();

    /** Poster-frame extraction shells out to ffmpeg, so it must not run on the render thread. */
    private static final ExecutorService thumbExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "snapmatica-thumbs");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    public static File photoDir() {
        return new File(Minecraft.getInstance().gameDirectory, "snapmatica/photos");
    }

    public static File videoDir() {
        return new File(Minecraft.getInstance().gameDirectory, "snapmatica/videos");
    }

    /** Newest first, which is the order anyone actually wants to look at their shots in. */
    public static List<Entry> scan() {
        List<Entry> out = new ArrayList<>();
        collect(photoDir(), ".png", false, out);
        collect(videoDir(), ".mp4", true, out);
        out.sort(Comparator.comparingLong(Entry::modified).reversed());
        return out;
    }

    private static void collect(File dir, String ext, boolean video, List<Entry> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.stream(fs)
                .filter(f -> f.isFile() && f.getName().toLowerCase().endsWith(ext))
                .forEach(f -> out.add(new Entry(f, video, f.lastModified())));
    }

    /**
     * The texture for an entry, or null while it is not available yet.
     *
     * <p>For a video that means the poster frame, which is generated on first request and
     * appears a moment later — the grid simply draws a placeholder until then rather than
     * blocking the frame on an ffmpeg process.
     */
    public static Identifier texture(Entry e) {
        File src = e.video() ? posterFile(e.file()) : e.file();
        String key = src.getAbsolutePath();

        Identifier cached = loaded.get(key);
        if (cached != null) return cached;
        if (failed.contains(key)) return null;

        if (!src.exists()) {
            if (e.video()) requestPoster(e.file());
            return null;
        }
        return upload(key, src);
    }

    private static Identifier upload(String key, File src) {
        try (InputStream is = new FileInputStream(src)) {
            NativeImage image = NativeImage.read(is);
            aspects.put(key, (float) image.getWidth() / Math.max(1, image.getHeight()));
            // Identifier paths allow only [a-z0-9_./-]; a timestamped filename has none of the
            // rest, but lowercase it and strip anything else to be safe.
            String path = "gallery/" + key.toLowerCase().replaceAll("[^a-z0-9_/.-]", "_");
            if (path.length() > 200) path = "gallery/" + Integer.toHexString(key.hashCode());
            final Identifier id = Identifier.fromNamespaceAndPath("snapmatica", path);
            Minecraft.getInstance().getTextureManager()
                    .register(id, new DynamicTexture(id::toString, image));
            loaded.put(key, id);
            evictIfNeeded();
            return id;
        } catch (Exception ex) {
            System.err.println("[Snapmatica] Gallery: failed to load " + src + " — " + ex);
            failed.add(key);
            return null;
        }
    }

    private static void evictIfNeeded() {
        Minecraft mc = Minecraft.getInstance();
        while (loaded.size() > MAX_TEXTURES) {
            var it = loaded.entrySet().iterator();
            var oldest = it.next();
            it.remove();
            mc.getTextureManager().release(oldest.getValue());
        }
    }

    /**
     * Width over height for an entry, or 3:2 until the image has actually been decoded — the
     * grid draws a placeholder in that frame anyway, so the guess is never seen stretched.
     */
    public static float aspect(Entry e) {
        Float a = aspects.get((e.video() ? posterFile(e.file()) : e.file()).getAbsolutePath());
        return a != null ? a : 3f / 2f;
    }

    /** Poster frames live next to the video, so they survive restarts and cost one extraction. */
    public static File posterFile(File video) {
        String n = video.getName();
        int dot = n.lastIndexOf('.');
        return new File(video.getParentFile(), (dot > 0 ? n.substring(0, dot) : n) + "_thumb.png");
    }

    private static void requestPoster(File video) {
        String key = video.getAbsolutePath();
        if (pending.containsKey(key)) return;
        pending.put(key, new boolean[]{true});
        thumbExecutor.submit(() -> {
            try {
                File out = posterFile(video);
                // One frame, a second in — the very first frame of a clip is often a fade or a
                // half-finished chunk load.
                ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-ss", "1",
                        "-i", video.getAbsolutePath(), "-frames:v", "1",
                        "-vf", "scale=320:-1", out.getAbsolutePath());
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process p = pb.start();
                if (p.waitFor() != 0 && !out.exists()) {
                    // A clip shorter than the seek point yields nothing; retry from the start.
                    ProcessBuilder pb2 = new ProcessBuilder("ffmpeg", "-y",
                            "-i", video.getAbsolutePath(), "-frames:v", "1",
                            "-vf", "scale=320:-1", out.getAbsolutePath());
                    pb2.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    pb2.redirectError(ProcessBuilder.Redirect.DISCARD);
                    pb2.start().waitFor();
                }
            } catch (Exception ignored) {
                // No ffmpeg, or it failed: the grid keeps its placeholder. Not worth a message.
            } finally {
                pending.remove(key);
            }
        });
    }

    /**
     * Reveals a file in the desktop's file manager, selected rather than merely opening the
     * folder — Explorer and Finder both take a flag for it, and elsewhere opening the parent
     * directory is the closest equivalent.
     */
    public static void revealInFolder(File f) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd;
        if (os.contains("win")) {
            cmd = new String[]{"explorer.exe", "/select," + f.getAbsolutePath()};
        } else if (os.contains("mac")) {
            cmd = new String[]{"open", "-R", f.getAbsolutePath()};
        } else {
            // Freedesktop has no universal "select"; the containing folder is honest.
            cmd = new String[]{"xdg-open", f.getParentFile().getAbsolutePath()};
        }
        run(cmd, "reveal " + f);
    }

    /** Copies an entry to the clipboard: the image itself for a photo, the file for a video. */
    public static void copyToClipboard(Entry e) {
        if (e.video()) ClipboardUtil.copyFileAsync(e.file());
        else           ClipboardUtil.copyImageAsync(e.file());
    }

    /**
     * Hands a file to whatever the desktop opens it with — the system video player for a clip,
     * the image viewer for a still.
     *
     * <p>Deliberately not {@code java.awt.Desktop}: Minecraft runs with {@code
     * java.awt.headless=true}, so every call there throws {@link java.awt.HeadlessException}.
     * The shell handlers below are what the game itself uses and need no AWT at all.
     */
    public static void openExternally(File f) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String p = f.getAbsolutePath();
        String[] cmd;
        if (os.contains("win")) {
            // FileProtocolHandler takes the path as one argument, spaces and all.
            cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", p};
        } else if (os.contains("mac")) {
            cmd = new String[]{"open", p};
        } else {
            cmd = new String[]{"xdg-open", p};
        }
        run(cmd, "open " + f);
    }

    /** Fires a desktop command off the render thread; a failure is logged, never thrown. */
    private static void run(String[] cmd, String what) {
        new Thread(() -> {
            try {
                new ProcessBuilder(cmd).start();
            } catch (Exception e) {
                System.err.println("[Snapmatica] Could not " + what + " — " + e);
            }
        }, "snapmatica-shell").start();
    }

    /** Drops every uploaded texture. Called on world exit so a session cannot leak VRAM. */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        for (Identifier id : loaded.values()) mc.getTextureManager().release(id);
        loaded.clear();
        failed.clear();
    }
}
