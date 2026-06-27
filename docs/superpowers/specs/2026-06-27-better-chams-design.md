# BetterChams Design Spec

**Date:** 2026-06-27  
**Phase:** 1 (Crystal + Player). Phase 2 = Hand (separate spec, different pipeline).

---

## Goal

Replace `CrystalCharms` with `BetterChams`: bloom outline + optional image fill for End Crystals and other Players. Image fill = screen-space stencil (entities are "holes" through which a shared image shows — perspective arises naturally from entity screen size).

---

## Scope

**In scope (Phase 1):**
- Migrate `CrystalCharms` → `BetterChams` (delete old module)
- Bloom outline on End Crystals (existing tech, re-wired to BetterChams)
- Bloom outline on other players (new `MixinPlayerRenderer`)
- Image fill: shared PNG/JPG overlaid inside all entity silhouettes with configurable opacity
- `ImagePickerScreen`: custom HUD to pick image from `<gamedir>/boze/images/`

**Out of scope (Phase 2):**
- First-person hand/arm chams (uses different render path — after entity_outline pipeline runs; requires separate framebuffer + post-pass)

---

## Module Settings

```
BetterChams (AddonModule)
├── crystalToggle  ToggleOption  "Bloom outline on End Crystals"          default ON
├── playerToggle   ToggleOption  "Bloom outline on other players"          default ON
├── range          SliderOption  "Max range (blocks)"    8–64, default 16
├── bloomToggle    ToggleOption  "Show bloom halo"                         default ON
├── fillToggle     ToggleOption  "Show image fill inside entities"         default OFF
├── fillOpacity    SliderOption  "Fill image opacity"   0.0–1.0, default 0.8
└── selectImage    ToggleOption  "Open image picker" → opens ImagePickerScreen, resets to false
```

`isInRange(Entity e)`: `mc.player.distanceToSqr(e) <= range^2`.

---

## Architecture: Shader Pipeline

### Shader file

`src/main/resources/assets/example-addon/shaders/post/better_chams.fsh`  
(replaces `crystal_glow.fsh`)

```glsl
#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform ChamsFill {
    float fillEnabled;   // 0.0 = outline-only, 1.0 = show fill
    float fillOpacity;
    float bloomEnabled;  // 0.0 = no bloom/edge, 1.0 = show bloom halo + crisp edge
};

uniform sampler2D InSampler;    // entity_outline silhouette (MC auto-bind via "In")
uniform sampler2D ImageSampler; // user image (manually bound to GL_TEXTURE2)

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / InSize;
    vec4 center = texture(InSampler, texCoord);

    if (center.a > 0.0) {
        if (fillEnabled > 0.5) {
            vec4 img = texture(ImageSampler, texCoord);
            fragColor = vec4(img.rgb, img.a * fillOpacity);
        } else {
            discard;
        }
        return;
    }

    // 1px crisp edge
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

    // Soft bloom halo (box blur, radius=6, step=3 → 169 samples)
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

    // When bloom disabled: discard all halo pixels
    if (bloomEnabled < 0.5) discard;

    // normalization=168, multiplier=1.5 → divide by 112
    float blurStrength = clamp(alphaSum / 112.0, 0.0, 1.0);
    if (blurStrength == 0.0) discard;

    vec3 glowColor = (alphaSum > 0.0) ? rgbSum / alphaSum : vec3(1.0);
    fragColor = vec4(glowColor, blurStrength);
}
```

> `bloomEnabled=0` discards ALL pixels outside entity silhouette (both crisp edge and halo). Fill pixels inside silhouette are unaffected by bloomEnabled.
> When `fillToggle` OFF + `bloomToggle` OFF: module effectively invisible (but `outlineColor` still set; entity_outline pipeline still runs at minor GPU cost — acceptable).

### entity_outline.json

`src/main/resources/assets/minecraft/post_effect/entity_outline.json` — same 2-pass structure, update fragment shader name + add ChamsFill uniforms:

```json
{
    "targets": { "swap": {} },
    "passes": [
        {
            "vertex_shader": "minecraft:core/screenquad",
            "fragment_shader": "example-addon:post/better_chams",
            "inputs": [{"sampler_name": "In", "target": "minecraft:entity_outline"}],
            "output": "swap",
            "uniforms": {
                "ChamsFill": [
                    {"name": "fillEnabled",  "type": "float", "value": 0.0},
                    {"name": "fillOpacity",  "type": "float", "value": 0.8},
                    {"name": "bloomEnabled", "type": "float", "value": 1.0}
                ]
            }
        },
        {
            "vertex_shader": "minecraft:core/screenquad",
            "fragment_shader": "minecraft:post/blit",
            "inputs": [{"sampler_name": "In", "target": "swap"}],
            "uniforms": {
                "BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0,1.0,1.0,1.0]}]
            },
            "output": "minecraft:entity_outline"
        }
    ]
}
```

---

## Architecture: Image Texture Binding

MC post_effect JSON cannot reference disk textures — only named framebuffer targets. Manual injection required via Mixin.

### Image loading (`BetterChams.loadImage`)

```java
public void loadImage(Path path) {
    try (InputStream is = Files.newInputStream(path)) {
        NativeImage img = NativeImage.read(is);
        Identifier id = Identifier.fromNamespaceAndPath("example-addon", "betterchamsfill");
        DynamicTexture dynTex = new DynamicTexture(() -> "betterchamsfill", img);
        Minecraft.getInstance().getTextureManager().register(id, dynTex);
        // Get GL texture ID via accessor
        imageTexId = ((DynamicTextureGlIdAccessor) dynTex).getGlId();
        imagePath = path.toString();
    } catch (Exception e) {
        imageTexId = 0;
    }
}
```

