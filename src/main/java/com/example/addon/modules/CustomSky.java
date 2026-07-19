package com.example.addon.modules;

import com.example.addon.render.ChamsImageTexture;
import com.example.addon.render.CustomSkyRenderer;
import com.example.addon.screens.ImagePickerScreen;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CustomSky extends AddonModule {

    public static final CustomSky INSTANCE = new CustomSky();

    public enum Mode {
        Off, Image, Shader
    }

    public final ModeOption mode = new ModeOption(this, "Mode", "Render mode for the custom sky.", Mode.Shader);

    public final ToggleOption selectImage = new ToggleOption(this, "SelectImage", "Open image picker from boze/sky/.", false);
    public final ToggleOption selectShader = new ToggleOption(this, "SelectShader", "Open shader picker from boze/sky/.", false);
    public final ToggleOption starryNight = new ToggleOption(this, "StarryNight", "Example", false);
    public final ToggleOption realTime = new ToggleOption(this, "RealTime",
        "Dim the custom sky to match vanilla's day/night brightness, instead of always full brightness.", false);

    // Post-effect "location": "example-addon:customsky" resolves to
    // textures/effect/customsky.png -- must register under that full path (same
    // missing-resource pitfall as TungTungSahur's smoke params textures).
    public static final Identifier SKY_TEX_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/customsky.png");
    public static final ChamsImageTexture SKY_TEXTURE = new ChamsImageTexture();

    // 1x1 params texture read by custom_sky.fsh's compositor pass -- kept OUTSIDE the
    // individual sky shaders (Image/StarryNight/arbitrary user .frag files) so RealTime
    // dims every mode uniformly without needing to touch each shader's own code.
    public static final Identifier PARAMS_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/customskyparams.png");
    private static net.minecraft.client.renderer.texture.DynamicTexture paramsTexture;

    private static final String BEDROCK_SHADER_NAME = "vangogh_sky.frag";

    // Ported from the Bedrock "newb" sky shader (nlOverworldSkyColors / nlRenderPixelStars):
    // per-cubemap-face UV hashed into a star grid, over a simple horizon->zenith gradient.
    private static final String BEDROCK_SHADER =
        "vec3 sky_gradient(vec3 dir) {\n" +
        "    vec3 zenith = vec3(0.02, 0.03, 0.09);\n" +
        "    vec3 horizon = vec3(0.08, 0.10, 0.20);\n" +
        "    float t = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);\n" +
        "    return mix(horizon, zenith, t);\n" +
        "}\n" +
        "\n" +
        "float hash13(vec3 p) {\n" +
        "    p = fract(p * 0.1031);\n" +
        "    p += dot(p, p.yzx + 33.33);\n" +
        "    return fract((p.x + p.y) * p.z);\n" +
        "}\n" +
        "\n" +
        "vec3 cubemap_face_uv(vec3 dir) {\n" +
        "    vec3 a = abs(dir);\n" +
        "    float ma;\n" +
        "    vec2 uv;\n" +
        "    float face;\n" +
        "    if (a.x >= a.y && a.x >= a.z) { ma = a.x; uv = vec2(dir.z * sign(dir.x), dir.y); face = dir.x > 0.0 ? 0.0 : 1.0; }\n" +
        "    else if (a.y >= a.x && a.y >= a.z) { ma = a.y; uv = vec2(dir.x, dir.z * sign(dir.y)); face = dir.y > 0.0 ? 2.0 : 3.0; }\n" +
        "    else { ma = a.z; uv = vec2(dir.x * -sign(dir.z), dir.y); face = dir.z > 0.0 ? 4.0 : 5.0; }\n" +
        "    uv = uv / ma * 0.5 + 0.5;\n" +
        "    return vec3(uv, face);\n" +
        "}\n" +
        "\n" +
        "float stars(vec3 dir) {\n" +
        "    vec3 fuv = cubemap_face_uv(dir);\n" +
        "    float grid = 140.0;\n" +
        "    vec2 cell = floor(fuv.xy * grid);\n" +
        "    vec2 f = fract(fuv.xy * grid);\n" +
        "    float h = hash13(vec3(cell, fuv.z));\n" +
        "    if (h < 0.985) return 0.0;\n" +
        "    vec2 starPos = vec2(hash13(vec3(cell, fuv.z + 11.0)), hash13(vec3(cell, fuv.z + 23.0)));\n" +
        "    float d = length(f - starPos);\n" +
        "    float brightness = fract(h * 173.0);\n" +
        "    return smoothstep(0.06, 0.0, d) * (0.4 + 0.6 * brightness);\n" +
        "}\n" +
        "\n" +
        "vec4 sky_color(vec3 dir) {\n" +
        "    vec3 col = sky_gradient(dir);\n" +
        "    col += vec3(stars(dir));\n" +
        "    return vec4(col, 1.0);\n" +
        "}\n" +
        "\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec4 clip = vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);\n" +
        "    vec4 viewPos = u_InverseProj * clip;\n" +
        "    viewPos = vec4(viewPos.xy, -1.0, 0.0);\n" +
        "    vec3 dir = normalize((u_InverseView * viewPos).xyz);\n" +
        "    fragColor = sky_color(dir);\n" +
        "}\n";

    private CustomSky() {
        super("CustomSky", "Replaces the sky with an image or fragment shader (e.g. StarryNight).");
        // Redraw the offscreen sky every rendered FRAME, not every logic tick (20 Hz):
        // CustomSkyRenderer.tick() rebuilds u_InverseView from the current camera angle,
        // so driving it off EventTick.Pre made the sky visibly lag behind the real
        // camera at framerates above 20 fps (reported as "sky janky despite 100 fps").
        // START_MAIN is a "drawing"-phase event (raw GL calls are safe here per
        // LevelRenderEvents' own doc split of extraction vs. drawing phases).
        LevelRenderEvents.START_MAIN.register(ctx -> {
            if (this.getState()) {
                CustomSkyRenderer.tick();
                updateParamsTexture();
            }
        });
    }

    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            SKY_TEXTURE.init();
            mc.getTextureManager().register(SKY_TEX_ID, SKY_TEXTURE);
            // customsky_image/customsky_shader were being written on every load (see
            // loadImage/loadShader) but nothing ever read them back -- selection was
            // lost on every restart. Mirror BetterChams.reloadTextureForCurrentMode().
            INSTANCE.reloadForCurrentMode();

            com.mojang.blaze3d.platform.NativeImage img =
                new com.mojang.blaze3d.platform.NativeImage(com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 1, 1, false);
            img.setPixelABGR(0, 0, 0xFFFFFFFF); // brightness = 1.0 (full) until first update
            paramsTexture = new net.minecraft.client.renderer.texture.DynamicTexture(() -> "customsky-params", img);
            mc.getTextureManager().register(PARAMS_ID, paramsTexture);
        });
    }

    private void updateParamsTexture() {
        if (paramsTexture == null) return;
        com.mojang.blaze3d.platform.NativeImage pixels = paramsTexture.getPixels();
        if (pixels == null) return;

        float brightness = 1.0f;
        if (realTime.getValue()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                // getSkyDarken() = (int)(15 - skyLightLevel): 0 at full daylight, 15 at
                // full night. Only darken by up to half at most -- full night still
                // reads as 50% brightness rather than dropping toward black.
                int skyDarken = mc.level.getSkyDarken();
                brightness = 1.0f - (skyDarken / 15.0f) * 0.5f;
            }
        }

        int v = Math.round(brightness * 255.0f) & 0xFF;
        pixels.setPixelABGR(0, 0, 0xFF000000 | (v << 16) | (v << 8) | v);
        paramsTexture.upload();
    }

    private void reloadForCurrentMode() {
        Mode m = (Mode) mode.getValue();
        if (m == Mode.Image) {
            String savedName = com.example.addon.AddonConfig.get("customsky_image", "");
            if (!savedName.isEmpty()) {
                Path p = shaderDir().resolve(savedName);
                if (Files.exists(p)) CustomSkyRenderer.loadImage(p);
            }
        } else if (m == Mode.Shader) {
            String savedName = com.example.addon.AddonConfig.get("customsky_shader", "");
            if (!savedName.isEmpty()) {
                Path p = shaderDir().resolve(savedName);
                if (Files.exists(p)) CustomSkyRenderer.loadCustomShader(p);
            }
        }
    }

    private static Path shaderDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("boze/sky");
    }

    private static void writeExampleShaders() {
        try {
            Path dir = shaderDir();
            Files.createDirectories(dir);
            Path bedrock = dir.resolve(BEDROCK_SHADER_NAME);
            if (!Files.exists(bedrock)) {
                Files.write(bedrock, BEDROCK_SHADER.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onEnable() {
        writeExampleShaders();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (selectImage.getValue()) {
            selectImage.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new ImagePickerScreen("boze/sky", "Select Sky Image", "(?i).*\\.(png|jpg|jpeg)$", this::loadImage)));
        }
        if (selectShader.getValue()) {
            selectShader.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new ImagePickerScreen("boze/sky", "Select Sky Shader", "(?i).*\\.frag$", this::loadShader)));
        }
        if (starryNight.getValue()) {
            starryNight.setValue(false);
            writeExampleShaders();
            loadShader(shaderDir().resolve(BEDROCK_SHADER_NAME));
        }
    }

    private void loadImage(Path path) {
        com.example.addon.AddonConfig.set("customsky_image", path.getFileName().toString());
        mode.setValueByName("Image");
        CustomSkyRenderer.loadImage(path);
    }

    private void loadShader(Path path) {
        com.example.addon.AddonConfig.set("customsky_shader", path.getFileName().toString());
        mode.setValueByName("Shader");
        CustomSkyRenderer.loadCustomShader(path);
    }
}
