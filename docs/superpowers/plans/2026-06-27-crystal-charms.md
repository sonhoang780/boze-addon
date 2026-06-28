# CrystalCharms Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render a crisp 1px + bloom outline following the exact End Crystal silhouette using MC's entity outline framebuffer system with a custom post-effect shader.

**Architecture:** Set `state.outlineColor` on nearby crystals via mixin → MC routes crystal geometry to entity_outline framebuffer → our `entity_outline.json` override replaces vanilla Sobel+blur with a Mint-style 2D box-blur bloom pass → blitted to main by vanilla `drawEntityOutlinesFramebuffer()`.

**Tech Stack:** Java 25, Fabric Mixin, GLSL 330, MC 26.1.2 Mojmap, Boze API 3.2.2.

## Global Constraints

- MC version: 26.1.2 (Mojmap class names — NOT Yarn/1.21.11 names)
- Boze API: 3.2.2 — use `dev.boze.api.addon.AddonModule`, `SliderOption`
- `#version 330` in shaders (not `330 core`) — matches MC's own shaders
- Build command: `.\gradlew build` — only source of truth for compile errors
- Mixin JSON: `bin/main/example-addon.mixins.json` (NOT `src/main/resources/`)
- Module registration: `src/main/java/com/example/addon/ExampleAddon.java`

---

## Key Verified Names (do NOT guess alternatives)

| Yarn | Mojmap |
|------|--------|
| `EndCrystalEntityRenderer` in `net.minecraft.client.render.entity` | `EndCrystalRenderer` in `net.minecraft.client.renderer.entity` |
| `EndCrystalEntityRenderState` in `net.minecraft.client.render.entity.state` | `EndCrystalRenderState` in `net.minecraft.client.renderer.entity.state` |
| `EndCrystalEntity` in `net.minecraft.entity.decoration` | `EndCrystal` in `net.minecraft.world.entity.boss.enderdragon` |
| `updateRenderState(...)` | `extractRenderState(...)` |

`state.outlineColor` is `int` on `EntityRenderState` (parent of `EndCrystalRenderState`). Format: `0xAARRGGBB`. Zero = no outline. Setting it non-zero enables the entity outline post-process.

---

## File Map

| Action | Path |
|--------|------|
| Create | `src/main/resources/assets/example-addon/shaders/post/crystal_glow.fsh` |
| Create | `src/main/resources/assets/minecraft/post_effect/entity_outline.json` |
| Create | `src/main/java/com/example/addon/modules/CrystalCharms.java` |
| Create | `src/main/java/com/example/addon/mixin/MixinEndCrystalRenderer.java` |
| Modify | `bin/main/example-addon.mixins.json` |
| Modify | `src/main/java/com/example/addon/ExampleAddon.java` |

---

### Task 1: Bloom shader + entity_outline.json override

**Files:**
- Create: `src/main/resources/assets/example-addon/shaders/post/crystal_glow.fsh`
- Create: `src/main/resources/assets/minecraft/post_effect/entity_outline.json`

**How it works:**
- Pass 1: `crystal_glow.fsh` reads `minecraft:entity_outline` (solid-colored crystal silhouette) → writes bloom to `swap`
- Pass 2: vanilla `blit` copies `swap` → `minecraft:entity_outline`
- `drawEntityOutlinesFramebuffer()` then blits `entity_outline` over `main` (additive alpha composite)

- [ ] **Step 1: Create shader directories**

```powershell
New-Item -ItemType Directory -Force "src/main/resources/assets/example-addon/shaders/post"
New-Item -ItemType Directory -Force "src/main/resources/assets/minecraft/post_effect"
```

- [ ] **Step 2: Write crystal_glow.fsh**

Create `src/main/resources/assets/example-addon/shaders/post/crystal_glow.fsh`:

```glsl
#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / InSize;
    vec4 center = texture(InSampler, texCoord);

    if (center.a > 0.0) {
        // Outline-only: discard filled crystal interior — show nothing inside silhouette
        discard;
    }

    // 1px crisp edge: if any immediate neighbor is inside silhouette, emit solid pixel
    for (int dx = -1; dx <= 1; ++dx) {
        for (int dy = -1; dy <= 1; ++dy) {
            if (dx == 0 && dy == 0) continue;
            vec4 nb = texture(InSampler, texCoord + oneTexel * vec2(float(dx), float(dy)));
            if (nb.a > 0.0) {
                fragColor = vec4(nb.rgb, 1.0);
                return;
            }
        }
    }

    // Soft bloom halo: 2D box blur over crystal silhouette
    // radius=6, step=3 → w=18 → 13 steps/axis → 169 samples
    // normalization = ((6^2+6)*4) = 168; glowMultiplier = 1.5 → divide by 112.0
    float alphaSum = 0.0;
    vec3  rgbSum   = vec3(0.0);
    float stepSize = 3.0;
    float w        = 18.0;
    for (float x = -w; x <= w; x += stepSize) {
        for (float y = -w; y <= w; y += stepSize) {
            vec4 s = texture(InSampler, texCoord + oneTexel * vec2(x, y));
            alphaSum += s.a;
            rgbSum   += s.rgb * s.a;
        }
    }

    float blurStrength = clamp(alphaSum / 112.0, 0.0, 1.0);
    if (blurStrength == 0.0) discard;

    vec3 glowColor = (alphaSum > 0.0) ? rgbSum / alphaSum : vec3(1.0);
    fragColor = vec4(glowColor, blurStrength);
}
```

- [ ] **Step 3: Write entity_outline.json override**

Create `src/main/resources/assets/minecraft/post_effect/entity_outline.json`:

