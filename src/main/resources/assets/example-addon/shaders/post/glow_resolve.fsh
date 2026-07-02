#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;        // final blurred glow buffer (after 4 glow_pass iterations)
uniform sampler2D OriginalSampler;  // raw silhouette, pre-blur (interior/edge test)
uniform sampler2D ImageSampler;     // BetterChams fill image
uniform sampler2D ParamsSampler;
uniform sampler2D FlareSampler;     // mask that warps the halo's spread per-direction
uniform sampler2D GlowTexSampler;   // image screen-blended onto the glow halo itself

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 params    = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint  = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 glowTint  = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec4 flipData  = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    vec4 flareData = texelFetch(ParamsSampler, ivec2(4, 0), 0);
    vec4 glowTexData = texelFetch(ParamsSampler, ivec2(5, 0), 0);

    float fillEnabled  = params.r;
    float fillOpacity  = params.g;
    float glowEnabled  = params.b;
    float glowIntensity = flipData.b;
    float sampleStep   = flipData.g * 4.0;  // 0-4.0, now used as inner-rim bleed radius (px)

    float flareEnabled  = flareData.r;
    float flareSize      = max(0.05, flareData.a * 2.0); // packed /2.0*255 in Java, unpack back to 0-2.0
    float glowTexEnabled = glowTexData.r;

    float doFlip  = flipData.r;
    float finalY  = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    vec4 orig = texture(OriginalSampler, texCoord);

    if (orig.a > 0.0) {
        vec4 fillResult = vec4(0.0);
        bool hasFill = fillEnabled > 0.5;
        if (hasFill) {
            vec4 img = texture(ImageSampler, flippedUv);
            fillResult = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
        }

        // Inner-rim glow: bleed glowTint into the interior near the silhouette edge,
        // additive to the main outer halo below (not a replacement for it). Radius is
        // driven by Sample Step (px) -- decoupled from Glow Thickness's outer radius.
        bool nearEdge = false;
        if (glowEnabled > 0.5 && sampleStep > 0.0) {
            vec2 rimTexel = sampleStep / OutSize;
            for (int i = 0; i < 8; i++) {
                float ang = float(i) * 0.7853981634; // i * 2*PI/8
                vec2 off = vec2(cos(ang), sin(ang)) * rimTexel;
                if (texture(OriginalSampler, texCoord + off).a <= 0.0) {
                    nearEdge = true;
                    break;
                }
            }
        }

        if (nearEdge) {
            vec4 rimColor = vec4(glowTint.rgb, glowIntensity * glowTint.a);
            if (glowTexEnabled > 0.5) {
                vec4 glowImg = texture(GlowTexSampler, flippedUv);
                rimColor.rgb = 1.0 - (1.0 - rimColor.rgb) * (1.0 - glowImg.rgb * glowImg.a);
            }
            fragColor = hasFill ? mix(fillResult, rimColor, rimColor.a) : rimColor;
            return;
        }

        if (hasFill) {
            fragColor = fillResult;
            return;
        }
        discard;
    }

    if (glowEnabled < 0.5) {
        discard;
    }

    vec4 glow = texture(InSampler, texCoord);
    float haloAlpha = glow.a;

    // Flare: warps the halo's spread per-direction using a mask, instead of the
    // uniform round Kawase falloff. The outward direction at this pixel is the
    // gradient of the blurred glow's own alpha (alpha is highest right at the
    // silhouette edge and fades outward, so -gradient points away from the object) --
    // this hugs each glowing silhouette's actual shape with no need to know its
    // screen position or a global "center". (1 - glow.a) approximates how far into the
    // halo's thickness this pixel sits (0 = right at the edge, 1 = at the outer reach),
    // used as the mask's sample radius so the mask's bright center reads near the edge
    // and its rim/rays read at the halo's outer extent, scaled by Flare Size.
    if (flareEnabled > 0.5 && glow.a > 0.0) {
        vec2 gradDir = vec2(dFdx(glow.a), dFdy(glow.a));
        float gradLen = length(gradDir);
        if (gradLen > 0.0001) {
            vec2 outward = -gradDir / gradLen;
            vec2 maskUv = outward * (1.0 - glow.a) / flareSize + 0.5;
            if (maskUv.x >= 0.0 && maskUv.x <= 1.0 && maskUv.y >= 0.0 && maskUv.y <= 1.0) {
                haloAlpha *= texture(FlareSampler, maskUv).a;
            } else {
                haloAlpha = 0.0;
            }
        }
    }

    vec4 haloColor = vec4(glowTint.rgb, haloAlpha * glowIntensity * glowTint.a);
    if (glowTexEnabled > 0.5 && haloAlpha > 0.0) {
        vec4 glowImg = texture(GlowTexSampler, flippedUv);
        haloColor.rgb = 1.0 - (1.0 - haloColor.rgb) * (1.0 - glowImg.rgb * glowImg.a);
    }

    if (haloColor.a <= 0.0) {
        discard;
    }
    fragColor = haloColor;
}
