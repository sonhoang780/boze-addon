#version 330 core
in vec2 vTexCoord;
out vec4 FragColor;
uniform sampler2D uTexture;
uniform vec2 uHalfTexelSize;
uniform float uOffset;
void main() {
    vec2 uv = vTexCoord;
    vec2 halfPixel = uHalfTexelSize * uOffset;
    vec4 sum = texture(uTexture, uv) * 4.0;
    sum += texture(uTexture, uv - halfPixel.xy);
    sum += texture(uTexture, uv + halfPixel.xy);
    sum += texture(uTexture, uv + vec2(halfPixel.x, -halfPixel.y));
    sum += texture(uTexture, uv - vec2(halfPixel.x, -halfPixel.y));
    FragColor = sum / 8.0;
}