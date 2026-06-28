# BetterChams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate CrystalCharms → BetterChams with bloom outline on End Crystals + other Players, plus optional image fill (screen-space stencil) with configurable opacity.

**Architecture:** Override vanilla `entity_outline.json` with `better_chams.fsh` that has a `ChamsFill` UBO (fillEnabled, fillOpacity, bloomEnabled) and an `ImageSampler` (custom `ChamsImageTexture` registered in TextureManager). `MixinPostPass` updates the UBO each frame. `MixinEndCrystalRenderer` + new `MixinPlayerRenderer` set `outlineColor` on target entities.

**Tech Stack:** MC 26.1.2 Mojmap, Boze API 3.2.2, Fabric API (ClientLifecycleEvents), GLSL post-effect pipeline, Blaze3D GpuBuffer/Std140Builder

## Global Constraints

- Mojmap class names: `PostPass` (`net.minecraft.client.renderer`), `DynamicTexture` (`net.minecraft.client.renderer.texture`), `NativeImage` (`com.mojang.blaze3d.platform`), `AvatarRenderer` (`net.minecraft.client.renderer.entity.player`), `AvatarRenderState` (`net.minecraft.client.renderer.entity.state`), `AbstractClientPlayer` (`net.minecraft.client.player`)
- `PostPass` fields (verified from binary): `name: String`, `customUniforms: Map<String,GpuBuffer>`, `inputs: List<PostPass.Input>`; method: `addToFrame(FrameGraphBuilder, Map, GpuBufferSlice)`
- `DynamicTexture`: constructor `(Supplier<String>, NativeImage)`, methods `setPixels(NativeImage)`, `upload()`
- `TextureManager.register(Identifier, AbstractTexture)` — Mojmap method name (not `registerTexture`)
- JSON TextureInput: fields `"sampler_name"`, `"location"`, `"width"`, `"height"` — loader transforms `location` via `withPath("textures/effect/"+name+".png")` before calling `textureManager.getTexture()`
- Register `ChamsImageTexture` under `Identifier.of("example-addon", "textures/effect/betterchamsfill.png")`
- Build verify: `.\gradlew build` after each task
- Both `src/main/resources/example-addon.mixins.json` AND `bin/main/example-addon.mixins.json` must be kept in sync

---

## Task 1: Shader + entity_outline.json

**Files:**
- Create: `src/main/resources/assets/example-addon/shaders/post/better_chams.fsh`
- Update: `src/main/resources/assets/minecraft/post_effect/entity_outline.json`
- Delete: `src/main/resources/assets/example-addon/shaders/post/crystal_glow.fsh`

**Interfaces:**
- Produces: `better_chams.fsh` consumed by `entity_outline.json`; `ChamsFill` UBO layout consumed by `MixinPostPass` (Task 6)

- [ ] **Step 1: Create `better_chams.fsh`**

Create `src/main/resources/assets/example-addon/shaders/post/better_chams.fsh`:

```glsl
#version 330

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform ChamsFill {
    float fillEnabled;
    float fillOpacity;
    float bloomEnabled;
};

uniform sampler2D InSampler;
uniform sampler2D ImageSampler;

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
                if (bloomEnabled > 0.5) {
                    fragColor = vec4(nb.rgb, 1.0);
                    return;
                }
            }
        }
    }

    if (bloomEnabled < 0.5) discard;

    // Soft bloom halo (box blur, radius=6, step=3)
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

- [ ] **Step 2: Update `entity_outline.json`**

Overwrite `src/main/resources/assets/minecraft/post_effect/entity_outline.json`:

```json
{
    "targets": {
        "swap": {}
    },
    "passes": [
        {
            "vertex_shader": "minecraft:core/screenquad",
            "fragment_shader": "example-addon:post/better_chams",
            "inputs": [
                {
                    "sampler_name": "In",
                    "target": "minecraft:entity_outline"
                },
                {
                    "sampler_name": "Image",
                    "location": "example-addon:betterchamsfill",
                    "width": 1,
                    "height": 1
                }
            ],
            "output": "swap",
            "uniforms": {
                "ChamsFill": [
                    {"type": "float", "value": 0.0},
                    {"type": "float", "value": 0.8},
                    {"type": "float", "value": 1.0}
                ]
            }
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
                    {"type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}
                ]
            },
            "output": "minecraft:entity_outline"
        }
    ]
}
```

- [ ] **Step 3: Delete crystal_glow.fsh**

Delete `src/main/resources/assets/example-addon/shaders/post/crystal_glow.fsh`.

- [ ] **Step 4: Build verify**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL (shaders compile, JSON valid)

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/assets/example-addon/shaders/post/better_chams.fsh
git add src/main/resources/assets/minecraft/post_effect/entity_outline.json
git rm src/main/resources/assets/example-addon/shaders/post/crystal_glow.fsh
git commit -m "feat: add better_chams.fsh + update entity_outline.json for BetterChams"
```

