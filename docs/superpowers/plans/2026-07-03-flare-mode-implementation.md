# BetterChams Flare Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild BetterChams' `Flare` toggle as a real volumetric fire effect (ported from Xor's "3D Fire" Shadertoy, 10 outer / 5 inner raymarch iterations) that wraps every currently-glowing silhouette (player, other players, crystals, hand) simultaneously, and lags/settles when the camera rotates.

**Architecture:** Per-pixel, in the same compositing shaders that already resolve the silhouette (`glow_resolve.fsh` for Glow-on, `fill_only_resolve.fsh` for Glow-off): compute a manual 4-tap gradient of the raw silhouette alpha to get an "outward" direction at any pixel near a silhouette edge (works whether or not Glow's blur ran, and needs no per-entity data — it's evaluated independently at every pixel, so it automatically covers however many entities are glowing at once). Project the pixel's own screen position onto the local (tangent, outward) basis to build a small 2D local canvas, feed that into a literal port of Xor's raymarch as its `fragCoord`/`iResolution`. Java-side (`BetterChams.java`) only needs to smooth a camera yaw/pitch lag offset and pack a handful of scalars (enabled, tint, canvas size, lag offset, time) into a widened params texture — no entity iteration, no projection matrices.

**Tech Stack:** Java (Fabric/Boze API), GLSL 330 (PostChain fragment shaders), MC 26.1.2.

## Global Constraints

- `.\gradlew build` after every task — this project has no automated shader/visual test harness; `.\gradlew build` (Java compile) + an in-game manual visual check is the established verification pattern for every prior feature this session.
- NativeImage packing in this codebase is ABGR: `(alpha << 24) | (blue << 16) | (green << 8) | red`.
- Raymarch iteration counts are fixed at **10 outer / 5 inner** per the approved spec (`docs/superpowers/specs/2026-07-03-flare-mode-design.md`) — not the reference's 50/5.
- Flare is fully independent of `glowToggle` — every task that touches shader control flow must preserve "Flare renders correctly whether Glow is enabled or disabled."
- Flare must apply to every simultaneously-glowing silhouette, not a single selected entity — no task should reintroduce per-entity selection/bounding-box logic.

**Params texture layout (final, `betterchamsparam`, widened from 4x1 to 7x1):**
| Texel | R | G | B | A |
|---|---|---|---|---|
| 0 (existing) | fillOn | fillOpacity | glowOn | glowThickness |
| 1 (existing) | fillColor.r | fillColor.g | fillColor.b | fillColor.a |
| 2 (existing) | outlineColor.r | outlineColor.g | outlineColor.b | outlineColor.a |
| 3 (existing) | flipY | innerGlow | glowIntensity | (unused, 255) |
| 4 (new) | flareEnabled | yawOffset (byte, -90..90) | pitchOffset (byte, -90..90) | flareSize (byte, 0..128px) |
| 5 (new) | flareTint.r | flareTint.g | flareTint.b | flareTint.a |
| 6 (new) | flareTime (byte, 0..10s wraparound) | unused | unused | 255 |

---

### Task 1: Flare options + packed params (no per-entity data)

**Files:**
- Modify: `src/main/java/com/example/addon/modules/BetterChams.java`

**Interfaces:**
- Produces: `flareToggle` (`ToggleOption`), `flareTint` (`ColorOption`), `flareSize` (`SliderOption`, local canvas size in pixels), and packs their values plus a smoothed camera-lag yaw/pitch offset and a wraparound time value into a widened `betterchamsparam` texture (see layout table above) via the existing `updateParamsTexture()` method — no new texture, no per-entity fields, no projection matrices.

**Step 1: Add the options**

In `BetterChams.java`, add near the existing `glowIntensity` field:

```java
public final ToggleOption flareToggle = new ToggleOption(this, "Flare",
    "Volumetric fire wrapping every currently-glowing silhouette. Independent of Glow -- Glow (if also on) adds bloom on top of the flame.", false);
public final dev.boze.api.option.ColorOption flareTint = new dev.boze.api.option.ColorOption(this, "FlareTint",
    "Tint multiplied onto the flare's base fire palette.", dev.boze.api.render.ColorMaker.staticColor(255, 120, 30), 1.0f);
public final SliderOption flareSize = new SliderOption(this, "Flare Size",
    "Size (px) of the local canvas each silhouette edge point's flame is rendered into.", 48.0, 8.0, 128.0, 1.0);
```

**Step 2: Add camera-lag smoothing fields**

Add near the other private fields:

```java
private float flareLaggedYaw = 0f, flareLaggedPitch = 0f;
private boolean flareLagInitialized = false;
```

