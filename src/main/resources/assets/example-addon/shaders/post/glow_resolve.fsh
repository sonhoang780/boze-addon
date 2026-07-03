#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;        // final blurred glow buffer (after 4 glow_pass iterations)
uniform sampler2D OriginalSampler;  // raw silhouette, pre-blur (interior/edge test)
uniform sampler2D ImageSampler;     // BetterChams fill image
uniform sampler2D ParamsSampler;

in vec2 texCoord;
out vec4 fragColor;

// Ported from Xor's "3D Fire" (https://www.shadertoy.com/view/3XXSWS), iteration
// counts cut to 10 outer / 5 inner (from the reference's 50/5) per the approved
// spec -- pure ALU (trig+noise, no texture reads), tunable if perf needs it.
// localFragCoord/localResolution are NOT a real screen-space region -- they're the
// pixel's own position projected onto a per-pixel local (tangent, outward) basis (see
// the gradient computation below), so this "canvas" follows whatever silhouette shape
// is under it without needing to know which/how many entities are on screen.
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
    vec4 params    = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint  = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 glowTint  = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec4 flipData  = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    vec4 flareData     = texelFetch(ParamsSampler, ivec2(4, 0), 0);
    vec4 flareTintData = texelFetch(ParamsSampler, ivec2(5, 0), 0);
    vec4 flareTimeData = texelFetch(ParamsSampler, ivec2(6, 0), 0);

    float fillEnabled  = params.r;
    float fillOpacity  = params.g;
    float glowEnabled  = params.b;
    float glowIntensity = flipData.b;
    bool innerGlowEnabled = flipData.g > 0.5;
    const float INNER_GLOW_RADIUS_PX = 6.0; // small, fixed bleed radius by design (Inner vs. Outer glow toggle)

    bool flareEnabled = flareData.r > 0.5;
    float flareYawOffset = flareData.g * 180.0 - 90.0;
    float flarePitchOffset = flareData.b * 180.0 - 90.0;
    float flareSizePx = flareData.a * 128.0;
    vec3 flareTint = flareTintData.rgb;
    float flareTime = flareTimeData.r * 10.0;

    float doFlip  = flipData.r;
    float finalY  = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    vec4 orig = texture(OriginalSampler, texCoord);

    // Flare: independent of Glow (evaluated regardless of glowEnabled). Computed once
    // here and added onto whatever this pixel's other branches decide below -- a
    // manual 4-tap gradient of the RAW (unblurred) silhouette alpha finds the "outward"
    // direction at any pixel near ANY silhouette edge, which is what lets this work for
    // every simultaneously-glowing entity without per-entity data, and without needing
    // Glow's blur to have run.
    vec3 flareContribution = vec3(0.0);
    if (flareEnabled) {
        float eps = 2.0 / OutSize.x;
        float aR = texture(OriginalSampler, texCoord + vec2(eps, 0.0)).a;
        float aL = texture(OriginalSampler, texCoord - vec2(eps, 0.0)).a;
        float aU = texture(OriginalSampler, texCoord + vec2(0.0, eps)).a;
        float aD = texture(OriginalSampler, texCoord - vec2(0.0, eps)).a;
        vec2 gradDir = vec2(aR - aL, aU - aD);
        float gradLen = length(gradDir);
        if (gradLen > 0.01) {
            vec2 outward = -gradDir / gradLen;
            // Momentum: rotate the local (tangent, outward) basis by the smoothed
            // camera-lag angle, so the flame's own local "up" twists with a camera
            // turn instead of sliding sideways.
            float lagAngle = radians(flareYawOffset) + radians(flarePitchOffset) * 0.3;
            float ca = cos(lagAngle), sa = sin(lagAngle);
            vec2 outwardLagged = mat2(ca, -sa, sa, ca) * outward;
            vec2 tangentLagged = vec2(-outwardLagged.y, outwardLagged.x);

            vec2 screenPx = texCoord * OutSize;
            vec2 localFragCoord = vec2(dot(screenPx, tangentLagged), dot(screenPx, outwardLagged));
            flareContribution = flareFire(localFragCoord, vec2(flareSizePx), flareTime, flareTint);
        }
    }

    if (orig.a > 0.0) {
        vec4 fillResult = vec4(0.0);
        bool hasFill = fillEnabled > 0.5;
        if (hasFill) {
            vec4 img = texture(ImageSampler, flippedUv);
            fillResult = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
        }

        // Inner-rim glow: bleed glowTint into the interior near the silhouette edge,
        // additive to the main outer halo below (not a replacement for it). This is a
        // small, FIXED-radius bleed by design (a wide inner glow would just look like a
        // second outline) -- InnerGlow is now a plain on/off toggle rather than a radius
        // slider, since at any radius small enough to still read as "inner" rather than
        // "thicker outline", it's inherently much fainter than the outer halo (which
        // spans up to 64px at the same intensity); the toggle stops implying a
        // "strength" control that can't actually make it comparably prominent.
        // edgeFactor is the FRACTION of the 8 ring samples that land outside the
        // silhouette (0 = deep interior, up to 1 = right at the boundary), used as a
        // smooth alpha gradient rather than a hard binary cutoff.
        float edgeFactor = 0.0;
        if (glowEnabled > 0.5 && innerGlowEnabled) {
            vec2 rimTexel = INNER_GLOW_RADIUS_PX / OutSize;
            for (int i = 0; i < 8; i++) {
                float ang = float(i) * 0.7853981634; // i * 2*PI/8
                vec2 off = vec2(cos(ang), sin(ang)) * rimTexel;
                if (texture(OriginalSampler, texCoord + off).a <= 0.0) {
                    edgeFactor += 1.0;
                }
            }
            edgeFactor /= 8.0;
        }

        if (edgeFactor > 0.0) {
            vec4 rimColor = vec4(glowTint.rgb, edgeFactor * glowIntensity * glowTint.a);
            fragColor = hasFill ? mix(fillResult, rimColor, rimColor.a) : rimColor;
            fragColor.rgb += flareContribution;
            return;
        }

        if (hasFill) {
            fragColor = fillResult;
            fragColor.rgb += flareContribution;
            return;
        }

        if (length(flareContribution) > 0.001) { fragColor = vec4(flareContribution, 1.0); return; }
        discard;
    }

    if (glowEnabled < 0.5) {
        if (length(flareContribution) > 0.001) { fragColor = vec4(flareContribution, 1.0); return; }
        discard;
    }

    vec4 glow = texture(InSampler, texCoord);
    if (glow.a <= 0.0) {
        if (length(flareContribution) > 0.001) { fragColor = vec4(flareContribution, 1.0); return; }
        discard;
    }

    // NOTE (known v1 limitation): GlowBlur's bright-pass reads minecraft:entity_outline's
    // raw texture BEFORE this resolve pass adds flareContribution, so Glow's bloom does
    // not pick up the flame's own bright pixels. Fixing this needs a pipeline reorder
    // (running the raymarch earlier, or restructuring compositing order) -- out of scope
    // for v1.
    fragColor = vec4(glowTint.rgb, glow.a * glowIntensity * glowTint.a);
    fragColor.rgb += flareContribution;
}
