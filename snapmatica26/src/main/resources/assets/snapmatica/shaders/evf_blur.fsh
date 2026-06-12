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

float computeCoc(float depth) {
    float r = depth / FocusDist;
    float coc;
    if (depth <= FocusDist) {
        coc = (1.0 - r) * MaxBlurPx;
    } else {
        coc = ((r - 1.0) / r) * MaxBlurPx;
    }
    return clamp(coc, 0.0, MaxBlurPx);
}

void main() {
    float rawD = texture(DepthSampler, texCoord).r;
    float depth = linearDepth(rawD);

    float coc = computeCoc(depth);

    // Below 0.5px CoC the blur is imperceptible — output sharp pixel
    if (coc < 0.5) {
        fragColor = texture(InSampler, texCoord);
        return;
    }

    // Sigma without the artificial 1.0 floor for smoother transitions
    float sigma = max(coc * 0.45, 0.1);
    int rad = min(int(ceil(coc)), 32);

    vec4 col = vec4(0.0);
    float totalW = 0.0;
    for (int i = -rad; i <= rad; i++) {
        vec2 sampleCoord = texCoord + BlurDir * float(i) * PixelSize;

        float fi = float(i);
        float gaussW = exp(-fi * fi / (2.0 * sigma * sigma));

        // Depth-aware weight: reduce contribution of sharper/closer samples
        // so foreground objects don't leak bleeding into blurred backgrounds.
        // Only pay the extra texture read when the blur is large enough to matter.
        float cocWeight = 1.0;
        if (coc > 2.0) {
            float sampleD = texture(DepthSampler, sampleCoord).r;
            float sampleCoc = computeCoc(linearDepth(sampleD));
            cocWeight = clamp(sampleCoc / coc, 0.15, 1.0);
        }

        col += texture(InSampler, sampleCoord) * gaussW * cocWeight;
        totalW += gaussW * cocWeight;
    }
    fragColor = col / totalW;
}
