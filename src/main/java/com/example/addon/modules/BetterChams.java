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
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.nio.file.Path;

public class BetterChams extends AddonModule {

    public static boolean isRenderingHand = false;

    public static final BetterChams INSTANCE = new BetterChams();

    public static final ChamsImageTexture CHAMS_TEXTURE = new ChamsImageTexture();
    public static final ChamsImageTexture OUTLINE_TEXTURE = new ChamsImageTexture();
    public static final ChamsImageTexture FLARE_TEXTURE = new ChamsImageTexture();
    public static final ChamsImageTexture GLOW_TEXTURE = new ChamsImageTexture();
    public static final Identifier TEX_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsfill.png");
    public static final Identifier OUTLINE_TEX_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsoutline.png");
    public static final Identifier FLARE_TEX_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsflare.png");
    public static final Identifier GLOW_TEX_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsglowtex.png");
    public static final Identifier PARAMS_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsparam.png");

    private static DynamicTexture paramsTexture;

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
    public final SliderOption sampleStep    = new SliderOption(this, "Sample Step",
        "Inner-rim glow bleed radius in pixels (into the silhouette from its edge). Independent of Glow Thickness (outer radius).", 1.0, 1.0, 4.0, 0.1);
    public final SliderOption glowIntensity = new SliderOption(this, "Glow Intensity",
        "Strength of the glow halo.", 0.97, 0.0, 1.0, 0.01);

    public final ToggleOption flareToggle = new ToggleOption(this, "Flare",
        "Nonuniform lens-flare-style rays radiating from the nearest glowing target, using a mask image. Only visible while Glow is on.", false);
    public final SliderOption flareSize = new SliderOption(this, "Flare Size",
        "Radius (fraction of screen) the flare mask is projected across.", 0.6, 0.05, 2.0, 0.01);
    public final ToggleOption selectFlareMask = new ToggleOption(this, "Select Flare Mask",
        "Open flare mask picker from boze/flares/.", false);

    public final ToggleOption glowTextureToggle = new ToggleOption(this, "Glow Texture",
        "Screen-blend an image onto the glow halo itself (not just the fill). Only visible while Glow is on.", false);
    public final ToggleOption selectGlowTexture = new ToggleOption(this, "Select Glow Texture",
        "Open glow overlay texture picker from boze/glowtextures/.", false);

    public enum FillMode {
        Off, Image, Gif, Shader
    }
    public final dev.boze.api.option.ModeOption fillMode = new dev.boze.api.option.ModeOption(this, "Image Fill",
        "Mode for fill image.", FillMode.Off);

    public final SliderOption fillOpacity   = new SliderOption(this, "Fill Opacity",
        "Opacity of image fill.", 0.8, 0.0, 1.0, 0.01);
    public final ToggleOption selectImage   = new ToggleOption(this, "Select Image",
        "Open image picker from boze/images/.", false);
    public final ToggleOption selectGif     = new ToggleOption(this, "Select GIF",
        "Open gif picker from boze/gifs/.", false);
    public final SliderOption frameDelay    = new SliderOption(this, "Frame Delay",
        "Delay between GIF frames in ms.", 50.0, 10.0, 300.0, 1.0);

