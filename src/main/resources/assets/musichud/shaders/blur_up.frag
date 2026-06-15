#version 330 core
in vec2 vTexCoord;
out vec4 FragColor;
uniform sampler2D uTexture;
uniform vec2 uHalfTexelSize;
uniform float uOffset;
void main() {
    vec2 uv = vTexCoord;
    vec2 halfPixel = uHalfTexelSize * uOffset;
    vec4 sum = texture(uTexture, uv + vec2(-halfPixel.x * 2.0, 0.0));
    sum += texture(uTexture, uv + vec2(-halfPixel.x, halfPixel.y)) * 2.0;
    sum += texture(uTexture, uv + vec2(0.0, halfPixel.y * 2.0));
    sum += texture(uTexture, uv + vec2(halfPixel.x, halfPixel.y)) * 2.0;
    sum += texture(uTexture, uv + vec2(halfPixel.x * 2.0, 0.0));
    sum += texture(uTexture, uv + vec2(halfPixel.x, -halfPixel.y)) * 2.0;
    sum += texture(uTexture, uv + vec2(0.0, -halfPixel.y * 2.0));
    sum += texture(uTexture, uv + vec2(-halfPixel.x, -halfPixel.y)) * 2.0;
    FragColor = sum / 12.0;
}