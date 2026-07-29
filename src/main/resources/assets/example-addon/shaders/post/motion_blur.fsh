#version 330

uniform sampler2D CurrSampler;
uniform sampler2D PrevSampler;
uniform sampler2D ParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 curr = texture(CurrSampler, texCoord);
    vec4 prev = texture(PrevSampler, texCoord);
    // MotionBlur.updateParams() packs this frame's blend factor (already time/motion
    // adjusted on the Java side) into the red channel of a 1x1 texture -- 8-bit is
    // plenty of precision for an alpha value, unlike TungTungSahur's 24-bit pack which
    // needed to carry world-space floats.
    float blend = texelFetch(ParamsSampler, ivec2(0, 0), 0).r;
    fragColor = mix(curr, prev, blend);
    fragColor.a = 1.0;
}
