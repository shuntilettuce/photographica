#version 150

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;  // non-linear depth [0,1] from scene framebuffer
uniform vec2 BlurDir;            // (1,0) for H pass, (0,1) for V pass
uniform vec2 PixelSize;          // (1/fbW, 1/fbH)
uniform float FocusDist;         // focus distance in blocks
uniform float MaxBlurPx;         // max blur radius in framebuffer pixels
uniform float Near;              // near clip plane in blocks
uniform float Far;               // far clip plane in blocks

in vec2 texCoord;
out vec4 fragColor;

float linearDepth(float d) {
    float ndc = 2.0 * d - 1.0;
    return 2.0 * Near * Far / (Far + Near - ndc * (Far - Near));
}

void main() {
    float rawD = texture(DepthSampler, texCoord).r;
    float depth = linearDepth(rawD);

    float r = depth / FocusDist;
    float coc;
    if (depth <= FocusDist) {
        coc = (1.0 - r) * MaxBlurPx;
    } else {
        coc = ((r - 1.0) / r) * MaxBlurPx;
    }
    coc = clamp(coc, 0.0, MaxBlurPx);

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
