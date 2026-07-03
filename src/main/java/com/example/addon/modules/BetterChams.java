package com.example.addon.modules;

import com.example.addon.rendering.ChamsImageTexture;
import com.example.addon.screens.ImagePickerScreen;
import com.mojang.blaze3d.platform.NativeImage;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.event.EventWorldRender;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

import java.nio.file.Path;

public class BetterChams extends AddonModule {

    public static boolean isRenderingHand = false;

    public static final BetterChams INSTANCE = new BetterChams();

    public static final ChamsImageTexture CHAMS_TEXTURE = new ChamsImageTexture();
    public static final ChamsImageTexture OUTLINE_TEXTURE = new ChamsImageTexture();
    public static final Identifier TEX_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsfill.png");
    public static final Identifier OUTLINE_TEX_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsoutline.png");
    public static final Identifier PARAMS_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsparam.png");
    // Separate from PARAMS_ID/paramsTexture -- that one is declared with a hardcoded
    // "width": 4 in FIVE different JSON post-effect files (entity_outline.json,
    // hand_outline.json x5 passes, fill_only_outline.json, fill_only_hand_outline.json).
    // Widening it in Java without updating every one of those broke Glow/outline
    // entirely (verified in-game: only a bare unprocessed outline showed, no halo, no
    // flare -- crash-2026-07-03-flare-black-outline). A dedicated texture avoids
    // touching any of those existing declarations.
    public static final Identifier FLARE_PARAMS_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsflareparam.png");
    private static DynamicTexture flareParamsTexture;

    private static DynamicTexture paramsTexture;
    private float flareLaggedYaw = 0f, flareLaggedPitch = 0f;
    private boolean flareLagInitialized = false;

    public final ToggleOption crystalToggle = new ToggleOption(this, "Crystals",
        "Glow outline on End Crystals.", true);
    public final ToggleOption handToggle    = new ToggleOption(this, "Hand",
        "Glow outline on hand.", true);
    public final ToggleOption playerToggle  = new ToggleOption(this, "Players",
        "Glow outline on other players.", true);
    public final ToggleOption selfToggle    = new ToggleOption(this, "Self",
        "Glow outline on yourself in 3rd person.", true);
    public final SliderOption range         = new SliderOption(this, "Range",
        "Max range in blocks.", 16.0, 8.0, 64.0, 1.0);
    public final ToggleOption glowToggle    = new ToggleOption(this, "Glow",
        "Show Kawase glow halo around the silhouette.", true);
    public final SliderOption glowThickness = new SliderOption(this, "Glow Thickness",
        "Radius of the glow effect in pixels.", 12.0, 1.0, 64.0, 1.0);
    public final ToggleOption innerGlow      = new ToggleOption(this, "InnerGlow",
        "Glow for inside", false);
    public final SliderOption glowIntensity = new SliderOption(this, "Glow Intensity",
        "Strength of the glow halo.", 0.97, 0.0, 1.0, 0.01);
    public final ToggleOption flareToggle = new ToggleOption(this, "Flare",
        "Volumetric fire wrapping every currently-glowing silhouette. Independent of Glow -- Glow (if also on) adds bloom on top of the flame.", false);
    public final dev.boze.api.option.ColorOption flareTint = new dev.boze.api.option.ColorOption(this, "FlareTint",
        "Tint multiplied onto the flare's base fire palette.", dev.boze.api.render.ColorMaker.staticColor(255, 120, 30), 1.0f);
    public final SliderOption flareSize = new SliderOption(this, "Flare Size",
        "Size (px) of the local canvas each silhouette edge point's flame is rendered into.", 48.0, 8.0, 128.0, 1.0);

    public enum FillMode {
        Off, Image, Gif, Shader
    }
    public final dev.boze.api.option.ModeOption fillMode = new dev.boze.api.option.ModeOption(this, "Image Fill",
        "Mode for fill image.", FillMode.Off);

    public final SliderOption fillOpacity   = new SliderOption(this, "FillOpacity",
        "Opacity of image fill.", 0.8, 0.0, 1.0, 0.01);
    public final ToggleOption selectImage   = new ToggleOption(this, "SelectImage",
        "Open image picker from boze/images/.", false);
    public final ToggleOption selectGif     = new ToggleOption(this, "SelectGIF",
        "Open gif picker from boze/gifs/.", false);
    public final SliderOption frameDelay    = new SliderOption(this, "FrameDelay",
        "Delay between GIF frames in ms.", 10.0, 0.0, 300.0, 1.0);
    public final ToggleOption bounce        = new ToggleOption(this, "Bounce",
        "Play the GIF forward then backward (ping-pong) instead of looping, to hide the jump-cut seam where it restarts.", false);