**Step 3: Widen the params texture to 7x1**

In `registerTextures()`, change the existing 4x1 allocation:

```java
NativeImage img = new NativeImage(NativeImage.Format.RGBA, 7, 1, false);
img.setPixelABGR(0, 0, 0xFF0000FF); // glow on, fill off, opacity 0, thickness max
img.setPixelABGR(1, 0, 0xFFFFFFFF); // fill color
img.setPixelABGR(2, 0, 0xFFFFFFFF); // outline color
img.setPixelABGR(3, 0, 0xFFFFFFFF); // flipY (255 = flip, 0 = no flip)
img.setPixelABGR(4, 0, 0xFF000000); // flare enabled/yaw/pitch/size, filled in by updateParamsTexture()
img.setPixelABGR(5, 0, 0xFFFFFFFF); // flare tint
img.setPixelABGR(6, 0, 0xFF0000FF); // flare time
paramsTexture = new DynamicTexture(() -> "chams-params", img);
mc.getTextureManager().register(PARAMS_ID, paramsTexture);
```

**Step 4: Pack flare data into texels 4-6**

In `updateParamsTexture()`, after the existing `pixels.setPixelABGR(3, 0, flipAbgr);` line, add:

```java
Minecraft mc = Minecraft.getInstance();
float realYaw = mc.player != null ? mc.player.getYRot() : 0f;
float realPitch = mc.player != null ? mc.player.getXRot() : 0f;
if (!flareLagInitialized) { flareLaggedYaw = realYaw; flareLaggedPitch = realPitch; flareLagInitialized = true; }
flareLaggedYaw += net.minecraft.util.Mth.wrapDegrees(realYaw - flareLaggedYaw) * 0.15f;
flareLaggedPitch += (realPitch - flareLaggedPitch) * 0.15f;
float yawOffset = net.minecraft.util.Mth.wrapDegrees(realYaw - flareLaggedYaw);
float pitchOffset = realPitch - flareLaggedPitch;

boolean flareOn = on && flareToggle.getValue();
int flareR = flareOn ? 255 : 0;
int flareG = Math.round((Math.max(-90f, Math.min(90f, yawOffset)) + 90f) / 180f * 255f) & 0xFF;
int flareB = Math.round((Math.max(-90f, Math.min(90f, pitchOffset)) + 90f) / 180f * 255f) & 0xFF;
int flareA = Math.round((float)(Math.max(8.0, Math.min(128.0, flareSize.getValue())) / 128.0 * 255.0)) & 0xFF;
pixels.setPixelABGR(4, 0, (flareA << 24) | (flareB << 16) | (flareG << 8) | flareR);

int flareTintC = flareTint.getValue().color.getPacked();
int flareTintAbgr = (flareTintC & 0xFF000000) | ((flareTintC & 0xFF) << 16) | (flareTintC & 0xFF00) | ((flareTintC >> 16) & 0xFF);
pixels.setPixelABGR(5, 0, flareTintAbgr);

// 10-second wraparound, byte-quantized (~40ms resolution -- fine for a flickering
// flame, not for anything needing frame-exact sync).
int flareTimeR = (int) ((System.currentTimeMillis() % 10000L) / 10000.0 * 255.0) & 0xFF;
pixels.setPixelABGR(6, 0, (0xFF << 24) | flareTimeR);
```

**Step 5: Build**

Run: `.\gradlew build -q -x test`
Expected: exit code 0.

**Step 6: Commit**

```bash
git add src/main/java/com/example/addon/modules/BetterChams.java
git commit -m "feat: BetterChams Flare options + packed params (no per-entity data)"
```

---

### Task 2: Port the Xor 3D Fire raymarch with the per-pixel gradient canvas into `glow_resolve.fsh`

**Files:**
- Modify: `src/main/resources/assets/example-addon/shaders/post/glow_resolve.fsh`

**Interfaces:**
- Consumes: `ParamsSampler` texels 4/5/6 from Task 1 (flare enabled/yaw/pitch/size, tint, time).
- Produces: a `vec3 flareFire(vec2 localFragCoord, vec2 localResolution, float time, vec3 tint)` function and the per-pixel gradient/canvas-construction code that calls it.

**Step 1: Add the raymarch function**

Add above `main()`:

```glsl
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
```

(The reference's `mat2(cos(p.y*.5 + vec4(0,33,11,0)))` constructor trick is replaced with an explicit standard 2D rotation matrix — mathematically equivalent for the rotation itself; the reference's extra phase offsets are a stylistic variation not needed here, the turbulence loop already provides shape variation.)

**Step 2: Read the flare params texels**

