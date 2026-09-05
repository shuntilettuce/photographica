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
const int   SAMPLES      = 96;
const float GOLDEN_ANGLE = 2.39996323;
const float TWO_PI       = 6.28318531;

float linearDepth(float d) {
    float ndc = 2.0 * d - 1.0;
    return 2.0 * Near * Far / (Far + Near - ndc * (Far - Near));
}

// Diffraction-limited spot size (Airy disc diameter, mid-visible 550nm), in mm. Combined
// with the defocus blur in quadrature below, this puts a physically real sharpness ceiling
// around f/11 instead of the lens reading "infinitely sharp" as the aperture keeps closing.
float airyDiscMM(float aperture) {
    return 2.44 * 0.00055 * aperture;
}

// Physically-based thin-lens circle of confusion, in framebuffer pixels.
float computeCoc(float depthM) {
    depthM = max(depthM, 0.05);
    float fmm = FocalLenMm;
    float cocMM;
    if (FocusDist >= 999.0) {
        cocMM = (fmm * fmm) / (Aperture * depthM * DofScale);
    } else {
        float s1mm = FocusDist * DofScale;
        float denom = Aperture * max(s1mm - fmm, 1.0);
        cocMM = (fmm * fmm) * abs(depthM - FocusDist) / (depthM * denom);
    }
    float airyMM = airyDiscMM(Aperture);
    cocMM = sqrt(cocMM * cocMM + airyMM * airyMM);
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
        c = max(c, smoothstep(200.0, 600.0, d) * 5.0);
        // "sc" drives the denoise kernel size — larger blur → larger box, so the grain a
        // wide gather leaves gets averaged away and a heavily out-of-focus region dissolves
        // smoothly (no defined edge), the way a real big-bokeh foreground does.
        float sc = c;
        bool soften = (c >= 2.0);
        if (!soften) {
            // Sharp pixel. If it sits on the in-focus subject's SILHOUETTE — a neighbour is
            // clearly out of focus — soften it too, so the outline feathers into the bokeh
            // instead of reading as a hard, jagged pixel staircase. Interior sharp pixels
            // (all neighbours sharp) are copied through untouched and stay crisp.
            float nb = 0.0;
            for (int k = 0; k < 8; k++) {
                float a  = float(k) * (TWO_PI / 8.0);
                float nd = linearDepth(texture(DepthSampler,
                        texCoord + vec2(cos(a), sin(a)) * 3.0 * PixelSize).r);
                nb = max(nb, max(computeCoc(nd) - 1.5, 0.0));
            }
            soften = (nb >= 4.0);
            sc = nb;
        }
        if (!soften) { fragColor = texture(InSampler, texCoord); return; }
        int rad = int(clamp(sc * 0.16, 2.0, 10.0));   // 5x5 … 21x21
        vec3 sum = vec3(0.0);
        for (int dy = -rad; dy <= rad; dy++)
            for (int dx = -rad; dx <= rad; dx++)
                sum += texture(InSampler, texCoord + vec2(float(dx), float(dy)) * PixelSize).rgb;
        float n = float((2 * rad + 1) * (2 * rad + 1));
        fragColor = vec4(sum / n, 1.0);
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
    cocP = max(cocP, smoothstep(200.0, 600.0, depthM) * 5.0);

    // Does a closer, out-of-focus pixel bloom over this one? (Run for every pixel: the
    // in-focus subject sits right at the focus distance, so some of its pixels read as
    // depth < focus and must still be allowed to receive a foreground bloom.)
    bool hasNearFg = false;
    if (FocusDist < 999.0) {
        for (int k = 0; k < 16 && !hasNearFg; k++) {
            float a = float(k) * (TWO_PI / 16.0);
            vec2 dir = vec2(cos(a), sin(a));
            for (int s = 1; s <= 5; s++) {
                float rr = MaxBlurPx * float(s) / 5.0;
                float sd = linearDepth(texture(DepthSampler, texCoord + dir * rr * PixelSize).r);
                if (sd < depthM - 0.5 && sd < FocusDist && max(computeCoc(sd) - 1.5, 0.0) >= rr - 1.0) {
                    hasNearFg = true; break;
                }
            }
        }
    }

    if (cocP < 0.5 && !hasNearFg) {    // sharp, nothing blooming over it → leave crisp
        fragColor = centre;
        return;
    }

    float gatherR       = MaxBlurPx;
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

    // Premultiplied layer accumulators.
    vec3 nearC = vec3(0.0); float nearA = 0.0;
    vec3 focC  = vec3(0.0); float focA  = 0.0;
    vec3 farC  = vec3(0.0); float farA  = 0.0;

    // Fill: a cheap, always-on nearest-background reconstruction. A sharp sample never
    // scatters into focC/farC below (a point with no bokeh has nothing to spread), so a
    // small gap in a near-field silhouette — a leaf edge, a fence gap — can end up with
    // zero focus/far signal even though the sharp subject behind it is only a few pixels
    // away. Fill exists purely as a fallback for that case; see the composite below.
    const float FILL_SOFT = 3.0;
    vec3 fillC = vec3(0.0); float fillW = 0.0;

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
        vec3  sCol    = texture(InSampler, sc).rgb;

        // Any sample at or behind the centre's own depth is a candidate for the fill
        // reconstruction, sharp or not — weighted by inverse-square distance so the
        // nearest visible bit of background dominates.
        if (sDepthM >= depthM - 0.5) {
            float fw = 1.0 / (r * r + FILL_SOFT * FILL_SOFT);
            fillC += sCol * fw; fillW += fw;
        }

        if (sCoc < 0.5) continue;          // sharp samples don't scatter (handled at centre only)
        if (r > sCoc)   continue;          // this sample's bokeh disc doesn't reach P

        float w = areaPerSample / max(sCoc * sCoc, 1.0);
        if (sDepthM < FocusDist) { nearC += sCol * w; nearA += w; }
        else                     { farC  += sCol * w; farA  += w; }
    }

    // Composite: near layer over everything behind it. The old version blended all three
    // layers by normalising through a shared wsum — but when the background right behind a
    // near-field silhouette is sharp (its samples never reach focC/farC, see above), that
    // wsum collapsed to just the near layer's own weight and cancelled straight back out
    // (nearCol * na / na = nearCol), making a partially-transparent leaf or fence gap read
    // as fully opaque. Compositing near over a reconstructed "under" colour with a plain
    // (1 - na) weight can't cancel like that, so a real fractional coverage stays fractional.
    vec3 nearCol = (nearA > 0.0) ? nearC / nearA : vec3(0.0);
    vec3 focCol  = (focA  > 0.0) ? focC  / focA  : vec3(0.0);
    vec3 farCol  = (farA  > 0.0) ? farC  / farA  : vec3(0.0);

    float na = clamp(nearA, 0.0, 1.0);
    float fo = clamp(focA,  0.0, 1.0);
    float fr = clamp(farA,  0.0, 1.0);

    float underA   = fo + fr * (1.0 - fo);
    vec3  underCol = (underA > 0.0001) ? (focCol * fo + farCol * fr * (1.0 - fo)) / underA : vec3(0.0);
    vec3  fillCol  = (fillW > 0.0) ? fillC / fillW : centre.rgb;
    // underA is near-binary: the gather either found real focus/far colour or it didn't, so
    // a narrow ramp is enough to pick between "real" and "filled" without a visible seam.
    float trust    = smoothstep(0.01, 0.12, underA);
    vec3  bgCol    = mix(fillCol, underCol, trust);

    fragColor = vec4(nearCol * na + bgCol * (1.0 - na), 1.0);
}
