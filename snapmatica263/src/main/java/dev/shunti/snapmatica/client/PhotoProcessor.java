package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Post‑processing applied to the raw screenshot before saving.
 * Computes exposure compensation and depth‑of‑field blur.
 */
@Environment(EnvType.CLIENT)
public final class PhotoProcessor {

    private PhotoProcessor() {}

    /** Returns the exposure‑compensation multiplier (1.0 = neutral). */
    public static double exposureFactor() {
        int em = SnapmaticaClient.exposureMode;
        // In auto modes, use the exact continuous target (autoShutterSecondsIdeal /
        // autoApertureIdeal) rather than the value rounded to the nearest marked stop for the
        // readout (autoShutterIdx / autoAperture). An auto axis is defined to land exactly on
        // neutral exposure; reading it back through its own rounded display reintroduced up to
        // half a stop of quantisation error as if it were real exposure error, and because this
        // mod's aperture moves continuously with zoom, an axis sitting near a stop boundary —
        // easy to be, at almost any zoom position — could cross it on an imperceptible change
        // and swing the photo a full stop between one frame and the next. See the field docs
        // on SnapmaticaClient for the fuller account.
        double shutter = (em == 1 || em == 3)
                ? SnapmaticaClient.autoShutterSecondsIdeal
                : SnapmaticaClient.SHUTTER_SECONDS[Math.max(0,
                        Math.min(SnapmaticaClient.SHUTTER_SECONDS.length - 1, SnapmaticaClient.shutterSpeedIdx))];
        double aperture = (em == 2 || em == 3)
                ? SnapmaticaClient.autoApertureIdeal
                : SnapmaticaClient.aperture;
        // The Auto-ISO assist's target, not the manual dial directly — equals it exactly
        // whenever that assist isn't engaged (see SnapmaticaClient.autoIsoIdeal's doc), so this
        // is a no-op everywhere except the dark-scene case it exists for.
        double iso     = SnapmaticaClient.autoIsoIdeal;

        // Neutral: f/5.6, 1/60 s, ISO 400 → factor = 1.0
        final double neutralAperture = 5.6;
        final double neutralShutter  = 1.0 / 60.0;
        final int    neutralIso      = 400;

        double evActual  = Math.log((aperture * aperture) / shutter) / Math.log(2);
        double evNeutral = Math.log((neutralAperture * neutralAperture) / neutralShutter) / Math.log(2);
        double isoOffset = Math.log(iso / (double) neutralIso) / Math.log(2);
        // The ND filter removes light before any of the above ever reaches the sensor, so it
        // is simply that many stops off whatever the dial would otherwise have delivered. In an
        // auto mode updateAutoValues has already pushed the shutter/aperture by the same
        // amount, so the two cancel exactly and the photograph comes out at the same brightness
        // with a slower shutter — which is the whole point of fitting one.
        double evDiff    = (evNeutral - evActual) + isoOffset - SnapmaticaClient.ndStops;

        // The ceiling used to be a flat 10x (±3.32 stops) regardless of anything — a sane
        // guard against a wildly mis-dialled manual setting, back when evDiff came only from
        // the dial. Dynamic range metering routinely asks for more than that on its own now
        // (see SnapmaticaClient.updateMetering), and once evDiff crossed the old ceiling, every
        // scene past that point saturated to the exact same 10x regardless of how much darker
        // one metered than another — a genuinely dark cave and a merely dim room came out
        // identically over-brightened instead of by degree. Scales with dynamicRangeStops so
        // widening that setting actually widens the range this can use, rather than being
        // capped by a number the setting knows nothing about; unchanged (still the original
        // 10x) with dynamic range simulation off, where evDiff is dial-only again.
        double ceiling = SnapmaticaClient.dynamicRangeSim
                ? Math.pow(2.0, SnapmaticaClient.dynamicRangeStops * 0.5)
                : 10.0;
        // The floor drops by the ND filter's own strength, and only by that. The guard exists
        // to catch a wildly mis-dialled setting, and an ND filter is the opposite of one: a
        // deliberate, known number of stops. Without this a 10-stop filter left in Manual would
        // saturate against a floor three stops down and quietly stop getting darker, which is
        // exactly the clamp-saturation bug metering already hit once from the other end.
        double floor = 1.0 / (ceiling * Math.pow(2.0, SnapmaticaClient.ndStops));
        return Math.max(floor, Math.min(ceiling, Math.pow(2.0, evDiff)));
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

        float sensorHeightMm = SnapmaticaClient.sensorHeightMm();
        float pxPerMm = sensorHeightPx / sensorHeightMm;
        float radiusPx = (coc * 1000f) * pxPerMm / 2f;

        return Math.max(0f, Math.min(50f, radiusPx));
    }
}