Find the existing block that reads `params`/`fillTint`/`glowTint`/`flipData` near the top of `main()` and add, right after it:

```glsl
vec4 flareData = texelFetch(ParamsSampler, ivec2(4, 0), 0);
vec4 flareTintData = texelFetch(ParamsSampler, ivec2(5, 0), 0);
vec4 flareTimeData = texelFetch(ParamsSampler, ivec2(6, 0), 0);

bool flareEnabled = flareData.r > 0.5;
float flareYawOffset = flareData.g * 180.0 - 90.0;
float flarePitchOffset = flareData.b * 180.0 - 90.0;
float flareSizePx = flareData.a * 128.0;
vec3 flareTint = flareTintData.rgb;
float flareTime = flareTimeData.r * 10.0;
```

**Step 3: Compute the per-pixel flame contribution**

Immediately after the existing `vec4 orig = texture(OriginalSampler, texCoord);` line, add:

```glsl
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
        // camera-lag angle, so the flame's own local "up" twists with a camera turn
        // instead of sliding sideways.
        float lagAngle = radians(flareYawOffset) + radians(flarePitchOffset) * 0.3;
        float ca = cos(lagAngle), sa = sin(lagAngle);
        vec2 outwardLagged = mat2(ca, -sa, sa, ca) * outward;
        vec2 tangentLagged = vec2(-outwardLagged.y, outwardLagged.x);

        vec2 screenPx = texCoord * OutSize;
        vec2 localFragCoord = vec2(dot(screenPx, tangentLagged), dot(screenPx, outwardLagged));
        flareContribution = flareFire(localFragCoord, vec2(flareSizePx), flareTime, flareTint);
    }
}
```

**Step 4: Add `flareContribution` everywhere `fragColor` gets assigned or the pixel gets discarded**

The rest of `main()` has several `fragColor = ...; return;` sites and two `discard;` sites. Update each so Flare shows up regardless of which branch runs, including turning bare discards into a flare-only fallback:

1. Inner-glow block: after `fragColor = hasFill ? mix(fillResult, rimColor, rimColor.a) : rimColor;`, add:
   ```glsl
   fragColor.rgb += flareContribution;
   ```
2. Fill-only block: after `fragColor = fillResult;`, add:
   ```glsl
   fragColor.rgb += flareContribution;
   ```
3. The interior `discard;` (no fill, no inner glow) — replace with:
   ```glsl
   if (length(flareContribution) > 0.001) { fragColor = vec4(flareContribution, 1.0); return; }
   discard;
   ```
4. `if (glowEnabled < 0.5) { discard; }` — replace with:
   ```glsl
   if (glowEnabled < 0.5) {
       if (length(flareContribution) > 0.001) { fragColor = vec4(flareContribution, 1.0); return; }
       discard;
   }
   ```
5. `if (glow.a <= 0.0) { discard; }` — replace with:
   ```glsl
   if (glow.a <= 0.0) {
       if (length(flareContribution) > 0.001) { fragColor = vec4(flareContribution, 1.0); return; }
       discard;
   }
   ```
6. Final halo assignment: after `fragColor = vec4(glowTint.rgb, glow.a * glowIntensity * glowTint.a);`, add:
   ```glsl
   fragColor.rgb += flareContribution;
   ```

**Step 5: Build**

Run: `.\gradlew build -q -x test`
Expected: exit 0.

**Step 6: Manual in-game verification**

Enable BetterChams, enable Flare (Glow can be on or off), stand near an end crystal or another player so at least two glowing silhouettes are visible at once. Expect an animated, turbulent flame wrapping BOTH silhouettes independently, each following its own shape. Compare the turbulent character against Xor's reference (https://www.shadertoy.com/view/3XXSWS) in a browser.

Rotate the camera quickly left/right then stop. Expect the flame's local orientation to visibly twist/swirl during the rotation and settle within roughly half a second.

**Step 7: Commit**

```bash
git add src/main/resources/assets/example-addon/shaders/post/glow_resolve.fsh
git commit -m "feat: port Xor 3D Fire raymarch with per-pixel gradient canvas into glow_resolve.fsh"
```

---

### Task 3: Same integration in `fill_only_resolve.fsh` (Glow-disabled path)

**Files:**
- Modify: `src/main/resources/assets/example-addon/shaders/post/fill_only_resolve.fsh`

**Interfaces:**
- Consumes: the exact `flareFire` function body from Task 2 (duplicated — these are separate compiled programs with no shared-include mechanism, matching the existing pattern where these two files already duplicate similar structure).

**Step 1: Add the identical `flareFire` function and the flare params texel reads**

