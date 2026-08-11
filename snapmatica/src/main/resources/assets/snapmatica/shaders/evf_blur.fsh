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
uniform float DistortK;          // radial distortion: >0 barrel, <0 pincushion, 0 off
uniform float Aspect;            // fbW/fbH, so the distortion stays radially round
uniform int   DoGather;          // 0 = no defocus to compute; pass the scene straight through
uniform vec2  MotionRotPx;       // screen shift from camera ROTATION over one sample, in px
uniform vec3  MotionVelCam;      // camera TRANSLATION over one sample, camera space, in blocks
uniform float FocalPx;           // focal length in pixels, for projecting that translation

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
// Coverage is decided by counting how many taps land on the near field, which is a binomial
// trial: its error is sqrt(p(1-p)/N), largest at intermediate coverage and ZERO at full
// coverage. That single fact ties together everything the defocus can get wrong at the top of
// its range. The old 120 px ceiling on the circle of confusion pinned every heavy foreground to
// p = 1, so there was no noise to see — and no transparency either, which is why a leaf against
// the lens kept a findable outline. Lifting the ceiling makes the opacity right and lets the
// noise through; more samples buy it back; and any other correction that stops diluting the
// foreground's share (see the layer-pivot note in the composite) spends the same currency.
//
// This stays at 128, on measurement rather than on principle. Raising it to 192 was tried:
// on a white fence a metre and a half from a 35 mm at f/2, focused 20 blocks out — a shot
// someone would actually take — the grain is 0.41 levels either way, identical. It only earns
// anything at the far end, where the fence is 60 cm from the lens and the focus is 148 m
// (1.64 levels against 1.40), and it costs 30-60% of the whole pass to get there. Not a trade
// worth making for a configuration no photographer would set up.
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

// How far the FOCUS has to be for the far-stop behaviour to be fully in effect.
//
// The infinity-only rules used to switch on the instant the focus reached its sentinel value,
// which made racking out to the far stop end in a visible jolt: the sharpening ramp appeared
// and the haze floor vanished between one frame and the next, however smoothly the focus
// itself had travelled. Blending them in across the last part of the range makes the arrival
// continuous. The thin-lens term needs no such treatment — its finite form already converges
// on the infinity form as the focus distance grows.
const float INF_BLEND_BEGIN = 300.0;   // blocks
const float INF_BLEND_FULL  = 3000.0;  // blocks

/** 0 while focused on anything near, 1 once the lens is effectively at its far stop. */
float infinityBlend(float focusBlocks) {
    return smoothstep(INF_BLEND_BEGIN, INF_BLEND_FULL, focusBlocks);
}

// Bokeh character. RIM > 0 brightens the edge of the disc (under-corrected spherical
// aberration, "nervous"); 0 is a flat disc; negative brightens the centre instead.
const float BOKEH_RIM = 0.55;
// Strength of the cat's-eye clipping at the frame corners. 0 = perfectly round everywhere.
const float CATS_EYE  = 0.65;

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

/*
 * There is no atmospheric haze floor any more.
 *
 * It forced a minimum of 5 px of blur on anything past a few hundred blocks, ramped in over
 * 200..600 blocks and faded out as the focus approached its far stop. It was written to stand
 * in for aerial perspective and to hide LOD popping, and it is not either of those things: a
 * lens does not defocus a mountain for being far away, and haze reduces contrast rather than
 * resolution. Because the amount came from a constant instead of from the optics, it did not
 * move when the aperture did — stopped down to f/22, where the circle of confusion is 1.4 px
 * and the whole scene should be sharp, distant terrain still came through as mush.
 *
 * It also stayed invisible until the defocus was made self-consistent. Only the pixel being
 * written got the floor; when that same pixel was somebody's neighbour it did not, so no tap
 * ever cleared the disc test and the gather did nothing with it. Applying the floor everywhere
 * a circle of confusion is asked for — correct in itself — switched on a feature that had been
 * dormant since it was written, which is why this only appeared now.
 *
 * Its other half was worse. Multiplying by (1 - infinityBlend) tied the floor to the FOCUS
 * rather than to the subject, so racking out to infinity made distant terrain snap from blurred
 * to sharp with nothing in the scene having changed — very visible in video. Without it the far
 * field is one continuous function of depth: 0.41 px at a 200-block focus, 0.12 px at infinity.
 */


