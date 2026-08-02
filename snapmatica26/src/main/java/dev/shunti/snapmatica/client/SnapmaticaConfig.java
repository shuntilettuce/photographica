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
 * Persists client-side camera settings across sessions in {@code config/snapmatica.properties}.
 * Covers exposure mode (M/Av/Tv/P), focus mode (MF/AF/MOB), aperture, shutter, ISO, focal
 * length, lens, orientation, motion blur, and the sneak-to-viewfinder toggle.
 */
@Environment(EnvType.CLIENT)
public final class SnapmaticaConfig {
    private SnapmaticaConfig() {}

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("snapmatica.properties");

    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[Snapmatica] 設定の読み込みに失敗: " + e);
            return;
        }
        SnapmaticaClient.viewfinderSneakEnabled = getBool(p, "viewfinderSneakEnabled", SnapmaticaClient.viewfinderSneakEnabled);
        SnapmaticaClient.portraitOrientation    = getBool(p, "portraitOrientation",    SnapmaticaClient.portraitOrientation);
        SnapmaticaClient.motionBlur             = getBool(p, "motionBlur",             SnapmaticaClient.motionBlur);
        SnapmaticaClient.exposureMode           = getInt (p, "exposureMode",           SnapmaticaClient.exposureMode);
        SnapmaticaClient.focusMode              = getInt (p, "focusMode",              SnapmaticaClient.focusMode);
        SnapmaticaClient.shutterSpeedIdx        = getInt (p, "shutterSpeedIdx",        SnapmaticaClient.shutterSpeedIdx);
        SnapmaticaClient.iso                    = getInt (p, "iso",                    SnapmaticaClient.iso);
        SnapmaticaClient.focalLengthMm          = getInt (p, "focalLengthMm",          SnapmaticaClient.focalLengthMm);
        SnapmaticaClient.lensType               = getInt (p, "lensType",               SnapmaticaClient.lensType);
        SnapmaticaClient.aperture               = getFloat(p, "aperture",              SnapmaticaClient.aperture);
        SnapmaticaClient.focusDistance          = getFloat(p, "focusDistance",         SnapmaticaClient.focusDistance);
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("viewfinderSneakEnabled", Boolean.toString(SnapmaticaClient.viewfinderSneakEnabled));
        p.setProperty("portraitOrientation",    Boolean.toString(SnapmaticaClient.portraitOrientation));
        p.setProperty("motionBlur",             Boolean.toString(SnapmaticaClient.motionBlur));
        p.setProperty("exposureMode",           Integer.toString(SnapmaticaClient.exposureMode));
        p.setProperty("focusMode",              Integer.toString(SnapmaticaClient.focusMode));
        p.setProperty("shutterSpeedIdx",        Integer.toString(SnapmaticaClient.shutterSpeedIdx));
        p.setProperty("iso",                    Integer.toString(SnapmaticaClient.iso));
        p.setProperty("focalLengthMm",          Integer.toString(SnapmaticaClient.focalLengthMm));
        p.setProperty("lensType",               Integer.toString(SnapmaticaClient.lensType));
        p.setProperty("aperture",               Float.toString(SnapmaticaClient.aperture));
        p.setProperty("focusDistance",          Float.toString(SnapmaticaClient.focusDistance));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Snapmatica client settings");
            }
        } catch (IOException e) {
            System.err.println("[Snapmatica] 設定の保存に失敗: " + e);
        }
    }

    private static boolean getBool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    private static int getInt(Properties p, String k, int def) {
        try { String v = p.getProperty(k); return v == null ? def : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static float getFloat(Properties p, String k, float def) {
        try { String v = p.getProperty(k); return v == null ? def : Float.parseFloat(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }
}