    public final dev.boze.api.option.ColorOption fillColor = new dev.boze.api.option.ColorOption(this, "Fill Color", "Color for image, gif and shader fill.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    public final dev.boze.api.option.ColorOption outlineColor = new dev.boze.api.option.ColorOption(this, "Glow Color", "Color for the glow halo and shader outline.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    public final ToggleOption selectShader  = new ToggleOption(this, "Select Shader", "Open shader picker from boze/shaders/.", false);

    private BetterChams() {
        super("BetterChams", "Glow outline + image fill for End Crystals and players.");
    }

    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            CHAMS_TEXTURE.init();
            OUTLINE_TEXTURE.init();
            FLARE_TEXTURE.init();
            GLOW_TEXTURE.init();
            // Initialize Outline texture with a solid white pixel by default so standard bloom works
            OUTLINE_TEXTURE.loadSolidColor(0xFFFFFFFF);

            if (INSTANCE != null) {
                INSTANCE.reloadTextureForCurrentMode();
            }

            mc.getTextureManager().register(TEX_ID, CHAMS_TEXTURE);
            mc.getTextureManager().register(OUTLINE_TEX_ID, OUTLINE_TEXTURE);
            mc.getTextureManager().register(FLARE_TEX_ID, FLARE_TEXTURE);
            mc.getTextureManager().register(GLOW_TEX_ID, GLOW_TEXTURE);
            // 6 columns: [0]=fill/glow/thickness [1]=fillTint [2]=glowTint [3]=flip/step/intensity
            // [4]=flare enabled/center.x/center.y/size [5]=glowTexture enabled
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 6, 1, false);
            img.setPixelABGR(0, 0, 0xFF0000FF); // glow on, fill off, opacity 0, thickness max
            img.setPixelABGR(1, 0, 0xFFFFFFFF); // fill color
            img.setPixelABGR(2, 0, 0xFFFFFFFF); // outline color
            img.setPixelABGR(3, 0, 0xFFFFFFFF); // flipY (255 = flip, 0 = no flip)
            img.setPixelABGR(4, 0, 0xFF000000); // flare disabled, center 0,0, size 0
            img.setPixelABGR(5, 0, 0xFF000000); // glow texture disabled
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
        // Independent of fillMode -- Flare and Glow Texture are their own toggles under Glow.
        String savedFlare = com.example.addon.AddonConfig.get("betterchams_flare", "");
        if (!savedFlare.isEmpty()) {
            Path fp = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("boze/flares/" + savedFlare);
            if (java.nio.file.Files.exists(fp)) FLARE_TEXTURE.loadImage(fp);
        }
        String savedGlowTex = com.example.addon.AddonConfig.get("betterchams_glowtex", "");
        if (!savedGlowTex.isEmpty()) {
            Path gp = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("boze/glowtextures/" + savedGlowTex);
            if (java.nio.file.Files.exists(gp)) GLOW_TEXTURE.loadImage(gp);
        }

        FillMode mode = (FillMode) fillMode.getValue();
        if (mode != FillMode.Shader) {
            OUTLINE_TEXTURE.loadSolidColor(0xFFFFFFFF);
        }

