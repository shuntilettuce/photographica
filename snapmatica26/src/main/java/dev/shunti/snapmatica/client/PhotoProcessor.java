package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class PhotoProcessor {
    private PhotoProcessor() {}

    public static double exposureFactor() {
        int em = SnapmaticaClient.exposureMode;
        int shutterIdx = (em == 1 || em == 3) ? SnapmaticaClient.autoShutterIdx : SnapmaticaClient.shutterSpeedIdx;
        float aperture = (em == 2 || em == 3) ? SnapmaticaClient.autoAperture : SnapmaticaClient.aperture;
        int   iso      = SnapmaticaClient.iso;
        double shutter = SnapmaticaClient.SHUTTER_SECONDS[Math.max(0, Math.min(SnapmaticaClient.SHUTTER_SECONDS.length - 1, shutterIdx))];

        final double neutralAperture = 5.6;
        final double neutralShutter  = 1.0 / 60.0;
        final int    neutralIso      = 400;

        double evActual  = Math.log((aperture * aperture) / shutter) / Math.log(2);
        double evNeutral = Math.log((neutralAperture * neutralAperture) / neutralShutter) / Math.log(2);
        double isoOffset = Math.log(iso / (double) neutralIso) / Math.log(2);
        double evDiff    = (evNeutral - evActual) + isoOffset;

        return Math.max(0.1, Math.min(10.0, Math.pow(2.0, evDiff)));
    }

    public static float dofBlurRadius(float depthMeters, float focusMeters, float sensorHeightPx) {
        if (depthMeters <= 0f || focusMeters <= 0f) return 0f;

        float aperture = SnapmaticaClient.aperture;
        float focalMm  = SnapmaticaClient.focalLengthMm;

        float f  = focalMm / 1000f;
        float v0 = 1f / (1f / f - 1f / focusMeters);
        float vd = 1f / (1f / f - 1f / depthMeters);

        float deltaV    = Math.abs(vd - v0);
        float apertureDiam = f / aperture;
        float coc       = apertureDiam * deltaV / v0;

        float sensorHeightMm = 24f;
        float pxPerMm    = sensorHeightPx / sensorHeightMm;
        float radiusPx   = (coc * 1000f) * pxPerMm / 2f;

        return Math.max(0f, Math.min(50f, radiusPx));
    }
}