/**
 * Diameter of the Airy disc, in mm — the blur a perfect lens cannot avoid.
 *
 * <p>Stopping down trades defocus for diffraction. Light passing a small opening spreads, by
 * 2.44 * lambda * N for the first dark ring, so a narrow aperture softens the WHOLE frame,
 * in focus or not. It is why no real lens is at its sharpest wide open OR fully stopped down;
 * the peak sits a few stops in, and past roughly f/11 on this sensor size the image visibly
 * degrades. Without it, f/22 was simply the sharpest setting available, which is not a
 * trade-off any photographer would recognise.
 *
 * <p>550 nm, the middle of the visible band.
 */
float airyDiscMM() {
    return 2.44 * 0.00055 * Aperture;
}

/**
 * Radial lens distortion — the reason a 14 mm looks like a 14 mm.
 *
 * <p>A rectilinear lens cannot hold straight lines straight across a very wide field, so wide
 * angles bow them outward (barrel) and long lenses pinch them inward (pincushion, far weaker).
 * It is the most recognisable signature an ultra-wide has, and without it every focal length
 * differed only in how much of the scene fitted in the frame.
 *
 * <p>Inverse mapping: given the pixel being written, this returns where to read from. The
 * (1 + K) divisor renormalises so the frame corner maps to the frame corner — otherwise a
 * barrel would read from beyond the source and leave the corners black, exactly the crop a
 * real body hides by making the sensor smaller than the image circle.
 *
 * <p>Aspect correction keeps the field round: without it the distortion would be an ellipse
 * stretched with the window.
 */
/**
 * Screen-space smear for one sample of a long exposure, in pixels.
 *
 * <p>A frame is an instant, but the sample it provides has to stand for the whole slice of time
 * until the next one. The accumulator can take at most one sample per rendered frame, so a
 * 1/15 s exposure at 60 fps is built from four instants — and four instants averaged is a
 * multiple exposure, not motion blur. Worse, the number of them depends on frame rate, so the
 * same pan looks different on a different machine.
 *
 * <p>Smearing each sample across the gap it represents fills the space between the ghosts. Two
 * contributions: turning the camera moves the whole frame uniformly, while moving it sideways
 * moves near things faster than far ones — hence the division by depth, which is the parallax
 * that makes translation read as speed rather than as a pan.
 */
vec2 motionSmearPx(vec2 uv, float depthM) {
    vec2 m = MotionRotPx;
    if (dot(MotionVelCam, MotionVelCam) > 1e-9) {
        // Pixel offset from the frame centre, needed for the forward-motion term: moving
        // ahead pushes everything radially outward, and the rate grows with distance from
        // the centre of expansion.
        vec2 p = (uv - 0.5) / PixelSize;
        float z = max(depthM, 0.25);
        m += (FocalPx * MotionVelCam.xy + p * MotionVelCam.z) / z;
    }
    return m;
}

vec2 lensDistort(vec2 uv) {
    if (abs(DistortK) < 1e-4) return uv;
    vec2 p = (uv - 0.5) * 2.0;          // -1..1
    p.x *= Aspect;                       // work in a square space
    float r2 = dot(p, p);
    // Normalised so r = 1 is the frame corner, whatever the aspect.
    float corner = 1.0 + Aspect * Aspect;
    float k = DistortK / corner;         // scale K into this r2 range
    p *= (1.0 + k * r2) / (1.0 + k * corner);
    p.x /= Aspect;
    return p * 0.5 + 0.5;
}

