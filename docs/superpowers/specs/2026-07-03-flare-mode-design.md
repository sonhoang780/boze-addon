# BetterChams Flare Mode — Design

> For agentic workers: after this spec is approved, use superpowers:writing-plans to turn it into a task-by-task implementation plan.

## Goal

Rebuild BetterChams' "Flare" toggle (previously a lens-flare mask feature, deleted by an unrelated commit before this session and no longer present anywhere in the codebase) as a fire/aura effect: real volumetric-looking flame tendrils hugging EVERY currently-glowing silhouette (player, other players, end crystals, hand — whatever the existing Glow/outline system already applies to), colored via a gradient, with momentum/inertia when the camera rotates — the flame visibly lags behind a camera turn and eases back to rest, rather than snapping instantly.

Reference: a purple "AURA" thumbnail (flame tendrils radiating from a silhouette), blue flame stock-footage loops, and — the deciding reference — Xor's "3D Fire" Shadertoy (https://www.shadertoy.com/view/3XXSWS): a 50-iteration raymarch through a hollow-cone SDF, with a nested turbulence loop (5 octaves) distorting the sample point every step, colored via `sin(depth/3 + vec4(7,2,3,0))` and `tanh` tonemapping. This is a genuine volumetric raymarch, not a 2D noise-and-gradient approximation.

## Non-goals

