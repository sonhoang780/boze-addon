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
uniform sampler2D OutlineParamsSampler; // same dedicated-texture pattern, see BetterChams.OUTLINE_PARAMS_ID

in vec2 texCoord;
out vec4 fragColor;

// Shared 8-direction set for the edge marches (Outline dilation + InnerGlow rim).
// Deliberately UNNORMALIZED diagonals (Chebyshev/square metric): normalized euclidean
// directions dilate into a disc and round every corner; the square metric keeps
// corners sharp like vanilla's glowing outline.
const vec2 EDGE_DIRS[8] = vec2[8](
    vec2( 1.0, 0.0), vec2( 1.0, 1.0), vec2( 0.0, 1.0), vec2(-1.0, 1.0),
    vec2(-1.0, 0.0), vec2(-1.0,-1.0), vec2( 0.0,-1.0), vec2( 1.0,-1.0));

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
    // edgeFade forces intensity to 0 right at h=0 (the silhouette edge) instead of
    // letting pow(clamp(1-hh,0,1),2) saturate to ~1 there regardless of turbulence --
    // that saturation was a flat, un-animated, full-brightness band tracing the exact
    // silhouette shape (looked like a rigid diamond outline on a rotating end crystal).
    // Fading it in over the first bit of h lets the turbulence actually modulate
    // brightness from the very first visible pixel instead of starting solid.
    float edgeFade = smoothstep(0.0, 0.18, h);
    float intensity = pow(clamp(1.0 - hh, 0.0, 1.0), 2.0) * edgeFade;
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
    vec4 flareNoiseData = texelFetch(FlareParamsSampler, ivec2(3, 0), 0);

    float fillEnabled  = params.r;
    float fillOpacity  = params.g;
    // b = ratio of the DESIRED visible halo radius (Glow Thickness) to the ACTUAL
    // blur-field radius, 0 = glow off. Flare widens the shared field to flareSize/2,
    // so the halo must be cut down to its own thickness instead of inheriting the
    // flame's full reach (see BetterChams.updateParamsTexture).
    float glowRatio    = params.b;
    bool  glowEnabled  = glowRatio > 0.0;
    float glowIntensity = flipData.b;
    bool innerGlowEnabled = flipData.g > 0.5;

    bool flareEnabled = flareData.r > 0.5;
    float flareYawOffset = flareData.g * 180.0 - 90.0;
    float flarePitchOffset = flareData.b * 180.0 - 90.0;
    float flareSizePx = flareData.a * 128.0;
    // Raw, NON-distance-scaled px -- used only for the turbulence noise frequency
    // (flareAura's "scale" arg). flareSizePx above shrinks toward its 8px floor as the
    // target recedes, and since the noise is sampled at screenPx/scale, a shrinking
    // scale makes it oscillate faster than the shrunk silhouette can display: one flame
    // fractured into a grid of tiny cells the farther away the target got (2026-07-04).
    // Keeping the noise frequency pinned to the raw slider value means the same handful
    // of licking tongues wrap the silhouette at any distance instead of multiplying.
    float flareNoiseScalePx = flareNoiseData.r * 128.0;
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

    // Per-pixel hand detection: ONE shared pass resolves both world entities and the
    // hand (MixinShaderManager nulls the vanilla chain; reprocessHandOutline runs
    // hand_outline.json over everything), so per-chain params can't separate them.
    // Instead the hand silhouette is drawn with blue = 250/255 (HAND_OUTLINE_COLOR)
    // while entities stay pure white; the blur carries that r-vs-b ratio into the
    // field, giving a smooth 0(entity)..1(hand) weight everywhere the hand's field
    // reaches. Used to (a) lift the distance scale for hand pixels (the hand never
    // moves relative to the camera) and (b) exempt the hand from the flare part-mask.
    float handness = clamp((glow.r - glow.b) / max(glow.r, 1e-4) * 60.0, 0.0, 1.0);
    float scaleN   = flipData.a * 2.0;                 // packed distance scale, 0..2
    float invScale = 1.0 / max(scaleN, 0.05);
    float unscale  = mix(1.0, invScale, handness);      // 1 for entities, 1/scale for hand

    // Shared blur-kernel radius actually applied to this buffer (BetterChams.
    // updateParamsTexture: max(Glow Thickness, flareSize/2 whenever Flare is on)) --
    // the correct reference for "how strong should this field's gradient be if it
    // came from one clean source", used below by BOTH Flare and Glow.
    float fieldRadiusPx = params.a * 255.0;

    // Field gradient, shared by three purposes below (Flare tongue coloring, Flare's
    // part-mask, and Glow's halo). The chain draws every glowing silhouette (hand AND
    // every world entity) into ONE buffer before the Kawase blur, so when two
    // silhouettes sit close together on screen (hand near a player/crystal, or two
    // world entities near each other) their blurred halos SUM into one buffer -- the
    // combined field folds into a saddle between them instead of staying a single
    // monotonic bump. Flare colors purely by the field's raw height via a periodic
    // sin(hh*5), so every fold-crossing repaints the same band again (concentric
    // "fingerprint" rings, user report 2026-07-04); Glow's halo is a monotonic ramp so
    // a fold there instead reads as an over-bright merged patch ("resonance", same
    // report). A real single-source edge keeps a healthy, consistent gradient almost
    // everywhere; a saddle's gradient goes slack right at the fold. Gating both
    // effects on gradient magnitude kills the fold artifacts while leaving genuine
    // single-source halos/tongues (strong gradient nearly everywhere they're visible)
    // untouched.
    vec2 gStep = 3.0 / OutSize;
    float gx = texture(InSampler, texCoord + vec2(gStep.x, 0.0)).a
             - texture(InSampler, texCoord - vec2(gStep.x, 0.0)).a;
    float gy = texture(InSampler, texCoord + vec2(0.0, gStep.y)).a
             - texture(InSampler, texCoord - vec2(0.0, gStep.y)).a;
    float gradMag = length(vec2(gx, gy));
    // Reference: the gradient a clean single-source field would have over the same
    // 2*gStep sample baseline if it ramped linearly across the shared blur radius.
    float gradRef = (2.0 * gStep.x * OutSize.x) / max(fieldRadiusPx, 1.0);
    float fieldCoherence = smoothstep(0.12, 0.35, gradMag / max(gradRef, 1e-4));

    // Flare: independent of Glow (glow_pass always runs in this chain, and Java widens
    // its blur radius to flareSize/2 whenever Flare is on, so the blurred field exists
    // even with Glow toggled off).
    vec3 flareContribution = vec3(0.0);
    if (flareEnabled && orig.a <= 0.0 && glow.a > 0.004) {
        // Packed size is the distance-scaled world value; hand pixels un-scale it.
        float effFlareSizePx = flareSizePx * unscale;
        float h = 1.0 - glow.a; // 0 at silhouette edge -> 1 at blur's outer reach
        vec2 screenPx = texCoord * OutSize;
        // Momentum: shift the noise field by the smoothed camera-lag offset so the
        // flames swirl behind a camera turn instead of being locked to the screen.
        vec2 lagShift = vec2(flareYawOffset, -flarePitchOffset) * (effFlareSizePx / 90.0) * 4.0;
        flareContribution = flareAura(screenPx + lagShift, h, flareNoiseScalePx, flareTime, flareTint);
        // Soften the field's outer cutoff so flame tips fade instead of clipping.
        flareContribution *= smoothstep(0.004, 0.05, glow.a);
        flareContribution *= fieldCoherence;

        // Part-mask heuristic (flames on hands/arms/legs and the crystal's lower
        // half, not over the top of the head / crystal tip), via the FIELD GRADIENT:
        // the blurred field rises toward the body, so the gradient's direction is the
        // local "toward body" normal. Where that normal points straight DOWN the pixel
        // is hovering over an upward-facing top (head crown, crystal tip) -> fade;
        // where it points sideways/up (beside limbs, under the body) -> keep. The
        // previous version tested raw-silhouette occupancy N px above the pixel,
        // which by construction lit a DOWN-SHIFTED COPY of the silhouette -- rendered
        // as a ghost "texture" of the entity hanging under it (user report 2026-07-04).
        // Hand pixels are exempted via handness: the hand hugs the screen bottom, so
        // its flames rise over its own top edge by design.
        // +y = screen-up: field growing downward (gy < 0) means the body is below.
        float upness = clamp(-gy / (length(vec2(gx, gy)) + 1e-5), 0.0, 1.0);
        float partMask = 1.0 - smoothstep(0.35, 0.85, upness);
        flareContribution *= mix(partMask, 1.0, handness);
    }

    if (orig.a > 0.0) {
        vec4 fillResult = vec4(0.0);
        bool hasFill = fillEnabled > 0.5;
        if (hasFill) {
            vec4 img = texture(ImageSampler, flippedUv);
            fillResult = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
        }

        // Inner-rim glow: a soft bleed of glowTint just inside the silhouette edge,
        // derived from the BLURRED field so its width follows the blur radius (inside
        // the silhouette the field reads ~0.5 at the boundary rising toward 1 deeper
        // in, so (1 - glow.a) is a smooth edge-distance gradient). The falloff is
        // tightened by the distance scale (flipData.a, 0..2): at range the rim would
        // otherwise hold the same body-relative thickness as up close, which read as
        // disproportionately thick -- shrinking the scale narrows the visible band to
        // just the near-edge sliver. Capped at ~47% alpha (user-tuned "alpha ~120").
        float edgeFactor = 0.0;
        if (glowEnabled && innerGlowEnabled) {
            const float INNER_RIM_MAX_ALPHA = 0.47; // ~120/255
            // Exact inward march (same technique as Outline) instead of any blurred-
            // field formula: on a silhouette SMALLER than the blur radius the field
            // reads "near edge" across the entire body, so every field-based rim
            // floods the whole interior at range (the "inner glow fills like a fill"
            // report, 2026-07-04). Marching real pixels can't flood: a pixel deeper
            // than rimPx from the edge never finds outside. Width = half the pixel's
            // effective glow radius (distance-scaled; hand un-scales), capped at 6px.
            float rimPx = clamp(glowRatio * unscale * fieldRadiusPx * 0.5, 1.0, 6.0);
            int rimSteps = int(ceil(rimPx));
            vec2 texelStep = 1.0 / OutSize;
            for (int i = 0; i < 8; i++) {
                for (int s = 1; s <= rimSteps; s++) {
                    if (texture(OriginalSampler, texCoord + EDGE_DIRS[i] * texelStep * float(s)).a <= 0.0) {
                        edgeFactor = max(edgeFactor, 1.0 - (float(s) - 1.0) / float(rimSteps));
                        break;
                    }
                }
            }
            edgeFactor *= INNER_RIM_MAX_ALPHA;
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
    bool haloOn = glowEnabled && glow.a > 0.0;

    // Crisp Outline: independent of Glow/Flare, so it must still evaluate even when
    // both are off. Ring-samples the RAW silhouette (not the blurred field), mirrored
    // to test the opposite side of the InnerGlow rim above (does this outside pixel
    // sit within outlineRadiusPx of an edge?).
    //
    // Multi-step march (not one probe at exactly R -- that skipped thin features and
    // left a detached ring), with two deliberate choices to match the vanilla
    // glowing-outline look the user asked for (side-by-side comparison 2026-07-03):
    //  - FLAT alpha (any hit = 1.0): a per-step falloff rendered as a soft glow-like
    //    band instead of a crisp line.
    //  - CHEBYSHEV metric (diagonals step (±s,±s), unnormalized): normalized euclidean
    //    directions dilate into a disc, rounding every corner; square dilation keeps
    //    corners sharp like vanilla's outline.
    vec4 outlineData     = texelFetch(OutlineParamsSampler, ivec2(0, 0), 0);
    vec4 outlineTintData = texelFetch(OutlineParamsSampler, ivec2(1, 0), 0);
    bool outlineEnabled  = outlineData.r > 0.5;
    float outlineRadiusPx = outlineData.g * 5.0;
    float outlineEdge = 0.0;
    if (outlineEnabled) {
        vec2 texelStep = 1.0 / OutSize;
        int steps = int(ceil(outlineRadiusPx));
        for (int i = 0; i < 8; i++) {
            if (outlineEdge >= 1.0) break;
            for (int s = 1; s <= steps; s++) {
                vec2 off = EDGE_DIRS[i] * texelStep * float(s);
                if (texture(OriginalSampler, texCoord + off).a > 0.0) {
                    outlineEdge = 1.0;
                    break;
                }
            }
        }
    }
    vec4 outlineContribution = vec4(outlineTintData.rgb, outlineEdge);

    if (!haloOn && flareLum <= 0.003 && outlineEdge <= 0.0) {
        fragColor = vec4(0.0); // not discard -- see the interior branch's comment
        return;
    }

    // Remap the shared blur field so the visible halo ends at Glow Thickness even when
    // Flare widened the field: field values within the top glowRatio fraction map to
    // 0..1 halo alpha, everything farther out clamps to 0. Hand pixels widen the
    // ratio back to the raw (unscaled) thickness via unscale.
    float effGlowRatio = clamp(glowRatio * unscale, 0.0, 1.0);
    float haloA = haloOn ? clamp((glow.a - (1.0 - effGlowRatio)) / max(effGlowRatio, 1e-4), 0.0, 1.0) * fieldCoherence : 0.0;
    vec4 halo = vec4(glowTint.rgb, haloA * glowIntensity * glowTint.a);
    vec4 haloFlare = vec4(halo.rgb + flareContribution, max(halo.a, flareLum));

    // Outline composites ON TOP of halo/flare. Drawing it underneath (the original
    // layering choice) meant the halo's near-edge alpha (~1 right at the silhouette)
    // completely masked the outline there, leaving it visible only where the halo had
    // faded -- which rendered as a detached ring floating a few px off the body with a
    // clear gap, tracking the blur field's rounded shape instead of the silhouette
    // (user-verified 2026-07-03). On top, the outline hugs the edge in every combo and
    // Outline Radius reads as pure thickness again.
    vec3 finalRGB = mix(haloFlare.rgb, outlineContribution.rgb, outlineContribution.a);
    float finalA  = max(haloFlare.a, outlineContribution.a);
    fragColor = vec4(finalRGB, finalA);
}
