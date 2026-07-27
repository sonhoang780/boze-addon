package com.example.addon.modules;

import com.example.addon.render.ChamsImageTexture;
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

    // Set by the outline-marker mixins whenever anything of OURS was submitted for
    // outline this frame; consumed (and reset) by MixinGameRenderer.reprocessHandOutline
    // to skip the full JFA pipeline on frames with an empty silhouette (menus, no
    // entities in range, hand toggle off).
    public static boolean silhouetteThisFrame = false;

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

    // Same dedicated-texture pattern as FLARE_PARAMS_ID -- keeps this out of the
    // hardcoded "width": 4 betterchamsparam declarations in the JSON post-effect files.
    public static final Identifier OUTLINE_PARAMS_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsoutlineparam.png");
    private static DynamicTexture outlineParamsTexture;

    // Hand vs world separation happens PER PIXEL inside the shared resolve pass, not
    // via separate textures/chains: when the module is on, MixinShaderManager nulls
    // the vanilla entity_outline chain and reprocessHandOutline resolves ALL outlines
    // (world entities + hand) with ONE hand_outline.json process() call -- so any
    // per-chain param split silently applies hand values to everything. Instead the
    // hand's silhouette is drawn with a marker color (blue 250 instead of 255, see
    // HAND_OUTLINE_COLOR); the blur preserves that ratio, and glow_resolve derives a
    // per-pixel "handness" from it to lift the distance scaling and the flare part-
    // mask for hand pixels only.
    public static final int HAND_OUTLINE_COLOR = 0xFFFFFFFA;

    // World entities (players/crystals/self) use their OWN marker too -- green 250
    // instead of pure white. Pure white 0xFFFFFFFF is exactly what foreign content
    // (Boze BlockHighlight in 3rd person with the crosshair on a block; extra
    // F3-era buffer content) lands in the SHARED entityOutlineTarget as, so a pure
    // white marker was indistinguishable from it: foreign silhouettes passed
    // isOurs, seeded the JFA field, and bloomed a giant soft halo around the
    // targeted block / debug content -- the "glow nhoe" in 3rd person and F3
    // (both views), 2026-07-04. With green-250 the isOurs guard can now REJECT
    // exact pure white as foreign.
    public static final int ENTITY_OUTLINE_COLOR = 0xFFFFFAFF;

    // Crystals need their own sub-marker so glow_resolve.fsh can tell "Player" (8
    // InnerGlow layers) apart from "Crystal" (4 layers) within the shared entity
    // class. R (always 255 in both HAND/ENTITY markers -- an unused bit until now)
    // drops to 250 instead, still passing isOurs's r>0.97 test (250/255=0.98), while
    // G/B keep the EXACT entity pattern (250/255) so the existing hand-vs-entity
    // split (which reads G/B, see isOurs and the b<0.995 checks) is untouched.
    public static final int CRYSTAL_OUTLINE_COLOR = 0xFFFAFAFF;

    // Current frame's field radius in px -- read by JfaField to pick its jump-flood
    // reach (more/bigger jump steps = wider distance search for big Glow Thickness /
    // Flare Size).
    public static volatile double fieldRadiusPxForBlur = 12.0;

    private static DynamicTexture paramsTexture;
    private float flareLaggedYaw = 0f, flareLaggedPitch = 0f;
    private boolean flareLagInitialized = false;

    // Glow/Flare's blur radius (and Flare's noise scale) are raw screen-space pixel
    // values -- fixed regardless of how far the silhouette is. Past a certain distance
    // the silhouette shrinks faster than the fixed-px blur kernel, so the kernel's own
    // (roughly diamond) footprint starts to dominate over the actual silhouette shape,
    // reading as a rhombus ballooning outward the farther away the target gets.
    //
    // A prior fix scaled by a heuristic "reference distance / actual distance" ratio --
    // that guessed reference distance never matched the real relationship between
    // distance and on-screen size (which also depends on FOV, resolution, and the
    // entity's own real-world height), so it under-corrected and the bloat persisted
    // (verified in-game, 2026-07-03 22:04 clip). This instead computes each glowing
    // entity's ACTUAL apparent on-screen height via the standard perspective-projection
    // formula (apparentPx = realHeight / (2 * distance * tan(fov/2)) * screenHeightPx)
    // and caps the blur/flare radius to a fraction of that -- grounded in real screen
    // geometry instead of a guessed constant, so it can never balloon past a fixed
    // fraction of however big the silhouette actually is on screen right now.
    // Reference apparent height: the on-screen size at which the sliders mean raw
    // pixels (a 1.8-block player at ~4.6 blocks, 1080p, FOV 70). Scaling the radii by
    // apparentPx/REF makes the effect PROPORTIONAL to the entity -- it shrinks and
    // grows with the silhouette like it's attached in world space, instead of holding
    // a fixed pixel size that visually balloons relative to a receding target (a cap
    // alone only stopped extreme bloat; the ratio still grew until the cap engaged).
    private static final double REF_APPARENT_PX = 300.0;
    private double smallestApparentPx = Double.MAX_VALUE;
    private double lastSmallestApparentPx = Double.MAX_VALUE;

    public void reportGlowDistance(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Self in 3rd person renders `entity == mc.player` (MixinAvatarRenderer's
        // self-outline branch), so distanceToSqr(entity) against itself is always
        // exactly 0 -- floored to 0.1, which reads as "impossibly close" and forced
        // the shared scale to its 2.0 cap. Use the CAMERA's position instead: in 3rd
        // person the camera is genuinely offset from the player by the real zoom
        // distance, which is what "how big does self's glow look" should track.
        double d = (entity == mc.player)
            ? Math.max(mc.gameRenderer.getMainCamera().position().distanceTo(entity.position()), 0.1)
            : Math.max(Math.sqrt(mc.player.distanceToSqr(entity)), 0.1);
        double fovRad = Math.toRadians(mc.options.fov().get());
        double screenH = mc.getWindow().getHeight();
        double entityHeight = Math.max(0.5, entity.getBbHeight());
        double apparentPx = (entityHeight / (2.0 * d * Math.tan(fovRad / 2.0))) * screenH;
        if (apparentPx < smallestApparentPx) smallestApparentPx = apparentPx;
    }

    // Time-smoothed (exponential lerp) so the halo/flame visibly eases between sizes
    // instead of popping when the nearest glowing entity changes or moves fast.
    private double smoothedScale = 1.0;
    private long scaleLastNanos = 0L;

    private void updateSmoothedScale() {
        // sqrt(ratio) instead of the raw ratio: apparentPx falls off as 1/distance, so
        // the raw ratio crashed toward the 0.05 floor within a few blocks (user report
        // 2026-07-04: "4 blocks away and the player's flare/glow is already gone").
        // Square-rooting flattens that curve (a ratio of 0.09 becomes 0.3 instead of
        // 0.09), and the floor is raised to 0.5 so a distant target always keeps at
        // least half-size glow/flare (0.35 still read as "shrinks too fast", user
        // 2026-07-04). Re-tune REF_APPARENT_PX itself (not this curve) if near-range
        // still feels off.
        double ratio = lastSmallestApparentPx == Double.MAX_VALUE ? 1.0 : lastSmallestApparentPx / REF_APPARENT_PX;
        double target = Math.max(0.5, Math.min(2.0, Math.sqrt(Math.max(0.0, ratio))));
        long now = System.nanoTime();
        float dt = scaleLastNanos == 0L ? 0.016f : Math.min((now - scaleLastNanos) / 1_000_000_000f, 0.25f);
        scaleLastNanos = now;
        double k = 1.0 - Math.exp(-8.0 * dt);
        smoothedScale += (target - smoothedScale) * k;
    }

    private double effectScale() {
        return smoothedScale;
    }

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
    public enum GlowMode {
        Off, Soft, Neon
    }
    // Named "GlowMode" (not "Glow") deliberately: the old option was a ToggleOption
    // named "Glow" (boolean true/false). BetterChams has no fromJson override for
    // null-safe loading (unlike MusicHUD/PlayMusic), so feeding old boolean-shaped
    // saved data into a same-named ModeOption risked throwing mid-load and aborting
    // every option after it. A new key sidesteps the type collision entirely --
    // the stale "Glow" entry is just orphaned in old config files.
    public final dev.boze.api.option.ModeOption glowMode = new dev.boze.api.option.ModeOption(this, "GlowMode",
        "", GlowMode.Soft);
    public final SliderOption glowThickness = new SliderOption(this, "Glow Thickness",
        "Radius of the glow effect in pixels.", 12.0, 1.0, 64.0, 1.0);
    public final ToggleOption innerGlow      = new ToggleOption(this, "InnerGlow",
        "Glow for inside", false);
    public final SliderOption glowIntensity = new SliderOption(this, "Glow Intensity",
        "Strength of the glow halo.", 0.97, 0.0, 1.0, 0.01);
    public final ToggleOption flareToggle = new ToggleOption(this, "Flare",
        "KABOOM KABLAU", false);
    public final dev.boze.api.option.ColorOption flareTint = new dev.boze.api.option.ColorOption(this, "FlareTint",
        "Flare tint", dev.boze.api.render.ColorMaker.staticColor(255, 120, 30), 1.0f);
    public final SliderOption flareSize = new SliderOption(this, "Flare Size",
        "", 48.0, 8.0, 128.0, 1.0);

    public enum OutlineMode {
        Off, Simple, Complex
    }
    // Named "OutlineMode" (not "Outline") deliberately: the old option was a
    // ToggleOption named "Outline" (boolean true/false) -- same JSON-type-collision
    // hazard as GlowMode replacing "Glow", see that enum's comment. Simple = the
    // original crisp JFA-edge outline. Complex = the old Gel Fill mode's outline
    // (GelParticleSystem wireframe box cage + Brownian dust + deep InnerGlow layers),
    // now decoupled from Image Fill entirely since Gel mode was removed.
    public final dev.boze.api.option.ModeOption outlineMode = new dev.boze.api.option.ModeOption(this, "OutlineMode",
        "Simple: crisp edge outline. Complex: wireframe box cage + Brownian dust + deep inner glow.", OutlineMode.Off);
    public final SliderOption outlineRadius = new SliderOption(this, "Outline Radius",
        "Outline thickness", 2.0, 1.0, 5.0, 1.0);
    public final dev.boze.api.option.ColorOption outlineTint = new dev.boze.api.option.ColorOption(this, "OutlineColor",
        "", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    // Independent of OutlineMode -- previously a hardcoded ~15% alpha (0x26FFFFFF)
    // applied only to players in Gel Fill mode via LivingEntityRenderer's isInvisible
    // ghost branch. Now a real slider driving a dedicated constant-alpha translucent
    // RenderType applied to both Players and Crystals (see BetterChamsGhostPipeline).
    // 1.0 = normal opaque rendering, 0.0 = fully invisible.
    public final SliderOption opacity = new SliderOption(this, "Opacity",
        "Body opacity for Players, Crystals, and Hands. 1.0 = normal, 0.0 = fully invisible.", 1.0, 0.0, 1.0, 0.01);
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
    public final ToggleOption selectShader  = new ToggleOption(this, "SelectShader",
        "Open shader picker from boze/shaders/.", false);

    // Complex OutlineMode only (formerly "Gel mode"): Brownian noise particles
    // drifting inside each body part's box, clipped per-part (see GelParticleSystem).
    // Values scale a real Java-side particle sim, not a shader effect. Density used to
    // be a slider -- removed (hardcoded to 1.0 in GelParticleSystem) since particles
    // are already per-part, not shared across the whole model; a single global
    // density knob added nothing a per-part volume-based target didn't already give.
    public final SliderOption gelSpeed = new SliderOption(this, "Gel Speed",
        "Complex outline: Brownian particle drift speed.", 0.3, 0.0, 2.0, 0.05);
    public final SliderOption gelTrailLength = new SliderOption(this, "Gel Trail Length",
        "Complex outline: how many past positions each particle's streak keeps.", 4.0, 1.0, 6.0, 1.0);

    public final SliderOption frameDelay    = new SliderOption(this, "FrameDelay",
        "Delay between GIF frames in ms.", 10.0, 0.0, 300.0, 1.0);
    public final ToggleOption bounce        = new ToggleOption(this, "Bounce",
        "Play back at the end of the GIF.", false);

    public final dev.boze.api.option.ColorOption fillColor = new dev.boze.api.option.ColorOption(this, "FillColor", "Color for image, gif and shader fill.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);
    public final dev.boze.api.option.ColorOption outlineColor = new dev.boze.api.option.ColorOption(this, "GlowColor", "Color for the glow halo and shader outline.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);

    private BetterChams() {
        super("BetterChams", "Better Chams");
    }

    /**
     * LevelRenderer.doEntityOutline() (verified via javap on 26.1.2) unconditionally does
     * `if (shouldShowEntityOutlines()) entityOutlineTarget.blitAndBlendToTexture(main)` every
     * frame -- no "was anything drawn into it this frame" check, and vanilla's own resolve of
     * that target only runs when its OWN haveGlowingEntities flag is set (spectator glow),
     * which our addon-submitted silhouettes never touch (that's why MixinShaderManager has to
     * intercept the postchain at all). While this module is on, our own submit+resolve
     * (MixinAvatarRenderer etc. + MixinGameRenderer#reprocessHandOutline) refreshes
     * entityOutlineTarget every frame. The instant it's disabled, nothing refreshes or clears
     * that target anymore -- its last-resolved-glow pixels just sit there and keep getting
     * blitAndBlendToTexture'd onto the screen indefinitely (the intermittent "chams rác after
     * turning off" report). Explicitly clear it here, same as JfaField's own clearField() (same
     * class of bug, already solved once for the glow field texture).
     */
    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer == null) return;
        com.mojang.blaze3d.pipeline.RenderTarget outlineTarget =
            ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).getEntityOutlineTarget();
        if (outlineTarget != null) {
            com.mojang.blaze3d.textures.GpuTexture colorTex = outlineTarget.getColorTexture();
            if (colorTex != null) {
                com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                    .clearColorTexture(colorTex, 0);
            }
        }
        com.example.addon.render.JfaField.clearField();
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

            NativeImage flareImg = new NativeImage(NativeImage.Format.RGBA, 4, 1, false);
            flareImg.setPixelABGR(0, 0, 0xFF000000); // flare enabled/yaw/pitch/size
            flareImg.setPixelABGR(1, 0, 0xFFFFFFFF); // flare tint
            flareImg.setPixelABGR(2, 0, 0xFF0000FF); // flare time
            flareImg.setPixelABGR(3, 0, 0xFF000000); // raw (non-distance-scaled) noise size
            flareParamsTexture = new DynamicTexture(() -> "chams-flare-params", flareImg);
            mc.getTextureManager().register(FLARE_PARAMS_ID, flareParamsTexture);

            NativeImage outlineImg = new NativeImage(NativeImage.Format.RGBA, 2, 1, false);
            outlineImg.setPixelABGR(0, 0, 0xFF000000); // outline enabled/radius
            outlineImg.setPixelABGR(1, 0, 0xFFFFFFFF); // outline tint
            outlineParamsTexture = new DynamicTexture(() -> "chams-outline-params", outlineImg);
            mc.getTextureManager().register(OUTLINE_PARAMS_ID, outlineParamsTexture);

        });
    }

    public boolean isInRange(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        double r = range.getValue();
        return mc.player.distanceToSqr(entity) <= r * r;
    }

    /** True unless Glow mode is Off. Kept as a method (not a field) so every call site reads the current ModeOption value. */
    public boolean glowOn() {
        return glowMode.getValue() != GlowMode.Off;
    }

    /** True unless OutlineMode is Off. Mirrors glowOn(); every call site reads the current ModeOption value. */
    public boolean outlineOn() {
        return outlineMode.getValue() != OutlineMode.Off;
    }

    private FillMode lastFillMode = FillMode.Off;

    public void reloadTextureForCurrentMode() {
        FillMode mode = (FillMode) fillMode.getValue();
        lastFillMode = mode;
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
                if (java.nio.file.Files.exists(p)) com.example.addon.render.ChamsCustomShader.loadShader(p);
            }
        }
    }

    public void loadImage(Path path) {
        if (path.toString().toLowerCase().endsWith(".frag")) {
            com.example.addon.AddonConfig.set("betterchams_shader", path.getFileName().toString());
            fillMode.setValueByName("Shader");
            com.example.addon.render.ChamsCustomShader.loadShader(path);
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
            com.example.addon.render.ChamsCustomShader.renderCustomShader();
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
        // Snapshot this frame's accumulated smallest apparent on-screen size (built up
        // by the per-entity mixins as they extract render state) and reset for the next frame.
        lastSmallestApparentPx = smallestApparentPx;
        smallestApparentPx = Double.MAX_VALUE;
        updateSmoothedScale();

        // GIF playback was driven off EventTick.Pre (20 Hz logic tick), so it could
        // advance at most one frame per ~50ms regardless of FrameDelay -- any delay
        // below 50ms (including 0 and the default 10) hit that same 20fps ceiling and
        // looked identical, since ChamsImageTexture.tick() only ever advances by one
        // frame per call. Driving it from the per-render-frame event instead lets
        // FrameDelay actually reach real framerate-limited speeds.
        if ((FillMode) fillMode.getValue() == FillMode.Gif) {
            CHAMS_TEXTURE.tick(frameDelay.getValue(), bounce.getValue());
        }

        // Flare animation params must refresh per render frame, not per 20Hz logic
        // tick -- tick-rate updates made the fire visibly step at ~20-25fps.
        if (flareToggle.getValue()) {
            updateFlareParamsTexture(Minecraft.getInstance(), getState());
        }
    }

    private void updateParamsTexture() {
        if (paramsTexture == null) return;
        Minecraft mc = Minecraft.getInstance();
        boolean on = getState();
        writeMainParams(paramsTexture, on, effectScale());

        updateFlareParamsTexture(mc, on);
        updateOutlineParamsTexture(on);
    }

    private void writeMainParams(DynamicTexture tex, boolean on, double scale) {
        if (tex == null) return;
        NativeImage pixels = tex.getPixels();
        if (pixels == null) return;

        // "Image Fill" == Off used to mean NO fill at all -- now it means a flat
        // FillColor fill instead (image/gif modes still tint their sampled texture by
        // FillColor same as before; Off just has no texture to sample from). r packs a
        // 3-state value the shaders branch on: 0 = no fill, 128 = flat FillColor fill,
        // 255 = image/gif-textured fill.
        FillMode fmodeForFill = (FillMode) fillMode.getValue();
        // Shader mode counts too -- ChamsCustomShader.renderCustomShader() paints
        // straight into CHAMS_TEXTURE same as Image/Gif, so it needs the same
        // ImageSampler fill branch (omitting it left Shader mode packing r=0,
        // i.e. "no fill", even though the texture had already been rendered).
        boolean imageFillOn = on && (fmodeForFill == FillMode.Image || fmodeForFill == FillMode.Gif || fmodeForFill == FillMode.Shader) && CHAMS_TEXTURE.hasImage();
        boolean flatFillOn = on && fmodeForFill == FillMode.Off;
        boolean glowFxOn = on && glowOn();
        boolean flareOn = on && flareToggle.getValue();
        int r = imageFillOn ? 255 : (flatFillOn ? 128 : 0);
        int g = Math.round((float)(fillOpacity.getValue() * 255)) & 0xFF;
        // Raw pixel radius, unpacked via *255 in shader. The same blurred field doubles
        // as Flare's flame canvas, so when Flare is on the blur must reach at least
        // flareSize/2 px even if Glow Thickness is small (or Glow is off entirely).
        // The FIELD is blurred at max(scale, 1): hand pixels need the raw (unscaled)
        // reach even when a far entity pulls the world scale below 1, since one shared
        // pass resolves both -- the shader then cuts each pixel down to its own
        // world/hand radius via the ratio + handness remap.
        double fieldScale = Math.max(scale, 1.0);
        double glowRadius = Math.max(1.0, glowThickness.getValue() * scale);
        double blurRadius = Math.max(1.0, glowThickness.getValue() * fieldScale);
        if (flareOn) blurRadius = Math.max(blurRadius, (flareSize.getValue() * fieldScale) / 2.0);
        // Crisp Outline's edge test now reads the SAME JFA distance field (see
        // glow_resolve.fsh) instead of its own independent march -- the field must
        // reach at least outlineRadius or a thin Glow Thickness + wide Outline Radius
        // combo would falsely read "no edge found" past the field's search limit.
        if (outlineOn()) blurRadius = Math.max(blurRadius, outlineRadius.getValue());

        // Hard ceiling: self up close in 3rd person can drive `scale` to its 2.0 cap
        // while ALSO already filling most of the frame on its own -- the resulting
        // blur/halo radius (still "correct" by the apparent-size formula) then reads
        // as the glow flooding the whole screen (user report 2026-07-04, reproduced
        // by switching to 3rd person; confirmed fixed by this cap). Clamp both the
        // packed shader radius and the Java-side value below to a fixed fraction of
        // screen height so neither the resolve shader nor JfaField's jump-flood
        // reach (which used the unclamped value here, independent of the packed
        // byte's own 255 clamp) can ever exceed a bounded portion of the screen
        // regardless of how large scale gets.
        double maxRadiusPx = Minecraft.getInstance().getWindow().getHeight() * 0.18;
        blurRadius = Math.min(blurRadius, maxRadiusPx);
        glowRadius = Math.min(glowRadius, blurRadius);

        int a = Math.min(255, Math.round((float)blurRadius)) & 0xFF;
        fieldRadiusPxForBlur = blurRadius;
        // b is no longer a plain on/off gate: it packs the ratio of the DESIRED visible
        // halo radius to the ACTUAL blur-field radius (0 = glow off). When Flare widens
        // the shared blur field to flareSize/2, the resolve shader remaps the field so
        // the visible halo still stops at Glow Thickness instead of stretching to the
        // flame's full reach (raising Flare Size used to balloon the halo into a huge
        // rounded cloud around everything).
        int b = 0;
        if (glowFxOn) {
            double ratio = Math.min(1.0, glowRadius / Math.max(blurRadius, 1e-3));
            b = Math.max(1, (int)Math.round(ratio * 255.0));
        }

        // NativeImage ABGR packing: (alpha << 24) | (blue << 16) | (green << 8) | red
        int abgr = (a << 24) | (b << 16) | (g << 8) | r;

        int fillAbgr = packTint(fillColor.getValue().color);
        int glowAbgr = packTint(outlineColor.getValue().color);

        int flipY = (fillMode.getValue() == FillMode.Shader) ? 0 : 255;
        int innerGlowPacked = innerGlow.getValue() ? 255 : 0;
        int intensityPacked = Math.round((float)(glowIntensity.getValue() * 255.0)) & 0xFF;
        // Alpha byte carries the distance scale (0..2 packed over 0..255): the shader
        // tightens the InnerGlow rim's falloff as the scale shrinks, so the rim tracks
        // the blur radius but doesn't read as disproportionately thick at range.
        int scalePacked = Math.round((float)(Math.max(0.0, Math.min(2.0, scale)) / 2.0 * 255.0)) & 0xFF;
        int flipAbgr = (scalePacked << 24) | (intensityPacked << 16) | (innerGlowPacked << 8) | flipY;

        pixels.setPixelABGR(0, 0, abgr);
        pixels.setPixelABGR(1, 0, fillAbgr);
        pixels.setPixelABGR(2, 0, glowAbgr);
        pixels.setPixelABGR(3, 0, flipAbgr);
        tex.upload();
    }

    private boolean warnedGradientUnsupported = false;

    /**
     * Packs a ClientColor into NativeImage ABGR with alpha forced to 0xFF.
     *
     * Alpha is forced rather than read from getPacked()'s top byte: getPacked() is only
     * documented as 0xRRGGBB and Gradient-mode colors return 0 there, which made tints
     * fully transparent. Opacity is controlled by the dedicated sliders, not this bit.
     *
     * Gradient fallback: Boze's Gradient ClientColor (dev.boze.client.dy wrapping a
     * screen-space gradient) returns 0 for getRed/Green/Blue AND getPacked() == 0x0 --
     * a gradient has no single RGB value and the client doesn't expose a sampled one
     * through the API, so there is nothing for the addon to read (verified via debug
     * log 2026-07-03: id=rainbow1 rgb=(0,0,0) packed=0x0). A REAL black still carries
     * alpha in the packed value (e.g. red = 0xfff00101), so packed == 0 exactly
     * identifies the unsupported case; substitute white instead of rendering an
     * invisible black effect, and tell the user once.
     */
    public int packTint(dev.boze.api.render.ClientColor c) {
        int red = c.getRed(), green = c.getGreen(), blue = c.getBlue();
        if (c.getPacked() == 0 && red == 0 && green == 0 && blue == 0) {
            red = green = blue = 255;
            if (!warnedGradientUnsupported) {
                warnedGradientUnsupported = true;
                dev.boze.api.utility.ChatHelper.sendMsg("BetterChams",
                    "§cGradient colors aren't readable through the Boze API (no color data) -- using white instead. Pick a Static or Changing color.");
            }
        }
        return (0xFF << 24) | (blue << 16) | (green << 8) | red;
    }

    private void updateOutlineParamsTexture(boolean on) {
        if (outlineParamsTexture == null) return;
        NativeImage pixels = outlineParamsTexture.getPixels();
        if (pixels == null) return;

        // Crisp silhouette outline is enabled for BOTH Simple and Complex now: in
        // Complex the shader restricts it to HAND pixels only (players/crystals use the
        // world-space wireframe boxes), so the hand keeps its Simple-style outline when
        // the mode switches to Complex (user 2026-07-15: hand outline vanished in Complex).
        boolean outlineEnabled = on && outlineOn();
        int enabled = outlineEnabled ? 255 : 0;
        // Outline is deliberately NOT distance-scaled: its radius is pure edge
        // thickness in pixels, and a 1-5px line reads fine at any distance.
        int radiusPacked = Math.round((float)(outlineRadius.getValue() / 5.0 * 255.0)) & 0xFF;
        // Blue byte was unused (always 0) -- carries the Glow ModeOption now so
        // glow_resolve.fsh can pick Soft (gaussian falloff) vs Neon (core + halo)
        // without a new texture/JSON declaration. 0 = Soft/Off, 255 = Neon.
        int glowModePacked = (glowMode.getValue() == GlowMode.Neon) ? 255 : 0;
        // Alpha byte here was always forced to 0xFF (spare) -- carries isComplexOutline
        // now so the resolve shader can pick the deep multi-layer InnerGlow (formerly
        // gated on Gel Fill mode) without a new texture. Complex's wireframe box cage
        // is rendered separately (GelParticleSystem), not a shader effect -- this flag
        // only controls the InnerGlow depth/layer-count now.
        boolean isComplexOutline = on && outlineMode.getValue() == OutlineMode.Complex;
        int complexPacked = isComplexOutline ? 255 : 0;
        pixels.setPixelABGR(0, 0, (complexPacked << 24) | (glowModePacked << 16) | (radiusPacked << 8) | enabled);

        pixels.setPixelABGR(1, 0, packTint(outlineTint.getValue().color));

        outlineParamsTexture.upload();
    }

    private long flareLastNanos = 0L;

    private void updateFlareParamsTexture(Minecraft mc, boolean on) {
        if (flareParamsTexture == null) return;

        float realYaw = mc.player != null ? mc.player.getYRot() : 0f;
        float realPitch = mc.player != null ? mc.player.getXRot() : 0f;
        if (!flareLagInitialized) { flareLaggedYaw = realYaw; flareLaggedPitch = realPitch; flareLagInitialized = true; }
        // Time-based smoothing (rate 6/s) instead of a fixed per-call factor: this now
        // runs per render frame AND per logic tick, so a fixed factor would make the
        // lag speed depend on framerate.
        long now = System.nanoTime();
        float dt = flareLastNanos == 0L ? 0.016f : Math.min((now - flareLastNanos) / 1_000_000_000f, 0.25f);
        flareLastNanos = now;
        float k = 1.0f - (float) Math.exp(-6.0 * dt);
        flareLaggedYaw += net.minecraft.util.Mth.wrapDegrees(realYaw - flareLaggedYaw) * k;
        flareLaggedPitch += (realPitch - flareLaggedPitch) * k;
        float yawOffset = net.minecraft.util.Mth.wrapDegrees(realYaw - flareLaggedYaw);
        float pitchOffset = realPitch - flareLaggedPitch;

        boolean flareOn = on && flareToggle.getValue();
        int flareR = flareOn ? 255 : 0;
        int flareG = Math.round((Math.max(-90f, Math.min(90f, yawOffset)) + 90f) / 180f * 255f) & 0xFF;
        int flareB = Math.round((Math.max(-90f, Math.min(90f, pitchOffset)) + 90f) / 180f * 255f) & 0xFF;
        int tintAbgr = packTint(flareTint.getValue().color);

        // 16-bit time over the 10s loop (R = high byte, G = low byte): a single byte
        // gave 256 steps / 10s, i.e. the fire animated in visible ~25fps increments.
        int t16 = (int) ((System.currentTimeMillis() % 10000L) / 10000.0 * 65535.0) & 0xFFFF;
        int tHi = (t16 >> 8) & 0xFF, tLo = t16 & 0xFF;
        int timeAbgr = (0xFF << 24) | (tLo << 8) | tHi;

        // Packed size is the distance-scaled WORLD size; the shader un-scales it per
        // pixel for hand-marked pixels (see HAND_OUTLINE_COLOR's comment).
        NativeImage pixels = flareParamsTexture.getPixels();
        if (pixels == null) return;
        double sizePx = flareSize.getValue() * effectScale();
        int flareA = Math.round((float)(Math.max(8.0, Math.min(128.0, sizePx)) / 128.0 * 255.0)) & 0xFF;
        pixels.setPixelABGR(0, 0, (flareA << 24) | (flareB << 16) | (flareG << 8) | flareR);
        pixels.setPixelABGR(1, 0, tintAbgr);
        pixels.setPixelABGR(2, 0, timeAbgr);

        // Raw, NON-distance-scaled noise size: flareAura's turbulence field is sampled
        // at screenPx/scale, so shrinking scale with distance (the block above, floored
        // at 8px) made the noise oscillate far faster than the shrunk silhouette could
        // display -- one flame fractured into a grid of tiny cells the farther the
        // target got (user report 2026-07-04, "further away -> Flare breaks into
        // tiles"). The turbulence frequency should track the slider's raw px value only,
        // so the same handful of licking tongues wrap the silhouette at any distance
        // instead of multiplying into confetti as it shrinks.
        int rawSizePacked = Math.round((float)(Math.max(8.0, Math.min(128.0, flareSize.getValue())) / 128.0 * 255.0)) & 0xFF;
        pixels.setPixelABGR(3, 0, (0xFF << 24) | rawSizePacked);
        flareParamsTexture.upload();
    }
}