```json
{
    "targets": {
        "swap": {}
    },
    "passes": [
        {
            "vertex_shader": "minecraft:core/screenquad",
            "fragment_shader": "example-addon:post/crystal_glow",
            "inputs": [
                {
                    "sampler_name": "In",
                    "target": "minecraft:entity_outline"
                }
            ],
            "output": "swap"
        },
        {
            "vertex_shader": "minecraft:core/screenquad",
            "fragment_shader": "minecraft:post/blit",
            "inputs": [
                {
                    "sampler_name": "In",
                    "target": "swap"
                }
            ],
            "uniforms": {
                "BlitConfig": [
                    {
                        "name": "ColorModulate",
                        "type": "vec4",
                        "value": [ 1.0, 1.0, 1.0, 1.0 ]
                    }
                ]
            },
            "output": "minecraft:entity_outline"
        }
    ]
}
```

- [ ] **Step 4: Build check**

```powershell
.\gradlew build
```

Expected: `BUILD SUCCESSFUL` — no shader or JSON syntax errors (these would only surface at runtime, build won't catch them).

---

### Task 2: CrystalCharms module

**Files:**
- Create: `src/main/java/com/example/addon/modules/CrystalCharms.java`
- Modify: `src/main/java/com/example/addon/ExampleAddon.java` (add import + `modules.add(CrystalCharms.INSTANCE)`)

- [ ] **Step 1: Write CrystalCharms.java**

Create `src/main/java/com/example/addon/modules/CrystalCharms.java`:

```java
package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

public class CrystalCharms extends AddonModule {
    public static final CrystalCharms INSTANCE = new CrystalCharms();

    public final SliderOption range = new SliderOption(this, "Range",
        "Max distance to show bloom outline on End Crystals (blocks).", 16.0, 8.0, 64.0, 1.0);

    public CrystalCharms() {
        super("CrystalCharms", "Bloom outline on nearby End Crystals.");
    }

    public boolean isInRange(EndCrystal crystal) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double r = range.getValue();
        return mc.player.distanceToSqr(crystal) <= r * r;
    }
}
```

- [ ] **Step 2: Register in ExampleAddon.java**

In `src/main/java/com/example/addon/ExampleAddon.java`:

Add import (after existing imports):
```java
import com.example.addon.modules.CrystalCharms;
```

Add registration (inside `initialize()`, after `modules.add(ChestButtons.INSTANCE);`):
```java
modules.add(CrystalCharms.INSTANCE);
```

- [ ] **Step 3: Build check**

```powershell
.\gradlew build
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 3: MixinEndCrystalRenderer + mixin registration

**Files:**
- Create: `src/main/java/com/example/addon/mixin/MixinEndCrystalRenderer.java`
- Modify: `bin/main/example-addon.mixins.json` (add `"MixinEndCrystalRenderer"` to `client` list)

**How it works:**
- Injects at RETURN of `extractRenderState` (Mojmap name for Yarn's `updateRenderState`)
- If module enabled + crystal within range: sets `state.outlineColor = 0xFFFFFFFF` (white, full alpha)
- MC then routes this crystal's geometry to entity_outline framebuffer → our bloom pass renders it

- [ ] **Step 1: Write MixinEndCrystalRenderer.java**

Create `src/main/java/com/example/addon/mixin/MixinEndCrystalRenderer.java`:

```java
package com.example.addon.mixin;

import com.example.addon.modules.CrystalCharms;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalRenderer.class)
public abstract class MixinEndCrystalRenderer {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;F)V",
        at = @At("RETURN")
    )
    private void crystalcharms$setOutlineColor(
        EndCrystal crystal,
        EndCrystalRenderState state,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (CrystalCharms.INSTANCE.getState() && CrystalCharms.INSTANCE.isInRange(crystal)) {
            state.outlineColor = 0xFFFFFFFF; // white bloom
        }
    }
}
```

- [ ] **Step 2: Register mixin in bin/main/example-addon.mixins.json**

In `bin/main/example-addon.mixins.json`, add `"MixinEndCrystalRenderer"` to `client` list:

```json
  "client": [
    "MixinMinecraftClient",
    "MixinGameRenderer",
    "MixinLivingEntity",
    "MixinLivingEntityRenderer",
    "MixinEntity",
    "MixinMultiPlayerGameMode",
    "MixinPlayerLikeEntity",
    "GuiGraphicsExtractorAccessor",
    "MixinLocalPlayer",
    "MixinEndCrystalRenderer"
  ]
```

- [ ] **Step 3: Build + verify**

```powershell
.\gradlew build
```

Expected: `BUILD SUCCESSFUL`. If mixin class name is wrong you'll see `ClassNotFoundError` or similar at runtime, not compile time.

- [ ] **Step 4: In-game test**

1. Load game, enable CrystalCharms
2. Place / find End Crystal within 16 blocks
3. Expected: white bloom outline follows crystal silhouette (including spikes + rotating gem)
4. Fly > 16 blocks away → bloom disappears
5. Other entities with Glowing effect → they get bloom instead of vanilla Sobel edge (expected behavior, tradeoff approved)

---

## Tradeoffs & Known Limitations

- **entity_outline.json override** affects ALL entity outlines (glowing players, etc. also get bloom) — approved in PvP context
- Glow parameters hardcoded in shader (`radius=6`, `step=3`, `multiplier=1.5`) — runtime-configurable uniforms require PostEffectProcessor API extension (future work)
- Color hardcoded to white (0xFFFFFFFF) — the outlineColor tints geometry in entity_outline framebuffer; customizable color requires passing color through module settings and computing from `ClientColor.getPacked()` (future work)
