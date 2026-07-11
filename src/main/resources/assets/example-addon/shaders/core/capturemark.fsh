#version 330

// Fragment half of TargetESP's capture-mark billboard pipeline. Reuses core/entity.vsh
// UNCHANGED (same as AuraStep's pipeline) -- just samples Sampler0 (capture.png) instead
// of a procedural effect, additive-blended so the marker glows over its target.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

uniform sampler2D Sampler0;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(Sampler0, texCoord0);
    // ADDITIVE blend (ONE, ONE) ignores alpha entirely -- a glow texel at 5% alpha
    // still adds its FULL white RGB, which is what bloated the mark's soft glow
    // halo into a thick solid band. Premultiply RGB by alpha instead so the glow's
    // contribution fades with its alpha exactly like the PNG looks in a viewer,
    // and only discard near-zero texels (kills filtering fringe, keeps the glow).
    if (texColor.a < 0.01) discard;
    texColor.rgb *= texColor.a;
    vec4 color = texColor * vertexColor * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
