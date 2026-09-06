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
 * Persists client-side camera settings that should survive leaving a world / restarting
 * the game. Stored as a plain {@code config/snapmatica.properties} file.
 *
 * Covers the full camera state — exposure mode (M/Av/Tv/P), focus mode (MF/AF/MOB),
 * aperture, shutter, ISO, focal length, lens, orientation, motion blur — plus the
 * sneak-to-viewfinder toggle.
 */
@Environment(EnvType.CLIENT)
public final class SnapmaticaConfig {
    private SnapmaticaConfig() {}

    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("snapmatica.properties");

    /** Load saved settings into {@link SnapmaticaClient}. Called once at client init. */
    public static void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("[Snapmatica] Failed to load settings: " + e);
            return;
        }
        SnapmaticaClient.viewfinderSneakEnabled = getBool(p, "viewfinderSneakEnabled", SnapmaticaClient.viewfinderSneakEnabled);
        SnapmaticaClient.portraitOrientation    = getBool(p, "portraitOrientation",    SnapmaticaClient.portraitOrientation);
        SnapmaticaClient.motionBlur             = getBool(p, "motionBlur",             SnapmaticaClient.motionBlur);
        SnapmaticaClient.focusPeaking           = getBool(p, "focusPeaking",           SnapmaticaClient.focusPeaking);
        SnapmaticaClient.dynamicRangeSim        = getBool(p, "dynamicRangeSim",        SnapmaticaClient.dynamicRangeSim);
        SnapmaticaClient.focusAreaWide          = getBool(p, "focusAreaWide",          SnapmaticaClient.focusAreaWide);
        SnapmaticaClient.dynamicRangeStops      = getFloat(p, "dynamicRangeStops",     SnapmaticaClient.dynamicRangeStops);
        SnapmaticaClient.photoFormat            = getInt (p, "photoFormat",           SnapmaticaClient.photoFormat);
        SnapmaticaClient.chromaticAberration    = getBool(p, "chromaticAberration",   SnapmaticaClient.chromaticAberration);
        SnapmaticaClient.focusBreathing         = getBool(p, "focusBreathing",        SnapmaticaClient.focusBreathing);
        SnapmaticaClient.wbKelvin               = getInt (p, "wbKelvin",              SnapmaticaClient.wbKelvin);
        SnapmaticaClient.ndStops                = getInt (p, "ndStops",               SnapmaticaClient.ndStops);
        SnapmaticaClient.apertureIntegration    = getBool(p, "apertureIntegration",   SnapmaticaClient.apertureIntegration);
        SnapmaticaClient.apertureSamples        = getInt(p,  "apertureSamples",       SnapmaticaClient.apertureSamples);
        SnapmaticaClient.galleryCols = getInt(p, "galleryCols", SnapmaticaClient.galleryCols);
        SnapmaticaClient.afSpeed                = getInt(p,  "afSpeed",   SnapmaticaClient.afSpeed);
        SnapmaticaClient.afPointX               = getFloat(p, "afPointX", SnapmaticaClient.afPointX);
        SnapmaticaClient.afPointY               = getFloat(p, "afPointY", SnapmaticaClient.afPointY);
        SnapmaticaClient.apertureDebugSamples   = getBool(p, "apertureDebugSamples",  SnapmaticaClient.apertureDebugSamples);
        SnapmaticaClient.ambientDof             = getBool(p, "ambientDof",            SnapmaticaClient.ambientDof);
        SnapmaticaClient.ambientAperture        = getFloat(p, "ambientAperture",      SnapmaticaClient.ambientAperture);
        SnapmaticaClient.ambientDofScaleMm      = getFloat(p, "ambientDofScaleMm",    SnapmaticaClient.ambientDofScaleMm);
        SnapmaticaClient.ambientQuality         = getInt (p, "ambientQuality",        SnapmaticaClient.ambientQuality);
        SnapmaticaClient.sensorCropFactor       = getFloat(p, "sensorCropFactor",     SnapmaticaClient.sensorCropFactor);
        SnapmaticaClient.droneMode               = getBool(p, "droneMode",              SnapmaticaClient.droneMode);
        SnapmaticaClient.freecamHidePlayer       = getBool(p, "freecamHidePlayer",      SnapmaticaClient.freecamHidePlayer);
        SnapmaticaClient.exposureMode           = getInt (p, "exposureMode",           SnapmaticaClient.exposureMode);
        SnapmaticaClient.focusMode              = getInt (p, "focusMode",              SnapmaticaClient.focusMode);
        SnapmaticaClient.shutterSpeedIdx        = getInt (p, "shutterSpeedIdx",        SnapmaticaClient.shutterSpeedIdx);
        SnapmaticaClient.iso                    = getInt (p, "iso",                    SnapmaticaClient.iso);
        SnapmaticaClient.focalLengthMm          = getInt (p, "focalLengthMm",          SnapmaticaClient.focalLengthMm);
        SnapmaticaClient.lensType               = getInt (p, "lensType",               SnapmaticaClient.lensType);
        SnapmaticaClient.aperture               = getFloat(p, "aperture",              SnapmaticaClient.aperture);
        // The blade opening is the physical state; the f-number is only its ratio to the
        // focal length. Restore it explicitly, or reloading would leave the two inconsistent
        // and the first zoom would jump the aperture to whatever the default diameter implied.
        SnapmaticaClient.apertureDiameterMm     = getFloat(p, "apertureDiameterMm",    SnapmaticaClient.apertureDiameterMm);
        SnapmaticaClient.dofScaleMm             = getFloat(p, "dofScaleMm",            SnapmaticaClient.dofScaleMm);
        SnapmaticaClient.focusDistance          = getFloat(p, "focusDistance",         SnapmaticaClient.focusDistance);
        // The ring starts wherever the lens was left, so nothing racks on world join.
        SnapmaticaClient.focusTarget            = SnapmaticaClient.focusDistance;
        VideoRecorder.setFps(getInt(p, "videoFps", VideoRecorder.getCurrentFps()));
        VideoRecorder.setWidth(getInt(p, "videoWidth", VideoRecorder.getCurrentWidth()));
    }

    /** Write current settings to disk. Cheap enough to call on each change. */
    public static void save() {
        Properties p = new Properties();
        p.setProperty("viewfinderSneakEnabled", Boolean.toString(SnapmaticaClient.viewfinderSneakEnabled));
        p.setProperty("portraitOrientation",    Boolean.toString(SnapmaticaClient.portraitOrientation));
        p.setProperty("motionBlur",             Boolean.toString(SnapmaticaClient.motionBlur));
        p.setProperty("focusPeaking",           Boolean.toString(SnapmaticaClient.focusPeaking));
        p.setProperty("dynamicRangeSim",        Boolean.toString(SnapmaticaClient.dynamicRangeSim));
        p.setProperty("focusAreaWide",          Boolean.toString(SnapmaticaClient.focusAreaWide));
        p.setProperty("dynamicRangeStops",      Float.toString(SnapmaticaClient.dynamicRangeStops));
        p.setProperty("photoFormat",            Integer.toString(SnapmaticaClient.photoFormat));
        p.setProperty("chromaticAberration",    Boolean.toString(SnapmaticaClient.chromaticAberration));
        p.setProperty("focusBreathing",         Boolean.toString(SnapmaticaClient.focusBreathing));
        p.setProperty("wbKelvin",               Integer.toString(SnapmaticaClient.wbKelvin));
        p.setProperty("ndStops",                Integer.toString(SnapmaticaClient.ndStops));
        p.setProperty("apertureIntegration",    Boolean.toString(SnapmaticaClient.apertureIntegration));
        p.setProperty("apertureSamples",        Integer.toString(SnapmaticaClient.apertureSamples));
        p.setProperty("galleryCols", Integer.toString(SnapmaticaClient.galleryCols));
        p.setProperty("afSpeed",                Integer.toString(SnapmaticaClient.afSpeed));
        p.setProperty("afPointX",               Float.toString(SnapmaticaClient.afPointX));
        p.setProperty("afPointY",               Float.toString(SnapmaticaClient.afPointY));
        p.setProperty("apertureDebugSamples",   Boolean.toString(SnapmaticaClient.apertureDebugSamples));
        p.setProperty("ambientDof",             Boolean.toString(SnapmaticaClient.ambientDof));
        p.setProperty("ambientAperture",        Float.toString(SnapmaticaClient.ambientAperture));
        p.setProperty("ambientDofScaleMm",      Float.toString(SnapmaticaClient.ambientDofScaleMm));
        p.setProperty("ambientQuality",         Integer.toString(SnapmaticaClient.ambientQuality));
        p.setProperty("sensorCropFactor",       Float.toString(SnapmaticaClient.sensorCropFactor));
        p.setProperty("droneMode",              Boolean.toString(SnapmaticaClient.droneMode));
        p.setProperty("freecamHidePlayer",      Boolean.toString(SnapmaticaClient.freecamHidePlayer));
        p.setProperty("exposureMode",           Integer.toString(SnapmaticaClient.exposureMode));
        p.setProperty("focusMode",              Integer.toString(SnapmaticaClient.focusMode));
        p.setProperty("shutterSpeedIdx",        Integer.toString(SnapmaticaClient.shutterSpeedIdx));
        p.setProperty("iso",                    Integer.toString(SnapmaticaClient.iso));
        p.setProperty("focalLengthMm",          Integer.toString(SnapmaticaClient.focalLengthMm));
        p.setProperty("lensType",               Integer.toString(SnapmaticaClient.lensType));
        p.setProperty("aperture",               Float.toString(SnapmaticaClient.aperture));
        p.setProperty("apertureDiameterMm",     Float.toString(SnapmaticaClient.apertureDiameterMm));
        p.setProperty("dofScaleMm",             Float.toString(SnapmaticaClient.dofScaleMm));
        p.setProperty("focusDistance",          Float.toString(SnapmaticaClient.focusDistance));
        p.setProperty("videoFps",               Integer.toString(VideoRecorder.getCurrentFps()));
        p.setProperty("videoWidth",             Integer.toString(VideoRecorder.getCurrentWidth()));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Snapmatica client settings");
            }
        } catch (IOException e) {
            System.err.println("[Snapmatica] Failed to save settings: " + e);
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
