# BetterChams Flare Mode — Design

> For agentic workers: after this spec is approved, use superpowers:writing-plans to turn it into a task-by-task implementation plan.

## Goal

Rebuild BetterChams' "Flare" toggle (previously a lens-flare mask feature, deleted by an unrelated commit before this session and no longer present anywhere in the codebase) as a fire/aura effect: flickering, animated flame-like tendrils hugging the entity silhouette's edge, colored via a gradient (e.g. purple→pink→white, or blue→white), with momentum/inertia when the camera rotates — the flame visibly lags behind a camera turn and eases back to rest, rather than snapping instantly.

Reference: a purple "AURA" thumbnail (flame tendrils radiating from a silhouette) and blue flame stock-footage loops.

## Non-goals

- Not a 3D volumetric raymarch (too expensive to apply to every glowing entity simultaneously — see Rejected Approaches).
- Not texture/video-based (no video decode pipeline reused here; procedural shader only).
- Not tied to the `Glow` toggle's on/off state (see Relationship to Glow below).

## Relationship to Glow

`Flare` and `Glow` are independent toggles:
- Flare OFF, Glow ON: existing round halo behavior, unchanged.
- Flare ON, Glow OFF: flame renders on its own, full effect, no dependency on Glow.
- Flare ON, Glow ON: Glow's bloom (bright-pass + `GlowBlur`'s dual-Kawase blur) applies on top of the flame, brightening it — Glow's bright-pass reads the composited silhouette+flare output, not just the plain silhouette, so the flame's own bright pixels can also bloom.

## Approach: FBM noise shape + color gradient

Real-time fire shaders (the reference images' look, and every practical Shadertoy fire effect) use fractal Brownian motion (FBM) noise to generate the *turbulent shape* (the flicker/tendril pattern), then map the noise value through a *color gradient* to produce the actual flame colors. FBM alone is grayscale noise; the gradient is what makes it read as "burning."

- 2-3 octave FBM (cheap — matches the cost class of `CustomSky`'s existing plasma/aurora shaders, not the far more expensive `TungTungSahur` volumetric raymarch).
- Sampled in 2D screen-space near the silhouette edge, reusing the existing 8-direction edge-distance technique from `glow_resolve.fsh`'s inner-glow (distance-to-silhouette-boundary), but for the *exterior* side and with a larger, turbulence-driven radius instead of a fixed smooth falloff.
- Noise animates over time (same `u_Time`-style uniform pattern already used in `CustomSky`).
- Noise value → color via a gradient: dark/transparent → configured core color → white-hot tip. One `ColorOption` (the core color) is enough; the tip is auto-derived by lerping toward white at high noise/intensity values, rather than exposing a second color option — keeps the UI surface small.

### Rejected: 3D volumetric raymarch (TungTungSahur-style)

Would look more "real" (actual depth), but costs roughly what `TungTungSahur`'s smoke raymarch costs for a single fading model — applying that per-entity for every simultaneously-glowing target (players, crystals, self) would be prohibitively expensive. Rejected for this reason alone.

### Rejected: video/texture loop

Reference images are stock video loops, but this codebase has no reason to decode/loop a video texture here when a procedural shader achieves the same visual family more cheaply and without needing bundled/downloaded assets. Not pursued.

## Momentum / inertia

Camera yaw+pitch delta drives an offset applied to the FBM noise sampling coordinate (not a screen-space quad offset — the flame is still anchored to the silhouette, but the noise's *phase*/sampling origin shifts). The offset itself is smoothed with the same exponential-lerp technique already used throughout this codebase (MusicHUD's `pos += (target - pos) * k`) — NOT a spring/overshoot simulation. Effect: turning the camera quickly shifts the flame's turbulence pattern in the opposite direction momentarily; it eases back to centered as the camera settles. No oscillation/bounce.

## Architecture

- New `ToggleOption Flare` in `BetterChams.java`, independent of `glowToggle`.
- New `ColorOption flareColor` (core color; tip auto-lerps to white).
- Packed into the existing params texture (`betterchamsparam`) alongside existing glow/fill params — same pattern as `glowIntensity`/`innerGlow`, no new texture needed unless slot count runs out (currently 4×1 RGBA texels = 16 float-ish channels via the existing float-in-color-channel encoding; confirm remaining free channels during planning).
- Rendered in the SAME compositing pass as the silhouette (`glow_resolve.fsh` for the Glow-enabled path, `fill_only_resolve.fsh` for the Glow-disabled path) so it composites correctly regardless of Glow's state — needs adding the FBM+gradient logic to both shaders (or a shared GLSL snippet duplicated in both, matching the existing pattern where these two shaders already share similar structure).
- Camera yaw/pitch lag state lives in `BetterChams.java` (a pair of smoothed floats updated per-frame, same shape as `MusicHUD`'s smoothing fields), packed into the params texture as an additional offset uniform.

## Testing

- Visual: enable Flare alone (Glow off) — flame tendrils visible, animated, no round halo.
- Visual: enable both Flare + Glow — flame visibly brighter/bloomed compared to Flare-alone.
- Visual: rotate camera rapidly then stop — flame noise pattern visibly lags then settles, no oscillation.
- Regression: existing Glow-only (Flare off) behavior unchanged.
