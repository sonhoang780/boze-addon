#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GlowPassConfig {
    float PassScale;
};

uniform sampler2D InSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 params    = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 flipData  = texelFetch(ParamsSampler, ivec2(3, 0), 0);

    float thickness  = params.a * 255.0;   // 0-64 px radius
    float sampleStep = flipData.g * 4.0;   // 0-4.0 kawase tap multiplier

    float offsetPixels = (thickness / 16.0) * sampleStep * PassScale;
    vec2 offset = offsetPixels / InSize;

    vec4 sum = texture(InSampler, texCoord + vec2(-offset.x, -offset.y))
             + texture(InSampler, texCoord + vec2( offset.x, -offset.y))
             + texture(InSampler, texCoord + vec2(-offset.x,  offset.y))
             + texture(InSampler, texCoord + vec2( offset.x,  offset.y));

    fragColor = sum * 0.25;
}
