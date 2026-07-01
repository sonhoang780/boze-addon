# BetterChams: JFA/Bloom → Glow (Dual-Kawase) Migration Design Spec

**Date:** 2026-07-01
**Builds on:** `2026-06-27-better-chams-design.md` (Phase 1: Crystal+Player JFA/bloom), Phase 2 (Hand, JFA/bloom, implemented since then).
**Scope:** Replace the entire JFA (jump-flood) + fixed-radius bloom outline pipeline with a single dual-Kawase downsample/upsample "Glow" pass. Applies to **all** entities the module touches: Crystal, Player, Hand, Self.

---

## Goal

Current outline pipeline (`entity_outline.json`, `hand_outline.json`) runs an 8-pass JFA chain (init + 6 flood passes at radii 32/16/8/4/2/1 + resolve + blit) every frame whenever bloom is on. This is expensive and scales poorly with more on-screen chammed entities. Replace it with a dual-Kawase blur chain (industry-standard cheap large-radius glow, used for bloom in many modern renderers), parameterized by 3 user-facing sliders: **Glow Thickness**, **Sample Step**, **Glow Intensity**.

`fillMode` (Off/Image/Gif/Shader) is **out of scope** — untouched, works exactly as today, independent of Glow.

---

## Module Settings Diff (`BetterChams.java`)

**Removed:**
- `bloomToggle` (ToggleOption "Bloom")
- `bloomRadius` (SliderOption "Bloom Radius", 1–64)
- `outlineOpacity` (SliderOption "Outline Opacity" — crisp-edge-only path, no longer meaningful once Bloom/Glow is a single on/off)

**Added (replacing the above 1:1 in UI position):**
- `glowToggle` — ToggleOption "Glow", default ON (same semantics as old `bloomToggle`: master on/off for the halo effect)
- `glowThickness` — SliderOption "Glow Thickness", 1.0–64.0, default 12.0 (replaces `bloomRadius`; drives dual-Kawase chain depth, see below)
- `sampleStep` — SliderOption "Sample Step", 1.0–4.0, step 0.1, default 1.0 (Kawase tap-offset multiplier)
- `glowIntensity` — SliderOption "Glow Intensity", 0.0–1.0, default 0.97 (final blend strength, matches value seen in reference screenshot)

**Unchanged:** `crystalToggle`, `handToggle`, `playerToggle`, `selfToggle`, `range`, `fillMode`, `fillOpacity`, `selectImage`, `selectGif`, `frameDelay`, `fillColor`, `selectShader`. `outlineColor` is kept but its label changes to "Glow Color" (tints the glow halo, same role `outlineColor` played for bloom tint before).

---

## Architecture: Dual-Kawase Glow

Dual-Kawase (a.k.a. dual-filter blur) approximates a large-radius Gaussian blur in O(log(radius)) passes instead of O(radius²): downsample the silhouette buffer through N half-resolution mip targets with a 4-tap box filter, then upsample back up through the same chain with an 8-tap tent filter, additively accumulating. Cost is roughly constant regardless of `glowThickness` (only the *chain depth* changes, by 1 level per doubling of thickness), unlike the direct O(radius²) ring-sample loop already present in `fill_only_resolve.fsh`'s bloom branch (hardcoded `w=12.0`, never scaled by a slider).

- **Chain depth** = `clamp(ceil(log2(glowThickness / 4.0)) + 1, 1, 6)` mip levels.
- **Downsample pass** (`glow_downsample.fsh`): 4-tap box filter (offsets ±0.5 texel in each axis of the *source* resolution), halves resolution each iteration.
- **Upsample pass** (`glow_upsample.fsh`): 8-tap tent filter, tap offset scaled by `sampleStep` (classic Kawase "expand" parameter — larger step = fewer effective samples needed for the same visual spread, cheaper but more prone to ring/banding artifacts at the silhouette edge).
- **Resolve pass** (`glow_resolve.fsh`): composites in one place — interior fill (Image/Gif/Shader, untouched logic from `better_chams.fsh`/`fill_only_resolve.fsh`) OR discard if `fillMode==Off`; exterior gets the accumulated glow color tinted by `outlineColor` ("Glow Color"), modulated by `glowIntensity`.

### Two postchain variants (routing unchanged in spirit, condition renamed)

1. **Glow ON** (`glowToggle==true`): full downsample→upsample→resolve chain. Files: `entity_outline.json` (world: Crystal/Player/Self) and `hand_outline.json` (Hand) rewritten to declare N mip targets instead of `jfa_a`/`jfa_b`/`swap`.
2. **Glow OFF, fillMode != Off**: single resolve pass, interior-fill-only, **no exterior halo code at all** (strip the old hardcoded `w=12.0` loop from `fill_only_resolve.fsh` — that loop was a leftover bloom hack; now that Glow is a real on/off master switch, "Glow off" must mean *no halo, period*). Files: `fill_only_outline.json` / `fill_only_hand_outline.json`, shader simplified.
3. **Glow OFF, fillMode == Off**: no postchain needed (existing `MixinShaderManager`/`MixinLevelRenderer` null-intercept behavior, unchanged from today — this case is orthogonal to the hand-fill bug fixed earlier this session).

