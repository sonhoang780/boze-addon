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
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);

    float thickness = params.a * 255.0;   // 0-64 px total outer glow radius

    // Total radius is driven by Glow Thickness alone. Sample Step no longer multiplies
    // in here -- it used to, which made the two sliders compound into an unbounded blob;
    // Sample Step now only controls the inner-rim bleed radius in glow_resolve.fsh.
    float offsetPixels = (thickness / 16.0) * PassScale;
    vec2 offset = offsetPixels / InSize;

    // 8-tap dual-Kawase kernel (4 diagonal corners + 4 orthogonal edges, weighted
    // 1:2) instead of the old 4-corner-only box filter. Sampling only the diagonals
    // left a directional bias that showed up as visible checkerboard/banding when
    // zoomed in; mixing in the orthogonal taps fixes that at the cost of 4 extra
    // texture reads per pass (still cheap -- 4 passes total).
    vec4 sum = texture(InSampler, texCoord + vec2(-offset.x, -offset.y)) * 1.0
             + texture(InSampler, texCoord + vec2( offset.x, -offset.y)) * 1.0
             + texture(InSampler, texCoord + vec2(-offset.x,  offset.y)) * 1.0
             + texture(InSampler, texCoord + vec2( offset.x,  offset.y)) * 1.0
             + texture(InSampler, texCoord + vec2( 0.0,      -offset.y * 2.0)) * 2.0
             + texture(InSampler, texCoord + vec2(-offset.x * 2.0,  0.0))      * 2.0
             + texture(InSampler, texCoord + vec2( offset.x * 2.0,  0.0))      * 2.0
             + texture(InSampler, texCoord + vec2( 0.0,       offset.y * 2.0)) * 2.0;

    fragColor = sum / 12.0;
}
