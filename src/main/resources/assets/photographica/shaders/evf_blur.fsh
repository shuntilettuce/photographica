#version 150

uniform sampler2D InSampler;
uniform vec2 BlurDir;    // (1,0) for H pass, (0,1) for V pass
uniform float Radius;    // blur radius in framebuffer pixels
uniform vec2 PixelSize;  // (1/fbW, 1/fbH)

in vec2 texCoord;
out vec4 fragColor;

void main() {
    if (Radius < 0.5) {
        fragColor = texture(InSampler, texCoord);
        return;
    }
    int r = int(ceil(Radius));
    r = min(r, 32); // cap to avoid very long loops
    float sigma = max(Radius * 0.5, 1.0);
    vec4 col = vec4(0.0);
    float totalW = 0.0;
    for (int i = -r; i <= r; i++) {
        float fi = float(i);
        float w = exp(-fi * fi / (2.0 * sigma * sigma));
        col += texture(InSampler, texCoord + BlurDir * fi * PixelSize) * w;
        totalW += w;
    }
    fragColor = col / totalW;
}
