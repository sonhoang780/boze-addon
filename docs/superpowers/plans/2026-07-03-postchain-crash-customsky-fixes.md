# PostChain Crash + TungTungSahur Fade + CustomSky Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the `Texture view does not exist` crash, TungTungSahur fade-xray artifact, CustomSky Image-mode black sky, and CustomSky Shader-mode stale-program confusion.

**Architecture:** All four bugs are in the existing PostChain/raw-GL rendering stack. No new systems — targeted fixes to texture lifecycle, render-stage selection, and error surfacing.

**Tech Stack:** MC 26.1.2, Boze API 3.2.2, Fabric rendering events, LWJGL raw GL.

## Global Constraints

- Never `TextureManager.register()` twice under the same Identifier for post-effect `location` textures: `PostPass$TextureInput` is a record caching the `AbstractTexture` **instance** at chain-compile time; re-register closes the cached instance → `getTextureView()` crash. Mutate pixels of the one registered texture instead.
- Post-effect `"location": "example-addon:X"` resolves to id `example-addon:textures/effect/X.png` — register under the full path.
- Fragment shader sampler uniforms must be `<sampler_name>Sampler`.
- `.\gradlew compileJava` after every task; in-game test at the end.

---

### Task 1: Fix crash — stop re-registering the smoke SDF texture

**Root cause (verified via javap on 26.1.2 `PostPass$TextureInput`):** record field `AbstractTexture texture` is resolved once when `ShaderManager.getPostChain` compiles/caches the chain. `TungTungSahur.buildTexture()` runs on every `onEnable()` and re-registers `SMOKE_SDF_ID` with a fresh `DynamicTexture` (register closes the old instance). Cached chain then holds a closed texture → `AbstractTexture.getTextureView` throws `IllegalStateException` on the next fade → crash (matches crash-2026-07-03_03.26.48).

**Files:**
- Modify: `src/main/java/com/example/addon/modules/TungTungSahur.java`

**Steps:**

- [ ] **Step 1: Make the SDF registration one-shot and mutate pixels in place**

In `registerTextures()`, keep the single 256x128 registration (white placeholder) as-is. Add a static flag:

```java
private static boolean sdfLoaded = false;
```

Replace the SDF block in `buildTexture(Minecraft mc)` (the `try` that creates a new `DynamicTexture` and re-registers `SMOKE_SDF_ID`) with an in-place pixel copy into the ALREADY-registered texture:

```java
// Copy the baked SDF atlas into the one registered smokeSdfTexture instead of
// re-registering: PostPass$TextureInput caches the AbstractTexture INSTANCE at
// chain-compile time, and TextureManager.register() closes the old instance --
// re-registering here crashed with "Texture view does not exist" on the next
// fade (see crash-2026-07-03_03.26.48).
if (!sdfLoaded && smokeSdfTexture != null) {
    try {
        Identifier sdfId = Identifier.fromNamespaceAndPath("example-addon", "textures/entity/tung_tung_sdf.png");
        try (var stream = mc.getResourceManager().getResourceOrThrow(sdfId).open()) {
            NativeImage src = NativeImage.read(stream);
            NativeImage dst = smokeSdfTexture.getPixels();
            if (dst != null && src.getWidth() == dst.getWidth() && src.getHeight() == dst.getHeight()) {
                src.copyRect(dst, 0, 0, 0, 0, src.getWidth(), src.getHeight(), false, false);
                smokeSdfTexture.upload();
                sdfLoaded = true;
            }
            src.close();
        }
    } catch (Exception ignored) {}
}
```

If `NativeImage.copyRect` with that signature doesn't exist on 26.1.2, fall back to a manual loop: `for y for x dst.setPixel(x, y, src.getPixel(x, y));` (verify method names `getPixel`/`setPixel` against the project's existing NativeImage usage in `updateSmokeParams`/`setFloat` — `setPixel` is already used there).

- [ ] **Step 2: Compile**

Run: `.\gradlew compileJava -q` — expect success.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/TungTungSahur.java
git commit -m "fix: stop re-registering smoke SDF texture (PostChain caches texture instance; re-register crashed getTextureView)"
```

---

### Task 2: Fix TungTungSahur fade rendering through water/terrain

**Root cause:** the fading model uses `RenderTypes.entityTranslucent` but is submitted in `LevelRenderEvents.AFTER_SOLID_FEATURES` — before translucent terrain (water) renders. Its depth writes then occlude the water pass, so through the model you see the lake bed with no water tint ("xray/fullbright" look).

**Files:**
- Modify: `src/main/java/com/example/addon/modules/TungTungSahur.java`

**Steps:**

- [ ] **Step 1: Split render registration by fade state**

In the static block, register a second hook and gate each on fade state so the model renders in exactly one stage per frame:

```java
LevelRenderEvents.AFTER_SOLID_FEATURES.register(ctx -> {
    if (INSTANCE.getState() && !INSTANCE.fadingOut) INSTANCE.onWorldRender(ctx);
});
// Fading model is translucent -- must render AFTER water (translucent terrain)
// so its depth writes don't cut the water pass out of the frame.
LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
    if (INSTANCE.fadingOut) INSTANCE.onWorldRender(ctx);
});
```

(Callback interface: `LevelRenderEvents$AfterTranslucentTerrain`, same `LevelRenderContext` parameter — verified present in fabric-rendering-v1 23.2.0. If the field name differs, check `LevelRenderEvents` class fields via javap and use the matching constant.)

- [ ] **Step 2: Compile**

Run: `.\gradlew compileJava -q` — expect success.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/TungTungSahur.java
git commit -m "fix: render fading TungTung model after translucent terrain so water isn't depth-culled"
```

