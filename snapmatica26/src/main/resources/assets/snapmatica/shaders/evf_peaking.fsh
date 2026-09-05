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

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

float luma(vec2 uv) {
    return dot(texture(InSampler, uv).rgb, LUMA);
}

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

    // Peaking has to mark the RIDGE of an edge, not every pixel that happens to sit on a
    // slope. The original test was a two-tap forward difference against a 0.06 threshold, and
    // in Minecraft that is not an edge detector at all: block textures are dithered per texel,
    // so neighbouring screen pixels routinely differ by more than 0.06 on a flat wall of
    // grass. The result was the whole in-focus plane carpeted in solid PeakColor — which,
    // since PeakColor is a warm red-orange and peaking is viewfinder-only, is also why the
    // finder read warmer than the photograph it was supposed to be previewing.
    //
    // Central differences (symmetric, so a single dithered texel no longer registers as a
    // slope the way a forward difference makes it), then non-maximum suppression: keep the
    // pixel only where the gradient is at least as strong as its neighbours' along the same
    // axis. That is the step that turns a filled region into the one-pixel outline a real
    // body draws.
    //
    // Nine luminance taps, in a cross five wide and five tall, which is what the neighbours'
    // OWN central differences need. Cheap next to the depth-of-field gather beside it, and
    // this pass only ever runs for the viewfinder.
    float lxm2 = luma(texCoord - vec2(2.0 * PixelSize.x, 0.0));
    float lxm1 = luma(texCoord - vec2(PixelSize.x, 0.0));
    float lc   = luma(texCoord);
    float lxp1 = luma(texCoord + vec2(PixelSize.x, 0.0));
    float lxp2 = luma(texCoord + vec2(2.0 * PixelSize.x, 0.0));
    float lym2 = luma(texCoord - vec2(0.0, 2.0 * PixelSize.y));
    float lym1 = luma(texCoord - vec2(0.0, PixelSize.y));
    float lyp1 = luma(texCoord + vec2(0.0, PixelSize.y));
    float lyp2 = luma(texCoord + vec2(0.0, 2.0 * PixelSize.y));

    float gx  = lxp1 - lxm1;             // gradient here
    float gy  = lyp1 - lym1;
    float gxm = lc   - lxm2;             // and one step either side, for the ridge test
    float gxp = lxp2 - lc;
    float gym = lc   - lym2;
    float gyp = lyp2 - lc;

    float mag = length(vec2(gx, gy));
    // Raised from 0.06. A dithered Minecraft texture sits well under this on a flat surface
    // once the difference is symmetric, while a real block boundary clears it easily.
    if (mag < 0.14) { fragColor = vec4(srcColor, 1.0); return; }

    // The ridge itself: strongest along whichever axis dominates the gradient.
    bool ridge = (abs(gx) >= abs(gy))
            ? (abs(gx) >= abs(gxm) && abs(gx) >= abs(gxp))
            : (abs(gy) >= abs(gym) && abs(gy) >= abs(gyp));
    if (!ridge) { fragColor = vec4(srcColor, 1.0); return; }

    fragColor = vec4(PeakColor, 1.0);
}