/**
 * Averages along the smear vector, centred, so the sample spreads both ways in time.
 *
 * <p>The tap count follows the LENGTH. At a fixed seven, a long smear left its taps far apart
 * and each one became a ghost of its own — so the very case the smear exists for, a low frame
 * rate during a long exposure moving the camera a long way between samples, was the case it
 * failed at. Reproducing the multiple exposure it was meant to remove, at finer spacing.
 *
 * <p>Roughly one tap per pixel and a half of travel, which is dense enough that neighbouring
 * taps overlap.
 */
vec3 smearSample(vec2 uv, vec2 smearPx) {
    float len  = length(smearPx);
    int   taps = int(clamp(len / 1.5, 4.0, 48.0));
    vec3  sum  = vec3(0.0);
    for (int i = 0; i < taps; i++) {
        float t = (float(i) + 0.5) / float(taps) - 0.5;   // -0.5 .. +0.5, evenly spread
        sum += texture(InSampler, uv + smearPx * t * PixelSize).rgb;
    }
    return sum / float(taps);
}

// Physically-based thin-lens circle of confusion, in framebuffer pixels.
float computeCoc(float depthM) {
    depthM = max(depthM, 0.05);
    float fmm = FocalLenMm;
    float cocMM;
    if (gFocus >= 99999.0) {
        cocMM = (fmm * fmm) / (Aperture * depthM * DofScale);
    } else {
        float s1mm = gFocus * DofScale;
        float denom = Aperture * max(s1mm - fmm, 1.0);
        cocMM = (fmm * fmm) * abs(depthM - gFocus) / (depthM * denom);
    }
    // Distant-subject sharpening, faded in with the focus rather than tied to the sentinel.
    cocMM *= 1.0 - infinityBlend(gFocus)
                 * smoothstep(INF_SHARP_BEGIN, INF_SHARP_FULL, depthM);
    // Defocus and diffraction add in quadrature — they are independent blurs, so the spot is
    // the root of the sum of squares rather than of the sum. At a wide aperture the defocus
    // term swamps the other; stopped right down, diffraction is all that is left and sets a
    // floor nothing can be sharper than.
    float airy = airyDiscMM();
    cocMM = sqrt(cocMM * cocMM + airy * airy);
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
        // Distortion is applied HERE, as the last thing that touches the image, and by moving
        // where this pass READS from. The gather before it works in undistorted space, which is
        // right: the depth buffer is undistorted, so computing defocus against a warped colour
        // would mismatch the two.
        //
        // Every read in this block goes through srcUV — colour, depth and the denoise taps
        // alike. Distorting only some of them would tear the image along the boundary between
        // the paths, since the sharp and blurred branches would be sampling different geometry.
        vec2 srcUV = lensDistort(texCoord);
        float d = linearDepth(texture(DepthSampler, srcUV).r);
        float c = max(computeCoc(d) - 1.5, 0.0);
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
        // Motion smear applies whether or not the pixel is defocused: a subject held sharp by
        // depth of field still moved during the slice this sample stands for.
        vec2  smear    = motionSmearPx(srcUV, d);
        float smearLen = length(smear);

        vec3 dofCol;
        if (smearLen > 0.75) {
            // Smearing IS a denoise — it averages along a line, which is what the residual
            // grain needed anyway. Running the Gaussian on top would cost seven times the taps
            // to redo work the motion has already done.
            dofCol = smearSample(srcUV, smear);
        } else if (!soften) {
            dofCol = texture(InSampler, srcUV).rgb;
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
            float starved = 1.0 - texture(InSampler, srcUV).a;
            // Reach, and tap count, decoupled.
            //
            // This was a solid (2r+1)^2 kernel, which is what forced the radius to stop at 8 px:
            // 289 taps already, and 1089 at 16. The reach it could afford was the one thing it
            // needed most. A gather of N taps over a disc of radius R leaves its residue at a
            // spatial scale of about R/sqrt(N) — 31 px for a 350 px disc at 128 taps — so an
            // 8 px kernel cannot see the blotches it is there to remove, and the grain survives
            // exactly where the defocus is strongest. Raising the gather's own tap count instead
            // does work (384 taps measured the grain down from 2.2 levels to 1.25) and costs
            // 2.8x the whole pass, which is not a trade worth making for residue.
            //
            // What is being averaged here is a smooth field plus per-pixel independent noise, and
            // for that a sparse set of taps is as good as a solid one: 32 of them cut the noise
            // by the same root-N, introduce no structure of their own because the signal under
            // them has none at this scale, and cost a ninth of the old kernel at a third of
            // its reach. Sized off the gather's residue scale rather than a fixed ceiling.
            // Sized off the gather's residue scale — a disc of radius R sampled N times leaves
            // blotches about R/sqrt(N) across — and NOT off this pixel's own circle of
            // confusion, which is what it used. In a foreground's haze the pixel underneath is
            // barely defocused itself: the wall behind a fence had a 17 px disc, so the old
            // rule asked for 2.7 px of smoothing against blotches 31 px wide. The scale that
            // matters belongs to whatever is blooming over the pixel, and MaxBlurPx bounds it.
            float rad   = clamp(MaxBlurPx * 0.09, 1.0, 28.0) * starved;
            float sigma = max(rad * 0.55, 0.5);
            if (rad < 0.75) {
                fragColor = vec4(smearLen > 0.75 ? smearSample(srcUV, smear)
                                                 : texture(InSampler, srcUV).rgb, 1.0);
                return;
            }
            const int DENOISE_TAPS = 32;
            float drot = texture(NoiseSampler, fract(gl_FragCoord.xy / NOISE_SIZE)).r * TWO_PI;
            vec3  sum   = texture(InSampler, srcUV).rgb;
            float wsum  = 1.0;
            for (int j = 0; j < DENOISE_TAPS; j++) {
                float fj = float(j) + 0.5;
                float dr = sqrt(fj / float(DENOISE_TAPS)) * rad;
                float da = fj * GOLDEN_ANGLE + drot;
                vec2  o  = vec2(cos(da), sin(da)) * dr;
                float w  = exp(-dr * dr / (2.0 * sigma * sigma));
                sum  += texture(InSampler, srcUV + o * PixelSize).rgb * w;
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

        vec4  fgv = texture(NearSampler, srcUV);
        vec4  bgv = texture(BgSampler,  srcUV);
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
    // Nothing to defocus — but the copy pass still has to run, because it is what applies the
    // distortion. Hand it the scene unchanged rather than walk 96 depth taps per pixel to
    // rediscover that every circle of confusion is sub-pixel.
    if (DoGather == 0) { fragColor = vec4(centre.rgb, 1.0); return; }

    float depthM = linearDepth(texture(DepthSampler, texCoord).r);
    float cocP   = max(computeCoc(depthM) - 1.5, 0.0);

    // Coarse scan for neighbours whose own disc is wide enough to reach this pixel. It yields
    // two things: whether a nearer, defocused neighbour blooms over us (hasNearFg), and how
    // far out anything that contributes actually lives (reachR).
    const int SCAN_RINGS = 6;
    bool  hasNearFg = false;
    float reachR    = 0.0;
    for (int k = 0; k < 16; k++) {
        float a = float(k) * (TWO_PI / 16.0);
        vec2 dir = vec2(cos(a), sin(a));
        for (int s = 1; s <= SCAN_RINGS; s++) {
            // Quadratic radial spacing, so the rings crowd near the centre. Even steps put
            // the innermost ring at MaxBlurPx/5 — 24 px at f/1.4 — and a foreground whose CoC
            // was smaller than that was never detected at all: reachR stayed 0, the gather
            // shrank to the background's own tiny CoC, and the foreground never scattered
            // outward. Its silhouette then kept the geometry it has in focus, corners and
            // all, when a defocused corner should round off to an arc of its CoC.
            //
            float t  = float(s) / float(SCAN_RINGS);
            float rr = MaxBlurPx * t * t;
            float sd = linearDepth(texture(DepthSampler, texCoord + dir * rr * PixelSize).r);
            float sc2 = max(computeCoc(sd) - 1.5, 0.0);
            // One sample stands for the whole annulus between its ring and the next, so a hit
            // counts when the CoC found comes within half a ring gap of reaching here: the
            // surface it belongs to almost certainly has a point that much nearer. Without
            // this the ray had to land within a pixel of a silhouette's edge to see it, and
            // the outermost pair of rings sit 37 px apart — so an isolated leaf's haze was cut
            // off at a clean circle around 0.85 of where its own disc actually reaches, and
            // that circle is a visible edge in a frame that has no edge in it.
            //
            // The asymmetry is deliberate. A false negative costs the pixel its ENTIRE near
            // field — the gather collapses to the background's own CoC and no foreground
            // reaches it at all — while a false positive only widens the gather past what it
            // needed and spends some sampling density, because the per-tap disc test below
            // still decides what actually contributes. Dithering the scan per pixel was tried
            // instead and is strictly worse: it scatters the depth taps out of cache for a
            // 30-130% cost, and having no more information than before, it converts the same
            // missing reach into noise rather than recovering it.
            float slack = MaxBlurPx * t / float(SCAN_RINGS);
            if (sc2 >= rr - 1.0 - slack) {
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
    // Tap count follows the AREA being gathered, not a constant.
    //
    // SAMPLES is sized for the widest disc the optics can ask for; spent on a narrow one it is
    // pure waste — the atmospheric haze floor puts a 4 px disc on every distant pixel, and at
    // 128 taps that is two samples per pixel of the disc, over most of a landscape frame. One
    // tap per half pixel of disc area is already far denser than the signal, and the floor is
    // where it matters: below about 3.5 px of radius the count bottoms out at 24, which is
    // still more taps than the disc has pixels.
    int   nTaps         = int(clamp(gatherR * gatherR * 2.0, 24.0, float(SAMPLES)));
    float areaPerSample = gatherR * gatherR / float(nTaps);
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

    // Near-field COVERAGE, kept apart from nearA. Both count the same discs, but nearA carries
    // the bokeh shaping (rim profile, cat's-eye) that decides what the near field LOOKS like,
    // and coverage must not: the rim profile alone averages 1 + BOKEH_RIM/2 over a disc, so a
    // foreground would come out 27% more opaque than its own geometry says purely because the
    // bokeh has character. This one is the plain thin-lens integral — each source pixel spreads
    // its light over pi*CoC^2, so what lands here is sum(dA / CoC^2), which is a real alpha.
    float nearCov = 0.0;

    // Each layer carries a colour weight and a COVERAGE separately. Both count the same discs,
    // but the colour weight also carries the bokeh shaping (rim profile, cat's-eye) that decides
    // what the blur LOOKS like, and coverage must not: the rim profile alone averages
    // 1 + BOKEH_RIM/2 over a disc, so a foreground would come out 27% more opaque than its own
    // geometry says purely because the bokeh has character. Coverage is the plain thin-lens
    // integral — each source pixel spreads its light over pi*CoC^2, so what lands on this pixel
    // is sum(dA / CoC^2), which is a real alpha.

    // What can be seen PAST the near field, for the pixels where the gather finds nothing
    // behind at all. A sharp background contributes to no layer — its own CoC is under half a
    // pixel, so every tap on it fails the disc test — which is exactly the situation inside a
    // defocused foreground silhouette, and why that silhouette used to survive at full opacity
    // (see the composite at the end). The scene behind a leaf is genuinely absent from a single
    // rendered frame, but the aperture sees around the leaf, so the background just outside it
    // is the honest estimate.
    //
    // Weighted toward the nearest such samples rather than averaged flat, so a small
    // silhouette fills with its own surroundings instead of a wide grey mean. A nearest-VALUE
    // reconstruction (1/r^2, plus a dedicated close-range spiral to sample it properly) was
    // tried, on the reasoning that it agrees with the real background at the silhouette edge
    // by construction and so cannot step there. It is worse: over a textured backdrop the
    // nearest value is a high-contrast sample of the wrong phase, and it drags into visible
    // streaks. A neighbourhood average reads as the backdrop softening, which is the milder
    // failure.
    vec3  fillC = vec3(0.0); float fillW = 0.0;
    float fillFar  = max(depthM * 0.25, 0.5);   // "clearly behind", scaled with distance
    // Half weight at FILL_SOFT px. One falloff for every fill tap, near or far, so the
    // estimate adapts on its own: where real background sits a few pixels away — the whole
    // edge of any thin silhouette — those taps outweigh the rest and the reconstruction meets
    // the sharp background it abuts. Deep inside a wide occluder nothing is close, every tap
    // carries a similar small weight, and it degrades into the broad average that is all that
    // is available there.
    //
    // Tight, because what gives a reconstructed region away is not its colour but its TEXTURE.
    // Measured against a true thin-lens render of the same scene, the colour either side of a
    // silhouette already matches to half a level out of 255 — it is local contrast that steps,
    // and a wide average has none of it, so the foreground's outline reappears as a smooth
    // band across an otherwise detailed backdrop. Pulling the estimate in to the nearest few
    // pixels carries some of that detail inward and removes about half the step. A flat
    // backdrop, where a too-tight fill would show as streaking, is unchanged.
    const float FILL_SOFT = 2.0;

    {
        float w  = areaPerSample / max(cocP * cocP, 0.25);
        float fw = smoothstep(3.0, 0.5, cocP);
        focC += centre.rgb * w * fw; focA += w * fw;
        float bw = w * (1.0 - fw);
        if (bw > 0.0) {
            if (depthM < gFocus) { nearC += centre.rgb * bw; nearA += bw; nearCov += bw; }
            else                    { farC  += centre.rgb * bw; farA  += bw; }
        }
    }

    for (int i = 0; i < nTaps; i++) {
        float fi  = float(i) + 0.5;
        float r   = sqrt(fi / float(nTaps)) * gatherR;
        float ang = fi * GOLDEN_ANGLE + rot;
        vec2  sc  = texCoord + vec2(cos(ang), sin(ang)) * r * PixelSize;

        float sDepthM = linearDepth(texture(DepthSampler, sc).r);
        // The haze floor belongs here as much as it does on the centre pixel. It was applied
        // only there, so the same surface had one circle of confusion when it was the pixel
        // being written and a smaller one when it was somebody's neighbour — and since a
        // sample only contributes within its OWN disc, a hazed distant surface reached nothing,
        // not even itself. Its self-coverage then came out at a third of the 1 it should be,
        // and the composite renormalises against that: a dark post against bright sky came out
        // over twice as opaque as the lens gives, 12 px outside its own edge, which reads as a
        // dark fringe clinging to the silhouette. Any CoC in this shader has to be the same
        // function of depth wherever it is asked for.
        float sCoc    = max(computeCoc(sDepthM) - 1.5, 0.0);

        // Soft disc edge. This test used to be `if (r > sCoc) continue;` — a binary in/out.
        // Whether a neighbour contributes then flipped abruptly as the gather radius crossed
        // that neighbour's own CoC, so the SET of contributing samples changed discontinuously
        // from pixel to pixel and the boundaries between those sets showed up as flat patches
        // — the painterly, brush-stroke look. Feathering membership over a one-pixel band
        // makes the same disc, with its edge antialiased instead of quantised.
        float edge = (sCoc >= 0.5) ? smoothstep(sCoc + 0.5, sCoc - 0.5, r) : 0.0;

        // Collect what lies behind, for the fill described above. Restricted to the inner part
        // of the disc because the weight has fallen to a tenth by three fall-off lengths and
        // the outer taps are most of the disc — and skipped entirely when nothing sampled is
        // behind this pixel, which is every tap of an ordinary far-field bokeh, so the common
        // case pays nothing for it. Samples do not need to be defocused to qualify: a SHARP
        // background is precisely what has no disc of its own and so reaches no layer.
        bool wantFill = (r < gatherR * 0.75) && (sDepthM > depthM + fillFar);
        if (edge <= 0.0 && !wantFill) continue;

        vec3 sCol = texture(InSampler, sc).rgb;
        if (wantFill) {
            float fw2 = 1.0 / (r * r + FILL_SOFT * FILL_SOFT);
            fillC += sCol * fw2;
            fillW += fw2;
        }
        if (edge <= 0.0) continue;

        // Brightness across the disc, not a flat fill.
        //
        // A real bokeh ball is not evenly lit. Residual spherical aberration piles light up at
        // one end of the disc: uncorrected, the rim goes bright and the centre hollow (the
        // "nervous", outlined bokeh of a cheap lens); over-corrected, the centre is bright and
        // the edge falls away softly. Which one a lens does is most of what people mean by the
        // character of its bokeh, and a flat disc reads as neither — just a smear of the mean.
        //
        // Weighted toward the rim, mildly, and lifted at the very edge so the disc still has a
        // defined boundary. In a blocky scene this gradation is what the shape reads as, far
        // more than the polygon the blades would cut.
        float rn   = clamp(r / max(sCoc, 1e-3), 0.0, 1.0);
        float prof = 1.0 + BOKEH_RIM * rn * rn;
        // Mechanical vignetting — "cat's eye". Off-axis, the disc is no longer a full circle:
        // the barrel clips it into a lemon, progressively as it approaches the frame edge.
        // Clipping the sample on the side facing away from centre reproduces it.
        vec2  toEdge = (texCoord - vec2(0.5)) * 2.0;
        float offAxis = clamp(length(toEdge), 0.0, 1.0);
        vec2  radialDir = (offAxis > 1e-4) ? toEdge / max(length(toEdge), 1e-4) : vec2(0.0);
        vec2  sampDir   = vec2(cos(ang), sin(ang));
        float outward   = dot(sampDir, radialDir);          // +1 = away from frame centre
        float catsEye   = 1.0 - CATS_EYE * offAxis * offAxis * max(outward, 0.0) * rn;

        contrib   += edge;
        // Two weights from one disc: wc is the geometric coverage, w is that shaped into what
        // the bokeh looks like. Only the near field needs them separated — focus and far are
        // renormalised against each other below, so any uniform factor cancels there.
        float wc   = areaPerSample / max(sCoc * sCoc, 1.0) * edge;
        float w    = wc * prof * max(catsEye, 0.0);
        if (sDepthM < gFocus) { nearC += sCol * w; nearA += w; nearCov += wc; }
        else                     { farC  += sCol * w; farA  += w; }
    }

    // Close-range sampling for the fill. The gather's taps are spread evenly over its whole
    // disc, so the few pixels either side of a silhouette edge — the one place the
    // reconstruction has to agree with the real background — hold barely two of them at a
    // 100 px gather, and a falloff that favours the nearest taps means nothing if the nearest
    // taps are not there. A tight spiral costs 24 taps and is what gives the weighting above
    // something to prefer.
    //
    // Only for a pixel in FRONT of the focal plane that has a disc of its own. Everything else
    // either carries its own sharp colour into the focus layer, which outweighs any fill by
    // two orders of magnitude, or is background and needs no reconstructing — so neither side
    // of this test can draw a contour, because the fill is unused on both.
    if (depthM < gFocus && cocP >= 0.5) {
        for (int j = 0; j < 24; j++) {
            float fj = float(j) + 0.5;
            float r2 = sqrt(fj / 24.0) * min(gatherR, 32.0);
            float a2 = fj * GOLDEN_ANGLE + rot;
            vec2  c2 = texCoord + vec2(cos(a2), sin(a2)) * r2 * PixelSize;
            float d2 = linearDepth(texture(DepthSampler, c2).r);
            if (d2 <= depthM + fillFar) continue;
            float w2 = 1.0 / (r2 * r2 + FILL_SOFT * FILL_SOFT);
            fillC += texture(InSampler, c2).rgb * w2;
            fillW += w2;
        }
    }

    vec3 nearCol = (nearA > 0.0) ? nearC / nearA : vec3(0.0);
    vec3 focCol  = (focA  > 0.0) ? focC  / focA  : vec3(0.0);
    vec3 farCol  = (farA  > 0.0) ? farC  / farA  : vec3(0.0);

    float na = clamp(nearCov, 0.0, 1.0);
    float fo = clamp(focA,  0.0, 1.0);
    float fr = clamp(farA,  0.0, 1.0);

    // Everything the gather resolved from BEHIND the near field, and how much of the aperture
    // that accounts for. Focus over far, renormalised against each other so a partially-sampled
    // background still reads as itself rather than as a wash.
    float underA   = clamp(fo + fr * (1.0 - fo), 0.0, 1.0);
    vec3  underCol = (underA > 1e-4)
                   ? (focCol * fo + farCol * fr * (1.0 - fo)) / (fo + fr * (1.0 - fo))
                   : vec3(0.0);

    // Did the gather see ANYTHING behind the near field? Deliberately a question with a bimodal
    // answer, not a deficit to be made up: underA is a biased estimator, but it collapses to
    // exactly zero in one situation — a sharp background has no disc, no tap on it clears the
    // membership test, and no layer receives it. That is the interior of a defocused foreground
    // silhouette and nothing else. Both sides of the mix below are estimates of the same
    // backdrop, so there is no foreground/background decision here for a seam to form along.
    float trust = smoothstep(0.01, 0.12, underA);
    if (fillW <= 0.0) trust = 1.0;   // no estimate to fall back on; leave the gather alone
    underCol = mix(fillC / max(fillW, 1e-4), underCol, trust);

    // The foreground-bokeh fix: `na` composites as an alpha instead of being divided back out.
    // A leaf a metre from a fast lens spreads over a disc hundreds of times its own area, so it
    // covers a few per cent of the aperture and belongs on screen as a few per cent. The gather
    // always computed that coverage correctly; the composite destroyed it by dividing the result
    // through by the sum of the very weights that carried it, so `nearCol * na / na` restored the
    // leaf at full opacity wherever a sharp background sat behind it and reached no layer.
    //
    // NOTE the known limitation this still carries. When the focus is far enough that a pixel's
    // own surface is itself "near", its self-weight lands in the same layer as whatever blooms
    // over it, uncapped — 225/16 against a foreground's 0.35 for a background 4 px out of
    // focus — so that foreground is diluted rather than composited over. Pivoting the layers on
    // the pixel's own depth and capping the self-weight at 1 fixes it and was measured to: the
    // opacity comes out right, and the grain triples, because the dilution had been hiding the
    // binomial error in the coverage count. Both together need more effective samples than a
    // point-sampled gather can pay for. See the note on prefiltering above SAMPLES.
    float uw     = mix(1.0, underA, trust);
    float wNear  = na;
    float wUnder = uw * (1.0 - na);
    float wsum   = wNear + wUnder;

    float confidence = clamp(contrib / (0.3125 * float(nTaps)), 0.0, 1.0);
    float covNoise   = sqrt(max(na * (1.0 - na), 0.0) / float(nTaps));
    float need       = max(1.0 - confidence, clamp(covNoise * 12.0, 0.0, 1.0));
    fragColor = (wsum > 0.001)
        ? vec4((nearCol * wNear + underCol * wUnder) / wsum, 1.0 - need)
        : vec4(centre.rgb, 1.0);
}