        if (mode != FillMode.Gif) {
            // Switching away from Gif (e.g. straight to Shader) doesn't call loadImage()
            // again, so a still-running gif decode must be cancelled here explicitly,
            // otherwise it finishes later and clobbers whatever mode we switched to.
            CHAMS_TEXTURE.cancelPendingDecode();
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

    public void loadFlareMask(Path path) {
        com.example.addon.AddonConfig.set("betterchams_flare", path.getFileName().toString());
        FLARE_TEXTURE.loadImage(path);
    }

    public void loadGlowTexture(Path path) {
        com.example.addon.AddonConfig.set("betterchams_glowtex", path.getFileName().toString());
        GLOW_TEXTURE.loadImage(path);
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
        if (selectFlareMask.getValue()) {
            selectFlareMask.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new ImagePickerScreen("boze/flares", "Select Flare Mask", "(?i).*\\.(png|jpg|jpeg)$", this::loadFlareMask)));
        }
        if (selectGlowTexture.getValue()) {
            selectGlowTexture.setValue(false);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new ImagePickerScreen("boze/glowtextures", "Select Glow Texture", "(?i).*\\.(png|jpg|jpeg)$", this::loadGlowTexture)));
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

    /**
     * Screen-space UV (0..1, y-down) of the nearest currently-glowing target's position,
     * or null if none is eligible / it's behind the camera. Eligibility mirrors the exact
     * conditions MixinEndCrystalRenderer / MixinAvatarRenderer use to decide whether an
     * entity gets the outline treatment at all (crystalToggle+range, playerToggle+range,
     * selfToggle+third-person) -- a flare with no matching glow target would be pointless.
     * V1 scope: one flare source per frame (nearest to camera), not one per glowing entity
     * -- the shared entity_outline resolve pass has no per-entity screen position to work
     * with without a much larger architecture change.
     */
    private Vec3 findFlareTargetPos(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 camPos = mc.gameRenderer.getMainCamera().position();

        Vec3 best = null;
        double bestDistSq = Double.MAX_VALUE;

        if (crystalToggle.getValue()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof EndCrystal crystal)) continue;
                if (!isInRange(crystal)) continue;
                double d = camPos.distanceToSqr(crystal.position());
                if (d < bestDistSq) { bestDistSq = d; best = crystal.position(); }
            }
        }

        if (playerToggle.getValue()) {
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof AbstractClientPlayer p) || p == mc.player) continue;
                if (!isInRange(p)) continue;
                double d = camPos.distanceToSqr(p.position());
                if (d < bestDistSq) { bestDistSq = d; best = p.getEyePosition(); }
            }
        }

        if (selfToggle.getValue() && !mc.options.getCameraType().isFirstPerson()) {
            double d = camPos.distanceToSqr(mc.player.position());
            if (d < bestDistSq) { bestDistSq = d; best = mc.player.getEyePosition(); }
        }

        return best;
    }

    /**
     * Standard direction-vector perspective projection: decompose (target - camera) into
     * the camera's forward/left/up basis, scale by tan(halfFov) to land in NDC, remap to
     * UV (y-down, matching texture space). Deliberately avoids reconstructing Mojang's
     * internal Projection matrix (RenderSystem no longer exposes a plain CPU-side
     * projection Matrix4f in 26.1.2 -- it's GPU-buffer only) -- this only needs public
     * Camera accessors (position/forwardVector/leftVector/upVector/getFov) plus the
     * window size, so it can't drift from an unverifiable internal matrix layout.
     */
    private static float[] worldToScreenUV(Minecraft mc, Vec3 worldPos) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        Vec3 diff = worldPos.subtract(camPos);
        double len = diff.length();
        if (len < 1.0e-4) return null;
        Vector3f dir = new Vector3f((float) (diff.x / len), (float) (diff.y / len), (float) (diff.z / len));

        Vector3f forward = new Vector3f(camera.forwardVector());
        Vector3f up = new Vector3f(camera.upVector());
        Vector3f left = new Vector3f(camera.leftVector());

        float f = dir.dot(forward);
        if (f <= 0.001f) return null; // behind camera

        float rightComp = -dir.dot(left); // "left" vector -> negate for a "right" component
        float upComp = dir.dot(up);

        float fovRad = (float) Math.toRadians(camera.getFov());
        float tanHalfFovY = (float) Math.tan(fovRad / 2.0);
        float aspect = (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        float tanHalfFovX = tanHalfFovY * aspect;

        float ndcX = (rightComp / f) / tanHalfFovX;
        float ndcY = (upComp / f) / tanHalfFovY;

        float u = ndcX * 0.5f + 0.5f;
        float v = 1.0f - (ndcY * 0.5f + 0.5f);
        return new float[]{u, v};
    }

    private void updateParamsTexture() {
        if (paramsTexture == null) return;
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
        // sampleStep in [1.0, 4.0] -> normalize to [0,1] range before packing so a plain texture() sample decodes as value/4.0
        int stepPacked = Math.round((float)(sampleStep.getValue() / 4.0 * 255.0)) & 0xFF;
        int intensityPacked = Math.round((float)(glowIntensity.getValue() * 255.0)) & 0xFF;
        int flipAbgr = (255 << 24) | (intensityPacked << 16) | (stepPacked << 8) | flipY;

        // Flare: only meaningful while Glow is also on (see flareToggle description).
        boolean flareOn = glowOn && flareToggle.getValue() && FLARE_TEXTURE.hasImage();
        float flareU = 0f, flareV = 0f;
        if (flareOn) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 targetPos = findFlareTargetPos(mc);
            float[] uv = targetPos != null ? worldToScreenUV(mc, targetPos) : null;
            if (uv == null) {
                flareOn = false;
            } else {
                flareU = uv[0];
                flareV = uv[1];
            }
        }
        int flareSizePacked = Math.round((float)(Math.min(2.0, flareSize.getValue()) / 2.0 * 255.0)) & 0xFF;
        int flareAbgr = ((flareSizePacked & 0xFF) << 24)
            | ((Math.round(flareV * 255f) & 0xFF) << 16)
            | ((Math.round(flareU * 255f) & 0xFF) << 8)
            | (flareOn ? 255 : 0);

        boolean glowTexOn = glowOn && glowTextureToggle.getValue() && GLOW_TEXTURE.hasImage();
        int glowTexAbgr = (255 << 24) | (glowTexOn ? 255 : 0);

        NativeImage pixels = paramsTexture.getPixels();
        if (pixels != null) {
            pixels.setPixelABGR(0, 0, abgr);
            pixels.setPixelABGR(1, 0, fillAbgr);
            pixels.setPixelABGR(2, 0, glowAbgr);
            pixels.setPixelABGR(3, 0, flipAbgr);
            pixels.setPixelABGR(4, 0, flareAbgr);
            pixels.setPixelABGR(5, 0, glowTexAbgr);
            paramsTexture.upload();
        }
    }
}
