#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;        // final blurred glow buffer (after 4 glow_pass iterations)
uniform sampler2D OriginalSampler;  // raw silhouette, pre-blur (interior/edge test)
uniform sampler2D ImageSampler;     // BetterChams fill image
uniform sampler2D ParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 params    = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint  = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 glowTint  = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec4 flipData  = texelFetch(ParamsSampler, ivec2(3, 0), 0);

    float fillEnabled  = params.r;
    float fillOpacity  = params.g;
    float glowEnabled  = params.b;
    float glowIntensity = flipData.b;

    float doFlip  = flipData.r;
    float finalY  = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    vec4 orig = texture(OriginalSampler, texCoord);

    if (orig.a > 0.0) {
        if (fillEnabled > 0.5) {
            vec4 img = texture(ImageSampler, flippedUv);
            fragColor = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
        } else {
            discard;
        }
        return;
    }

    if (glowEnabled < 0.5) {
        discard;
    }

    vec4 glow = texture(InSampler, texCoord);
    if (glow.a <= 0.0) {
        discard;
    }

    fragColor = vec4(glowTint.rgb, glow.a * glowIntensity * glowTint.a);
}
