#version 150

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // non-linear depth [0,1] from scene framebuffer
uniform sampler2D NoiseSampler;  // 64x64 blue-noise dither (void-and-cluster), GL_REPEAT
uniform vec2 BlurDir;            // .x >= 0.5 : gather pass; .x < 0.5 : copy pass
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

// Three-layer depth of field. Each pixel is sorted into NEAR (out-of-focus foreground),
// FOCUS (the sharp in-focus band — its width follows the circle of confusion, so it
// tracks aperture & focal length automatically), or FAR (out-of-focus background). The
// near and far layers are scattered as bokeh discs; the focus layer stays sharp. The
// layers are then composited far -> focus -> near with occlusion weighting, so an
// out-of-focus foreground feathers softly OVER the sharp subject behind it (instead of
// leaving a hard outline) while the subject itself stays crisp.
const int   SAMPLES      = 128;
const float GOLDEN_ANGLE = 2.39996323;
const float TWO_PI       = 6.28318531;

// At infinity focus, everything past this many blocks is forced sharp, ramping out between
// the two. The physical CoC is correct but only as correct as the depth it is fed, and LOD
// terrain (Voxy) does not report a trustworthy distance through the vanilla depth buffer.
// At infinity the far field IS the focal plane, so treat it as in focus.
const float INF_SHARP_BEGIN = 48.0;   // blocks — blur starts fading out here
const float INF_SHARP_FULL  = 128.0;  // blocks — dead sharp beyond here

float linearDepth(float d) {
    float ndc = 2.0 * d - 1.0;
    return 2.0 * Near * Far / (Far + Near - ndc * (Far - Near));
}

/**
 * Minimum blur on distant geometry regardless of the thin-lens result — an atmospheric-haze
 * floor that also hides LOD popping.
 *
 * This used to be an unconditional max(c, smoothstep(200, 600, d) * 5.0) at two sites, with
 * no reference to the focus distance: anything past 200 blocks was forced to at least 5 px
 * of blur even with the lens at infinity, so the infinity branch of computeCoc() returned
 * ~0 and the floor put the blur straight back. Off at infinity focus.
 */
float distantHazeFloor(float depthM) {
    if (FocusDist >= 99999.0) return 0.0;
    return smoothstep(200.0, 600.0, depthM) * 5.0;
}

// Physically-based thin-lens circle of confusion, in framebuffer pixels.
float computeCoc(float depthM) {
    depthM = max(depthM, 0.05);
    float fmm = FocalLenMm;
    float cocMM;
    if (FocusDist >= 99999.0) {
        cocMM = (fmm * fmm) / (Aperture * depthM * DofScale);
        cocMM *= 1.0 - smoothstep(INF_SHARP_BEGIN, INF_SHARP_FULL, depthM);
    } else {
        float s1mm = FocusDist * DofScale;
        float denom = Aperture * max(s1mm - fmm, 1.0);
        cocMM = (fmm * fmm) * abs(depthM - FocusDist) / (depthM * denom);
    }
    return clamp(cocMM * PxPerMm, 0.0, MaxBlurPx);
}

// Per-pixel sample rotation comes from a precomputed 128x128 blue-noise texture (generated
// offline with the void-and-cluster algorithm). Blue noise spreads its energy into the
// high frequencies, so neighbouring pixels get well-separated rotations with no dense/
// sparse clumping — the gather's residual grain becomes a fine, even dither the denoise
// pass removes cleanly, instead of the clumpy "fibres" a white-noise hash produces.
#define NOISE_SIZE 128.0

// 2-D hash, used to give each 128px tile its own toroidal offset so the texture doesn't
// visibly repeat on a grid. A toroidally-shifted blue-noise tile is still valid blue
// noise, so each tile looks different while keeping the good spectral properties.
vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

vec3 hash32(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yzz) * p3.zyx);
}

