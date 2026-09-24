package dev.shunti.photographica.client.render;

import dev.shunti.photographica.Photographica;
import dev.shunti.photographica.network.RequestPhotoPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side cache mapping photo UUIDs to registered GPU texture identifiers.
 * Photos are loaded from <gameDir>/photographica/photos/<datetime>_<uuid>.jpg on first use
 * and registered with Minecraft's TextureManager for fast re-use.
 *
 * <p>A UUID whose PNG isn't on disk yet — always true for a photo someone ELSE took, until
 * fetched at least once — triggers a {@link RequestPhotoPayload} to the server instead of
 * failing immediately; see {@link #onFetched} / {@link #onNotFound}, called from
 * {@code PhotographicaClient}'s network receivers once the server answers. The fetch result is
 * written to the same local path a photo taken on this client would have used, so once fetched
 * a photo behaves identically to a local one for the rest of the session.
 */
@Environment(EnvType.CLIENT)
public final class PhotoTextureCache {
    private PhotoTextureCache() {}

    private static final Map<UUID, Identifier> loaded = new HashMap<>();
    private static final Map<UUID, Boolean> portrait = new HashMap<>();
    private static final Map<UUID, int[]> size = new HashMap<>(); // {width, height}
    private static final Set<UUID> failed = new HashSet<>();
    private static final Set<UUID> fetching = new HashSet<>();

    /** True when the photo's PNG is taller than it is wide (a portrait/2:3 shot). */
    public static boolean isPortrait(UUID photoId) {
        return Boolean.TRUE.equals(portrait.get(photoId));
    }

    /** Native pixel width/height of the loaded texture, or null if not loaded (yet). */
    public static int @Nullable [] getSize(UUID photoId) {
        return size.get(photoId);
    }

    /** True while a fetch from the server is in flight — screens can show "読み込み中" instead
     *  of the harder "not found" state while this is true. */
    public static boolean isFetching(UUID photoId) {
        return fetching.contains(photoId);
    }

    private static File photosDir() {
        return new File(MinecraftClient.getInstance().runDirectory, "photographica/photos");
    }

    /** Where a freshly downloaded photo gets written — always the current {@code .jpg}
     *  filename. Looking one up that might already be on disk goes through
     *  {@link #findPhotoFile}, which also accepts the older {@code .png} files pre-JPEG
     *  versions of this mod wrote. */
    private static File localFile(UUID photoId) {
        return new File(photosDir(), photoId + ".jpg");
    }

    public static @Nullable Identifier getOrLoad(UUID photoId) {
        if (failed.contains(photoId)) return null;
        Identifier cached = loaded.get(photoId);
        if (cached != null) return cached;

        File file = findPhotoFile(photosDir(), photoId);
        if (file == null) {
            if (fetching.add(photoId)) {
                ClientPlayNetworking.send(new RequestPhotoPayload(photoId));
            }
            return null;
        }

        try (InputStream is = new FileInputStream(file)) {
            NativeImage image = NativeImage.read(is);
            portrait.put(photoId, image.getHeight() > image.getWidth());
            size.put(photoId, new int[]{image.getWidth(), image.getHeight()});
            // Identifier path must be lowercase without hyphens.
            String path = "dynamic/photo_" + photoId.toString().replace("-", "");
            Identifier texId = Identifier.of(Photographica.MOD_ID, path);
            final Identifier finalTexId = texId;
            //? if >=1.21.11 {
            /*MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(finalTexId, new NativeImageBackedTexture(() -> finalTexId.toString(), image));*/
            //?} else {
            NativeImageBackedTexture tex = new NativeImageBackedTexture(image);
            tex.setFilter(true, false);
            MinecraftClient.getInstance().getTextureManager().registerTexture(texId, tex);
            //?}
            loaded.put(photoId, texId);
            return texId;
        } catch (IOException e) {
            Photographica.LOGGER.error("Failed to load photo texture {}", photoId, e);
            failed.add(photoId);
            return null;
        }
    }

    /**
     * Called from {@code PhotographicaClient}'s network receiver once a requested photo's
     * bytes have fully arrived and been written to {@link #localFile}. Clears the fetching
     * flag so the next {@link #getOrLoad} call takes the normal local-file path.
     */
    public static void onFetched(UUID photoId) {
        fetching.remove(photoId);
        failed.remove(photoId);
    }

    /** Called when the server answers a request with "no copy of this either" — stop waiting. */
    public static void onNotFound(UUID photoId) {
        fetching.remove(photoId);
        failed.add(photoId);
    }

    /** Extensions a photo may carry. Photographica always writes {@code <uuid>.jpg} now (see
     *  {@code PhotoCapture#savePhoto}), but {@code .png} files written by pre-JPEG versions of
     *  this mod are still sitting on players' disks and must keep loading. */
    private static final String[] PHOTO_EXTENSIONS = { ".jpg", ".jpeg", ".png" };

    /**
     * Resolves a photo's UUID to whatever file for it actually exists on disk, or null.
     *
     * <p>Checks the exact {@code <uuid>.<ext>} path first — the only form photographica itself
     * has ever written, so this is a plain file-exists check, not a directory scan, for every
     * lookup that matters. The {@code <datetime>_<uuid>.<ext>} form below it is a naming scheme
     * from snapmatica (ported here for shared-code reasons); photographica has never written
     * it, so that branch is a fallback that in practice never fires, not the common case.
     */
    public static @Nullable File findPhotoFile(File dir, UUID photoId) {
        if (!dir.isDirectory()) return null;
        for (String ext : PHOTO_EXTENSIONS) {
            File exact = new File(dir, photoId + ext);
            if (exact.exists()) return exact;
        }
        String bare = photoId.toString().replace("-", "");
        for (String ext : PHOTO_EXTENSIONS) {
            String suffix = "_" + bare + ext;
            File[] matches = dir.listFiles((d, name) -> name.endsWith(suffix));
            if (matches != null && matches.length > 0) return matches[0];
        }
        return null;
    }

    /** Call when leaving a world so stale textures from a previous session are discarded. */
    public static void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Identifier id : loaded.values()) {
            mc.getTextureManager().destroyTexture(id);
        }
        loaded.clear();
        portrait.clear();
        size.clear();
        fetching.clear();
        failed.clear();
    }
}
