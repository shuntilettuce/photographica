package dev.hitom.photographica.client.render;

import dev.hitom.photographica.Photographica;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
import java.util.Map;
import java.util.UUID;

/**
 * Client-side cache mapping photo UUIDs to registered GPU texture identifiers.
 * Photos are loaded from <gameDir>/photographica/photos/<uuid>.png on first use
 * and registered with Minecraft's TextureManager for fast re-use.
 */
@Environment(EnvType.CLIENT)
public final class PhotoTextureCache {
    private PhotoTextureCache() {}

    private static final Map<UUID, Identifier> loaded = new HashMap<>();
    /** Photo id → timestamp of last failed load. Retried after {@link #RETRY_COOLDOWN_MS}. */
    private static final Map<UUID, Long> failedAt = new HashMap<>();

    /**
     * Failures are retried after this many milliseconds rather than cached forever.
     * Without this, a frame/print rendered once before its PNG is on disk (chunk sync
     * race, just-developed film, photo taken by another player) would stay blank for
     * the entire session even after the file appears.
     */
    private static final long RETRY_COOLDOWN_MS = 2000L;

    public static @Nullable Identifier getOrLoad(UUID photoId) {
        Identifier cached = loaded.get(photoId);
        if (cached != null) return cached;

        Long failTime = failedAt.get(photoId);
        if (failTime != null && System.currentTimeMillis() - failTime < RETRY_COOLDOWN_MS) {
            return null;
        }

        File photoDir = new File(MinecraftClient.getInstance().runDirectory, "photographica/photos");
        File file = findPhotoFile(photoDir, photoId);
        if (file == null) {
            failedAt.put(photoId, System.currentTimeMillis());
            return null;
        }

        try (InputStream is = new FileInputStream(file)) {
            NativeImage image = NativeImage.read(is);
            // Identifier path must be lowercase without hyphens.
            String path = "dynamic/photo_" + photoId.toString().replace("-", "");
            Identifier texId = Identifier.of(Photographica.MOD_ID, path);
            //? if >=1.21.11 {
            /*MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(texId, new NativeImageBackedTexture(() -> path, image));*/
            //?} else {
            MinecraftClient.getInstance().getTextureManager()
                    .registerTexture(texId, new NativeImageBackedTexture(image));
            //?}
            loaded.put(photoId, texId);
            failedAt.remove(photoId);
            return texId;
        } catch (IOException e) {
            Photographica.LOGGER.error("Failed to load photo texture {}", photoId, e);
            failedAt.put(photoId, System.currentTimeMillis());
            return null;
        }
    }

    public static @Nullable File findPhotoFile(File dir, UUID photoId) {
        if (!dir.isDirectory()) return null;
        // New format: <datetime>_<uuid_no_dashes>.png
        String suffix = "_" + photoId.toString().replace("-", "") + ".png";
        File[] matches = dir.listFiles((d, name) -> name.endsWith(suffix));
        if (matches != null && matches.length > 0) return matches[0];
        // Legacy format: <uuid>.png
        File legacy = new File(dir, photoId + ".png");
        return legacy.exists() ? legacy : null;
    }

    /** Call when leaving a world so stale textures from a previous session are discarded. */
    public static void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Identifier id : loaded.values()) {
            mc.getTextureManager().destroyTexture(id);
        }
        loaded.clear();
        failedAt.clear();
    }
}