---

### Task 3: CustomSky Shader mode — surface compile errors + Shadertoy support

**Root cause of "mọi shader.frag đều ra starry night":** `CustomSkyRenderer.loadCustomShader` keeps the previous program when compilation fails and only logs to latest.log. User loaded StarryNight once; every subsequent (incompatible) `.frag` failed silently and the starry program kept rendering.

**Files:**
- Modify: `src/main/java/com/example/addon/rendering/CustomSkyRenderer.java`

**Steps:**

- [ ] **Step 1: Report load results to chat**

`loadCustomShader` — after the compile attempt:

```java
if (newProg != -1) {
    if (customProgram != -1) GL20.glDeleteProgram(customProgram);
    customProgram = newProg;
    com.example.addon.util.ChatHelper.sendMsg("CustomSky", "§aLoaded shader: " + path.getFileName());
} else {
    com.example.addon.util.ChatHelper.sendMsg("CustomSky", "§cShader compile failed: " + path.getFileName() + " §7(see latest.log; previous shader kept)");
}
```

(Verify ChatHelper class path/name — FakeFly already uses `ChatHelper.sendMsg("FakeFly", ...)`; copy its import.)

- [ ] **Step 2: Accept Shadertoy-style shaders**

Most downloadable `.frag` files are Shadertoy dumps (`void mainImage(out vec4, in vec2)`, `iTime`, `iResolution`). In `loadCustomShader`, after stripping `#version`/`out vec4`, detect and wrap:

```java
if (userCode.contains("mainImage")) {
    userCode =
        "#define iTime u_Time\n" +
        "#define iResolution vec3(u_Resolution, 1.0)\n" +
        userCode +
        "\nvoid main() {\n" +
        "    vec2 fragCoord = (texCoord * 0.5 + 0.5) * u_Resolution;\n" +
        "    mainImage(fragColor, fragCoord);\n" +
        "}\n";
}
```

Guard: only apply when the file does NOT already define `void main(`.

- [ ] **Step 3: Compile + commit**

Run: `.\gradlew compileJava -q` — expect success.

```bash
git add src/main/java/com/example/addon/rendering/CustomSkyRenderer.java
git commit -m "feat: CustomSky chat feedback on shader load + Shadertoy mainImage wrapper"
```

---

### Task 4: CustomSky Image mode black — instrument, then fix

**Root cause unconfirmed.** Candidates: `stbi_load` returning null (silent except log), image program never compiled, GL texture-unit state. Instrument first, fix from evidence.

**Files:**
- Modify: `src/main/java/com/example/addon/rendering/CustomSkyRenderer.java`

**Steps:**

- [ ] **Step 1: Chat feedback in `loadImage`**

Success path: `ChatHelper.sendMsg("CustomSky", "§aLoaded image: " + path.getFileName() + " (" + w.get(0) + "x" + h.get(0) + ")");`
Failure path: `ChatHelper.sendMsg("CustomSky", "§cImage load failed: " + org.lwjgl.stb.STBImage.stbi_failure_reason());`
Also report if `imageProgram` compile fails (reuse Task 3 pattern).

- [ ] **Step 2: Restore GL texture state after raw binds**

In `tick()` (Image branch) and `loadImage`, save/restore the previously bound texture to avoid desyncing blaze3d's state cache:

```java
int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
// ... bind + draw / upload ...
GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
```

- [ ] **Step 3: Compile, commit, then in-game test**

```bash
git add src/main/java/com/example/addon/rendering/CustomSkyRenderer.java
git commit -m "fix: CustomSky image-mode diagnostics + GL texture state restore"
```

In-game: select each image in `boze/sky/`; chat now states load success/failure. If loads succeed but sky still black, report back the chat output + latest.log — next suspect is the equirect sampling path (debug by outputting `fragColor = vec4(1,0,0,1)` in IMAGE_FRAG to isolate program vs texture).

---

### Final verification checklist (in-game)

- [ ] Toggle TungTungSahur on → off → on → off repeatedly: no crash (Task 1), smoke visible during 2s fade (params/SDF now actually reach the shader), fading model does NOT xray water (Task 2).
- [ ] CustomSky Shader mode: StarryNight toggle → stars; load a Shadertoy `.frag` → renders or chat shows compile error (Task 3).
- [ ] CustomSky Image mode: chat confirms image load; sky shows the image (Task 4).
- [ ] `custom_sky.fsh` depth threshold: if sky replaces terrain instead of sky, flip `depth < 0.9999` to `depth < 0.0001`.