---

## Task 2: ChamsImageTexture

**Files:**
- Create: `src/main/java/com/example/addon/rendering/ChamsImageTexture.java`

**Interfaces:**
- Produces: `ChamsImageTexture extends AbstractTexture` consumed by `BetterChams.CHAMS_TEXTURE` (Task 3) and registered in TextureManager before `PostChain.createPass()` runs

- [ ] **Step 1: Create directory and file**

Create `src/main/java/com/example/addon/rendering/ChamsImageTexture.java`:

```java
package com.example.addon.rendering;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class ChamsImageTexture extends AbstractTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChamsImageTexture.class);

    @Nullable private DynamicTexture inner;
    private boolean hasImage = false;

    // No GL calls in constructor — safe to construct during mod init
    public ChamsImageTexture() {}

    @Override
    public com.mojang.blaze3d.textures.GpuTextureView getGlTextureView() {
        if (inner == null) {
            // Lazy init — called from render thread, GL is ready
            NativeImage blank = new NativeImage(1, 1, true);
            inner = new DynamicTexture(() -> "betterchams-fill-blank", blank);
        }
        return inner.getGlTextureView();
    }

    // Call from render thread only
    public void loadImage(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            NativeImage img = NativeImage.read(is);
            DynamicTexture newInner = new DynamicTexture(() -> "betterchams-fill", img);
            if (inner != null) inner.close();
            inner = newInner;
            hasImage = true;
        } catch (Exception e) {
            LOGGER.error("BetterChams: failed to load image {}", path, e);
        }
    }

    public boolean hasImage() {
        return hasImage && inner != null;
    }

    @Override
    public void close() {
        if (inner != null) {
            inner.close();
            inner = null;
        }
        super.close();
    }
}
```

- [ ] **Step 2: Build verify**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL (ChamsImageTexture compiles cleanly)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/rendering/ChamsImageTexture.java
git commit -m "feat: add ChamsImageTexture (lazy AbstractTexture for dynamic image fill)"
```

---

## Task 3: BetterChams module

**Files:**
- Create: `src/main/java/com/example/addon/modules/BetterChams.java`

**Interfaces:**
- Consumes: `ChamsImageTexture` from Task 2
- Produces: `BetterChams.INSTANCE` (singleton), `BetterChams.CHAMS_TEXTURE`, `isInRange(Entity)`, `loadImage(Path)` consumed by Tasks 5, 6, 7

- [ ] **Step 1: Create `BetterChams.java`**

Create `src/main/java/com/example/addon/modules/BetterChams.java`:

```java
package com.example.addon.modules;

import com.example.addon.rendering.ChamsImageTexture;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.module.AddonModule;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

import java.nio.file.Files;
import java.nio.file.Path;

public class BetterChams extends AddonModule {

    public static final BetterChams INSTANCE = new BetterChams();
    public static final ChamsImageTexture CHAMS_TEXTURE = new ChamsImageTexture();

    public static final ResourceLocation TEX_ID =
        ResourceLocation.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsfill.png");

