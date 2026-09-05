package dev.hitom.photographica.client.render;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.network.RequestPhotoPayload;
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
 * Photos are loaded from <gameDir>/photographica/photos/<uuid>.png on first use
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

    private static File localFile(UUID photoId) {
        return new File(MinecraftClient.getInstance().runDirectory,
                "photographica/photos/" + photoId + ".jpg");
    }

    public static @Nullable Identifier getOrLoad(UUID photoId) {
        if (failed.contains(photoId)) return null;
        Identifier cached = loaded.get(photoId);
        if (cached != null) return cached;

        File file = localFile(photoId);
        if (!file.exists()) {
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
