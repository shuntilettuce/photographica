#version 150

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // non-linear depth [0,1] from scene framebuffer
uniform vec2 BlurDir;            // (1,0) for H pass, (0,1) for V pass
uniform vec2 PixelSize;          // (1/fbW, 1/fbH)
uniform float FocusDist;         // focus distance in blocks (metres)
uniform float MaxBlurPx;         // max blur radius in framebuffer pixels (perf clamp)
uniform float Near;              // near clip plane in blocks
uniform float Far;               // far clip plane in blocks
uniform float FocalLenMm;        // lens focal length in mm
uniform float Aperture;          // f-number (N)
uniform float PxPerMm;           // framebuffer pixels per mm of sensor height
uniform float DofScale;          // mm of subject distance per Minecraft block

in vec2 texCoord;
out vec4 fragColor;

float linearDepth(float d) {
    float ndc = 2.0 * d - 1.0;
    return 2.0 * Near * Far / (Far + Near - ndc * (Far - Near));
}

// Physically-based thin-lens circle of confusion, projected onto the sensor and
// converted to framebuffer pixels. Unlike a normalized model, this keeps deep
// depth-of-field for wide/normal lenses (distant terrain stays sharp) and only
// produces strong bokeh for long lenses / wide apertures / close focus.
//   coc_mm = f^2 / (N * (S1 - f)) * |S2 - S1| / S2
float computeCoc(float depthM) {
    depthM = max(depthM, 0.05);
    float fmm = FocalLenMm;
    float cocMM;
    if (FocusDist >= 99999.0) {
        // focus at infinity (sentinel): coc_mm = f^2 / (N * S2_mm)
        cocMM = (fmm * fmm) / (Aperture * depthM * DofScale);
    } else {
        float s1mm = FocusDist * DofScale;
        float denom = Aperture * max(s1mm - fmm, 1.0);
        cocMM = (fmm * fmm) * abs(depthM - FocusDist) / (depthM * denom);
    }
    return clamp(cocMM * PxPerMm, 0.0, MaxBlurPx);
}

void main() {
    float rawD   = texture(DepthSampler, texCoord).r;
    float depthM = linearDepth(rawD);
    float coc    = max(computeCoc(depthM) - 1.5, 0.0);

    bool isForeground = (FocusDist < 99999.0) && (depthM < FocusDist);

    // Foreground-bleed prescan (scatter-as-gather):
    // A real lens spreads a foreground bokeh disc over background pixels at the
    // object edge.  We detect nearby foreground pixels whose disc (radius = CoC)
    // covers this background pixel, then expand the gather radius accordingly so
    // those foreground pixels can contribute — giving the edge a soft, blurry halo
    // instead of the hard outline that gather-only DoF would otherwise produce.
    float fgBleedCoc = 0.0;
    if (!isForeground && FocusDist < 99999.0) {
        int psRad = min(int(ceil(MaxBlurPx)), 36);
        for (int i = -psRad; i <= psRad; i += 2) {
            vec2  sc = texCoord + BlurDir * float(i) * PixelSize;
            float sd = linearDepth(texture(DepthSampler, sc).r);
            if (sd < FocusDist) {
                float fc = max(computeCoc(sd) - 1.5, 0.0);
                // Only count if the foreground bokeh disc actually reaches this pixel.
                if (fc >= abs(float(i))) {
                    fgBleedCoc = max(fgBleedCoc, fc);
                }
            }
        }
    }

    float effectiveCoc = max(coc, fgBleedCoc);
    if (effectiveCoc < 0.3) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // Deadband: keeps a band around the focus plane fully sharp. A larger value
    // widens the depth of field so close / wide-open shots still hold a usable
    // sharp subject (optical DoF alone is paper-thin there), and removes the
    // high-frequency shimmer of depth jitter at the focus plane.
    float sigma = max(coc * 0.85, 0.1);
    int   rad   = min(int(ceil(effectiveCoc * 1.7)), 36);

    vec4  col    = vec4(0.0);
    float totalW = 0.0;

    for (int i = -rad; i <= rad; i++) {
        vec2  sc   = texCoord + BlurDir * float(i) * PixelSize;
        float fi   = float(i);

        float sDepthM = linearDepth(texture(DepthSampler, sc).r);
        bool  sFg     = (FocusDist < 99999.0) && (sDepthM < FocusDist);
        float sCoc    = max(computeCoc(sDepthM) - 1.5, 0.0);

        float gaussW;
        float cocWeight;

        if (isForeground) {
            // Foreground pixel: accept all contributions — bokeh disc extends into
            // background, producing the soft halo a real lens gives at near edges.
            gaussW    = exp(-fi * fi / (2.0 * sigma * sigma));
            cocWeight = 1.0;
        } else if (sFg && sCoc > coc) {
            // Foreground sample bleeding into background edge (scatter-as-gather):
            // use the foreground sample's own CoC as the kernel width so its bokeh
            // disc smears naturally over neighbouring background pixels.
            float fgSigma = max(sCoc * 0.85, 0.1);
            gaussW    = exp(-fi * fi / (2.0 * fgSigma * fgSigma));
            cocWeight = 1.0;
        } else {
            // Background pixel sampling background: suppress sharp/closer samples so
            // a sharp in-focus subject doesn't bleed into soft background.
            gaussW    = exp(-fi * fi / (2.0 * sigma * sigma));
            cocWeight = (coc > 2.0)
                        ? clamp(sqrt(sCoc / max(coc, 0.01)), 0.12, 1.0)
                        : 1.0;
        }

        col    += texture(InSampler, sc) * gaussW * cocWeight;
        totalW += gaussW * cocWeight;
    }

    fragColor = col / totalW;
}
