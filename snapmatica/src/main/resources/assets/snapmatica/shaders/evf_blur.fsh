#version 150

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // non-linear depth [0,1] from scene framebuffer
uniform sampler2D NoiseSampler;  // 128x128 blue-noise dither (void-and-cluster), GL_REPEAT
uniform sampler2D NearSampler;   // low-res, big-blurred premultiplied FOREGROUND layer
uniform sampler2D BgSampler;     // low-res, big-blurred BACKGROUND (foreground holes filled)
uniform int   Pass;              // 0 = gather/copy, 1 = extract fg, 2 = blur, 3 = extract bg
uniform vec2 BlurDir;            // gather/copy: .x>=0.5 gather, <0.5 copy.  blur: H=(1,0) V=(0,1)
uniform vec2 PixelSize;          // 1/texW, 1/texH of the CURRENT render target
uniform float FocusDist;         // focus distance in blocks (metres); ignored when AfMode=1
uniform int   AfMode;            // 1 = derive focus from the centre pixel's depth, on the GPU
uniform int   NearDownscale;     // full-res texels per near-layer texel (EvfBlurRenderer)
uniform int   NearLayer;         // 0 = composite the gather alone, skipping the near-field
uniform float MaxBlurPx;         // max blur radius in framebuffer pixels (perf clamp)
uniform float Near;              // near clip plane in blocks
uniform float Far;               // far clip plane in blocks
uniform float FocalLenMm;        // lens focal length in mm
uniform float Aperture;          // f-number (N)
uniform float PxPerMm;           // framebuffer pixels per mm of sensor height
uniform float DofScale;          // mm of subject distance per Minecraft block

in vec2 texCoord;
out vec4 fragColor;

// Depth of field in two cooperating parts:
//  • A per-pixel 3-layer disc GATHER (NEAR / FOCUS / FAR) handles the in-focus subject and
//    the background bokeh, and the near→far occlusion that softens a foreground silhouette.
//  • A separate LOW-RES NEAR-FIELD layer handles heavily out-of-focus FOREGROUND: it is
//    extracted premultiplied, blurred huge & cheap at 1/4 resolution (so a close foreground
//    DISSOLVES with no defined edge, the way a real fast/long lens does), then composited
//    back over the gather result with its blurred alpha.
// Sample density falls as the square of the CoC — a 96-tap disc at 60 px radius leaves under
// one sample per hundred pixels, and that shortfall is what the denoise below was covering
// for. Paying for the samples here is the honest fix: the noise never appears, so it does not
// have to be smeared away afterwards.
const int   SAMPLES      = 128;
const float GOLDEN_ANGLE = 2.39996323;
const float TWO_PI       = 6.28318531;
#define NOISE_SIZE 128.0

float linearDepth(float d) {
    float ndc = 2.0 * d - 1.0;
    return 2.0 * Near * Far / (Far + Near - ndc * (Far - Near));
}

// At infinity focus, everything past this many blocks is forced sharp; the blur ramps
// out between the two. The physical CoC formula below is correct, but it can only be as
// correct as the depth it is fed — and LOD terrain drawn by Voxy does not report a
// trustworthy distance through the vanilla depth buffer (a known, unfixed limitation).
// Rather than blur distant geometry on the strength of a bogus depth, treat "far" as what
// infinity focus means it is: in focus. Costs some telephoto far-field softness that a
// real lens would show — deliberate, since a sharp horizon is the point of infinity focus.
const float INF_SHARP_BEGIN = 48.0;   // blocks — blur starts fading out here
const float INF_SHARP_FULL  = 128.0;  // blocks — dead sharp beyond here

/**
 * The focus distance every function below works from — {@code FocusDist}, or the depth under
 * the reticle when AfMode is on. Resolved once per fragment in main(), never read directly.
 *
 * <p>GPU-side autofocus exists because the CPU cannot see what the camera is pointed at. Its
 * distance came from a 1000-block world raycast, which passes straight through LOD terrain a
 * mod like Voxy drew, and the obvious fallback — reading the depth buffer back — is barred:
 * on hybrid-GPU machines glReadPixels on a depth FBO crashed the NVIDIA driver outright
 * whenever a LOD mod was drawing the distance (see PhotoCapture.onWorldRenderEnd). Sampling
 * that same depth here costs one texture fetch, needs no read-back at all, and focuses on
 * whatever is actually on screen — LOD terrain included.
 */
float gFocus;