    public final dev.boze.api.option.ColorOption fillColor = new dev.boze.api.option.ColorOption(this, "FillColor", "Color for image, gif and shader fill.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    public final dev.boze.api.option.ColorOption outlineColor = new dev.boze.api.option.ColorOption(this, "GlowColor", "Color for the glow halo and shader outline.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    public final ToggleOption selectShader  = new ToggleOption(this, "SelectShader", "Open shader picker from boze/shaders/.", false);

    private BetterChams() {
        super("BetterChams", "Better Chams");
    }

    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            CHAMS_TEXTURE.init();
            OUTLINE_TEXTURE.init();
            // Initialize Outline texture with a solid white pixel by default so standard bloom works
            OUTLINE_TEXTURE.loadSolidColor(0xFFFFFFFF);
            
            if (INSTANCE != null) {
                INSTANCE.reloadTextureForCurrentMode();
            }

            mc.getTextureManager().register(TEX_ID, CHAMS_TEXTURE);
            mc.getTextureManager().register(OUTLINE_TEX_ID, OUTLINE_TEXTURE);
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 4, 1, false);
            img.setPixelABGR(0, 0, 0xFF0000FF); // glow on, fill off, opacity 0, thickness max
            img.setPixelABGR(1, 0, 0xFFFFFFFF); // fill color
            img.setPixelABGR(2, 0, 0xFFFFFFFF); // outline color
            img.setPixelABGR(3, 0, 0xFFFFFFFF); // flipY (255 = flip, 0 = no flip)
            paramsTexture = new DynamicTexture(() -> "chams-params", img);
            mc.getTextureManager().register(PARAMS_ID, paramsTexture);