    public final ToggleOption crystalToggle =
        new ToggleOption(this, "Crystals", "Bloom outline on End Crystals.", true);
    public final ToggleOption playerToggle =
        new ToggleOption(this, "Players", "Bloom outline on other players.", true);
    public final SliderOption range =
        new SliderOption(this, "Range", "Max range in blocks.", 16.0, 8.0, 64.0, 1.0);
    public final ToggleOption bloomToggle =
        new ToggleOption(this, "Bloom", "Show bloom halo and crisp edge.", true);
    public final ToggleOption fillToggle =
        new ToggleOption(this, "Image Fill", "Show image fill inside entity silhouettes.", false);
    public final SliderOption fillOpacity =
        new SliderOption(this, "Fill Opacity", "Opacity of image fill.", 0.8, 0.0, 1.0, 0.01);
    public final ToggleOption selectImage =
        new ToggleOption(this, "Select Image", "Open image picker from boze/images/.", false);

    private BetterChams() {
        super("BetterChams", "Bloom outline + image fill for End Crystals and players.");
    }

    public static void registerTexture() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc ->
            mc.getTextureManager().register(TEX_ID, CHAMS_TEXTURE)
        );
    }

    public boolean isInRange(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double r = range.getValue();
        return mc.player.distanceToSqr(entity) <= r * r;
    }

    public void loadImage(Path path) {
        CHAMS_TEXTURE.loadImage(path);
    }

    @Override
    public void onTick() {
        if (selectImage.getValue()) {
            selectImage.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new com.example.addon.screens.ImagePickerScreen()));
        }
    }
}
```

- [ ] **Step 2: Build verify**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL (may fail if ImagePickerScreen not yet created — acceptable; check that BetterChams itself compiles)

Note: if build fails due to missing `ImagePickerScreen`, proceed to Task 4 first, then re-run build.

- [ ] **Step 3: Commit (after Task 4 if needed)**

```bash
git add src/main/java/com/example/addon/modules/BetterChams.java
git commit -m "feat: add BetterChams module (7 settings, ChamsImageTexture registration)"
```

---

## Task 4: ImagePickerScreen

**Files:**
- Create: `src/main/java/com/example/addon/screens/ImagePickerScreen.java`

**Interfaces:**
- Consumes: `BetterChams.INSTANCE.loadImage(Path)` (Task 3)
- Produces: Screen class consumed by `BetterChams.onTick()` select handler

- [ ] **Step 1: Create screens directory + ImagePickerScreen.java**

Create `src/main/java/com/example/addon/screens/ImagePickerScreen.java`:

```java
package com.example.addon.screens;

import com.example.addon.modules.BetterChams;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ImagePickerScreen extends Screen {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("(?i).*\\.(png|jpg|jpeg)$");
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 320;
    private static final int ITEM_H = 18;
    private static final int BG = 0xCC101010;
    private static final int BORDER = 0x3CFFFFFF;

    private final List<Path> files = new ArrayList<>();
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    public ImagePickerScreen() {
        super(Component.literal("Select Image"));
    }

    @Override
    protected void init() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("boze/images");
        try {
            Files.createDirectories(dir);
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> IMAGE_PATTERN.matcher(p.getFileName().toString()).matches())
                      .sorted()
                      .forEach(files::add);
            }
        } catch (IOException e) {
            // directory unreadable — show empty list
        }

        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        int btnY = panelY + PANEL_H - 26;

        addRenderableWidget(Button.builder(Component.literal("Select"), btn -> onSelect())
            .bounds(panelX + 10, btnY, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Close"), btn -> onClose())
            .bounds(panelX + PANEL_W - 130, btnY, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;

        g.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BG);
        g.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, BORDER);
        g.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, BORDER);
        g.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, BORDER);
        g.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, BORDER);

        g.drawString(font, "Select Image", panelX + 8, panelY + 6, 0xFFFFFFFF);

        int listX = panelX + 6;
        int listY = panelY + 20;
        int listH = PANEL_H - 60;
        int visibleItems = listH / ITEM_H;

        for (int i = scrollOffset; i < Math.min(files.size(), scrollOffset + visibleItems); i++) {
            int iy = listY + (i - scrollOffset) * ITEM_H;
            if (i == selectedIndex) {
                g.fill(listX - 2, iy - 1, listX + PANEL_W - 12, iy + ITEM_H - 1, 0x553399FF);
            }
            boolean hovered = mx >= listX - 2 && mx < listX + PANEL_W - 12 && my >= iy - 1 && my < iy + ITEM_H - 1;
            if (hovered && i != selectedIndex) {
                g.fill(listX - 2, iy - 1, listX + PANEL_W - 12, iy + ITEM_H - 1, 0x22FFFFFF);
            }
            String name = files.get(i).getFileName().toString();
            g.drawString(font, name, listX, iy + 1, 0xFFFFFFFF);
        }

        if (files.isEmpty()) {
            g.drawString(font, "No images in boze/images/", listX, listY + 4, 0xFF888888);
        }

        super.render(g, mx, my, partialTick);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int panelX = (width - PANEL_W) / 2;
        int panelY = (height - PANEL_H) / 2;
        int listX = panelX + 6;
        int listY = panelY + 20;
        int listH = PANEL_H - 60;
        int visibleItems = listH / ITEM_H;

        for (int i = scrollOffset; i < Math.min(files.size(), scrollOffset + visibleItems); i++) {
            int iy = listY + (i - scrollOffset) * ITEM_H;
            if (mx >= listX - 2 && mx < listX + PANEL_W - 12 && my >= iy - 1 && my < iy + ITEM_H - 1) {
                selectedIndex = i;
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int maxOffset = Math.max(0, files.size() - (PANEL_H - 60) / ITEM_H);
        scrollOffset = (int) Math.max(0, Math.min(maxOffset, scrollOffset - dy));
        return true;
    }

    private void onSelect() {
        if (selectedIndex >= 0 && selectedIndex < files.size()) {
            Path p = files.get(selectedIndex);
            BetterChams.INSTANCE.loadImage(p);
        }
        onClose();
    }
}
```

- [ ] **Step 2: Build verify**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/screens/ImagePickerScreen.java
git commit -m "feat: add ImagePickerScreen for BetterChams image picker"
```

