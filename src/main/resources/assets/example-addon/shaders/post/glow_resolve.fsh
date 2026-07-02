#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;        // final blurred glow buffer (after 4 glow_pass iterations)
uniform sampler2D OriginalSampler;  // raw silhouette, pre-blur (interior/edge test)
uniform sampler2D ImageSampler;     // BetterChams fill image
uniform sampler2D ParamsSampler;
uniform sampler2D FlareSampler;     // lens-flare-style mask, projected from flareCenter
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
    vec2  flareCenter    = vec2(flareData.g, flareData.b);
    float flareSize      = max(0.001, flareData.a * 2.0); // packed /2.0*255 in Java, unpack back to 0-2.0
    float glowTexEnabled = glowTexData.r;

    float doFlip  = flipData.r;
    float finalY  = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    // Flare: screen-space radial mask projected from flareCenter, independent of the
    // silhouette -- it can paint over empty space same as a real lens flare. Additive on
    // top of everything else below (Glow still applies to Flare per design: this only
    // ever contributes when glowEnabled is also true, checked at each return site).
    vec4 flareContrib = vec4(0.0);
    if (flareEnabled > 0.5) {
        vec2 flareUv = (texCoord - flareCenter) / flareSize + 0.5;
        if (flareUv.x >= 0.0 && flareUv.x <= 1.0 && flareUv.y >= 0.0 && flareUv.y <= 1.0) {
            vec4 mask = texture(FlareSampler, flareUv);
            flareContrib = vec4(glowTint.rgb, mask.a * glowIntensity * glowTint.a) * mask.a;
        }
    }

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

        vec4 result;
        bool hasResult = false;
        if (nearEdge) {
            vec4 rimColor = vec4(glowTint.rgb, glowIntensity * glowTint.a);
            if (glowTexEnabled > 0.5) {
                vec4 glowImg = texture(GlowTexSampler, flippedUv);
                rimColor.rgb = 1.0 - (1.0 - rimColor.rgb) * (1.0 - glowImg.rgb * glowImg.a);
            }
            result = hasFill ? mix(fillResult, rimColor, rimColor.a) : rimColor;
            hasResult = true;
        } else if (hasFill) {
            result = fillResult;
            hasResult = true;
        }

        if (hasResult) {
            if (flareEnabled > 0.5) {
                result.rgb = 1.0 - (1.0 - result.rgb) * (1.0 - flareContrib.rgb * flareContrib.a);
                result.a = max(result.a, flareContrib.a);
            }
            fragColor = result;
            return;
        }

        if (flareEnabled > 0.5 && flareContrib.a > 0.0) {
            fragColor = flareContrib;
            return;
        }
        discard;
    }

    if (glowEnabled < 0.5) {
        if (flareEnabled > 0.5 && flareContrib.a > 0.0) {
            fragColor = flareContrib;
            return;
        }
        discard;
    }

    vec4 glow = texture(InSampler, texCoord);
    vec4 haloColor = vec4(glowTint.rgb, glow.a * glowIntensity * glowTint.a);
    if (glowTexEnabled > 0.5 && glow.a > 0.0) {
        vec4 glowImg = texture(GlowTexSampler, flippedUv);
        haloColor.rgb = 1.0 - (1.0 - haloColor.rgb) * (1.0 - glowImg.rgb * glowImg.a);
    }
    if (flareEnabled > 0.5) {
        haloColor.rgb = 1.0 - (1.0 - haloColor.rgb) * (1.0 - flareContrib.rgb * flareContrib.a);
        haloColor.a = max(haloColor.a, flareContrib.a);
    }

    if (haloColor.a <= 0.0) {
        discard;
    }
    fragColor = haloColor;
}