Copy verbatim from Task 2 Steps 1-2. This file's silhouette sampler is `InSampler` (confirmed from the current file content), not `OriginalSampler` — the gradient taps below use `InSampler`.

**Step 2: Replace this file's body**

This file currently has exactly: read `params`/`fillTint`/`flipData`, compute `orig`, one `if (orig.a > 0.0 && fillEnabled > 0.5) { ...; return; }`, and a trailing `discard;`. Replace the whole body of `main()` with:

```glsl
void main() {
    vec4 params = texelFetch(ParamsSampler, ivec2(0, 0), 0);
    vec4 fillTint = texelFetch(ParamsSampler, ivec2(1, 0), 0);
    vec4 flipData = texelFetch(ParamsSampler, ivec2(3, 0), 0);
    vec4 flareData = texelFetch(ParamsSampler, ivec2(4, 0), 0);
    vec4 flareTintData = texelFetch(ParamsSampler, ivec2(5, 0), 0);
    vec4 flareTimeData = texelFetch(ParamsSampler, ivec2(6, 0), 0);

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
            vec2 screenPx = texCoord * OutSize;
            vec2 localFragCoord = vec2(dot(screenPx, tangentLagged), dot(screenPx, outwardLagged));
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
```

**Step 3: Build**

Run: `.\gradlew build -q -x test`
Expected: exit 0.

**Step 4: Manual in-game verification**

Turn Glow OFF, keep Flare ON, same multi-silhouette scene as Task 2 Step 6. Expect the flame to render identically to the Glow-on case. This confirms "Flare independent of Glow."

**Step 5: Commit**

```bash
git add src/main/resources/assets/example-addon/shaders/post/fill_only_resolve.fsh
git commit -m "feat: port Xor 3D Fire raymarch into fill_only_resolve.fsh (Glow-off path)"
```

---

### Task 4: Verify Glow bloom stacks on top of Flare when both are enabled

**Files:**
- None expected (verification-only task).

**Step 1: Manual in-game check**

Enable both Flare and Glow, same multi-silhouette scene. Compare against Flare-alone. Expect the flame's bright pixels to visibly bloom/haze outward, same as Glow already does for the plain white silhouette.

**Step 2: If bloom does NOT pick up the flame**

`GlowBlur`'s bright-pass reads `minecraft:entity_outline`'s raw texture directly, populated BEFORE `glow_resolve.fsh`'s resolve pass adds the flame — Glow's bloom can't see pixels that only exist because of Flare's own contribution. This is an ordering limitation, not a bug. Do not attempt a pipeline reorder in this task. Add a one-line comment in `glow_resolve.fsh` near the `flareContribution` block documenting this as a known v1 limitation:

```glsl
// NOTE (known v1 limitation): GlowBlur's bright-pass reads minecraft:entity_outline's
// raw texture BEFORE this resolve pass adds flareContribution, so Glow's bloom does
// not pick up the flame's own bright pixels. Fixing this needs a pipeline reorder
// (running the raymarch earlier, or restructuring compositing order) -- out of scope
// for v1.
```

**Step 3: Commit only if Step 2's comment was added**

```bash
git add src/main/resources/assets/example-addon/shaders/post/glow_resolve.fsh
git commit -m "docs: note Glow+Flare bloom-stacking limitation in glow_resolve.fsh"
```

---

### Task 5: Performance pass

**Files:**
- Modify: `src/main/resources/assets/example-addon/shaders/post/glow_resolve.fsh`
- Modify: `src/main/resources/assets/example-addon/shaders/post/fill_only_resolve.fsh`

**Step 1: Measure frame time with Flare on vs off**

In-game, with Flare on and 2-3 glowing silhouettes visible at a typical distance, note the FPS counter (or `F3` debug overlay) with Flare on, then toggle it off, same scene. Record both numbers.

**Step 2: Decide based on the drop**

- Small drop (a few fps): done, no further tuning for v1.
- Large/objectionable drop: reduce the inner turbulence loop first (bigger multiplier) — e.g. change the loop's divisor from `/= 0.6` to a coarser step, or lower the outer loop below 10 — in that order, re-measuring after each change.

**Step 3: Build and re-verify visually**

Run: `.\gradlew build -q -x test`
Expected: exit 0. Re-check the flame still looks reasonably close to the reference's character after any iteration-count changes.

**Step 4: Commit**

```bash
git add src/main/resources/assets/example-addon/shaders/post/glow_resolve.fsh src/main/resources/assets/example-addon/shaders/post/fill_only_resolve.fsh
git commit -m "perf: tune Flare raymarch iteration counts based on in-game frame time"
```