            NativeImage flareImg = new NativeImage(NativeImage.Format.RGBA, 3, 1, false);
            flareImg.setPixelABGR(0, 0, 0xFF000000); // flare enabled/yaw/pitch/size
            flareImg.setPixelABGR(1, 0, 0xFFFFFFFF); // flare tint
            flareImg.setPixelABGR(2, 0, 0xFF0000FF); // flare time
            flareParamsTexture = new DynamicTexture(() -> "chams-flare-params", flareImg);
            mc.getTextureManager().register(FLARE_PARAMS_ID, flareParamsTexture);
        });
    }

    public boolean isInRange(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double r = range.getValue();
        return mc.player.distanceToSqr(entity) <= r * r;
    }

    private FillMode lastFillMode = FillMode.Off;

    public void reloadTextureForCurrentMode() {
        FillMode mode = (FillMode) fillMode.getValue();
        if (mode != FillMode.Shader) {
            OUTLINE_TEXTURE.loadSolidColor(0xFFFFFFFF);
        }
        
        if (mode == FillMode.Image) {
            String savedName = com.example.addon.AddonConfig.get("betterchams_image", "");
            if (!savedName.isEmpty()) {
                Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("boze/images/" + savedName);
                if (java.nio.file.Files.exists(p)) CHAMS_TEXTURE.loadImage(p);
            }
        } else if (mode == FillMode.Gif) {
            String savedName = com.example.addon.AddonConfig.get("betterchams_gif", "");
            if (!savedName.isEmpty()) {
                Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("boze/gifs/" + savedName);
                if (java.nio.file.Files.exists(p)) CHAMS_TEXTURE.loadImage(p);
            }
        } else if (mode == FillMode.Shader) {
            String savedName = com.example.addon.AddonConfig.get("betterchams_shader", "");
            if (!savedName.isEmpty()) {
                Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("boze/shaders/" + savedName);
                if (java.nio.file.Files.exists(p)) com.example.addon.rendering.ChamsCustomShader.loadShader(p);
            }
        }
    }

    public void loadImage(Path path) {
        if (path.toString().toLowerCase().endsWith(".frag")) {
            com.example.addon.AddonConfig.set("betterchams_shader", path.getFileName().toString());
            fillMode.setValueByName("Shader");
            com.example.addon.rendering.ChamsCustomShader.loadShader(path);
        } else {
            OUTLINE_TEXTURE.loadSolidColor(0xFFFFFFFF);
            CHAMS_TEXTURE.loadImage(path);
            if (path.toString().toLowerCase().endsWith(".gif")) {
                com.example.addon.AddonConfig.set("betterchams_gif", path.getFileName().toString());
                fillMode.setValueByName("Gif");
            } else {
                com.example.addon.AddonConfig.set("betterchams_image", path.getFileName().toString());
                fillMode.setValueByName("Image");
            }
        }
        lastFillMode = (FillMode) fillMode.getValue();
    }

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        FillMode currentMode = (FillMode) fillMode.getValue();
        if (currentMode != lastFillMode) {
            lastFillMode = currentMode;
            reloadTextureForCurrentMode();
        }

        if (selectImage.getValue()) {
            selectImage.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new ImagePickerScreen("boze/images", "Select Image", "(?i).*\\.(png|jpg|jpeg)$", this::loadImage)));
        }
        if (selectGif.getValue()) {
            selectGif.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new ImagePickerScreen("boze/gifs", "Select GIF", "(?i).*\\.gif$", this::loadImage)));
        }
        if (selectShader.getValue()) {
            selectShader.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new ImagePickerScreen("boze/shaders", "Select Shader", "(?i).*\\.frag$", this::loadImage)));
        }
        
        if (currentMode == FillMode.Shader) {
            com.example.addon.rendering.ChamsCustomShader.renderCustomShader();
            // CHAMS_TEXTURE and OUTLINE_TEXTURE are already updated directly by ChamsCustomShader
        } else {
            // Unused textures revert to inner when they are not in Shader mode
            // Actually, we don't need to do anything because if it's Image/Gif it uses inner automatically.
            // But we should ensure the shader resize doesn't permanently overwrite the image.
            // For now, we rely on loadImage to reload the image/gif.
        }
        
        updateParamsTexture();
    }

    @EventHandler
    private void onWorldRender(EventWorldRender event) {
        // GIF playback was driven off EventTick.Pre (20 Hz logic tick), so it could
        // advance at most one frame per ~50ms regardless of FrameDelay -- any delay
        // below 50ms (including 0 and the default 10) hit that same 20fps ceiling and
        // looked identical, since ChamsImageTexture.tick() only ever advances by one
        // frame per call. Driving it from the per-render-frame event instead lets
        // FrameDelay actually reach real framerate-limited speeds.
        if ((FillMode) fillMode.getValue() == FillMode.Gif) {
            CHAMS_TEXTURE.tick(frameDelay.getValue(), bounce.getValue());
        }
    }

    private void updateParamsTexture() {
        if (paramsTexture == null) return;
        Minecraft mc = Minecraft.getInstance();
        boolean on = getState();
        boolean fillOn = on && (fillMode.getValue() != FillMode.Off) && CHAMS_TEXTURE.hasImage();
        boolean glowOn = on && glowToggle.getValue();
        int r = fillOn ? 255 : 0;
        int g = Math.round((float)(fillOpacity.getValue() * 255)) & 0xFF;
        int b = glowOn ? 255 : 0;
        int a = Math.round((float)(double)glowThickness.getValue()) & 0xFF; // raw pixel radius, unpacked via *255 in shader

        // NativeImage ABGR packing: (alpha << 24) | (blue << 16) | (green << 8) | red
        int abgr = (a << 24) | (b << 16) | (g << 8) | r;

        int fillC = fillColor.getValue().color.getPacked();
        int fillAbgr = (fillC & 0xFF000000) | ((fillC & 0xFF) << 16) | (fillC & 0xFF00) | ((fillC >> 16) & 0xFF);

        int glowC = outlineColor.getValue().color.getPacked();
        int glowAbgr = (glowC & 0xFF000000) | ((glowC & 0xFF) << 16) | (glowC & 0xFF00) | ((glowC >> 16) & 0xFF);

        int flipY = (fillMode.getValue() == FillMode.Shader) ? 0 : 255;
        int innerGlowPacked = innerGlow.getValue() ? 255 : 0;
        int intensityPacked = Math.round((float)(glowIntensity.getValue() * 255.0)) & 0xFF;
        int flipAbgr = (255 << 24) | (intensityPacked << 16) | (innerGlowPacked << 8) | flipY;

        NativeImage pixels = paramsTexture.getPixels();
        if (pixels != null) {
            pixels.setPixelABGR(0, 0, abgr);
            pixels.setPixelABGR(1, 0, fillAbgr);
            pixels.setPixelABGR(2, 0, glowAbgr);
            pixels.setPixelABGR(3, 0, flipAbgr);
            paramsTexture.upload();
        }

        updateFlareParamsTexture(mc, on);
    }

    private void updateFlareParamsTexture(Minecraft mc, boolean on) {
        if (flareParamsTexture == null) return;
        NativeImage pixels = flareParamsTexture.getPixels();
        if (pixels == null) return;

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
        pixels.setPixelABGR(0, 0, (flareA << 24) | (flareB << 16) | (flareG << 8) | flareR);

        int flareTintC = flareTint.getValue().color.getPacked();
        int flareTintAbgr = (flareTintC & 0xFF000000) | ((flareTintC & 0xFF) << 16) | (flareTintC & 0xFF00) | ((flareTintC >> 16) & 0xFF);
        pixels.setPixelABGR(1, 0, flareTintAbgr);

        int flareTimeR = (int) ((System.currentTimeMillis() % 10000L) / 10000.0 * 255.0) & 0xFF;
        pixels.setPixelABGR(2, 0, (0xFF << 24) | flareTimeR);

        flareParamsTexture.upload();
    }
}
