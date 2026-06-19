package dev.hitom.photographica.client.render;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.PhotoData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;
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
    private static final Map<UUID, Boolean> portrait = new HashMap<>();
    private static final Set<UUID> failed = new HashSet<>();

    /** True when the photo's PNG is taller than it is wide (a portrait/2:3 shot). */
    public static boolean isPortrait(UUID photoId) {
        return Boolean.TRUE.equals(portrait.get(photoId));
    }

    public static @Nullable Identifier getOrLoad(UUID photoId) {
        if (failed.contains(photoId)) return null;
        Identifier cached = loaded.get(photoId);
        if (cached != null) return cached;

        File photoDir = new File(Minecraft.getInstance().gameDirectory, "photographica/photos");
        File file = PhotoData.findPhotoFile(photoDir, photoId);
        if (file == null) {
            failed.add(photoId);
            return null;
        }

        try (InputStream is = new FileInputStream(file)) {
            NativeImage image = NativeImage.read(is);
            portrait.put(photoId, image.getHeight() > image.getWidth());
            // Identifier path must be lowercase without hyphens.
            String path = "dynamic/photo_" + photoId.toString().replace("-", "");
            Identifier texId = Identifier.fromNamespaceAndPath(Photographica.MOD_ID, path);
            Minecraft.getInstance().getTextureManager()
                    .register(texId, new DynamicTexture(() -> path, image));
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
        Minecraft mc = Minecraft.getInstance();
        for (Identifier id : loaded.values()) {
            mc.getTextureManager().release(id);
        }
        loaded.clear();
        portrait.clear();
        failed.clear();
    }
}
