package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists client-side settings that should survive leaving a world / restarting
 * the game. Stored as a plain {@code config/snapmatica.properties} file.
 *
 * Currently only the sneak-to-viewfinder toggle is persisted; the field list is
 * trivial to extend.
 */
@Environment(EnvType.CLIENT)
public final class SnapmaticaConfig {
    private SnapmaticaConfig() {}

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("snapmatica.properties");

    private static final String KEY_VIEWFINDER_SNEAK = "viewfinderSneakEnabled";

    /** Load saved settings into {@link SnapmaticaClient}. Called once at client init. */
    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[Snapmatica] 設定の読み込みに失敗: " + e);
            return;
        }
        SnapmaticaClient.viewfinderSneakEnabled = Boolean.parseBoolean(
                p.getProperty(KEY_VIEWFINDER_SNEAK,
                        Boolean.toString(SnapmaticaClient.viewfinderSneakEnabled)));
    }

    /** Write current settings to disk. Cheap enough to call on each change. */
    public static void save() {
        Properties p = new Properties();
        p.setProperty(KEY_VIEWFINDER_SNEAK,
                Boolean.toString(SnapmaticaClient.viewfinderSneakEnabled));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Snapmatica client settings");
            }
        } catch (IOException e) {
            System.err.println("[Snapmatica] 設定の保存に失敗: " + e);
        }
    }
}
