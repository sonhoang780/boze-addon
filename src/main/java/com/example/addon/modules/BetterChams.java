package com.example.addon.modules;

import com.example.addon.rendering.ChamsImageTexture;
import com.example.addon.screens.ImagePickerScreen;
import com.mojang.blaze3d.platform.NativeImage;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
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

    private static DynamicTexture paramsTexture;

    public final ToggleOption crystalToggle = new ToggleOption(this, "Crystals",
        "Bloom outline on End Crystals.", true);
    public final ToggleOption handToggle    = new ToggleOption(this, "Hand",
        "Bloom outline on hand.", true);
    public final ToggleOption playerToggle  = new ToggleOption(this, "Players",
        "Bloom outline on other players.", true);
    public final ToggleOption selfToggle    = new ToggleOption(this, "Self",
        "Bloom outline on yourself in 3rd person.", true);
    public final SliderOption range         = new SliderOption(this, "Range",
        "Max range in blocks.", 16.0, 8.0, 64.0, 1.0);
    public final ToggleOption bloomToggle   = new ToggleOption(this, "Bloom",
        "Show bloom halo and crisp edge.", true);
    public final SliderOption bloomRadius   = new SliderOption(this, "Bloom Radius",
        "Radius of the bloom effect in pixels.", 12.0, 1.0, 64.0, 1.0);
        
    public enum FillMode {
        Off, Image, Gif, Shader
    }
    public final dev.boze.api.option.ModeOption fillMode = new dev.boze.api.option.ModeOption(this, "Image Fill",
        "Mode for fill image.", FillMode.Off);
        
    public final SliderOption outlineOpacity = new SliderOption(this, "Outline Opacity", "Opacity of the solid outline when Bloom is off.", 1.0, 0.0, 1.0, 0.01);
    public final SliderOption fillOpacity   = new SliderOption(this, "Fill Opacity",
        "Opacity of image fill.", 0.8, 0.0, 1.0, 0.01);
    public final ToggleOption selectImage   = new ToggleOption(this, "Select Image",
        "Open image picker from boze/images/.", false);
    public final ToggleOption selectGif     = new ToggleOption(this, "Select GIF",
        "Open gif picker from boze/gifs/.", false);
    public final SliderOption frameDelay    = new SliderOption(this, "Frame Delay",
        "Delay between GIF frames in ms.", 50.0, 10.0, 300.0, 1.0);
    
    public final dev.boze.api.option.ColorOption fillColor = new dev.boze.api.option.ColorOption(this, "Fill Color", "Color for image, gif and shader fill.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    public final dev.boze.api.option.ColorOption outlineColor = new dev.boze.api.option.ColorOption(this, "Outline Color", "Color for bloom and shader outline.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    public final ToggleOption selectShader  = new ToggleOption(this, "Select Shader", "Open shader picker from boze/shaders/.", false);

    private BetterChams() {
        super("BetterChams", "Bloom outline + image fill for End Crystals and players.");
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
            img.setPixelABGR(0, 0, 0xFF0000FF); // bloom on, fill off, opacity 0, radius max
            img.setPixelABGR(1, 0, 0xFFFFFFFF); // fill color
            img.setPixelABGR(2, 0, 0xFFFFFFFF); // outline color
            img.setPixelABGR(3, 0, 0xFFFFFFFF); // flipY (255 = flip, 0 = no flip)
            paramsTexture = new DynamicTexture(() -> "chams-params", img);
            mc.getTextureManager().register(PARAMS_ID, paramsTexture);
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
        
        if (currentMode == FillMode.Gif) {
            CHAMS_TEXTURE.tick(frameDelay.getValue());
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

    private void updateParamsTexture() {
        if (paramsTexture == null) return;
        boolean on = getState();
        boolean fillOn  = on && (fillMode.getValue() != FillMode.Off) && CHAMS_TEXTURE.hasImage();
        boolean bloomOn = on && bloomToggle.getValue();
        int r = fillOn  ? 255 : 0;
        int g = Math.round((float)(fillOpacity.getValue() * 255)) & 0xFF;
        int b = bloomOn ? 255 : 0;
        int a = Math.round((float)(double)bloomRadius.getValue()) & 0xFF; // Pack bloom radius into Alpha
        
        // NativeImage ABGR packing: (alpha << 24) | (blue << 16) | (green << 8) | red
        int abgr = (a << 24) | (b << 16) | (g << 8) | r;
        
        int fillC = fillColor.getValue().color.getPacked();
        int fillAbgr = (fillC & 0xFF000000) | ((fillC & 0xFF) << 16) | (fillC & 0xFF00) | ((fillC >> 16) & 0xFF);
        
        int outC = outlineColor.getValue().color.getPacked();
        int outAbgr = (outC & 0xFF000000) | ((outC & 0xFF) << 16) | (outC & 0xFF00) | ((outC >> 16) & 0xFF);

        int flipY = (fillMode.getValue() == FillMode.Shader) ? 0 : 255;
        int outOp = Math.round((float)(outlineOpacity.getValue() * 255)) & 0xFF;
        int flipAbgr = (255 << 24) | (255 << 16) | (outOp << 8) | flipY;

        NativeImage pixels = paramsTexture.getPixels();
        if (pixels != null) {
            pixels.setPixelABGR(0, 0, abgr);
            pixels.setPixelABGR(1, 0, fillAbgr);
            pixels.setPixelABGR(2, 0, outAbgr);
            pixels.setPixelABGR(3, 0, flipAbgr);
            paramsTexture.upload();
        }
    }
}