---

## Task 5: MixinPlayerRenderer (AvatarRenderer)

**Files:**
- Create: `src/main/java/com/example/addon/mixin/MixinPlayerRenderer.java`

**Interfaces:**
- Produces: mixin that sets `state.outlineColor` on non-local players when BetterChams.playerToggle is on

- [ ] **Step 1: Create `MixinPlayerRenderer.java`**

Create `src/main/java/com/example/addon/mixin/MixinPlayerRenderer.java`:

```java
package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class MixinPlayerRenderer {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
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

Note: `PlayerRenderer` in `net.minecraft.client.renderer.entity.player` is the Mojmap name for `AvatarRenderer`. Verify from binary if build fails. Method descriptor uses `PlayerRenderState` (Mojmap) not `AvatarRenderState`. Check the actual Mojmap class name if `PlayerRenderer` or `PlayerRenderState` fail to resolve — the verified names from binary were `AvatarRenderer` and `AvatarRenderState`, so update imports if needed:
- If `PlayerRenderer` doesn't exist: try `AvatarRenderer` in `net.minecraft.client.renderer.entity.player`
- If `PlayerRenderState` doesn't exist: try `AvatarRenderState` in `net.minecraft.client.renderer.entity.state`

- [ ] **Step 2: Build verify**

Run: `.\gradlew build`

If compile error due to wrong class names, look up actual class name:
```powershell
Add-Type -Assembly System.IO.Compression.FileSystem
$jar = "C:\Users\conng\Downloads\example-addon-master\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-043a8b3edf\26.1.2\minecraft-merged-043a8b3edf-26.1.2.jar"
$z = [System.IO.Compression.ZipFile]::OpenRead($jar)
$z.Entries | Where-Object { $_.FullName -match "entity/player" -and $_.Name -match "\.class$" } | ForEach-Object { $_.FullName }
$z.Dispose()
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/mixin/MixinPlayerRenderer.java
git commit -m "feat: add MixinPlayerRenderer to set outline color on other players"
```

---

## Task 6: MixinPostPass (ChamsFill UBO update)

**Files:**
- Create: `src/main/java/com/example/addon/mixin/MixinPostPass.java`

**Interfaces:**
- Consumes: `PostPass.name` (String field), `PostPass.customUniforms` (Map<String,GpuBuffer>), `BetterChams.INSTANCE` settings
- Produces: per-frame UBO update for `ChamsFill` block so shader reads current fill/bloom state

- [ ] **Step 1: Create `MixinPostPass.java`**

Create `src/main/java/com/example/addon/mixin/MixinPostPass.java`:

```java
package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(PostPass.class)
public abstract class MixinPostPass {

