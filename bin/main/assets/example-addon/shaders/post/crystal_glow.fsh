#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / InSize;
    vec4 center = texture(InSampler, texCoord);

    if (center.a > 0.0) {
        // Outline-only: discard filled crystal interior
        discard;
    }

    // 1px crisp edge: if any immediate neighbor is inside silhouette, emit solid pixel
    for (int dx = -1; dx <= 1; ++dx) {
        for (int dy = -1; dy <= 1; ++dy) {
            if (dx == 0 && dy == 0) continue;
            vec4 nb = texture(InSampler, texCoord + oneTexel * vec2(float(dx), float(dy)));
            if (nb.a > 0.0) {
                fragColor = vec4(nb.rgb, 1.0);
                return;
            }
        }
    }

    // Soft bloom halo: 2D box blur over crystal silhouette
    // radius=6, step=3 -> w=18 -> 13 steps/axis -> 169 samples
    // normalization = ((6^2+6)*4) = 168; glowMultiplier = 1.5 -> divide by 112.0
    float alphaSum = 0.0;
    vec3  rgbSum   = vec3(0.0);
    float stepSize = 3.0;
    float w        = 18.0;
    for (float x = -w; x <= w; x += stepSize) {
        for (float y = -w; y <= w; y += stepSize) {
            vec4 s = texture(InSampler, texCoord + oneTexel * vec2(x, y));
            alphaSum += s.a;
            rgbSum   += s.rgb * s.a;
        }
    }

    float blurStrength = clamp(alphaSum / 112.0, 0.0, 1.0);
    if (blurStrength == 0.0) discard;

    vec3 glowColor = (alphaSum > 0.0) ? rgbSum / alphaSum : vec3(1.0);
    fragColor = vec4(glowColor, blurStrength);
}
