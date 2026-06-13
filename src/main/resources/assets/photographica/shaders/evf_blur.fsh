#version 150

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // non-linear depth [0,1] from scene framebuffer
uniform vec2 BlurDir;            // (1,0) for H pass, (0,1) for V pass
uniform vec2 PixelSize;          // (1/fbW, 1/fbH)
uniform float FocusDist;         // focus distance in blocks
uniform float MaxBlurPx;         // max blur radius in framebuffer pixels
uniform float Near;              // near clip plane in blocks
uniform float Far;               // far clip plane in blocks
uniform float FocalLenMm;        // lens focal length in mm
uniform float Aperture;          // f-number (N)
uniform float PxPerMm;           // framebuffer pixels per mm of sensor height

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
        cocMM = (fmm * fmm) / (Aperture * depthM * 200.0);
    } else {
        float s1mm = FocusDist * 200.0;
        float denom = Aperture * max(s1mm - fmm, 1.0);
        cocMM = (fmm * fmm) * abs(depthM - FocusDist) / (depthM * denom);
    }
    return clamp(cocMM * PxPerMm, 0.0, MaxBlurPx);
}

void main() {
    float rawD = texture(DepthSampler, texCoord).r;
    float depth = linearDepth(rawD);

    float coc = computeCoc(depth);

    int rad = int(ceil(coc));
    rad = min(rad, 32);

    if (rad == 0) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    float sigma = max(coc * 0.5, 1.0);
    vec4 col = vec4(0.0);
    float totalW = 0.0;
    for (int i = -rad; i <= rad; i++) {
        float fi = float(i);
        float w = exp(-fi * fi / (2.0 * sigma * sigma));
        col += texture(InSampler, texCoord + BlurDir * fi * PixelSize) * w;
        totalW += w;
    }
    fragColor = col / totalW;
}