float resolveFocus() {
    if (AfMode == 0) return FocusDist;
    float d = linearDepth(texture(DepthSampler, vec2(0.5, 0.5)).r);
    // Reticle on sky / at the far plane: infinity, matching the CPU path's sentinel. Without
    // this the focus would sit at the far plane as a FINITE distance and re-enable the haze
    // floor below, softening the very horizon the camera is focused on.
    return (d >= Far * 0.98) ? 100000.0 : d;
}

/**
 * Minimum blur applied to distant geometry regardless of the thin-lens result — an
 * atmospheric-haze floor that also hides LOD popping.
 *
 * It used to be an unconditional `max(c, smoothstep(200, 600, d) * 5.0)` at two sites, which
 * meant ANY geometry past 200 blocks was forced to at least 5 px of blur even with the lens
 * focused at infinity. That, not the depth values, is why the horizon stayed soft at inf:
 * the infinity branch of computeCoc() correctly returned ~0 and this floor put the blur
 * straight back. At infinity focus the far field IS the focal plane, so the floor is off.
 */
float distantHazeFloor(float depthM) {
    if (gFocus >= 99999.0) return 0.0;
    return smoothstep(200.0, 600.0, depthM) * 5.0;
}

// Physically-based thin-lens circle of confusion, in framebuffer pixels.
float computeCoc(float depthM) {
    depthM = max(depthM, 0.05);
    float fmm = FocalLenMm;
    float cocMM;
    if (gFocus >= 99999.0) {
        cocMM = (fmm * fmm) / (Aperture * depthM * DofScale);
        cocMM *= 1.0 - smoothstep(INF_SHARP_BEGIN, INF_SHARP_FULL, depthM);
    } else {
        float s1mm = gFocus * DofScale;
        float denom = Aperture * max(s1mm - fmm, 1.0);
        cocMM = (fmm * fmm) * abs(depthM - gFocus) / (depthM * denom);
    }
    return clamp(cocMM * PxPerMm, 0.0, MaxBlurPx);
}

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
    // Every pass must resolve this identically, or the low-res near-field layer would be
    // extracted against a different focus than the gather it is composited over.
    gFocus = resolveFocus();

    // ── Pass 1 / 3: split the scene into FOREGROUND and BACKGROUND premultiplied layers ─
    // Pass 1 takes the out-of-focus foreground (alpha ramps in with CoC so only genuinely
    // blurred foreground is taken). Pass 3 takes the COMPLEMENT — everything else — as the
    // background layer. Both render to a 1/4-res buffer; alpha = coverage, rgb = colour*alpha.
    // The big Gaussian below then (a) feathers the foreground's alpha so its edge goes
    // translucent, and (b) bleeds the background INTO the foreground's holes, so when the
    // translucent foreground edge is composited the scene behind it shows through and the
    // silhouette dissolves — instead of revealing more of the sharp foreground.
    if (Pass == 1 || Pass == 3) {
        float d   = linearDepth(texture(DepthSampler, texCoord).r);
        float coc = max(computeCoc(d) - 1.5, 0.0);
        // Alpha here is COVERAGE — "is this pixel heavily-defocused foreground", 0 or 1 —
        // and not, as it was, the fraction `smoothstep(30, 70, coc)` of the blur being routed
        // to this layer.
        //
        // Treating a routing weight as coverage is what made the original block findable in
        // the result. A block at coc = 40 got a = 0.25, so the composite `fg.rgb + under *
        // (1 - fg.a)` reproduced only a quarter of its colour and filled the other 75% with
        // background — the block read weaker than it should, weaker still outside its
        // footprint where alpha had fallen further, and the ramp between 30 and 70 px drew a
        // contour across any surface whose CoC swept through that range. That contour is the
        // visible boundary. With real coverage the composite is ordinary alpha-over: full
        // colour where the foreground is, falling off exactly as the disc spreads it.
        //
        // The narrow band is only to keep the selection from popping; it is not a blend.
        float fg  = (d < gFocus) ? smoothstep(28.0, 36.0, coc) : 0.0;
        float a   = (Pass == 1) ? fg : (1.0 - fg);
        // Box-average the COLOUR over the near-texel's full-res footprint. Point-sampling
        // high-frequency terrain at 1/4 res aliases into coarse blotches that the blur then
        // smears around (the "mosaic / べっちゃり"); a proper box downsample removes it.
        // Taps must span exactly one low-res texel's full-res footprint, so the count follows
        // NearDownscale rather than being fixed — a hardcoded 4x4 would over-blur the moment
        // the downscale factor changed.
        float n = float(NearDownscale);
        float half_ = (n - 1.0) * 0.5;
        vec3 col = vec3(0.0);
        for (int sy = 0; sy < NearDownscale; sy++)
            for (int sx = 0; sx < NearDownscale; sx++) {
                vec2 o = (vec2(float(sx), float(sy)) - half_) * (1.0 / n) * PixelSize;
                col += texture(InSampler, texCoord + o).rgb;
            }
        col *= (1.0 / (n * n));
        fragColor = vec4(col * a, a);
        return;
    }

    // ── Pass 2 / 4: separable Gaussian on a premultiplied near layer (low-res) ────────
    // Pass 2 = FOREGROUND: the radius TRACKS the local CoC, so the blur grows smoothly with
    // defocus and matches the full-res gather at the hand-off — no sudden jump from a fixed
    // huge kernel, and a only-mildly-defocused foreground isn't over-smeared. Pass 4 =
    // BACKGROUND fill: a fixed wide kernel that bleeds the scene into the foreground holes.
    // Taps are spaced to span ~2.5σ and sampled at non-integer offsets, so bilinear averages
    // each texel pair — no texel is skipped (a skip would re-introduce the 2-texel mosaic).
    // ── Pass 2: FOREGROUND — convolve with the APERTURE, i.e. a disc ─────────────────
    // A lens convolves the scene with the shape of its aperture. That is what rounds a
    // defocused corner into an arc of its own CoC, and what turns a highlight into a bokeh
    // disc. This pass used to run a separable Gaussian: radially symmetric, but with an
    // infinite smooth falloff and no edge anywhere. A Gaussian SMOOTHS a corner without ever
    // rounding it — the silhouette keeps the angular geometry it has in focus, however hard
    // it is blurred. No amount of tuning sigma fixes that; the kernel has the wrong shape.
    //
    // The radius deliberately matches the old kernel's apparent spread (a Gaussian reads
    // about twice its sigma), so this changes the SHAPE of the defocus and not its strength.
    if (Pass == 2) {
        float d    = linearDepth(texture(DepthSampler, texCoord).r);
        float coc  = max(computeCoc(d) - 1.5, 0.0);
        // Floor so the foreground also feathers OUTWARD past its silhouette a little (holes
        // just outside still gather some foreground) — the edge dissolves on both sides.
        float radius = max(coc * 0.24, 8.0);    // low-res texels (coc is full-res px)

        const int DISC_TAPS = 64;
        float rot  = texture(NoiseSampler, fract(gl_FragCoord.xy / NOISE_SIZE)).r * TWO_PI;
        vec4  acc  = vec4(0.0);
        for (int i = 0; i < DISC_TAPS; i++) {
            float fi = float(i) + 0.5;
            // sqrt keeps the taps uniformly dense over the disc's AREA rather than crowding
            // the centre, so the kernel is flat — which is what gives it a defined edge.
            float r  = sqrt(fi / float(DISC_TAPS)) * radius;
            float a  = fi * GOLDEN_ANGLE + rot;
            acc += texture(InSampler, texCoord + vec2(cos(a), sin(a)) * r * PixelSize);
        }
        fragColor = acc / float(DISC_TAPS);
        return;
    }

    // ── Pass 4: BACKGROUND fill — a Gaussian is right here ───────────────────────────
    // This one only has to bleed background colour into the holes the foreground left, so it
    // wants a wide, featureless spread with no edge of its own to show through.
    if (Pass == 4) {
        float sigma = 16.0;
        float stp  = sigma * 2.5 / 24.0;
        vec4  acc  = texture(InSampler, texCoord);
        float wsum = 1.0;
        for (int i = 1; i <= 24; i++) {
            float o = float(i) * stp;
            float w = exp(-o * o / (2.0 * sigma * sigma));
            acc  += texture(InSampler, texCoord + BlurDir * o * PixelSize) * w;
            acc  += texture(InSampler, texCoord - BlurDir * o * PixelSize) * w;
            wsum += 2.0 * w;
        }
        fragColor = acc / wsum;
        return;
    }

    // ── Copy / denoise + composite the near-field ────────────────────────────────────
    if (BlurDir.x < 0.5) {
        float d = linearDepth(texture(DepthSampler, texCoord).r);
        float c = max(computeCoc(d) - 1.5, 0.0);
        c = max(c, distantHazeFloor(d));
        float sc = c;
        // Only pixels that are THEMSELVES defocused get softened.
        //
        // This used to also soften any SHARP pixel whose neighbours were blurred, box-blurring
        // it by up to 21x21. That is backwards optically: a lens does not smear an in-focus
        // surface because something out of focus happens to lie next to it — the defocused
        // object spreads OVER the sharp one, which stays sharp underneath. The result was a
        // halo of smeared background hugging every defocused silhouette, which is the
        // unnatural boundary. Spreading is the near-field layer's job, and it composites with
        // a real coverage alpha further down; doing it here as well was double-dipping.
        bool soften = (c >= 2.0);
        vec3 dofCol;
        if (!soften) {
            dofCol = texture(InSampler, texCoord).rgb;
        } else {
            // Gaussian-weighted, not a flat box. An unweighted square kernel gives every
            // sample in a 21x21 block the same say, so a bright or dark neighbour lands at
            // full strength right out to the corners — the smeared, streaky "wet paint"
            // texture. Weighting by distance keeps the same radius while letting the centre
            // dominate, which reads as defocus rather than as smudging.
            // Deliberately gentle. This is a DENOISE of the gather's sampling residue, not a
            // second depth-of-field blur — but it was sized like one (radius to 10 px,
            // scaling hard with CoC), and a Gaussian laid over a disc gather dissolves
            // exactly the disc character the gather just produced. That stacked smear is the
            // remaining "にじみ". Halving the radius trades a little residual grain, which
            // the raised sample count below pays back, for bokeh that keeps its shape.
            // Scale by how starved the gather was here, not by how blurred the pixel is.
            // Sizing this off CoC alone smoothed every defocused pixel equally, including the
            // ones the gather had resolved perfectly well — which is bokeh thrown away for
            // nothing. Where confidence is high this collapses to no denoise at all.
            float starved = 1.0 - texture(InSampler, texCoord).a;
            float rad   = clamp(sc * 0.16, 1.0, 8.0) * starved;
            float sigma = max(rad * 0.5, 0.5);
            if (rad < 0.75) { fragColor = vec4(texture(InSampler, texCoord).rgb, 1.0); return; }
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
            dofCol = sum / wsum;
        }
        // Composite the big-blurred near-field foreground over the scene. The under-layer
        // must be the BACKGROUND pulled in from outside the silhouette (bgc), not the sharp
        // foreground, so the translucent foreground edge dissolves into the scene behind it.
        // Outside the foreground (fg.a→0) we keep the full-res gather result (sharp subject +
        // far bokeh); the mix ramps to the filled background as foreground coverage rises.
        // The low-res near-field layer is optional. Every boundary artefact this shader has
        // had traced back to it: it selects foreground by thresholding CoC, and that threshold
        // draws a contour across any surface whose CoC sweeps through it — a hard line in
        // screen space that follows nothing in the scene. Widening the band traded the line
        // for washed-out colour; narrowing it traded the colour back for a sharper line.
        // There is no setting that removes it, because the seam is the mechanism.
        //
        // The gather below it no longer needs the help: it sizes its radius to the local blur,
        // feathers its disc edge, and reports its own sampling confidence. Leaving it to do
        // the whole job costs the "close foreground dissolves with no edge at all" look, and
        // removes the seam, the milky fill and the low-resolution blocking with it.
        if (NearLayer == 0) {
            fragColor = vec4(dofCol, 1.0);
            return;
        }

        vec4  fgv = texture(NearSampler, texCoord);
        vec4  bgv = texture(BgSampler,  texCoord);
        float fga = clamp(fgv.a, 0.0, 1.0);
        float bga = clamp(bgv.a, 0.0, 1.0);

        // Un-premultiply the fill, but floor the divisor well above zero. Deep inside a
        // silhouette almost no background was gathered, and dividing by an alpha of ~1e-3
        // multiplied the handful of samples that did land there by up to a thousand —
        // that amplification was the source of the colour blotches.
        vec3 bgc = bgv.rgb / max(bgv.a, 0.05);

        // Trust the fill in proportion to how much foreground is actually here AND how much
        // background the fill actually captured. The previous weight was fga * 3.0, so a
        // pixel only a third covered by foreground had the real scene replaced outright by
        // the wide-Gaussian fill. That veil reached far past the silhouette and averaged the
        // colour underneath it flat — the milky halo and the wrong colours around a
        // defocused foreground.
        float fillW = fga * smoothstep(0.0, 0.25, bga);
        vec3  under = mix(dofCol, bgc, fillW);
        fragColor = vec4(fgv.rgb + under * (1.0 - fga), 1.0);
        return;
    }

    // ── Gather pass (3-layer disc bokeh): main → aux ─────────────────────────────────
    vec4  centre = texture(InSampler, texCoord);
    float depthM = linearDepth(texture(DepthSampler, texCoord).r);
    float cocP   = max(computeCoc(depthM) - 1.5, 0.0);
    cocP = max(cocP, distantHazeFloor(depthM));

    // Coarse scan for neighbours whose own disc is wide enough to reach this pixel. It yields
    // two things: whether a nearer, defocused neighbour blooms over us (hasNearFg), and how
    // far out anything that contributes actually lives (reachR).
    bool  hasNearFg = false;
    float reachR    = 0.0;
    for (int k = 0; k < 16; k++) {
        float a = float(k) * (TWO_PI / 16.0);
        vec2 dir = vec2(cos(a), sin(a));
        for (int s = 1; s <= 6; s++) {
            // Quadratic radial spacing, so the rings crowd near the centre. Even steps put
            // the innermost ring at MaxBlurPx/5 — 24 px at f/1.4 — and a foreground whose CoC
            // was smaller than that was never detected at all: reachR stayed 0, the gather
            // shrank to the background's own tiny CoC, and the foreground never scattered
            // outward. Its silhouette then kept the geometry it has in focus, corners and
            // all, when a defocused corner should round off to an arc of its CoC.
            float t  = float(s) / 6.0;
            float rr = MaxBlurPx * t * t;
            float sd = linearDepth(texture(DepthSampler, texCoord + dir * rr * PixelSize).r);
            float sc2 = max(computeCoc(sd) - 1.5, 0.0);
            if (sc2 >= rr - 1.0) {
                reachR = max(reachR, min(sc2, MaxBlurPx));
                if (sd < depthM - 0.5 && sd < gFocus) hasNearFg = true;
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
    // 120 px disc when the pixel's own CoC is 3 px puts, on average, 160 * (3/120)^2 ≈ 0.1
    // samples inside the radius that can contribute. The colour was decided by whether one
    // random tap happened to land, which is grain by construction. Matching the radius to the
    // content puts every tap inside the disc, and the variance collapses.
    float gatherR       = clamp(max(cocP, reachR), 1.0, MaxBlurPx);
    float areaPerSample = gatherR * gatherR / float(SAMPLES);
    vec2 ntile = floor(gl_FragCoord.xy / NOISE_SIZE);
    vec2 lc    = fract(gl_FragCoord.xy / NOISE_SIZE);
    vec3 th    = hash32(ntile);
    if (th.x > 0.5) lc.x = 1.0 - lc.x;
    if (th.y > 0.5) lc.y = 1.0 - lc.y;
    if (th.z > 0.5) lc = lc.yx;
    lc = fract(lc + hash22(ntile + 19.7));
    float rot  = texture(NoiseSampler, lc).r * TWO_PI;

    // How many taps actually landed inside a contributing disc. When the gather has to be
    // sized for a big neighbour while this pixel's own CoC is small, most taps fall outside
    // everything and the result rests on the few that did not — visible as grain hugging
    // depth discontinuities. Carried out in alpha so the copy pass can denoise exactly the
    // pixels that were starved, and leave well-sampled bokeh untouched.
    float contrib = 0.0;

    vec3 nearC = vec3(0.0); float nearA = 0.0;
    vec3 focC  = vec3(0.0); float focA  = 0.0;
    vec3 farC  = vec3(0.0); float farA  = 0.0;

    {
        float w  = areaPerSample / max(cocP * cocP, 0.25);
        float fw = smoothstep(3.0, 0.5, cocP);
        focC += centre.rgb * w * fw; focA += w * fw;
        float bw = w * (1.0 - fw);
        if (bw > 0.0) {
            if (depthM < gFocus) { nearC += centre.rgb * bw; nearA += bw; }
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
        if (sCoc < 0.5) continue;

        // Soft disc edge. This test used to be `if (r > sCoc) continue;` — a binary in/out.
        // Whether a neighbour contributes then flipped abruptly as the gather radius crossed
        // that neighbour's own CoC, so the SET of contributing samples changed discontinuously
        // from pixel to pixel and the boundaries between those sets showed up as flat patches
        // — the painterly, brush-stroke look. Feathering membership over a one-pixel band
        // makes the same disc, with its edge antialiased instead of quantised.
        float edge = smoothstep(sCoc + 0.5, sCoc - 0.5, r);
        if (edge <= 0.0) continue;

        contrib   += edge;
        vec3  sCol = texture(InSampler, sc).rgb;
        float w    = areaPerSample / max(sCoc * sCoc, 1.0) * edge;
        if (sDepthM < gFocus) { nearC += sCol * w; nearA += w; }
        else                     { farC  += sCol * w; farA  += w; }
    }

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
