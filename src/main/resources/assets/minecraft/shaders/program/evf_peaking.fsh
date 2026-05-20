#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;

in vec2 texCoord;
in vec2 sampleStep;

out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 px = 1.0 / InSize;

    float tl = luma(texture(DiffuseSampler, texCoord + px * vec2(-1.0, -1.0)).rgb);
    float tm = luma(texture(DiffuseSampler, texCoord + px * vec2( 0.0, -1.0)).rgb);
    float tr = luma(texture(DiffuseSampler, texCoord + px * vec2( 1.0, -1.0)).rgb);
    float ml = luma(texture(DiffuseSampler, texCoord + px * vec2(-1.0,  0.0)).rgb);
    float mr = luma(texture(DiffuseSampler, texCoord + px * vec2( 1.0,  0.0)).rgb);
    float bl = luma(texture(DiffuseSampler, texCoord + px * vec2(-1.0,  1.0)).rgb);
    float bm = luma(texture(DiffuseSampler, texCoord + px * vec2( 0.0,  1.0)).rgb);
    float br = luma(texture(DiffuseSampler, texCoord + px * vec2( 1.0,  1.0)).rgb);

    float gx = (-tl + tr) + 2.0 * (-ml + mr) + (-bl + br);
    float gy = (-tl - 2.0 * tm - tr) + (bl + 2.0 * bm + br);
    float edge = sqrt(gx * gx + gy * gy);

    vec4 color = texture(DiffuseSampler, texCoord);

    const float threshold = 0.10;
    if (edge > threshold) {
        float t = clamp((edge - threshold) * 6.0, 0.0, 1.0);
        // Safelight EMBER colour (#E08A3C ≈ vec3(0.878, 0.541, 0.235))
        vec3 peakColor = vec3(0.878, 0.541, 0.235);
        color.rgb = mix(color.rgb, peakColor, t * 0.80);
    }

    fragColor = color;
}
