#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;
uniform sampler2D ImageSampler;
uniform sampler2D ParamsSampler;
uniform sampler2D OutlineParamsSampler; // see BetterChams.OUTLINE_PARAMS_ID

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

    // Crisp Outline: this chain has no blur pyramid (Glow/Flare are both off by
    // construction whenever it's routed to), so InSampler IS the raw silhouette --
    // same flat-alpha Chebyshev dilation as glow_resolve.fsh's version (see its
    // comment: flat alpha for a crisp vanilla-style line, square metric for sharp
    // corners, multi-step march so thin features aren't skipped).
    if (orig.a <= 0.0) {
        vec4 outlineData     = texelFetch(OutlineParamsSampler, ivec2(0, 0), 0);
        vec4 outlineTintData = texelFetch(OutlineParamsSampler, ivec2(1, 0), 0);
        if (outlineData.r > 0.5) {
            float outlineRadiusPx = outlineData.g * 5.0;
            vec2 texelStep = 1.0 / OutSize;
            int steps = int(ceil(outlineRadiusPx));
            const vec2 OUTLINE_DIRS[8] = vec2[8](
                vec2( 1.0, 0.0), vec2( 1.0, 1.0), vec2( 0.0, 1.0), vec2(-1.0, 1.0),
                vec2(-1.0, 0.0), vec2(-1.0,-1.0), vec2( 0.0,-1.0), vec2( 1.0,-1.0));
            float outlineEdge = 0.0;
            for (int i = 0; i < 8; i++) {
                if (outlineEdge >= 1.0) break;
                for (int s = 1; s <= steps; s++) {
                    vec2 off = OUTLINE_DIRS[i] * texelStep * float(s);
                    if (texture(InSampler, texCoord + off).a > 0.0) {
                        outlineEdge = 1.0;
                        break;
                    }
                }
            }
            if (outlineEdge > 0.0) {
                fragColor = vec4(outlineTintData.rgb, outlineEdge);
                return;
            }
        }
    }

    discard;
}
