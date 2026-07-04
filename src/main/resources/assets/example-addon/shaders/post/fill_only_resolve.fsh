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

    // Same foreign-content guard as glow_resolve.fsh's isOurs(): the vanilla
    // entityOutlineTarget is shared, and Boze's BlockHighlight (plus F3-era content)
    // draws into it as PURE WHITE. Only silhouettes this addon drew (the green-250
    // entity marker or the blue-250 hand marker) may receive fill/outline; exact
    // pure white is rejected as foreign (see glow_resolve.fsh's isOurs comment).
    bool ours = orig.a > 0.0 && orig.r > 0.97 && orig.g > 0.95 && orig.b > 0.95
        && (orig.g < 0.995 || orig.b < 0.995);
    if (orig.a > 0.0 && !ours) {
        fragColor = orig;
        return;
    }

    if (ours && fillEnabled > 0.5) {
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
                    vec4 n = texture(InSampler, texCoord + off);
                    if (n.a > 0.0 && n.r > 0.97 && n.g > 0.95 && n.b > 0.95
                        && (n.g < 0.995 || n.b < 0.995)) {
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

    // MUST write transparent black instead of discard (same lesson as
    // glow_resolve.fsh): a discarded pixel keeps whatever the swap target held from
    // its previous use, and that stale content then gets blitted back over the
    // outline target -- erasing/corrupting pixels this pass meant to leave alone.
    fragColor = vec4(0.0);
}