`DynamicTextureGlIdAccessor` = `@Accessor` mixin interface on `DynamicTexture` to expose the internal GL texture ID.

### MixinPostEffectProcessor

Injects at HEAD of `PostEffectProcessor.render(FrameGraphBuilder, int, int, DefaultFramebufferSet)`.

1. Check `this` is entity_outline processor (via `getId()` or equivalent field → `"minecraft:entity_outline"`)
2. Bind image to GL_TEXTURE2 (using `RenderSystem` wrappers)
3. Update `ChamsFill` uniform block: `fillEnabled`, `fillOpacity`, `bloomEnabled`
4. Set `ImageSampler` uniform to texture unit 2

The `ImageSampler` uniform location is set once after shader compilation (inject into post-compilation hook or set lazily on first render).

**Verify:** Need to check Mojmap class/method names for `PostEffectProcessor` in MC 26.1.2 binary jar (same lookup process used for `EndCrystalRenderer`).

---

## Architecture: Player Mixin

New `MixinPlayerRenderer.java`:

```java
@Mixin(PlayerRenderer.class)  // verify Mojmap name from binary jar
public abstract class MixinPlayerRenderer {

    @Inject(
        method = "extractRenderState(L...AbstractClientPlayer;L...PlayerRenderState;F)V",
        at = @At("RETURN")
    )
    private void betterchamss$setOutlineColor(
        AbstractClientPlayer player,
        PlayerRenderState state,
        float tickDelta,
        CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player) return;
        if (!BetterChams.INSTANCE.getState()) return;
        if (!BetterChams.INSTANCE.playerToggle.getValue()) return;
        if (BetterChams.INSTANCE.isInRange(player)) {
            state.outlineColor = 0xFFFFFFFF;
        }
    }
}
```

**Player overlays (jacket, sleeves, glasses):** `PlayerModel` submits all model parts (base + second skin layer) in the same `submitModel` call with the render state's `outlineColor`. No extra code needed — full silhouette including overlays appears in entity_outline automatically.

**Armor layers:** Rendered via `LivingEntityRenderer.renderLayers()` → separate `submitModel` calls. If they share the same render state, they also appear in entity_outline silhouette (adds to chams realism).

**Mojmap names to verify from binary jar:**
- `PlayerRenderer` package
- `AbstractClientPlayer` package  
- `PlayerRenderState` package
- Method descriptor for `extractRenderState`

---

## Architecture: ImagePickerScreen

`src/main/java/com/example/addon/screens/ImagePickerScreen.java`

Visual style: matches other screens (native `context.fill()` + `0x3CFFFFFF` border + button outlines — no Skija).

```
Panel: 280×320, centered
├── Title bar: "Select Image"
├── Scroll list (files from <gamedir>/boze/images/, filter .png/.jpg/.jpeg)
│   └── Click item → selectedPath, load preview
├── Preview: 80×80 thumbnail (DrawContext.blit using DynamicTexture)
├── [Select] → BetterChams.INSTANCE.loadImage(selectedPath); close screen
└── [Close]  → mc.setScreen(null)
```

Directory management:
- `Path dir = FabricLoader.getInstance().getGameDir().resolve("boze/images")`
- `Files.createDirectories(dir)` on screen open
- `Files.list(dir).filter(p -> name matches (?i).*\.(png|jpg|jpeg)$)`

`selectImage` toggle pattern (same as `editWhitelist` in InventoryCleaner):
```java
if (selectImage.getValue()) {
    selectImage.setValue(false);
    mc.execute(() -> mc.setScreen(new ImagePickerScreen()));
}
```

---

## File Map

| Action | Path |
|--------|------|
| **Create** | `src/main/java/com/example/addon/modules/BetterChams.java` |
| **Create** | `src/main/java/com/example/addon/screens/ImagePickerScreen.java` |
| **Create** | `src/main/java/com/example/addon/mixin/MixinPlayerRenderer.java` |
| **Create** | `src/main/java/com/example/addon/mixin/MixinPostEffectProcessor.java` |
| **Create** | `src/main/java/com/example/addon/mixin/accessor/DynamicTextureGlIdAccessor.java` |
| **Create** | `src/main/resources/assets/example-addon/shaders/post/better_chams.fsh` |
| **Update** | `src/main/resources/assets/minecraft/post_effect/entity_outline.json` |
| **Update** | `src/main/java/com/example/addon/mixin/MixinEndCrystalRenderer.java` |
| **Update** | `src/main/java/com/example/addon/ExampleAddon.java` |
| **Update** | `src/main/resources/example-addon.mixins.json` |
| **Update** | `bin/main/example-addon.mixins.json` |
| **Delete** | `src/main/java/com/example/addon/modules/CrystalCharms.java` |
| **Delete** | `src/main/resources/assets/example-addon/shaders/post/crystal_glow.fsh` |

---

## Open Questions (resolve during implementation)

1. Mojmap class names for `PlayerRenderer`, `AbstractClientPlayer`, `PlayerRenderState` — verify from `minecraft-merged-043a8b3edf-26.1.2.jar` (same process as `EndCrystalRenderer`).
2. Mojmap class/method for `PostEffectProcessor.render()` + how to access pass list + `CompiledShaderProgram` → determine exact uniform-update API.
3. `DynamicTexture` GL ID field name → accessor target.
4. `bloomEnabled=0` = discard all halo/edge pixels outside silhouette (clarified in FSH above).
5. `fillToggle` ON but `imageTexId == 0` (no image selected): `MixinPostEffectProcessor` keeps `fillEnabled=0.0` → falls back to outline-only. No fill shown until user picks an image.