- Not texture/video-based (no video decode pipeline reused here; procedural shader only).
- Not tied to the `Glow` toggle's on/off state (see Relationship to Glow below).
- No per-entity Java-side selection or world-position tracking (see Revision note #2 — superseded approach).

## Revision history

1. First draft proposed a cheap 2D FBM-noise-plus-gradient approximation, explicitly rejecting a real raymarch as too expensive. The user provided the exact reference shader (Xor's 3D Fire) and wants that specific technique — replaced with the real raymarch, cost tradeoff made explicit instead of quietly downgrading it.
2. Second draft (this revision) dropped an entity-selection + world-AABB-to-screen-projection design that would have limited Flare to one target per frame. The user clarified Flare must wrap **every** currently-glowing silhouette simultaneously (player, other players, crystals, hand), matching how Glow's existing halo already works. The original (deleted) Flare implementation had already solved exactly this problem once, by warping the halo **per-pixel using the screen-space gradient of the glow's own alpha** (`dFdx`/`dFdy` of `glow.a`) to find each pixel's local "outward" direction — no per-entity data needed at all, so it automatically covers however many entities are glowing at once. This spec reuses that mechanism, adapted to also work when Glow is off (see Approach).

## Relationship to Glow

`Flare` and `Glow` are independent toggles:
- Flare OFF, Glow ON: existing round halo behavior, unchanged.
- Flare ON, Glow OFF: flame renders on its own, full effect, no dependency on Glow.
- Flare ON, Glow ON: Glow's bloom (bright-pass + `GlowBlur`'s dual-Kawase blur) applies on top of the flame, brightening it.

## Approach: per-pixel local raymarch canvas from the silhouette's own alpha gradient

Xor's shader is a self-contained "mini scene": a fixed camera at the origin looking at a hollow, upward-flaring cone made of turbulent noise, using its own `iResolution`/`fragCoord` purely to build a local ray direction. It has no notion of a real 3D world or entity position, which is exactly why it can be driven by a purely 2D, per-pixel local coordinate frame instead of any real screen-space bounding box:

- At every pixel, compute a manual 4-tap gradient of the **raw, unblurred silhouette alpha** (a small-offset Sobel-style sample: `vec2 gradDir = vec2(aRight - aLeft, aUp - aDown)`, using the SAME `OriginalSampler`/`InSampler` silhouette texture both compositing shaders already sample — not the blurred glow texture, so this works whether or not Glow/`GlowBlur` even ran this frame). This is the same idea the deleted Flare used (`dFdx`/`dFdy` on `glow.a`), generalized to not depend on the glow blur being present.
- `gradDir` is near-zero far from any silhouette edge and large right at a boundary — this alone gates "is Flare relevant here" (`length(gradDir) > threshold`), with zero extra per-entity bookkeeping. It naturally covers every glowing silhouette on screen simultaneously, because it's evaluated independently at every pixel.
- `outward = -normalize(gradDir)` (points from filled interior toward empty exterior). `tangent = vec2(-outward.y, outward.x)`.
- Local raymarch canvas: `localFragCoord = vec2(dot(screenPx, tangent), dot(screenPx, outward))`, `localResolution = vec2(flareSizePx, flareSizePx)` (a tunable constant/slider, not derived from any entity's real size). Projecting the pixel's own absolute screen position onto this locally-varying (tangent, outward) basis gives a continuously-varying 2D frame that follows the silhouette's actual shape as you move around its boundary, without ever needing to know which entity is where.
- Everything else (the raymarch loop, the turbulence loop, the cone SDF, the `sin`/`tanh` coloring) ports from Xor's shader unchanged in structure.

- Cost: **outer raymarch loop set to 10 iterations (down from Xor's reference 50)**, inner turbulence loop kept at the reference's 5 octaves (10 × 5 = 50 total noise evals vs. the reference's 250). Pure ALU (trig + noise math, no texture reads besides the 4 small-offset alpha taps for the gradient). Only evaluated where `length(gradDir)` clears the threshold (near a silhouette edge), not full-screen. Both iteration counts stay tunable after the first in-game frame-time check.
- Color: keep Xor's `sin(depth/3 + vec4(7,2,3,0))`-style palette as the base, multiplied by a `ColorOption` tint so users aren't stuck with the reference's orange-red (needed for the purple "AURA" look too).

### Rejected: video/texture loop

Reference images include stock video loops, but this codebase has no reason to decode/loop a video texture here when the procedural raymarch achieves the same visual family without bundled/downloaded assets. Not pursued.

### Rejected: per-entity world-AABB-to-screen projection (single target)

Superseded — see Revision history #2. Would have required Java-side entity iteration/eligibility selection and per-frame projection matrix math, and only supported one target at a time. The gradient-based approach needs none of that and naturally supports every simultaneously-glowing entity.

## Momentum / inertia

Camera yaw+pitch delta drives an offset applied to the raymarch's sample point (analogous to Xor's own `p.z += 5. + cos(t)` animation term, but driven by camera rotation instead of pure time) — smoothed with the same exponential-lerp technique used throughout this codebase (MusicHUD's `pos += (target - pos) * k`), not a spring/overshoot simulation. Turning the camera quickly shifts the flame's pattern momentarily; it settles as the camera stops. No oscillation/bounce. This offset is a single global value (not per-entity — it's derived from the player's own camera rotation, which is the same for every simultaneously-rendered silhouette).

## Architecture

- New `ToggleOption Flare` in `BetterChams.java`, independent of `glowToggle`.
- New `ColorOption` for tint.
- New `SliderOption` for the local canvas size (`flareSizePx`-equivalent, replaces the deleted original's "Flare Size" slider concept).
- Java-side per-frame work is now minimal: no entity iteration, no projection matrix, no per-entity bounding box. Just smooth the camera yaw/pitch lag offset (two floats, same shape as `MusicHUD`'s smoothing fields) and pack (flare enabled, tint, canvas size, lag offset, time) into the **existing** `betterchamsparam` texture — confirm remaining free channels during planning; these are few enough scalar values that a new texture likely isn't needed (unlike the superseded per-entity-bbox design, which needed one).
- Rendered in the SAME compositing pass as the silhouette (`glow_resolve.fsh` for the Glow-enabled path, `fill_only_resolve.fsh` for the Glow-disabled path), evaluated per-pixel via the gradient method above — applies uniformly to however many glowing silhouettes are on screen.

## Testing

- Visual: enable Flare alone (Glow off), with multiple glowing targets on screen at once (e.g. self + an end crystal) — flame wraps each silhouette independently, animated, matching the Xor reference's turbulent character.
- Visual: enable both Flare + Glow — flame visibly brighter/bloomed compared to Flare-alone.
- Visual: rotate camera rapidly then stop — flame pattern visibly lags then settles, no oscillation.
- Perf: measure frame time with Flare on vs off at 10 outer / 5 inner iterations; adjust counts up (quality) or down (perf) based on the result.
- Regression: existing Glow-only (Flare off) behavior unchanged.
