# BetterChams Flare Mode — Design

> For agentic workers: after this spec is approved, use superpowers:writing-plans to turn it into a task-by-task implementation plan.

## Goal

Rebuild BetterChams' "Flare" toggle (previously a lens-flare mask feature, deleted by an unrelated commit before this session and no longer present anywhere in the codebase) as a fire/aura effect: real volumetric-looking flame tendrils hugging the entity silhouette, colored via a gradient, with momentum/inertia when the camera rotates — the flame visibly lags behind a camera turn and eases back to rest, rather than snapping instantly.

Reference: a purple "AURA" thumbnail (flame tendrils radiating from a silhouette), blue flame stock-footage loops, and — the deciding reference — Xor's "3D Fire" Shadertoy (https://www.shadertoy.com/view/3XXSWS): a 50-iteration raymarch through a hollow-cone SDF, with a nested turbulence loop (5 octaves) distorting the sample point every step, colored via `sin(depth/3 + vec4(7,2,3,0))` and `tanh` tonemapping. This is a genuine volumetric raymarch, not a 2D noise-and-gradient approximation.

## Non-goals

- Not texture/video-based (no video decode pipeline reused here; procedural shader only).
- Not tied to the `Glow` toggle's on/off state (see Relationship to Glow below).
- V1 does not support multiple simultaneous Flare targets rendering independently in the same frame (see Scope limitation below) — same limitation the original (deleted) Flare implementation had, for the same architectural reason.

## Revision note

An earlier draft of this spec proposed a cheap 2D FBM-noise-plus-gradient approximation, explicitly rejecting a real raymarch as too expensive. The user provided the exact reference shader (Xor's 3D Fire) and wants that specific technique, not the cheaper approximation. This revision replaces the approach section accordingly and is explicit about the resulting cost tradeoff instead of quietly downgrading it.

## Relationship to Glow

`Flare` and `Glow` are independent toggles:
- Flare OFF, Glow ON: existing round halo behavior, unchanged.
- Flare ON, Glow OFF: flame renders on its own, full effect, no dependency on Glow.
- Flare ON, Glow ON: Glow's bloom (bright-pass + `GlowBlur`'s dual-Kawase blur) applies on top of the flame, brightening it — Glow's bright-pass reads the composited silhouette+flare output, not just the plain silhouette, so the flame's own bright pixels can also bloom.

## Approach: self-contained local raymarch per silhouette

Xor's shader is a self-contained "mini scene": a fixed camera at the origin looking at a hollow, upward-flaring cone made of turbulent noise, using its own `iResolution`/`fragCoord` purely to build a local ray direction — it has no notion of a real 3D world, entity position, or Minecraft camera. This means it can be reused almost verbatim by treating **the entity's silhouette screen-space bounding box as the shader's own local canvas**:

- `iResolution` → the silhouette's on-screen bounding box size (pixels).
- `fragCoord` → the current pixel's position within that bounding box.
- Everything else (the raymarch loop, the turbulence loop, the cone SDF, the `sin`/`tanh` coloring) ports unchanged.

This avoids needing real per-entity world-position/camera uniforms (unlike `TungTungSahur`'s smoke, which raymarches an actual SDF placed at the entity's real world position) — the flame is a self-contained "column" rendered into whatever screen rectangle the silhouette occupies, same trick `ChamsCustomShader` already uses for its own local 2D canvas (`u_Size`) instead of a real `u_InverseProj`/`u_InverseView`.

- Cost is real: 50 outer iterations × up to 5 turbulence octaves per step is meaningfully more expensive than `CustomSky`'s plasma shaders, and it's pure ALU (trig + noise math, no texture reads) rather than the texture-fetch-heavy SDF lookups `TungTungSahur`'s smoke does — direction of comparison isn't obvious without testing. **Iteration counts (50 / 5) are a tunable knob, not fixed** — first in-game test determines whether they need reducing (e.g. 24 outer / 3 inner) for acceptable frame time. Cost also scales with how many screen pixels the effect actually covers (the silhouette's bounding box), not the whole screen, which is a meaningful mitigating factor vs. a full-screen background shader.
- Runs only where near/around the silhouette (masked, same as the rest of the glow/outline pipeline) — not full-screen.
- Color: keep Xor's `sin(depth/3 + vec4(7,2,3,0))`-style palette as the base (it's what produces the reference look), with a `ColorOption` allowing a hue/tint shift so users aren't stuck with exactly the reference's orange-red (needed to hit the purple "AURA" look too). Exact tint mechanism (multiply, HSV shift, or palette-swap) to be finalized during planning once the base port is visually working.

### Scope limitation carried over from the original Flare: one target per frame

The shared compositing pass (`glow_resolve.fsh` / `fill_only_resolve.fsh`) resolves per-pixel from the silhouette alpha texture; it has no per-entity screen-space bounding box to work with today. Supporting Flare on multiple simultaneously-glowing entities independently (e.g. player + 2 crystals all on fire at once, each with their own local raymarch canvas) needs each pixel to know which entity's bounding box it belongs to — a real architecture change (a per-entity data table, capped at N entities, looked up per-pixel). The original Flare implementation had this exact same limitation and scoped to "nearest eligible target only." This spec keeps that scope for v1; expanding to multi-target is a separate follow-up if needed after the single-target version is validated.

### Rejected: video/texture loop

Reference images include stock video loops, but this codebase has no reason to decode/loop a video texture here when the procedural raymarch achieves the same visual family without bundled/downloaded assets. Not pursued.

## Momentum / inertia

Camera yaw+pitch delta drives an offset applied to the raymarch's sample point (analogous to Xor's own `p.z += 5. + cos(t)` animation term, but driven by camera rotation instead of pure time) — the flame's turbulence pattern shifts as the camera turns and eases back via the same exponential-lerp smoothing used throughout this codebase (MusicHUD's `pos += (target - pos) * k`), not a spring/overshoot simulation. Turning the camera quickly shifts the flame momentarily; it settles as the camera stops. No oscillation/bounce.

## Architecture

- New `ToggleOption Flare` in `BetterChams.java`, independent of `glowToggle`.
- New `ColorOption` for tint (exact mechanism TBD during planning, see above).
- The single eligible flare target's screen-space bounding box (x0,y0,x1,y1) and the smoothed camera-lag offset are packed into the params texture (`betterchamsparam`), same pattern as existing packed floats — confirm remaining free channels during planning, may need a wider texture (e.g. matching `tungsmokeparams`' 8x3 layout) rather than reusing the current 4x1 one.
- Rendered in the SAME compositing pass as the silhouette (`glow_resolve.fsh` for the Glow-enabled path, `fill_only_resolve.fsh` for the Glow-disabled path) so it composites correctly regardless of Glow's state.
- Eligible-target selection (which entity gets the flare) reuses the same eligibility logic the original Flare's `findFlareTargetPos` used (mirrors `MixinEndCrystalRenderer`/`MixinAvatarRenderer`'s outline-eligibility conditions) — re-derive this in planning since the original method was deleted along with the rest of the feature.

## Testing

- Visual: enable Flare alone (Glow off) — volumetric flame column visible around the silhouette, animated, matching the Xor reference's turbulent character (not flat 2D noise).
- Visual: enable both Flare + Glow — flame visibly brighter/bloomed compared to Flare-alone.
- Visual: rotate camera rapidly then stop — flame pattern visibly lags then settles, no oscillation.
- Perf: measure frame time with Flare on vs off at the reference iteration counts; reduce iterations if it's a meaningful drop, re-test.
- Regression: existing Glow-only (Flare off) behavior unchanged.
