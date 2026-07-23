#version 330

// Fragment half of KillEffect's Logo mode -- reuses core/entity.vsh (via
// RenderPipelines.ENTITY_SNIPPET, same as capturemark.fsh) unchanged, just samples
// Sampler0 (boze_logo.png) with REAL alpha compositing (TRANSLUCENT blend target, not
// additive) so the logo reads as a solid spinning card instead of a glow smear.

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
    if (texColor.a < 0.01) discard;
    vec4 color = texColor * vertexColor * ColorModulator;
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
