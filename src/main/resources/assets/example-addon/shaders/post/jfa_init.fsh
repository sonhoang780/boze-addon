#version 330
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec4 col = texture(InSampler, texCoord);
    if (col.a > 0.0) {
        fragColor = vec4(127.0/255.0, 127.0/255.0, 0.0, 1.0);
    } else {
        fragColor = vec4(0.0);
    }
}