    @Shadow private String name;
    @Shadow private Map<String, GpuBuffer> customUniforms;

    @Inject(method = "addToFrame", at = @At("HEAD"))
    private void betterchamss$updateChamsFill(FrameGraphBuilder builder, Map<ResourceLocation, ?> handles, GpuBufferSlice slice, CallbackInfo ci) {
        if (!"minecraft:entity_outline/0".equals(this.name)) return;
        GpuBuffer old = this.customUniforms.get("ChamsFill");
        if (old == null) return;

        boolean fillOn = BetterChams.INSTANCE.getState()
            && BetterChams.INSTANCE.fillToggle.getValue()
            && BetterChams.CHAMS_TEXTURE.hasImage();
        boolean bloomOn = !BetterChams.INSTANCE.getState()
            || BetterChams.INSTANCE.bloomToggle.getValue();

        float fillEnabled  = fillOn  ? 1.0f : 0.0f;
        float fillOpacity  = (float) BetterChams.INSTANCE.fillOpacity.getValue();
        float bloomEnabled = bloomOn ? 1.0f : 0.0f;

        old.close();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140SizeCalculator calc = new Std140SizeCalculator();
            calc.putFloat(); calc.putFloat(); calc.putFloat();
            Std140Builder b = Std140Builder.onStack(stack, calc.get());
            b.putFloat(fillEnabled);
            b.putFloat(fillOpacity);
            b.putFloat(bloomEnabled);
            this.customUniforms.put("ChamsFill",
                RenderSystem.getDevice().createBuffer(() -> "ChamsFill", GpuBuffer.USAGE_UNIFORM, b.get()));
        }
    }
}
```

Note: `@Shadow private String name` and `@Shadow private Map<String, GpuBuffer> customUniforms` must exactly match the field names in the `PostPass` binary (verified: `name` and `customUniforms`). If `@Shadow` fails at runtime, check remapping — use `remap = false` if loom is not mapping these correctly.

- [ ] **Step 2: Build verify**

Run: `.\gradlew build`

If `@Shadow` field names don't match, look up in binary:
```powershell
# Already done — confirmed: "name" and "customUniforms" from binary string extraction
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/mixin/MixinPostPass.java
git commit -m "feat: add MixinPostPass to update ChamsFill UBO per frame"
```

---

## Task 7: Update MixinEndCrystalRenderer

**Files:**
- Update: `src/main/java/com/example/addon/mixin/MixinEndCrystalRenderer.java`

**Interfaces:**
- Change reference from `CrystalCharms.INSTANCE` → `BetterChams.INSTANCE` + check `crystalToggle`

- [ ] **Step 1: Update the mixin**

Edit `src/main/java/com/example/addon/mixin/MixinEndCrystalRenderer.java`:

```java
package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
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
        if (!BetterChams.INSTANCE.getState()) return;
        if (!BetterChams.INSTANCE.crystalToggle.getValue()) return;
        if (BetterChams.INSTANCE.isInRange(crystal)) {
            state.outlineColor = 0xFFFFFFFF;
        }
    }
}
```

- [ ] **Step 2: Build verify**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/mixin/MixinEndCrystalRenderer.java
git commit -m "fix: update MixinEndCrystalRenderer to reference BetterChams"
```

---

## Task 8: Module registration + cleanup

**Files:**
- Update: `src/main/java/com/example/addon/ExampleAddon.java`
- Update: `src/main/resources/example-addon.mixins.json`
- Update: `bin/main/example-addon.mixins.json`
- Delete: `src/main/java/com/example/addon/modules/CrystalCharms.java`

**Interfaces:**
- Wires everything together; removes CrystalCharms

- [ ] **Step 1: Delete CrystalCharms.java**

Delete `src/main/java/com/example/addon/modules/CrystalCharms.java`.

