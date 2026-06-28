#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform ChamsFill {
    float fillEnabled;
    float fillOpacity;
    float bloomEnabled;
};

uniform sampler2D InSampler;
uniform sampler2D ImageSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / InSize;
    vec4 center = texture(InSampler, texCoord);

    if (center.a > 0.0) {
        if (fillEnabled > 0.5) {
            vec4 img = texture(ImageSampler, texCoord);
            fragColor = vec4(img.rgb, img.a * fillOpacity);
        } else {
            discard;
        }
        return;
    }

    // 1px crisp edge
    for (int dx = -1; dx <= 1; ++dx) {
        for (int dy = -1; dy <= 1; ++dy) {
            if (dx == 0 && dy == 0) continue;
            vec4 nb = texture(InSampler, texCoord + oneTexel * vec2(float(dx), float(dy)));
            if (nb.a > 0.0) {
                if (bloomEnabled > 0.5) {
                    fragColor = vec4(nb.rgb, 1.0);
                    return;
                }
            }
        }
    }

    if (bloomEnabled < 0.5) discard;

    // Soft bloom halo (box blur, radius=6, step=3)
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
