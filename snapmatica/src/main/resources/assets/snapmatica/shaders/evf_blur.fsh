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
    float rawD = texture(DepthSampler, texCoord).r;
    float depthM = linearDepth(rawD);

    float coc = computeCoc(depthM);

    // Deadband: keeps a band around the focus plane fully sharp. A larger value
    // widens the depth of field so close / wide-open shots still hold a usable
    // sharp subject (optical DoF alone is paper-thin there), and removes the
    // high-frequency shimmer of depth jitter at the focus plane.
    coc = max(coc - 1.5, 0.0);

    if (coc < 0.3) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // Is this pixel part of the foreground (closer than the focus plane)?
    // Foreground and background blur require different edge treatment (see below).
    bool isForeground = (FocusDist < 99999.0) && (depthM < FocusDist);

    // sigma = 0.85 × coc with a 1.7× sample radius: wide Gaussian tail for soft,
    // physically realistic transitions between in-focus and out-of-focus regions.
    float sigma = max(coc * 0.85, 0.1);
    int rad = min(int(ceil(coc * 1.7)), 36);

    vec4 col = vec4(0.0);
    float totalW = 0.0;
    for (int i = -rad; i <= rad; i++) {
        vec2 sampleCoord = texCoord + BlurDir * float(i) * PixelSize;

        float fi = float(i);
        float gaussW = exp(-fi * fi / (2.0 * sigma * sigma));

        float cocWeight = 1.0;
        if (coc > 2.0) {
            float sampleCoc = computeCoc(linearDepth(texture(DepthSampler, sampleCoord).r));
            if (isForeground) {
                // Foreground (near) blur: allow all contributions, including background
                // pixels within the kernel radius. This lets the bokeh "disc" extend
                // beyond the geometric silhouette of the near object — the near edges
                // blend into the background, producing the soft halo a real lens gives.
                cocWeight = 1.0;
            } else {
                // Background blur: down-weight sharper / closer samples so a sharp
                // in-focus subject doesn't bleed into the soft background. A sqrt
                // roll-off (instead of a linear ratio) keeps far more of the
                // partially-blurred neighbours, so the silhouette of background
                // bokeh dissolves into a soft gradient instead of a crisp edge.
                cocWeight = clamp(sqrt(sampleCoc / coc), 0.12, 1.0);
            }
        }

        col += texture(InSampler, sampleCoord) * gaussW * cocWeight;
        totalW += gaussW * cocWeight;
    }
    fragColor = col / totalW;
}