- [ ] **Step 2: Update both mixins.json files**

In both `src/main/resources/example-addon.mixins.json` AND `bin/main/example-addon.mixins.json`, update the `client` array:

Remove: `"MixinEndCrystalRenderer"` (it already exists — keep it)
Add: `"MixinPlayerRenderer"`, `"MixinPostPass"`

Final `client` array in both files:
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
    "MixinEndCrystalRenderer",
    "MixinPlayerRenderer",
    "MixinPostPass"
]
```

- [ ] **Step 3: Update ExampleAddon.java**

Replace `CrystalCharms` import and registration with `BetterChams`. Also call `BetterChams.registerTexture()` early in `initialize()`:

```java
// Replace:
import com.example.addon.modules.CrystalCharms;
// With:
import com.example.addon.modules.BetterChams;

// In initialize(), replace:
modules.add(CrystalCharms.INSTANCE);
// With:
BetterChams.registerTexture();
modules.add(BetterChams.INSTANCE);
```

Full relevant section of `initialize()`:
```java
@Override
public boolean initialize() {
    // ... existing config migrator ...
    BetterChams.registerTexture();  // ← add near top, before module adds
    // ... existing dispatcher.registerCommand calls ...
    modules.add(AntiMace.INSTANCE);
    // ... existing modules ...
    modules.add(ChestButtons.INSTANCE);
    modules.add(BetterChams.INSTANCE);  // ← replaces CrystalCharms.INSTANCE
    modules.add(EbookReader.INSTANCE);
    // ... rest unchanged ...
}
```

- [ ] **Step 4: Build verify**

Run: `.\gradlew build`
Expected: BUILD SUCCESSFUL with no compile errors.

If `@Shadow` fields fail with `Cannot find field 'name' / 'customUniforms'` at runtime (mixin error in log), add `remap = false` to `@Mixin(PostPass.class)` → `@Mixin(targets = "net.minecraft.client.renderer.PostPass")` and verify the fields are not being remapped incorrectly by loom.

- [ ] **Step 5: Commit**

```bash
git rm src/main/java/com/example/addon/modules/CrystalCharms.java
git add src/main/java/com/example/addon/ExampleAddon.java
git add src/main/resources/example-addon.mixins.json
git add bin/main/example-addon.mixins.json
git commit -m "feat: complete BetterChams migration — remove CrystalCharms, register mixins"
```

---

## Verification Checklist

After all tasks complete:

- [ ] `.\gradlew build` → BUILD SUCCESSFUL
- [ ] In-game: enable BetterChams → End Crystals show bloom outline
- [ ] In-game: enable BetterChams → other players show bloom outline
- [ ] In-game: disable `Bloom` toggle → no bloom halo (entities still outlined by crisp edge)
- [ ] In-game: enable `Image Fill` → after picking image, entity silhouettes show image fill
- [ ] In-game: `Fill Opacity` slider changes fill transparency
- [ ] In-game: disable BetterChams → no outline on any entity (outlineColor never set → hasOutline=false → pipeline skipped)
- [ ] `Select Image` toggle → ImagePickerScreen opens, shows files from `boze/images/`
- [ ] Pick image → image shows through entity silhouettes on next frame

## Troubleshooting

**Bloom not visible:** Check `MixinEndCrystalRenderer` + `MixinPlayerRenderer` are in both mixins.json. Check `BetterChams.INSTANCE.getState()` returns true (module enabled).

**UBO update crash (`@Shadow` field not found):** `PostPass.name` and `PostPass.customUniforms` are confirmed Mojmap field names from binary. If loom remaps them, add `@Mixin(remap = false)` on `MixinPostPass` class (not the inject).

**Image not showing:** Verify `ChamsImageTexture` registered before first `PostChain.createPass()` call. Check `ClientLifecycleEvents.CLIENT_STARTED` fired. If PostChain was already created before registration, press F3+T to force resource reload.

**`entity_outline/0` ID doesn't match:** The suffix `/0` is appended by `PostChain.load()` via `id.withSuffix("/0")`. If entity_outline PostChain passes are loaded differently in 26.1.2, change the check in `MixinPostPass` to `name.contains("entity_outline")`.
