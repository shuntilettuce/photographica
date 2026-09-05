package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
 * <p>Photos are PNG, JPG or DNG, whichever {@link SnapmaticaClient#photoFormat} was set to
 * when the shutter was pressed. All three appear in the roll, but each needs its own decoder —
 * see {@link #decode}. Videos are mp4, which nothing in Minecraft can
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

    /**
     * EXIF read back per file, so the viewer can show what a shot was taken at without
     * re-reading (and re-parsing) the file on every frame it is on screen. A miss is cached
     * too, as an empty Optional — a video, a PNG from before this mod wrote metadata, or
     * anything else with nothing to show should be asked about once, not forever.
     */
    private static final Map<String, java.util.Optional<PhotoExif.Info>> exifCache = new HashMap<>();

    /** What the shot was taken at, or null if the file carries no readable metadata. */
    public static PhotoExif.Info exif(Entry e) {
        if (e.video()) return null;
        return exifCache.computeIfAbsent(e.file().getAbsolutePath(),
                k -> java.util.Optional.ofNullable(PhotoExif.read(e.file()))).orElse(null);
    }

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
        // Every format the shutter can produce. None of the three shares a decoder: see
        // #decode — NativeImage reads PNG and nothing else, JPEG goes through ImageIO, and a
        // DNG is read back by the code that wrote it.
        collect(photoDir(), new String[]{".png", ".jpg", ".jpeg", ".dng"}, false, out);
        collect(videoDir(), new String[]{".mp4"}, true, out);
        out.sort(Comparator.comparingLong(Entry::modified).reversed());
        return out;
    }

    private static void collect(File dir, String[] exts, boolean video, List<Entry> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.stream(fs)
                .filter(f -> f.isFile() && matchesAny(f.getName().toLowerCase(), exts))
                .forEach(f -> out.add(new Entry(f, video, f.lastModified())));
    }

    private static boolean matchesAny(String name, String[] exts) {
        for (String ext : exts) if (name.endsWith(ext)) return true;
        return false;
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

    /**
     * Decodes a saved shot, whichever of the three formats it is.
     *
     * <p>{@code NativeImage.read} is PNG-only — it validates the PNG signature and throws
     * "Bad PNG Signature" on anything else, which is what made every JPG in the roll show as
     * unreadable. Only the PNG path can use it.
     *
     * <p>JPEG goes through ImageIO instead. That is safe here despite Minecraft running with
     * {@code java.awt.headless=true}: ImageIO's codecs are pure decoders with no display
     * dependency, which is the same distinction {@link ClipboardUtil} already relies on, and
     * separate from AWT's GUI classes, which genuinely do throw when headless.
     *
     * <p>DNG is read by {@link DngReader} — this mod wrote it, so it can read it back.
     */
    private static NativeImage decode(File src) throws IOException {
        String n = src.getName().toLowerCase();
        if (n.endsWith(".dng")) {
            try {
                return DngReader.read(src);
            } catch (Exception e) {
                throw new IOException("DNG decode failed: " + e, e);
            }
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            java.awt.image.BufferedImage buf;
            try (InputStream is = new FileInputStream(src)) {
                buf = javax.imageio.ImageIO.read(is);
            }
            if (buf == null) throw new IOException("ImageIO could not decode " + src.getName());
            int w = buf.getWidth(), h = buf.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = buf.getRGB(x, y);
                    int r = (rgb >>> 16) & 0xFF, g = (rgb >>> 8) & 0xFF, b = rgb & 0xFF;
                    Pixels.setAbgr(img, x, y, 0xFF000000 | (b << 16) | (g << 8) | r);
                }
            }
            return img;
        }
        try (InputStream is = new FileInputStream(src)) {
            return NativeImage.read(is);
        }
    }

    private static Identifier upload(String key, File src) {
        try {
            NativeImage image = decode(src);
            if (image == null) throw new IOException("no decoder for " + src.getName());
            aspects.put(key, (float) image.getWidth() / Math.max(1, image.getHeight()));
            // Identifier paths allow only [a-z0-9_./-]; a timestamped filename has none of the
            // rest, but lowercase it and strip anything else to be safe.
            String path = "gallery/" + key.toLowerCase().replaceAll("[^a-z0-9_/.-]", "_");
            if (path.length() > 200) path = "gallery/" + Integer.toHexString(key.hashCode());
            Identifier id = Identifier.fromNamespaceAndPath("snapmatica", path);
            //? if >=1.21.10 {
            final Identifier fid = id;
            Minecraft.getInstance().getTextureManager()
                    .register(fid, new DynamicTexture(() -> fid.toString(), image));
            //?} else {
            /*Minecraft.getInstance().getTextureManager()
                    .register(id, new DynamicTexture(image));
            *///?}
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

    /**
     * Deletes a shot from disk — the poster frame too, for a video — and drops any cached
     * texture for it so the grid never redraws a destroyed handle.
     */
    public static void deleteEntry(Entry e) {
        File src = e.video() ? posterFile(e.file()) : e.file();
        String key = src.getAbsolutePath();
        Identifier tex = loaded.remove(key);
        if (tex != null) Minecraft.getInstance().getTextureManager().release(tex);
        failed.remove(key);
        aspects.remove(key);
        exifCache.remove(e.file().getAbsolutePath());

        if (e.video()) {
            File poster = posterFile(e.file());
            if (poster.exists() && !poster.delete()) {
                System.err.println("[Snapmatica] Could not delete poster " + poster);
            }
        }
        if (!e.file().delete()) {
            System.err.println("[Snapmatica] Could not delete " + e.file());
        }
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
