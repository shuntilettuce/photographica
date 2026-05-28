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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
    private static final Set<UUID> failed = new HashSet<>();

    public static @Nullable Identifier getOrLoad(UUID photoId) {
        if (failed.contains(photoId)) return null;
        Identifier cached = loaded.get(photoId);
        if (cached != null) return cached;

        File file = new File(MinecraftClient.getInstance().runDirectory,
                "photographica/photos/" + photoId + ".png");
        if (!file.exists()) {
            failed.add(photoId);
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
            return texId;
        } catch (IOException e) {
            Photographica.LOGGER.error("Failed to load photo texture {}", photoId, e);
            failed.add(photoId);
            return null;
        }
    }

    /** Call when leaving a world so stale textures from a previous session are discarded. */
    public static void clear() {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (Identifier id : loaded.values()) {
            mc.getTextureManager().destroyTexture(id);
        }
        loaded.clear();
        failed.clear();
    }
}
