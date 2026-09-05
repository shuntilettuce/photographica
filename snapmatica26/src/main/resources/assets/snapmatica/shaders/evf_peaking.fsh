#version 150

// Focus peaking: highlights high-contrast edges that sit within a narrow band of the focus
// distance, the same visual aid a real mirrorless body draws over manual focus. Deliberately
// a separate pass from the DoF gather rather than woven into it — it needs only the focus
// band and an edge check, nothing the gather already computes, and keeping it independent
// means it can never perturb the physically-modelled blur that pass exists for.
//
// Two passes, both full-res:
//   0 = detect + highlight: mainTex -> aux, since reading and writing the SAME texture in one
//       draw is a feedback loop with undefined results.
//   1 = plain copy: aux -> mainTex, to land the result back where the rest of the pipeline
//       (and the screenshot) expects the finished frame to be.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // non-linear depth [0,1] from the scene framebuffer; pass 0 only
uniform int   Pass;
uniform vec2  PixelSize;
uniform float FocusDist;         // focus distance in blocks; ignored when AfMode=1
uniform int   AfMode;            // 1 = derive focus from the centre pixel's depth, on the GPU
uniform float Near;
uniform float Far;
uniform vec3  PeakColor;

in vec2 texCoord;
out vec4 fragColor;

float linearDepth(float d) {
    float ndc = 2.0 * d - 1.0;
    return 2.0 * Near * Far / (Far + Near - ndc * (Far - Near));
}

void main() {
    if (Pass == 1) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    vec3 srcColor = texture(InSampler, texCoord).rgb;

    float focus = FocusDist;
    if (AfMode == 1) {
        float cd = linearDepth(texture(DepthSampler, vec2(0.5, 0.5)).r);
        focus = (cd >= Far * 0.98) ? 100000.0 : cd;
    }

    float depth = linearDepth(texture(DepthSampler, texCoord).r);

    // A fixed fraction of the focus distance, not a fixed number of blocks — the same
    // reasoning the depth of field itself uses: a subject 3 m out and one 300 m out both
    // need the band scaled to how far away focus actually is, or a telephoto shot would
    // either peak everything in the shot or nothing in it.
    float band = max(focus * 0.03, 0.1);
    if (abs(depth - focus) > band) { fragColor = vec4(srcColor, 1.0); return; }

    // Cheap two-tap luminance gradient — plenty to tell texture/edges from a flat sky or
    // wall, without the cost of a full Sobel kernel on every viewfinder frame.
    vec3 dx = texture(InSampler, texCoord + vec2(PixelSize.x, 0.0)).rgb - srcColor;
    vec3 dy = texture(InSampler, texCoord + vec2(0.0, PixelSize.y)).rgb - srcColor;
    const vec3 LUMA = vec3(0.299, 0.587, 0.114);
    float edge = length(vec2(dot(dx, LUMA), dot(dy, LUMA)));

    if (edge < 0.06) { fragColor = vec4(srcColor, 1.0); return; }

    fragColor = vec4(PeakColor, 1.0);
}
