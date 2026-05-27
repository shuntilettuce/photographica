package dev.shunti.snapmatica.client;

import net.minecraft.client.MinecraftClient;

/**
 * Post‑processing applied to the raw screenshot before saving.
 * Computes exposure compensation and depth‑of‑field blur.
 */
public final class PhotoProcessor {

    private PhotoProcessor() {}

    /** Returns the exposure‑compensation multiplier (1.0 = neutral). */
    public static double exposureFactor() {
        float aperture   = SnapmaticaClient.aperture;
        int iso          = SnapmaticaClient.iso;
        double shutter   = SnapmaticaClient.SHUTTER_SECONDS[SnapmaticaClient.shutterSpeedIdx];

        // “Sunny 16” reference: f/16, ISO 100, 1/100 s → EV 15
        double evActual   = Math.log((aperture * aperture) / shutter) / Math.log(2);
        double evRef      = Math.log((16.0 * 16.0) / (1.0 / 100.0)) / Math.log(2);
        double isoOffset  = Math.log(iso / 100.0) / Math.log(2);
        double evDiff     = (evRef - evActual) + isoOffset;

        return Math.max(0.1, Math.min(10.0, Math.pow(2.0, evDiff)));
    }

    /**
     * Approximate blur radius (px) of a subject at {@code depthMeters}
     * when the camera is focused at {@code focusMeters}.
     */
    public static float dofBlurRadius(float depthMeters, float focusMeters,
                                      float sensorHeightPx) {
        if (depthMeters <= 0f || focusMeters <= 0f) return 0f;

        float aperture = SnapmaticaClient.aperture;
        float focalMm  = SnapmaticaClient.focalLengthMm;

        // thin‑lens formula – all distances in metres
        float f  = focalMm / 1000f;                     // focal length
        float v0 = 1f / (1f / f - 1f / focusMeters);   // image distance for focus plane
        float vd = 1f / (1f / f - 1f / depthMeters);   // image distance for depth point

        float deltaV = Math.abs(vd - v0);
        float apertureDiam = f / aperture;
        float coc = apertureDiam * deltaV / v0;          // circle of confusion diameter (m)

        float sensorHeightMm = 24f;
        float pxPerMm = sensorHeightPx / sensorHeightMm;
        float radiusPx = (coc * 1000f) * pxPerMm / 2f;

        return Math.max(0f, Math.min(50f, radiusPx));
    }
}
