#version 150

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // non-linear depth [0,1] from scene framebuffer
uniform sampler2D NoiseSampler;  // 128x128 blue-noise dither (void-and-cluster), GL_REPEAT
uniform sampler2D NearSampler;   // low-res, big-blurred premultiplied FOREGROUND layer
uniform sampler2D BgSampler;     // low-res, big-blurred BACKGROUND (foreground holes filled)
uniform int   Pass;              // 0 = gather/copy, 1 = extract fg, 2 = blur, 3 = extract bg
uniform vec2 BlurDir;            // gather/copy: .x>=0.5 gather, <0.5 copy.  blur: H=(1,0) V=(0,1)
uniform vec2 PixelSize;          // 1/texW, 1/texH of the CURRENT render target
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

// Depth of field in two cooperating parts:
//  • A per-pixel 3-layer disc GATHER (NEAR / FOCUS / FAR) handles the in-focus subject and
//    the background bokeh, and the near→far occlusion that softens a foreground silhouette.
//  • A separate LOW-RES NEAR-FIELD layer handles heavily out-of-focus FOREGROUND: it is
//    extracted premultiplied, blurred huge & cheap at 1/4 resolution (so a close foreground
//    DISSOLVES with no defined edge, the way a real fast/long lens does), then composited
//    back over the gather result with its blurred alpha.
const int   SAMPLES      = 96;
const float GOLDEN_ANGLE = 2.39996323;
const float TWO_PI       = 6.28318531;
#define NOISE_SIZE 128.0

float linearDepth(float d) {
    float ndc = 2.0 * d - 1.0;
    return 2.0 * Near * Far / (Far + Near - ndc * (Far - Near));
}

// Physically-based thin-lens circle of confusion, in framebuffer pixels.
float computeCoc(float depthM) {
    depthM = max(depthM, 0.05);
    float fmm = FocalLenMm;
    float cocMM;
    if (FocusDist >= 99999.0) {
        cocMM = (fmm * fmm) / (Aperture * depthM * DofScale);
    } else {
        float s1mm = FocusDist * DofScale;
        float denom = Aperture * max(s1mm - fmm, 1.0);
        cocMM = (fmm * fmm) * abs(depthM - FocusDist) / (depthM * denom);
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
        // Only route GENUINELY heavily-blurred foreground into the low-res near-field; a
        // mildly defocused mid-ground stays in the full-res gather (pushing it through the
        // 1/4-res buffer would mush it into low-res blocks). Threshold is absolute CoC px, so
        // it disengages on its own at narrow apertures where the blur is small anyway.
        float fg  = (d < FocusDist) ? smoothstep(30.0, 70.0, coc) : 0.0;
        float a   = (Pass == 1) ? fg : (1.0 - fg);
        // Box-average the COLOUR over the near-texel's full-res footprint. Point-sampling
        // high-frequency terrain at 1/4 res aliases into coarse blotches that the blur then
        // smears around (the "mosaic / べっちゃり"); a proper box downsample removes it.
        vec3 col = vec3(0.0);
        for (int sy = 0; sy < 4; sy++)
            for (int sx = 0; sx < 4; sx++) {
                vec2 o = (vec2(float(sx), float(sy)) - 1.5) * 0.25 * PixelSize;
                col += texture(InSampler, texCoord + o).rgb;
            }
        col *= (1.0 / 16.0);
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
    if (Pass == 2 || Pass == 4) {
        float sigma;
        if (Pass == 2) {
            float d   = linearDepth(texture(DepthSampler, texCoord).r);
            float coc = max(computeCoc(d) - 1.5, 0.0);
            // ∝ CoC, with a small floor so the foreground also feathers OUTWARD past its
            // silhouette a little (holes just outside still gather some foreground) — the
            // edge dissolves on both sides, not only inward.
            sigma = max(coc * 0.12, 4.0);       // low-res texels (coc is full-res px)
        } else {
            sigma = 16.0;
        }
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
        c = max(c, smoothstep(200.0, 600.0, d) * 5.0);
        float sc = c;
        bool soften = (c >= 2.0);
        if (!soften) {
            // Sharp pixel on the in-focus subject's SILHOUETTE (a neighbour is clearly out
            // of focus) → soften so the outline feathers into the bokeh. Interior sharp
            // pixels are copied through untouched.
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
        vec3 dofCol;
        if (!soften) {
            dofCol = texture(InSampler, texCoord).rgb;
        } else {
            int rad = int(clamp(sc * 0.16, 2.0, 10.0));   // 5x5 … 21x21, scales with blur
            vec3 sum = vec3(0.0);
            for (int dy = -rad; dy <= rad; dy++)
                for (int dx = -rad; dx <= rad; dx++)
                    sum += texture(InSampler, texCoord + vec2(float(dx), float(dy)) * PixelSize).rgb;
            dofCol = sum / float((2 * rad + 1) * (2 * rad + 1));
        }
        // Composite the big-blurred near-field foreground over the scene. The under-layer
        // must be the BACKGROUND pulled in from outside the silhouette (bgc), not the sharp
        // foreground, so the translucent foreground edge dissolves into the scene behind it.
        // Outside the foreground (fg.a→0) we keep the full-res gather result (sharp subject +
        // far bokeh); the mix ramps to the filled background as foreground coverage rises.
        vec4 fgv = texture(NearSampler, texCoord);
        vec4 bgv = texture(BgSampler,  texCoord);
        vec3 bgc = bgv.rgb / max(bgv.a, 1e-3);          // un-premultiply → filled bg colour
        vec3 under = mix(dofCol, bgc, clamp(fgv.a * 3.0, 0.0, 1.0));
        fragColor = vec4(fgv.rgb + under * (1.0 - clamp(fgv.a, 0.0, 1.0)), 1.0);
        return;
    }

    // ── Gather pass (3-layer disc bokeh): main → aux ─────────────────────────────────
    vec4  centre = texture(InSampler, texCoord);
    float depthM = linearDepth(texture(DepthSampler, texCoord).r);
    float cocP   = max(computeCoc(depthM) - 1.5, 0.0);
    cocP = max(cocP, smoothstep(200.0, 600.0, depthM) * 5.0);   // distant-haze floor

    bool hasNearFg = false;
    if (FocusDist < 99999.0) {
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
    vec2 ntile = floor(gl_FragCoord.xy / NOISE_SIZE);
    vec2 lc    = fract(gl_FragCoord.xy / NOISE_SIZE);
    vec3 th    = hash32(ntile);
    if (th.x > 0.5) lc.x = 1.0 - lc.x;
    if (th.y > 0.5) lc.y = 1.0 - lc.y;
    if (th.z > 0.5) lc = lc.yx;
    lc = fract(lc + hash22(ntile + 19.7));
    float rot  = texture(NoiseSampler, lc).r * TWO_PI;

    vec3 nearC = vec3(0.0); float nearA = 0.0;
    vec3 focC  = vec3(0.0); float focA  = 0.0;
    vec3 farC  = vec3(0.0); float farA  = 0.0;

    {
        float w  = areaPerSample / max(cocP * cocP, 0.25);
        float fw = smoothstep(3.0, 0.5, cocP);
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
        if (sCoc < 0.5) continue;
        if (r > sCoc)   continue;

        vec3  sCol = texture(InSampler, sc).rgb;
        float w    = areaPerSample / max(sCoc * sCoc, 1.0);
        if (sDepthM < FocusDist) { nearC += sCol * w; nearA += w; }
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

    fragColor = (wsum > 0.001)
        ? vec4((nearCol * wNear + focCol * wFocus + farCol * wFar) / wsum, 1.0)
        : centre;
}
