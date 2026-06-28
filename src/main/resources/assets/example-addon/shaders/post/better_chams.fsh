#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;
uniform sampler2D ImageSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 params      = texture(ParamsSampler, vec2(0.5));
    float fillEnabled  = params.r;
    float fillOpacity  = params.g;
    float bloomEnabled = params.b;

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

    // Soft bloom halo (Smooth cone blur, radius=12, step=1)
    float alphaSum = 0.0;
    vec3  rgbSum   = vec3(0.0);
    float w = 12.0;
    
    for (float x = -w; x <= w; x += 1.0) {
        for (float y = -w; y <= w; y += 1.0) {
            float dist = sqrt(x*x + y*y);
            if (dist <= w) {
                float weight = 1.0 - (dist / w);
                vec4 s = texture(InSampler, texCoord + oneTexel * vec2(x, y));
                alphaSum += s.a * weight;
                rgbSum   += s.rgb * (s.a * weight);
            }
        }
    }

    float blurStrength = clamp(alphaSum / 8.0, 0.0, 1.0);
    if (blurStrength == 0.0) discard;

    vec3 glowColor = (alphaSum > 0.0) ? rgbSum / alphaSum : vec3(1.0);
    fragColor = vec4(glowColor, blurStrength);
}
