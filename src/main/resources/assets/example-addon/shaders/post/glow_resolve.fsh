#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;        // dual JFA distance field (see JfaField.java): r=handDist/255px, g=handValid, b=entityDist/255px, a=entityValid
uniform sampler2D OriginalSampler;  // raw silhouette, pre-field (interior/edge test)
uniform sampler2D ImageSampler;     // BetterChams fill image
uniform sampler2D ParamsSampler;
uniform sampler2D FlareParamsSampler; // separate from ParamsSampler -- see BetterChams.FLARE_PARAMS_ID's comment
uniform sampler2D OutlineParamsSampler; // same dedicated-texture pattern, see BetterChams.OUTLINE_PARAMS_ID

in vec2 texCoord;
out vec4 fragColor;

// Shared 8-direction set for the InnerGlow rim march (the only remaining march in
// this file -- everything else now derives from the JFA distance field). Deliberately
// UNNORMALIZED diagonals (Chebyshev/square metric): normalized euclidean directions
// dilate into a disc and round every corner; the square metric keeps corners sharp
// like vanilla's glowing outline.
const vec2 EDGE_DIRS[8] = vec2[8](
    vec2( 1.0, 0.0), vec2( 1.0, 1.0), vec2( 0.0, 1.0), vec2(-1.0, 1.0),
    vec2(-1.0, 0.0), vec2(-1.0,-1.0), vec2( 0.0,-1.0), vec2( 1.0,-1.0));

// TRUE only for silhouettes this addon drew itself: the green-250 entity marker
// (ENTITY_OUTLINE_COLOR -- players/crystals/self) or the blue-250 hand marker
// (HAND_OUTLINE_COLOR). The vanilla entityOutlineTarget is SHARED -- Boze's
// BlockHighlight draws the crosshair block's outline into it (3rd person +
// crosshair on a block), and with F3 open more foreign content lands there too.
// That foreign content arrives as PURE WHITE, which the old ">0.95 threshold"
// guard accepted -- it then seeded the JFA field and bloomed a giant soft halo
// around the targeted block/debug content (the "glow nhoe" in 3rd person and F3,
// 2026-07-04). Entities therefore moved OFF pure white to green-250, and this
// guard now explicitly rejects exact white (g and b both at 255).
// JfaField's seed pass applies this same guard (so foreign content never seeds
// the distance field at all), but the interior branch below still needs it
// directly against the raw silhouette.
bool isOurs(vec4 c) {
    return c.a > 0.0 && c.r > 0.97 && c.g > 0.95 && c.b > 0.95
        && (c.g < 0.995 || c.b < 0.995);
}

// Exact distance (px) to the nearest addon-drawn silhouette pixel of EITHER class
// (used only for the flare part-mask gradient, which is an approximate "which way
// is the body" heuristic -- combining both classes here is fine, unlike the halo/
// flare radius formulas below which must stay per-class exact), or a finite
// stand-in (300px, well past any realistic glow reach) when neither class's field
// found a seed within its search radius.
float jfaDistAt(vec2 uv) {
    vec4 s = texture(InSampler, uv);
    float hd = (s.g > 0.5) ? s.r * 255.0 : 300.0;
    float ed = (s.a > 0.5) ? s.b * 255.0 : 300.0;
    return min(hd, ed);
}

