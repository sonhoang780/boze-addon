#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;
uniform sampler2D ImageSampler;
uniform sampler2D ParamsSampler;
uniform sampler2D FlareParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

// Same raymarch as glow_resolve.fsh -- duplicated verbatim since these are separate
// compiled programs with no shared-include mechanism (matches this codebase's existing
// pattern for these two files). See glow_resolve.fsh for the technique's full comment.
vec3 flareFire(vec2 localFragCoord, vec2 localResolution, float time, vec3 tint) {
    vec4 O = vec4(0.0);
    float i = 0.0, z = 0.0, d;

    for (i = 0.0; i < 10.0; i++) {
        vec3 p = z * normalize(vec3(localFragCoord + localFragCoord, 0.0) - localResolution.xyy);
        p.z += 5.0 + cos(time);
        float rot = p.y * 0.5;
        mat2 twist = mat2(cos(rot), -sin(rot), sin(rot), cos(rot));
        p.xz *= twist / max(p.y * 0.1 + 1.0, 0.1);

        for (d = 2.0; d < 15.0; d /= 0.6) {
            p += cos((p.yzx - vec3(time / 0.1, time, d)) * d) / d;
        }
        d = 0.01 + abs(length(p.xz) + p.y * 0.3 - 0.5) / 7.0;
        z += d;
        O += (sin(z / 3.0 + vec4(7.0, 2.0, 3.0, 0.0)) + 1.1) / d;
    }

    O = tanh(O / 1e3);
    return O.rgb * tint;
}

void main() {
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 flipData = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    vec4 flareData = texelFetch(FlareParamsSampler, ivec2(0, 0), 0);
    vec4 flareTintData = texelFetch(FlareParamsSampler, ivec2(1, 0), 0);
    vec4 flareTimeData = texelFetch(FlareParamsSampler, ivec2(2, 0), 0);

    float fillEnabled = params.r;
    float fillOpacity = params.g;

    float doFlip = flipData.r;
    float finalY = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    bool flareEnabled = flareData.r > 0.5;
    float flareYawOffset = flareData.g * 180.0 - 90.0;
    float flarePitchOffset = flareData.b * 180.0 - 90.0;
    float flareSizePx = flareData.a * 128.0;
    vec3 flareTint = flareTintData.rgb;
    float flareTime = flareTimeData.r * 10.0;

    vec4 orig = texture(InSampler, texCoord);

    vec3 flareContribution = vec3(0.0);
    if (flareEnabled) {
        float eps = 2.0 / OutSize.x;
        float aR = texture(InSampler, texCoord + vec2(eps, 0.0)).a;
        float aL = texture(InSampler, texCoord - vec2(eps, 0.0)).a;
        float aU = texture(InSampler, texCoord + vec2(0.0, eps)).a;
        float aD = texture(InSampler, texCoord - vec2(0.0, eps)).a;
        vec2 gradDir = vec2(aR - aL, aU - aD);
        float gradLen = length(gradDir);
        if (gradLen > 0.01) {
            vec2 outward = -gradDir / gradLen;
            float lagAngle = radians(flareYawOffset) + radians(flarePitchOffset) * 0.3;
            float ca = cos(lagAngle), sa = sin(lagAngle);
            vec2 outwardLagged = mat2(ca, -sa, sa, ca) * outward;
            vec2 tangentLagged = vec2(-outwardLagged.y, outwardLagged.x);
            // mod() folds absolute screen position into a repeating flareSizePx window --
            // see glow_resolve.fsh for why (otherwise the noise field aliases to near-black).
            vec2 screenPx = texCoord * OutSize;
            vec2 localAxis = vec2(dot(screenPx, tangentLagged), dot(screenPx, outwardLagged));
            vec2 localFragCoord = mod(localAxis, flareSizePx);
            flareContribution = flareFire(localFragCoord, vec2(flareSizePx), flareTime, flareTint);
        }
    }

    if (orig.a > 0.0 && fillEnabled > 0.5) {
        vec4 img = texture(ImageSampler, flippedUv);
        fragColor = vec4(img.rgb * fillTint.rgb + flareContribution, img.a * fillOpacity * fillTint.a);
        return;
    }

    if (length(flareContribution) > 0.001) {
        fragColor = vec4(flareContribution, 1.0);
        return;
    }

    discard;
}
