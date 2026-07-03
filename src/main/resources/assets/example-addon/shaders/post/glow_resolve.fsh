#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;        // final blurred glow buffer (after 4 glow_pass iterations)
uniform sampler2D OriginalSampler;  // raw silhouette, pre-blur (interior/edge test)
uniform sampler2D ImageSampler;     // BetterChams fill image
uniform sampler2D ParamsSampler;
uniform sampler2D FlareParamsSampler; // separate from ParamsSampler -- see BetterChams.FLARE_PARAMS_ID's comment

in vec2 texCoord;
out vec4 fragColor;

// Continuous screen-space aura, built from Xor's "3D Fire" DNA (the 5-octave
// cos-turbulence loop, sin() palette, tanh() tonemap -- shadertoy.com/view/3XXSWS)
// but WITHOUT the reference's cone raymarch. The cone renders exactly one flame
// centered on its own canvas; tiling that canvas around a silhouette produced
// disjoint mini-flames with visible seams. Here the turbulence field is evaluated
// directly at the pixel's screen position (continuous everywhere, no tiles), and
// the flame's "height" coordinate h comes from the blurred silhouette falloff
// (0 at the body edge -> 1 at the blur's outer reach): the noise displaces that
// falloff line in and out, which is what makes licking tongues that wrap around
// every silhouette on screen at once.
vec3 flareAura(vec2 screenPx, float h, float scale, float time, vec3 tint) {
    // xy continuous across the screen; z drifts with time so the swirl evolves.
    vec3 p = vec3(screenPx / scale, 0.4 * time);
    vec3 p0 = p;
    for (float d = 2.0; d < 15.0; d /= 0.6) {
        p += cos((p.yzx - vec3(time / 0.1, time, d)) * d) / d;
    }
    // Signed swirl displacement accumulated by the turbulence loop.
    float turb = (p.x - p0.x) + (p.y - p0.y);
    // Tongues: noise pushes the falloff line outward/inward.
    float hh = max(h * (1.3 + 0.5 * turb) + 0.25 * turb, 0.0);
    float intensity = pow(clamp(1.0 - hh, 0.0, 1.0), 2.0);
    vec4 col = (sin(hh * 5.0 - time * 2.0 + vec4(7.0, 2.0, 3.0, 0.0)) + 1.1) * intensity * 2.0;
    return tanh(col).rgb * tint;
}

void main() {
    vec4 params    = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint  = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 glowTint  = texelFetch(ParamsSampler, ivec2(2, 0), 0);
    vec4 flipData  = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    vec4 flareData     = texelFetch(FlareParamsSampler, ivec2(0, 0), 0);
    vec4 flareTintData = texelFetch(FlareParamsSampler, ivec2(1, 0), 0);
    vec4 flareTimeData = texelFetch(FlareParamsSampler, ivec2(2, 0), 0);

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
    // 16-bit time (R = high byte, G = low byte) over a 10s loop: the old single-byte
    // packing quantized time to 256 steps / 10s = ~25 visible steps per second, which
    // read as "the fire runs at 25fps" no matter the framerate.
    float flareTime = (flareTimeData.r * 255.0 * 256.0 + flareTimeData.g * 255.0) / 65535.0 * 10.0;

    float doFlip  = flipData.r;
    float finalY  = doFlip > 0.5 ? (1.0 - texCoord.y) : texCoord.y;
    vec2 flippedUv = vec2(texCoord.x, finalY);

    vec4 orig = texture(OriginalSampler, texCoord);
    vec4 glow = texture(InSampler, texCoord); // blurred silhouette field (glow_pass pyramid)

    // Flare: independent of Glow (glow_pass always runs in this chain, and Java widens
    // its blur radius to flareSize/2 whenever Flare is on, so the blurred field exists
    // even with Glow toggled off).
    vec3 flareContribution = vec3(0.0);
    if (flareEnabled && orig.a <= 0.0 && glow.a > 0.004) {
        float h = 1.0 - glow.a; // 0 at silhouette edge -> 1 at blur's outer reach
        vec2 screenPx = texCoord * OutSize;
        // Momentum: shift the noise field by the smoothed camera-lag offset so the
        // flames swirl behind a camera turn instead of being locked to the screen.
        vec2 lagShift = vec2(flareYawOffset, -flarePitchOffset) * (flareSizePx / 90.0) * 4.0;
        flareContribution = flareAura(screenPx + lagShift, h, flareSizePx, flareTime, flareTint);
        // Soften the field's outer cutoff so flame tips fade instead of clipping.
        flareContribution *= smoothstep(0.004, 0.05, glow.a);
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
            return;
        }

        if (hasFill) {
            fragColor = fillResult;
            return;
        }

        // MUST write transparent black instead of discard: a discarded pixel keeps
        // whatever the swap target held from the previous pass -- which in these
        // chains is an intermediate glow_pass blur of the raw WHITE silhouette. With
        // the blur radius widened for Flare, that stale content showed up as huge
        // opaque white blobs over every entity (and the whole hand) whenever fill
        // was off.
        fragColor = vec4(0.0);
        return;
    }

    // Outside the silhouette: halo (if Glow on) plus flame, additively. Where only the
    // flame exists, its own luminance drives alpha so the dark parts of the fire stay
    // transparent instead of stamping opaque black over the scene.
    float flareLum = max(max(flareContribution.r, flareContribution.g), flareContribution.b);
    bool haloOn = glowEnabled > 0.5 && glow.a > 0.0;
    if (!haloOn && flareLum <= 0.003) {
        fragColor = vec4(0.0); // not discard -- see the interior branch's comment
        return;
    }

    vec4 halo = haloOn ? vec4(glowTint.rgb, glow.a * glowIntensity * glowTint.a) : vec4(0.0);
    fragColor = vec4(halo.rgb + flareContribution, max(halo.a, flareLum));
}
