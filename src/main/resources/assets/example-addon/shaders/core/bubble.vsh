#version 330

// Vertex half of the Bubble pipeline. Same job as core/entity.vsh but ALSO passes
// view-space position + normal to the fragment shader so it can do per-pixel
// fresnel / thin-film / specular (entity.vsh only bakes lighting into a vertex
// color, no normals reach the fragment stage).

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

out vec3 viewPos;
out vec3 viewNormal;
out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    vec4 vp = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * vp;
    viewPos = vp.xyz;
    // ModelViewMat here is rotation+uniform-scale only (PoseStack translate/rotate/scale),
    // so its upper 3x3 is fine for normals -- no inverse-transpose needed.
    viewNormal = mat3(ModelViewMat) * Normal;
    texCoord0 = UV0;
    vertexColor = Color;
}
