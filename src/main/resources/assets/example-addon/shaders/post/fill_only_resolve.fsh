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

// Flare is NOT handled here: the fill_only chains are only routed to when Flare is
// off (see MixinLevelRenderer/MixinGameRenderer gating) -- Flare needs the blurred
// silhouette field that only the glow-style chains produce, so with Flare on the
// resolve goes through glow_resolve.fsh instead (which also handles fill).
void main() {
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 flipData = texelFetch(ParamsSampler, ivec2(3, 0), 0);

    float fillEnabled = params.r;
    float fillOpacity = params.g;

    float doFlip = flipData.r;
    float finalY = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    vec4 orig = texture(InSampler, texCoord);

    if (orig.a > 0.0 && fillEnabled > 0.5) {
        vec4 img = texture(ImageSampler, flippedUv);
        fragColor = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
        return;
    }

    discard;
}
