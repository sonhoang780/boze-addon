#version 330

// Fragment half of PenisESP's pipeline. Reuses core/bubble.vsh (it already passes
// view-space normals through). Simple lambert-ish shading from the view-facing
// term so the untextured cylinder/spheres read as 3D volumes, not flat cutouts.

in vec3 viewPos;
in vec3 viewNormal;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec3 N = normalize(viewNormal);
    vec3 V = normalize(-viewPos);
    float ndv = abs(dot(N, V));
    float shade = 0.55 + 0.45 * ndv;
    fragColor = vec4(vertexColor.rgb * shade, vertexColor.a);
}