// Continuous screen-space aura, built from Xor's "3D Fire" DNA (the 5-octave
// cos-turbulence loop, sin() palette, tanh() tonemap -- shadertoy.com/view/3XXSWS)
// but WITHOUT the reference's cone raymarch. The cone renders exactly one flame
// centered on its own canvas; tiling that canvas around a silhouette produced
// disjoint mini-flames with visible seams. Here the turbulence field is evaluated
// directly at the pixel's screen position (continuous everywhere, no tiles), and
// the flame's "height" coordinate h comes from the exact JFA distance (0 at the
// body edge -> 1 at the flare's reach): the noise displaces that falloff line in
// and out, which is what makes licking tongues that wrap around every silhouette
// on screen at once.
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
    vec4 outlineData     = texelFetch(OutlineParamsSampler, ivec2(0, 0), 0);
    vec4 outlineTintData = texelFetch(OutlineParamsSampler, ivec2(1, 0), 0);

    float fillEnabled  = params.r;
    float fillOpacity  = params.g;
    // b = ratio of the DESIRED visible halo radius (Glow Thickness) to the ACTUAL
    // field search radius, 0 = glow off. Flare widens the shared field to flareSize/2,
    // so the halo must be cut down to its own thickness instead of inheriting the
    // flame's full reach (see BetterChams.updateParamsTexture).
    float glowRatio    = params.b;
    bool  glowEnabled  = glowRatio > 0.0;
    float glowIntensity = flipData.b;
    bool innerGlowEnabled = flipData.g > 0.5;
    // Blue byte of outline-params row 0 was unused -- carries the Glow ModeOption
    // (BetterChams.GlowMode): 0 = Soft, 255 = Neon.
    bool neonMode = outlineData.b > 0.5;
    // Alpha byte carries OutlineMode==Complex. Hoisted to main() scope (was local to
    // the interior branch) so the EXTERIOR crisp-outline test can also read it: in
    // Complex mode the crisp silhouette outline is restricted to HAND pixels only.
    bool isComplexOutline = outlineData.a > 0.5;

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
    vec4 glow = texture(InSampler, texCoord); // dual JFA field at this pixel

    // Two INDEPENDENT nearest-seed distances (see JfaField.render's class doc):
    // hand and entity each get their own exact distance, so each can use its own
    // radius formula below without the other's ownership ever cutting it off at
    // a Voronoi boundary (the "round bite out of the hand's halo near a far
    // crystal" bug, 2026-07-04).
    bool handValid = glow.g > 0.5;
    bool entityValid = glow.a > 0.5;
    float handDistPx = handValid ? glow.r * 255.0 : 1e6;
    float entityDistPx = entityValid ? glow.b * 255.0 : 1e6;
    bool distValid = handValid || entityValid;
    float distPx = min(handValid ? handDistPx : 1e6, entityValid ? entityDistPx : 1e6);

    float scaleN   = flipData.a * 2.0;                 // packed distance scale, 0..2
    float invScale = 1.0 / max(scaleN, 0.05);
    float unscaleHand   = invScale; // hand always un-scales back to its own on-screen size
    float unscaleEntity = 1.0;      // entity uses the shared world-distance scale directly

    // Shared field search radius actually used this frame (BetterChams.
    // updateParamsTexture: max(Glow Thickness, flareSize/2 whenever Flare is on,
    // Outline Radius whenever Outline is on)).
    float fieldRadiusPx = params.a * 255.0;

    // Flare: independent of Glow (the field is always computed in this chain, and
    // Java widens its search radius to flareSize/2 whenever Flare is on, so the
    // field covers the needed reach even with Glow toggled off). Computed per class
    // then combined via max() -- NOT summed, so overlap between a hand flame and an
    // entity flame doesn't double-brighten, and NEITHER class's flame gets cut off
    // by the other's (smaller/larger) reach.
    vec3 flareContribution = vec3(0.0);
    if (flareEnabled && orig.a <= 0.0) {
        vec2 screenPx = texCoord * OutSize;

        if (handValid) {
            float effFlareSizePx = flareSizePx * unscaleHand;
            float effReach = max(effFlareSizePx * 0.5, 1.0);
            float h = clamp(handDistPx / effReach, 0.0, 1.0);
            vec2 lagShift = vec2(flareYawOffset, -flarePitchOffset) * (effFlareSizePx / 90.0) * 4.0;
            vec3 handFlare = flareAura(screenPx + lagShift, h, flareNoiseScalePx, flareTime, flareTint);
            handFlare *= 1.0 - smoothstep(effReach * 0.9, effReach * 1.15, handDistPx);
            // Hand is exempt from the up-ness part-mask below (hugs screen bottom,
            // its flames are meant to rise over its own top edge by design).
            flareContribution = max(flareContribution, handFlare);
        }

        if (entityValid) {
            float effFlareSizePx = flareSizePx * unscaleEntity;
            float effReach = max(effFlareSizePx * 0.5, 1.0);
            float h = clamp(entityDistPx / effReach, 0.0, 1.0);
            vec2 lagShift = vec2(flareYawOffset, -flarePitchOffset) * (effFlareSizePx / 90.0) * 4.0;
            vec3 entityFlare = flareAura(screenPx + lagShift, h, flareNoiseScalePx, flareTime, flareTint);
            entityFlare *= 1.0 - smoothstep(effReach * 0.9, effReach * 1.15, entityDistPx);

            // Part-mask heuristic (flames on the crystal's lower half, not over its
            // tip / a player's head), via the DISTANCE FIELD GRADIENT: distance
            // rises away from the body, so the gradient's direction is the local
            // "away from body" normal. Where that normal points straight UP the
            // pixel is hovering over an upward-facing top -> fade; sideways/down ->
            // keep.
            vec2 gStep = 3.0 / OutSize;
            float gx = jfaDistAt(texCoord + vec2(gStep.x, 0.0)) - jfaDistAt(texCoord - vec2(gStep.x, 0.0));
            float gy = jfaDistAt(texCoord + vec2(0.0, gStep.y)) - jfaDistAt(texCoord - vec2(0.0, gStep.y));
            float upness = clamp(gy / (length(vec2(gx, gy)) + 1e-5), 0.0, 1.0);
            float partMask = 1.0 - smoothstep(0.35, 0.85, upness);
            entityFlare *= partMask;
            flareContribution = max(flareContribution, entityFlare);
        }
    }

    if (orig.a > 0.0) {
        // Foreign silhouette (Boze BlockHighlight, F3-era content): pass it through
        // exactly as drawn -- no fill, no rim, and critically no vec4(0) overwrite,
        // which would erase Boze's own highlight from the buffer.
        if (!isOurs(orig)) {
            fragColor = orig;
            return;
        }

        // fillEnabled is a packed 3-state value: 0 = no fill, ~0.5 = flat FillColor fill
        // (Image Fill mode == Off), ~1.0 = image/gif-textured fill tinted by FillColor.
        vec4 fillResult = vec4(0.0);
        bool hasFill = fillEnabled > 0.25;
        // Hands now get the SAME deep linear-gradient InnerGlow as players/crystals
        // in Complex mode (user request 2026-07-16 -- previously hands were excluded
        // and kept the thin 6px rim). isHandPixel still matters below for the
        // distance unscale (hand un-scales to its own on-screen size).
        bool isHandPixel = orig.b < 0.995;
        bool complexHere = isComplexOutline;
        if (fillEnabled > 0.75) {
            vec4 img = texture(ImageSampler, flippedUv);
            fillResult = vec4(img.rgb * fillTint.rgb, img.a * fillOpacity * fillTint.a);
        } else if (hasFill) {
            fillResult = vec4(fillTint.rgb, fillOpacity * fillTint.a);
        }

        // Inner-rim glow: a soft bleed of glowTint just inside the silhouette edge.
        // Still an exact inward march (same technique as before) rather than a field
        // formula: on a silhouette SMALLER than the search radius, a field-based rim
        // would flood the whole interior at range (the "inner glow fills like a fill"
        // report, 2026-07-04). Marching real pixels can't flood: a pixel deeper than
        // rimPx from the edge never finds outside. Width = half the pixel's
        // effective glow radius (distance-scaled; hand un-scales), capped at 6px.
        //
        // The march only finds the nearest-edge PIXEL DISTANCE now (nearestEdgeDist);
        // the actual falloff curve reuses the SAME Neon (exp core+halo) / Soft
        // (gaussian) formulas as the outer halo, driven by neonMode -- previously a
        // flat linear ramp capped at a fixed 0.47 alpha regardless of mode, which
        // read as barely-there next to the outer halo's full-intensity Neon/Soft
        // curves (user report, 2026-07-04: "gần như chả khác gì" with InnerGlow
        // on/off). Same peak intensity as the outer halo now (both reach 1.0 at the
        // edge before glowIntensity), just confined inward instead of outward.
        float edgeFactor = 0.0;
        if (glowEnabled && innerGlowEnabled) {
            // Interior pixel: the raw marker itself already tells us hand vs entity
            // (no field/boundary ambiguity possible here), see isOurs's comment.
            float unscalePixel = (orig.b < 0.995) ? unscaleHand : unscaleEntity;
            // Complex outline wants InnerGlow to bleed noticeably far into the model --
            // user wants it reaching roughly 1/3 of each limb box's depth (tay/chân/
            // thân/đầu), well past the old 40px cap (2026-07-16). No per-part geometry
            // is available in this 2D silhouette march (see rimCapPx's old comment), so
            // this is an approximation: a bigger cap + a bigger reach multiplier than
            // Simple's thin-rim formula. Only Complex gets the deeper reach; Simple
            // keeps the original thin-rim look.
            // Complex cap 120 -> 40: the 8-dir march costs 8*cap fetches per interior
            // pixel; 120 was the main GPU drop with a close-up model (2026-07-21).
            float rimCapPx = complexHere ? 40.0 : 6.0;
            float rimPx = clamp(glowRatio * unscalePixel * fieldRadiusPx * (complexHere ? 1.2 : 0.5), 1.0, rimCapPx);
            int rimSteps = int(ceil(rimPx));
            vec2 texelStep = 1.0 / OutSize;
            float nearestEdgeDist = 1e6;
            for (int i = 0; i < 8; i++) {
                for (int s = 1; s <= rimSteps; s++) {
                    if (texture(OriginalSampler, texCoord + EDGE_DIRS[i] * texelStep * float(s)).a <= 0.0) {
                        nearestEdgeDist = min(nearestEdgeDist, float(s - 1));
                        break;
                    }
                }
            }
            if (nearestEdgeDist < 1e5) {
                float thicknessPxInner = max(rimPx, 0.5);
                if (neonMode) {
                    float coreWidthPx = max(1.0, thicknessPxInner * 0.4);
                    float core = exp(-nearestEdgeDist / coreWidthPx);
                    float haloWidthPx = max(thicknessPxInner * 0.9, 0.5);
                    float outerHalo = 0.45 * exp(-nearestEdgeDist / haloWidthPx);
                    edgeFactor = clamp(core + outerHalo, 0.0, 1.0);
                } else {
                    float sigma = max(thicknessPxInner * 0.6, 0.5);
                    edgeFactor = exp(-(nearestEdgeDist * nearestEdgeDist) / (2.0 * sigma * sigma));
                }

                // Gel: continuous linear fade going inward, not discrete stepped bands
                // (user request, 2026-07-16 -- replaces the earlier quantized-layer
                // look). Still the same real per-pixel inward march against
                // OriginalSampler (the raw silhouette) as the rest of InnerGlow --
                // explicitly NOT the JFA distance field (InSampler): JFA self-seeds
                // every interior pixel at distance 0 and would give a degenerate/
                // deformed banding at range (per the user's explicit ban on using it
                // here).
                if (complexHere) {
                    float depthT = clamp(nearestEdgeDist / max(rimPx, 0.5), 0.0, 1.0); // 0 at edge, 1 at rimPx deep
                    edgeFactor *= mix(1.0, 0.15, depthT);
                }
            }
        }

        // base = fill + inner rim (the original interior result).
        vec4 base;
        if (edgeFactor > 0.0) {
            vec4 rimColor = vec4(glowTint.rgb, edgeFactor * glowIntensity * glowTint.a);
            base = hasFill ? mix(fillResult, rimColor, rimColor.a) : rimColor;
        } else if (hasFill) {
            base = fillResult;
        } else {
            // MUST write transparent black instead of discard: a discarded pixel keeps
            // whatever the swap target held from the previous pass. With fill off this
            // must read as fully transparent, not stale content from a previous frame.
            base = vec4(0.0);
        }

        // Cross-class glow/outline OVER the fill: this pixel is interior to ONE class,
        // but the OTHER class's silhouette (e.g. a held block, hand-class, moving into
        // a player's fill, entity-class) is overwritten out of this buffer here, so the
        // exterior halo/outline pass below never fires over the overlap -- the block's
        // outline/glow vanished exactly where it entered the fill (user report,
        // 2026-07-15; reference shows the block's outline must stay crisp over the
        // fill). Re-emit the nearest OTHER-class silhouette's halo + crisp outline on
        // top of the fill, using that class's own field distance + radius formula.
        {
            bool thisIsHand = orig.b < 0.995;                    // hand marker has b<1
            float otherDist  = thisIsHand ? entityDistPx : handDistPx;
            bool  otherValid = thisIsHand ? entityValid  : handValid;
            float otherUnscale = thisIsHand ? unscaleEntity : unscaleHand;
            if (otherValid) {
                float crossHaloA = 0.0;
                if (glowEnabled) {
                    float effRatio = clamp(glowRatio * otherUnscale, 0.0, 1.0);
                    float thick = max(effRatio * fieldRadiusPx, 1.0);
                    if (neonMode) {
                        float coreWidthPx = max(1.5, thick * 0.15);
                        float core = exp(-otherDist / coreWidthPx);
                        float haloWidthPx = max(thick * 0.5, 0.5);
                        crossHaloA = clamp(core + 0.45 * exp(-otherDist / haloWidthPx), 0.0, 1.0);
                    } else {
                        float sigma = max(thick * 0.5, 0.5);
                        crossHaloA = exp(-(otherDist * otherDist) / (2.0 * sigma * sigma));
                    }
                    crossHaloA *= glowIntensity;
                }
                if (crossHaloA > 0.0) {
                    base = vec4(mix(base.rgb, glowTint.rgb, crossHaloA * glowTint.a),
                                max(base.a, crossHaloA * glowTint.a));
                }
                // Crisp Outline (Simple mode) of the other class, on top of everything.
                bool  xOutlineOn = outlineData.r > 0.5;
                float xOutlineRadPx = outlineData.g * 5.0;
                if (xOutlineOn && otherDist <= xOutlineRadPx) {
                    base = vec4(outlineTintData.rgb, 1.0);
                }
            }
        }

        fragColor = base;
        return;
    }

    // Outside the silhouette: halo (if Glow on) plus flame, additively. Where only the
    // flame exists, its own luminance drives alpha so the dark parts of the fire stay
    // transparent instead of stamping opaque black over the scene.
    float flareLum = max(max(flareContribution.r, flareContribution.g), flareContribution.b);

    // Crisp Outline: independent of Glow/Flare, so it must still evaluate even when
    // both are off. Now reads the same exact JFA distance instead of its own 8-dir
    // march -- BetterChams.writeMainParams widens the field's search radius to at
    // least outlineRadius whenever Outline is on, so the field always reaches far
    // enough. (Euclidean distance rounds corners slightly more than the old
    // Chebyshev march; revisit if that reads wrong against vanilla's outline.)
    bool outlineEnabled  = outlineData.r > 0.5;
    float outlineRadiusPx = outlineData.g * 5.0;
    // In Complex mode the crisp silhouette outline is HAND-only (players/crystals use
    // the world-space wireframe boxes) -- test against the hand field there; Simple
    // outlines every silhouette via the combined min-distance.
    float outlineTestDist  = isComplexOutline ? (handValid ? handDistPx : 1e6) : distPx;
    bool  outlineTestValid = isComplexOutline ? handValid : distValid;
    float outlineEdge = (outlineEnabled && outlineTestValid && outlineTestDist <= outlineRadiusPx) ? 1.0 : 0.0;
    vec4 outlineContribution = vec4(outlineTintData.rgb, outlineEdge);

    // Gel's outline used to be a screen-space shell-ring shader effect here; replaced
    // with a real 3D wireframe box per body part (GelParticleSystem.renderOutlineBoxes,
    // reusing the same per-part AABB the particles already compute) per explicit user
    // request + reference image (2026-07-13) -- a shader halo can't reproduce "each
    // body part boxed by a slightly bigger wireframe cube" since it has no per-part
    // geometry, only a flat 2D silhouette distance field.

    if (!glowEnabled && flareLum <= 0.003 && outlineEdge <= 0.0) {
        fragColor = vec4(0.0); // not discard -- see the interior branch's comment
        return;
    }

    // Halo alpha: a direct analytic function of the exact distance -- no remap, no
    // banding, no "+slack" cap hack. Computed per class (own unscale/thickness) and
    // combined via max() -- NOT a single combined-field formula, which is exactly
    // what let one class's (e.g. a far, distance-shrunk entity's) small radius
    // formula take over right at the Voronoi boundary and bite a notch out of the
    // other class's (e.g. a close hand's) much larger halo (2026-07-04).
    float effGlowRatioHand = clamp(glowRatio * unscaleHand, 0.0, 1.0);
    float thicknessPxHand = max(effGlowRatioHand * fieldRadiusPx, 1.0);
    float haloAHand = 0.0;
    if (glowEnabled && handValid) {
        if (neonMode) {
            float coreWidthPx = max(1.5, thicknessPxHand * 0.15);
            float core = exp(-handDistPx / coreWidthPx);
            float haloWidthPx = max(thicknessPxHand * 0.5, 0.5);
            float outerHalo = 0.45 * exp(-handDistPx / haloWidthPx);
            haloAHand = clamp(core + outerHalo, 0.0, 1.0);
        } else {
            float sigma = max(thicknessPxHand * 0.5, 0.5);
            haloAHand = exp(-(handDistPx * handDistPx) / (2.0 * sigma * sigma));
        }
        haloAHand *= glowIntensity;
    }

    float effGlowRatioEntity = clamp(glowRatio * unscaleEntity, 0.0, 1.0);
    float thicknessPxEntity = max(effGlowRatioEntity * fieldRadiusPx, 1.0);
    float haloAEntity = 0.0;
    if (glowEnabled && entityValid) {
        if (neonMode) {
            // Neon: bright, tight core right at the edge + a wide, faint halo --
            // both exp-of-distance, both using GlowColor (BetterChams design choice).
            float coreWidthPx = max(1.5, thicknessPxEntity * 0.15);
            float core = exp(-entityDistPx / coreWidthPx);
            float haloWidthPx = max(thicknessPxEntity * 0.5, 0.5);
            float outerHalo = 0.45 * exp(-entityDistPx / haloWidthPx);
            haloAEntity = clamp(core + outerHalo, 0.0, 1.0);
        } else {
            // Soft: gaussian-of-distance. Band-free at any radius because it's a
            // continuous function of a float distance, not a stretched 8-bit alpha
            // slice (the old blur field's actual banding source).
            float sigma = max(thicknessPxEntity * 0.5, 0.5);
            haloAEntity = exp(-(entityDistPx * entityDistPx) / (2.0 * sigma * sigma));
        }
        haloAEntity *= glowIntensity;
    }

    float haloA = max(haloAHand, haloAEntity);
    vec4 halo = vec4(glowTint.rgb, haloA * glowTint.a);
    vec4 haloFlare = vec4(halo.rgb + flareContribution, max(halo.a, flareLum));

    // Outline composites ON TOP of halo/flare. Drawing it underneath (the original
    // layering choice) meant the halo's near-edge alpha (~1 right at the silhouette)
    // completely masked the outline there, leaving it visible only where the halo had
    // faded -- which rendered as a detached ring floating a few px off the body with a
    // clear gap, tracking the field's rounded shape instead of the silhouette
    // (user-verified 2026-07-03). On top, the outline hugs the edge in every combo and
    // Outline Radius reads as pure thickness again.
    vec3 finalRGB = mix(haloFlare.rgb, outlineContribution.rgb, outlineContribution.a);
    float finalA  = max(haloFlare.a, outlineContribution.a);
    fragColor = vec4(finalRGB, finalA);
}
