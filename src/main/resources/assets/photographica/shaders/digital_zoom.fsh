#version 150

// Drone digital zoom (see LensKind#digitalZoomSoftenPx): each of the drone's two sensors only
// resolves full detail at its own native focal length, so any longer focal length is a crop +
// upscale — N source pixels stretched over N*ratio destination pixels.
//
// The render itself already framed the shot at the true focal length, so this pass does NOT
// crop or magnify anything: it only throws detail away, reconstructing the frame from a
// coarser sample grid. Reconstruction is BILINEAR, not nearest — a real upscale goes soft and
// mushy, it does not turn into visible mosaic blocks, and nearest-neighbour here read as a
// pixelation filter rather than a camera limitation.
//
// Two passes, both full-res (reading and writing the SAME texture in one draw is a feedback
// loop with undefined results, same reasoning evf_peaking.fsh already documents):
//   0 = soften: mainTex -> aux
//   1 = plain copy: aux -> mainTex

uniform sampler2D InSampler;
uniform int   Pass;
uniform vec2  PixelSize; // 1/framebuffer size
uniform float BlockPx;   // destination pixels per genuine source pixel; <= 1 means no loss

in vec2 texCoord;
out vec4 fragColor;

void main() {
    if (Pass == 1) {
        fragColor = texture(InSampler, texCoord);
        return;
    }
    if (BlockPx <= 1.0) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // Sample grid coarser than the framebuffer by exactly the upscale ratio: grid points sit
    // at integer coordinates in this space, and everything between them is interpolated rather
    // than genuinely resolved — which is precisely the detail a real digital zoom lacks.
    vec2 cell = PixelSize * BlockPx;
    vec2 g  = texCoord / cell - 0.5;
    vec2 gi = floor(g);
    vec2 f  = g - gi;
    // Smoothstep instead of raw linear: bilinear alone leaves faint diamond-shaped creases
    // along the grid at high ratios, which reads as an artificial filter; easing the weights
    // hides the grid and leaves plain softness.
    f = f * f * (3.0 - 2.0 * f);

    vec2 uv00 = (gi + 0.5) * cell;
    vec2 uv10 = uv00 + vec2(cell.x, 0.0);
    vec2 uv01 = uv00 + vec2(0.0, cell.y);
    vec2 uv11 = uv00 + cell;

    vec4 c0 = mix(texture(InSampler, uv00), texture(InSampler, uv10), f.x);
    vec4 c1 = mix(texture(InSampler, uv01), texture(InSampler, uv11), f.x);
    fragColor = mix(c0, c1, f.y);
}
