#version 330

// Fragment half of AuraStep's fire glow billboard -- a flat additive-blended
// decal (radial white-on-black gradient texture, Sampler0) laid on the ground
// under each fire tuft to fake bloom brightening the flames, since the engine
// has no real bloom pass this can hook into. Reuses core/entity.vsh UNCHANGED,
// same as aurastep.fsh -- only the fragment differs (plain texture sample
// instead of a procedural effect).

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif

in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
#ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 faceVertexColor = vertexColor;
#endif

    vec4 tex = texture(Sampler0, texCoord0);
    // Additive: alpha channel itself carries the glow's fade-out shape (the
    // gradient texture is already black at the rim), so the same luminance
    // doubles as the additive weight -- no separate mask needed.
    float glow = max(tex.r, max(tex.g, tex.b));
    vec4 color = vec4(tex.rgb, glow) * faceVertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    // No lightmap multiply -- additive glow reads the same in daylight or a dark cave.

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