void main() {
    // Copy / denoise pass. The disc gather leaves fine sampling grain on blurred regions;
    // since those are already low-frequency a small 3x3 average wipes the grain without
    // losing any real detail, while in-focus pixels are copied through untouched so the
    // sharp subject stays crisp.
    if (BlurDir.x < 0.5) {
        float d = linearDepth(texture(DepthSampler, texCoord).r);
        float c = max(computeCoc(d) - 1.5, 0.0);
        c = max(c, distantHazeFloor(d));
        if (c < 2.0) { fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0); return; }

        // Scale by how STARVED the gather was here, which it reported in alpha, not by how
        // blurred the pixel is. Sizing this off CoC alone smoothed every defocused pixel
        // equally — including the ones the gather resolved perfectly well, which is bokeh
        // thrown away for nothing. Where confidence is high this collapses to no denoise.
        float starved = 1.0 - texture(InSampler, texCoord).a;
        float rad     = clamp(c * 0.16, 1.0, 8.0) * starved;
        if (rad < 0.75) { fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0); return; }

        // Gaussian-weighted, not a flat box: an unweighted square gives a bright or dark
        // neighbour full strength out to the corners, which reads as smudging rather than
        // defocus. Same radius, centre dominant.
        float sigma = max(rad * 0.5, 0.5);
        int   irad  = int(rad);
        vec3  sum   = vec3(0.0);
        float wsum  = 0.0;
        for (int dy = -irad; dy <= irad; dy++)
            for (int dx = -irad; dx <= irad; dx++) {
                vec2  o = vec2(float(dx), float(dy));
                float w = exp(-dot(o, o) / (2.0 * sigma * sigma));
                sum  += texture(InSampler, texCoord + o * PixelSize).rgb * w;
                wsum += w;
            }
        fragColor = vec4(sum / wsum, 1.0);
        return;
    }

    vec4  centre = texture(InSampler, texCoord);
    float depthM = linearDepth(texture(DepthSampler, texCoord).r);
    float cocP   = max(computeCoc(depthM) - 1.5, 0.0);

    // Atmospheric softness floor: the most distant LOD terrain is low-detail and, left
    // razor-sharp (e.g. when the focus is racked all the way out), aliases into harsh
    // blocky chunks. A gentle minimum blur that grows with distance keeps far terrain
    // soft even when it is the focus subject. Near / mid subjects are untouched. This
    // pushes hazed pixels out of the sharp FOCUS layer into FAR, so they get blurred.
    cocP = max(cocP, distantHazeFloor(depthM));

    // Coarse scan for neighbours whose own disc is wide enough to reach this pixel. It yields
    // both whether a nearer, defocused neighbour blooms over us, and how far out anything
    // that contributes actually lives. Ring radii are spaced QUADRATICALLY so they crowd near
    // the centre: evenly spaced, the innermost sat at MaxBlurPx/5 (24 px at f/1.4) and a
    // foreground blurred less than that was never detected at all, so the gather shrank to
    // the background's own CoC and the foreground never scattered outward — its silhouette
    // then kept the geometry it has in focus, corners and all.
    bool  hasNearFg = false;
    float reachR    = 0.0;
    for (int k = 0; k < 16; k++) {
        float a = float(k) * (TWO_PI / 16.0);
        vec2 dir = vec2(cos(a), sin(a));
        for (int s = 1; s <= 6; s++) {
            float t   = float(s) / 6.0;
            float rr  = MaxBlurPx * t * t;
            float sd  = linearDepth(texture(DepthSampler, texCoord + dir * rr * PixelSize).r);
            float sc2 = max(computeCoc(sd) - 1.5, 0.0);
            if (sc2 >= rr - 1.0) {
                reachR = max(reachR, min(sc2, MaxBlurPx));
                if (sd < depthM - 0.5 && sd < FocusDist) hasNearFg = true;
            }
        }
    }

    if (cocP < 0.5 && !hasNearFg) {    // sharp, nothing blooming over it → leave crisp
        fragColor = vec4(centre.rgb, 1.0);   // alpha 1 = fully sampled, needs no denoise
        return;
    }

    // Size the gather to what can actually contribute: this pixel's own disc, or the disc of
    // the widest neighbour reaching it.
    //
    // This was pinned at MaxBlurPx for every pixel, and that is why WEAKLY defocused areas
    // were the noisiest — the opposite of what you would expect. Spreading the taps over a
    // 120 px disc when the pixel's own CoC is 3 px puts, on average, 128 * (3/120)^2 ~ 0.08
    // samples inside the radius that can contribute: the colour was decided by whether one
    // random tap happened to land. Matching the radius to the content puts every tap inside
    // the disc, and the variance collapses.
    float gatherR       = clamp(max(cocP, reachR), 1.0, MaxBlurPx);
    float areaPerSample = gatherR * gatherR / float(SAMPLES);
    // Per-128px tile: apply one of the 8 square symmetries (90 deg rotations + flips, the
    // only ones that keep the tile seamless & still blue noise) PLUS a toroidal offset,
    // all chosen from the tile hash. Each tile becomes a different — but still valid —
    // blue-noise patch, so the texture's grid repetition vanishes entirely.
    vec2 ntile = floor(gl_FragCoord.xy / NOISE_SIZE);
    vec2 lc    = fract(gl_FragCoord.xy / NOISE_SIZE);
    vec3 th    = hash32(ntile);
    if (th.x > 0.5) lc.x = 1.0 - lc.x;        // flip X
    if (th.y > 0.5) lc.y = 1.0 - lc.y;        // flip Y
    if (th.z > 0.5) lc = lc.yx;               // transpose → gives the 90 deg rotations
    lc = fract(lc + hash22(ntile + 19.7));    // toroidal shift
    float rot  = texture(NoiseSampler, lc).r * TWO_PI;

    // How many taps actually landed inside a contributing disc. When the gather has to be
    // sized for a big neighbour while this pixel's own CoC is small, most taps fall outside
    // everything and the result rests on the few that did not — grain hugging depth
    // discontinuities. Carried out in alpha so the copy pass denoises exactly the pixels
    // that were starved and leaves well-sampled bokeh alone.
    float contrib = 0.0;

    // Premultiplied layer accumulators.
    vec3 nearC = vec3(0.0); float nearA = 0.0;
    vec3 focC  = vec3(0.0); float focA  = 0.0;
    vec3 farC  = vec3(0.0); float farA  = 0.0;

    // Centre deposits into its own layer. Blend smoothly between the sharp FOCUS layer and
    // the blurred NEAR/FAR layer across the in-focus boundary: a hard cutoff here made a
    // region snap from sharp to blurred (with a 4x weight jump and a change of occlusion
    // priority) the instant its CoC crossed the threshold, so racking focus produced a
    // visible "click" in the boundary blur. The smoothstep + continuous weight remove it.
    {
        float w  = areaPerSample / max(cocP * cocP, 0.25);
        float fw = smoothstep(3.0, 0.5, cocP);          // 1 = sharp (focus), 0 = clearly blurred
        focC += centre.rgb * w * fw; focA += w * fw;
        float bw = w * (1.0 - fw);
        if (bw > 0.0) {
            if (depthM < FocusDist) { nearC += centre.rgb * bw; nearA += bw; }
            else                    { farC  += centre.rgb * bw; farA  += bw; }
        }
    }

    for (int i = 0; i < SAMPLES; i++) {
        float fi  = float(i) + 0.5;
        float r   = sqrt(fi / float(SAMPLES)) * gatherR;
        float ang = fi * GOLDEN_ANGLE + rot;
        vec2  sc  = texCoord + vec2(cos(ang), sin(ang)) * r * PixelSize;

        float sDepthM = linearDepth(texture(DepthSampler, sc).r);
        float sCoc    = max(computeCoc(sDepthM) - 1.5, 0.0);
        if (sCoc < 0.5) continue;          // sharp samples don't scatter (handled at centre only)

        // Soft disc edge. This was a binary `if (r > sCoc) continue;`, so whether a neighbour
        // contributed flipped abruptly as the gather radius crossed that neighbour's own CoC.
        // The SET of contributing samples therefore changed discontinuously from pixel to
        // pixel, and the boundaries between those sets showed up as flat patches — the
        // painterly, brush-stroke look. Feathering over a one-pixel band makes the same disc
        // with its edge antialiased instead of quantised.
        float edge = smoothstep(sCoc + 0.5, sCoc - 0.5, r);
        if (edge <= 0.0) continue;

        contrib   += edge;
        vec3  sCol = texture(InSampler, sc).rgb;
        float w    = areaPerSample / max(sCoc * sCoc, 1.0) * edge;
        if (sDepthM < FocusDist) { nearC += sCol * w; nearA += w; }
        else                     { farC  += sCol * w; farA  += w; }
    }

    // Composite far -> focus -> near with occlusion weighting (a nearer layer hides the
    // ones behind it; normalising by present coverage keeps holes from going black).
    vec3 nearCol = (nearA > 0.0) ? nearC / nearA : vec3(0.0);
    vec3 focCol  = (focA  > 0.0) ? focC  / focA  : vec3(0.0);
    vec3 farCol  = (farA  > 0.0) ? farC  / farA  : vec3(0.0);

    float na = clamp(nearA, 0.0, 1.0);
    float fo = clamp(focA,  0.0, 1.0);
    float fr = clamp(farA,  0.0, 1.0);

    float wNear  = na;
    float wFocus = fo * (1.0 - na);
    float wFar   = fr * (1.0 - na) * (1.0 - fo);
    float wsum   = wNear + wFocus + wFar;

    float confidence = clamp(contrib / 40.0, 0.0, 1.0);
    fragColor = (wsum > 0.001)
        ? vec4((nearCol * wNear + focCol * wFocus + farCol * wFar) / wsum, confidence)
        : vec4(centre.rgb, 1.0);
}
