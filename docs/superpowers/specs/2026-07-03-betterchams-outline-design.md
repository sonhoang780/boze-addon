# BetterChams: Outline feature

## Purpose

Add a fourth, independent BetterChams effect layer: a crisp, non-blurred outline
hugging the silhouette edge. Distinct from the existing three layers:
- **Glow** — blurred halo (Kawase pyramid), `glowToggle`/`glowThickness`/`outlineColor`("GlowColor")
- **Flare** — volumetric fire wrapping the silhouette, `flareToggle`/`flareSize`/`flareTint`
- **Fill** — image/gif/shader fill, `fillMode`/`fillColor`

Outline must work standalone (Glow/Flare/Fill all off) and composite correctly
when any combination of the others is also on.

## Options (new)

| Field | Type | Display name | Range/default |
|---|---|---|---|
| `outlineToggle` | ToggleOption | "Outline" | default off |
| `outlineRadius` | SliderOption | "Outline Radius" | 1.0–5.0 px, default 2.0 |
| `outlineTint` | ColorOption | "OutlineColor" | default white |

Named to avoid clashing with the existing `outlineColor` field (bound to display
name "GlowColor", already taken) and the existing `OUTLINE_TEXTURE`/`OUTLINE_TEX_ID`
("betterchamsoutline"/"OutlineImage", the unrelated Shader-fill-mode outline
texture).

## Technique

Outline does **not** need the blurred silhouette field. It's a ring-sample
edge-detect against the **raw** silhouette texture, evaluated from just outside
the silhouette (mirrors the existing InnerGlow technique in `glow_resolve.fsh`,
which does the same ring-sample from just inside — but with a fixed 6px radius
and hardcoded 8-tap sample; here the test flips to "am I just outside the
silhouette, within `outlineRadius` px, with the silhouette on one side of me,"
and the radius is the user-configured slider value instead of a constant).

Because it only needs the raw silhouette, it can be added to **both** resolve
shaders (`glow_resolve.fsh` and `fill_only_resolve.fsh`), giving it full
coverage across every BetterChams rendering path without needing the blur
pyramid to run.

## New texture: `betterchamsoutlineparam`

A dedicated 2x1 `DynamicTexture` (`OUTLINE_PARAMS_ID`), following the same
pattern as `FLARE_PARAMS_ID` — kept separate from `betterchamsparam` (which is
hardcoded to `"width": 4` in five existing JSON declarations; widening it broke
Glow/Flare entirely once already, per the existing comment in
`BetterChams.java`).

- Pixel 0: `r` = enabled (0/255), `g` = radius packed `round(radius / 5.0 * 255)`, `b`/`a` unused (0/255).
- Pixel 1: RGB = `outlineTint` color (via `getRed()/getGreen()/getBlue()`, alpha forced to `0xFF` — same fix just applied to the other color options, since `getPacked()`'s alpha byte isn't reliable for Gradient-mode `ClientColor`s).

## Compositing order

Per user decision: **Glow halo + Flare draw on top of Outline** (Outline is the
innermost fixed layer; halo/flare, when also on, visually dominate over it in
their overlap region — Outline is most visible when Glow is off or in the halo's
more-transparent outer reach).

Standard "src-over" alpha compositing, computed once per pixel in the
outside-silhouette branch:

```
outlineContribution = (outlineTint.rgb, edgeAlpha)   // edgeAlpha from ring-sample, 0 if not near edge or toggle off
haloFlare            = (halo.rgb + flare.rgb, max(halo.a, flareLum))  // existing computation, unchanged

finalRGB = haloFlare.rgb * haloFlare.a + outlineContribution.rgb * outlineContribution.a * (1 - haloFlare.a)
finalA   = haloFlare.a + outlineContribution.a * (1 - haloFlare.a)
```

This replaces the existing early-out (`if (!haloOn && flareLum <= 0.003) { fragColor = vec4(0.0); return; }`)
in `glow_resolve.fsh` — Outline must still be evaluated even when neither halo
nor flare is active, so the early-out becomes conditional on Outline also being
off.

`fill_only_resolve.fsh` currently `discard`s outside the silhouette entirely;
that becomes an Outline-only evaluation (no halo/flare exist on this path by
construction — it's only ever routed to when Glow and Flare are both off).

## Files touched

**Java:**
- `BetterChams.java` — 3 new options, `OUTLINE_PARAMS_ID` + `outlineParamsTexture`, pack/upload logic (folded into the existing `updateParamsTexture()` tick, no new per-frame event needed since Outline has no animation).

**Shaders:**
- `glow_resolve.fsh` — new `OutlineParamsSampler` uniform, ring-sample + composite.
- `fill_only_resolve.fsh` — new `OutlineParamsSampler` uniform, ring-sample + composite (replacing the outside-silhouette `discard`).

**Post-effect JSON (add one sampler input to the existing resolve pass in each):**
- `assets/minecraft/post_effect/entity_outline.json`
- `assets/example-addon/post_effect/hand_outline.json`
- `assets/example-addon/post_effect/fill_only_outline.json`
- `assets/example-addon/post_effect/fill_only_hand_outline.json`

**Gating** (add `|| outlineToggle.getValue()` to the existing three-way
"is anything active" check so Outline alone triggers silhouette capture and
postchain routing):
- `MixinShaderManager` (1 site)
- `MixinLevelRenderer` — both the routing-gate check and the fill_only-chain-selection condition (route to the cheap `fill_only` chain when Glow+Flare are off and either Fill or Outline is on)
- `MixinAvatarRenderer` (1 site)
- `MixinEndCrystalRenderer` (1 site)
- `MixinGameRenderer` (3 sites)
- `MixinItemInHandRenderer` (1 site)

## Out of scope (not requested)

- Separate outline opacity/intensity slider (solid edge-alpha only, like the
  existing InnerGlow toggle's fixed-strength approach).
- Animated/gradient-along-edge outline.
- Per-mode (Static/Changing/Gradient) special-casing beyond the alpha fix
  already applied to the other color options.