---

## Files Removed

- `shaders/post/jfa_init.fsh`
- `shaders/post/jfa_flood_{1,2,4,8,16,32}.fsh`
- `shaders/post/jfa_resolve.fsh`
- `shaders/post/better_chams.fsh` (dead code already — not referenced by any `post_effect` json; confirmed via grep. Delete as cleanup, unrelated to Glow migration but found during this pass.)

## Files Added

- `shaders/post/glow_downsample.fsh`
- `shaders/post/glow_upsample.fsh`
- `shaders/post/glow_resolve.fsh`

## Files Rewritten

- `assets/minecraft/post_effect/entity_outline.json` (resource-pack override of vanilla — world entities)
- `assets/example-addon/post_effect/hand_outline.json`
- `assets/example-addon/post_effect/fill_only_outline.json` (simplify `fill_only_resolve.fsh` — drop halo branch)
- `assets/example-addon/post_effect/fill_only_hand_outline.json` (same)

---

## Params Texture Packing (4×1 `betterchamsparam` texture, no new texel — reuse freed slots)

| Pixel | Channel | Old meaning | New meaning |
|---|---|---|---|
| 0 | r | fillEnabled | fillEnabled (unchanged) |
| 0 | g | fillOpacity | fillOpacity (unchanged) |
| 0 | b | bloomEnabled | glowEnabled |
| 0 | a | bloomRadius (0–255) | glowThickness (0–255, unchanged range) |
| 1 | rgba | fillTint | fillTint (unchanged) |
| 2 | rgba | outlineTint | glowTint (renamed only) |
| 3 | r | flipY | flipY (unchanged) |
| 3 | g | outlineOpacity (removed) | **sampleStep** packed `round(sampleStep/4.0*255)`, decode `*4.0/255.0` |
| 3 | b | constant 255 | **glowIntensity** packed `round(glowIntensity*255)` |
| 3 | a | constant 255 | unused, keep 255 |

`BetterChams.updateParamsTexture()` rewritten to match this table; `reloadTextureForCurrentMode()` unaffected (only touches `CHAMS_TEXTURE`/`OUTLINE_TEXTURE`, not params).

---

## Mixin Changes (rename-only, no new logic beyond what's already there)

- `MixinShaderManager.betterchams$interceptPostChain`: condition `bloomToggle.getValue() || fillMode!=Off` → `glowToggle.getValue() || fillMode!=Off`.
- `MixinLevelRenderer.betterchams$redirectGetPostChain`: same rename.
- `MixinGameRenderer.betterchams$reprocessHandOutline` / `betterchams$flushHandOutline`: same rename, both guard checks.
- `MixinItemInHandRenderer.betterchams$startHand`: guard added this session (`glowToggle`/`fillMode` check) — just rename `bloomToggle`→`glowToggle`, logic already correct.

No mixin gains new injection points; this migration is shader/config-layer only.

---

## Fallback Plan (kept as escape hatch, not built preemptively)

If in-world testing (see note below) shows dual-Kawase is *not* faster than a direct single-pass ring-sample loop (Approach A from brainstorming — stride the sampling loop by `sampleStep` texels instead of checking every texel, capped by `glowThickness` radius), swap only `glow_resolve.fsh`'s glow-accumulation source: instead of reading the upsampled mip chain, do the ring loop directly against the raw silhouette buffer with `sampleStep` as the loop stride. This changes one shader + drops the mip targets from the two rewritten postchain jsons — no Java/mixin/option changes needed, since `glowThickness`/`sampleStep`/`glowIntensity` already mean the right things for either implementation. Decision point: after first in-world perf test, before considering the feature done.

---

## Note: Manual In-World Perf Test Protocol (informational, not part of implementation — run after code lands)

1. Load a scene with 10–20+ simultaneous outlined entities (spawn multiple FakePlayer / mobs via existing kit/Baritone tooling) — single-entity scenes don't show pipeline cost difference.
2. Open F3 debug screen, read frame time (ms), not just FPS (less noisy).
3. Stand still facing the entity cluster, let FPS settle 5–10s, record baseline at `glowThickness=8` and `glowThickness=64` separately — dual-Kawase cost should barely move between the two; a ring-sample fallback would visibly degrade at 64.
4. If dual-Kawase underperforms a hand-rolled ring-sample test build by >10–15% at any thickness, or shows visible banding/ring artifacts at high `sampleStep`, switch to the fallback per the section above.
5. Otherwise, ship dual-Kawase as-is.
