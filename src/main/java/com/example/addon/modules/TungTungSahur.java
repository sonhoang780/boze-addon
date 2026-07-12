package com.example.addon.modules;

import com.example.addon.render.ObjMesh;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LevelRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public class TungTungSahur extends AddonModule {
    public static final TungTungSahur INSTANCE = new TungTungSahur();

    private static final Identifier TEXTURE_ID =
        Identifier.fromNamespaceAndPath("example-addon", "tung_tung_companion");

    // Post-effect "location": "example-addon:tungsmokeparams" resolves to the resource
    // path textures/effect/tungsmokeparams.png -- registering under the bare name bound
    // the sampler to the missing-texture fallback (see "Missing resource ..." warnings
    // in logs), so the shader read garbage params and never drew smoke. Must register
    // under the full resolved path, exactly like BetterChams' FILL/PARAM/OUTLINE ids.
    private static final Identifier SMOKE_PARAMS_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/tungsmokeparams.png");
    private static final Identifier SMOKE_SDF_ID =
        Identifier.fromNamespaceAndPath("example-addon", "textures/effect/tungsmokesdf.png");
    private static DynamicTexture smokeParamsTexture;
    private static DynamicTexture smokeSdfTexture;
    // SDF atlas content is static; copy it into smokeSdfTexture once, ever
    private static boolean sdfLoaded = false;

    private static final Identifier SCREAM_ID = Identifier.fromNamespaceAndPath("example-addon", "scream");
    private static final SoundEvent SCREAM_SOUND = SoundEvent.createVariableRangeEvent(SCREAM_ID);

    private static final long FADE_DURATION_MS = 2000L;

    public final ToggleOption scream = new ToggleOption(this, "Scream", "We popped the ai bubble", false);
    public final SliderOption modelScale = new SliderOption(this, "Scale", "Model scale multiplier", 1.0, 0.01, 5.0, 0.01);

    private static RenderType renderLayerCutout;
    private static RenderType renderLayerTranslucent;

    // ── Follow state ──────────────────────────────────────────────────────────
    private double  posX, posY, posZ;
    private double  prevPosX, prevPosY, prevPosZ;
    private float   bodyYaw;
    private float   prevBodyYaw;
    private float   ageInTicks;
    private boolean initialized;
    private ObjMesh mesh;

    // ── Fade-out state (persists past onDisable) ──────────────────────────────
    public boolean fadingOut    = false;
    private long fadeStartMs  = 0L;
    public float smokeFadeAlpha = 0f;

    private SoundInstance currentScreamSound;

    static {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(ctx -> {
            if (INSTANCE.getState() && !INSTANCE.fadingOut) INSTANCE.onWorldRender(ctx);
        });
        // Fading model uses entityTranslucent -- must render AFTER translucent terrain
        // (water). Submitted from AFTER_SOLID_FEATURES it wrote depth before the water
        // pass, culling water behind it: looking through the model showed the lake bed
        // with no water tint (xray look).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            if (INSTANCE.fadingOut) INSTANCE.onWorldRender(ctx);
        });
        // Plain Fabric tick event, not a Boze @EventHandler subscription -- Boze
        // unsubscribes @EventHandler methods the instant onDisable() runs, which froze
        // position/yaw interpolation for the entire 2s fade (the "giật" bug). This hook
        // stays registered permanently and self-gates on getState()||fadingOut, exactly
        // mirroring how the render hook above already survives past module-disable.
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (INSTANCE.getState() || INSTANCE.fadingOut) INSTANCE.onTick();
        });
    }

    public TungTungSahur() {
        super("TungTungSahur", "TUNG TUNG");
    }

    /**
     * Registers the smoke post-process's two data textures via the standard
     * DynamicTexture + TextureManager pattern (same as BetterChams' paramsTexture) --
     * replaces a prior implementation that reflected into PostChain's private
     * `persistentTargets` field and wrote through writeToTexture(), silently failing
     * (caught, unlogged) whenever that field/target-key didn't match, which is why the
     * smoke effect never actually appeared. Call once from ExampleAddon.initialize().
     */
    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            NativeImage paramsImg = new NativeImage(NativeImage.Format.RGBA, 8, 3, false);
            smokeParamsTexture = new DynamicTexture(() -> "tungsmoke-params", paramsImg);
            mc.getTextureManager().register(SMOKE_PARAMS_ID, smokeParamsTexture);

            // 256x128 all-white placeholder until the real baked SDF loads in
            // onEnable()/buildTexture() -- avoids sampling an unregistered texture if
            // the smoke chain ever runs before the module is first enabled.
            NativeImage sdfPlaceholder = new NativeImage(NativeImage.Format.RGBA, 256, 128, false);
            sdfPlaceholder.fillRect(0, 0, 256, 128, 0xFFFFFFFF);
            smokeSdfTexture = new DynamicTexture(() -> "tungsmoke-sdf", sdfPlaceholder);
            mc.getTextureManager().register(SMOKE_SDF_ID, smokeSdfTexture);
        });
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();

        // Cancel any in-progress fade
        fadingOut = false;

        if (mc != null && mc.getSoundManager() != null && currentScreamSound != null) {
            mc.getSoundManager().stop(currentScreamSound);
            currentScreamSound = null;
        }

        initialized = false;
        ageInTicks  = 0f;
        mesh        = null;

        if (mc != null) {
            buildTexture(mc);
            loadMesh(mc);
            // initialized used to only flip true inside onTick(), the following client
            // tick. A disable landing before that first tick (e.g. rapid re-toggling
            // faster than one tick) hit onDisable()'s `mesh != null && initialized`
            // check while initialized was still false, wiping the model instantly with
            // no fade at all. Computing the initial pose here, synchronously, means a
            // same-tick disable already has a valid initialized model to fade from.
            if (mc.player != null) initializePose(mc);
        }
    }

    private void initializePose(Minecraft mc) {
        double yawRad = Math.toRadians(mc.player.getYRot());
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);
        double fwd = 1.8;
        double side = 2.2;
        posX = mc.player.getX() - sinYaw * fwd + cosYaw * side;
        posY = mc.player.getY();
        posZ = mc.player.getZ() + cosYaw * fwd + sinYaw * side;
        prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
        bodyYaw = mc.player.getYRot();
        prevBodyYaw = bodyYaw;
        initialized = true;
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();

        if (mc != null && mc.getSoundManager() != null) {
            if (currentScreamSound != null) {
                mc.getSoundManager().stop(currentScreamSound);
                currentScreamSound = null;
            }
            if (scream.getValue()) {
                currentScreamSound = SimpleSoundInstance.forUI(SCREAM_SOUND, 1.0f, 4.0f);
                mc.getSoundManager().play(currentScreamSound);
            }
        }

        // Start fade instead of clearing immediately
        if (mesh != null && initialized) {
            fadingOut    = true;
            fadeStartMs  = System.currentTimeMillis();
            smokeFadeAlpha = 1f;
        } else {
            mesh        = null;
            initialized = false;
        }
    }

    // ── Tick: update follow position ─────────────────────────────────────────

    private void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mesh == null) return;

        ageInTicks++;

        double yawRad  = Math.toRadians(mc.player.getYRot());
        double sinYaw  = Math.sin(yawRad);
        double cosYaw  = Math.cos(yawRad);
        double fwd  = 1.8;
        double side = 2.2;
        double targetX = mc.player.getX() - sinYaw * fwd + cosYaw * side;
        double targetY = mc.player.getY();
        double targetZ = mc.player.getZ() + cosYaw * fwd + sinYaw * side;

        if (!initialized) {
            posX = targetX; posY = targetY; posZ = targetZ;
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            bodyYaw = mc.player.getYRot();
            prevBodyYaw = bodyYaw;
            initialized = true;
        } else {
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            prevBodyYaw = bodyYaw;

            double k = 0.15;
            posX += (targetX - posX) * k;
            posY += (targetY - posY) * k;
            posZ += (targetZ - posZ) * k;
        }

        float dYaw = Mth.wrapDegrees(mc.player.getYRot() - bodyYaw);
        bodyYaw += dYaw * 0.15f;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void onWorldRender(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mesh == null || !initialized) return;

        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;

        // Compute fade alpha; end fade if expired
        int alphaInt = 255;
        boolean isFading = fadingOut;
        if (isFading) {
            long elapsed = System.currentTimeMillis() - fadeStartMs;
            float alpha  = 1f - (float) elapsed / FADE_DURATION_MS;
            smokeFadeAlpha = alpha;
            if (alpha <= 0f) {
                fadingOut   = false;
                mesh        = null;
                initialized = false;
                return;
            }
            alphaInt = Math.max(1, (int)(alpha * 255));
        }

        PoseStack matrices = ctx.poseStack();
        Vec3 camPos = mc.gameRenderer.getMainCamera().position();

        float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        double renderX   = prevPosX + (posX - prevPosX) * tickDelta;
        double renderY   = prevPosY + (posY - prevPosY) * tickDelta;
        double renderZ   = prevPosZ + (posZ - prevPosZ) * tickDelta;
        float  renderYaw = prevBodyYaw + Mth.wrapDegrees(bodyYaw - prevBodyYaw) * tickDelta;

        int light   = LevelRenderer.getLightCoords(mc.level,
                          BlockPos.containing(renderX, renderY, renderZ));
        int overlay = OverlayTexture.NO_OVERLAY;

        float scale = modelScale.getValue().floatValue();

        matrices.pushPose();
        matrices.translate(renderX - camPos.x, renderY - camPos.y, renderZ - camPos.z);
        matrices.mulPose(Axis.YP.rotationDegrees(180f - renderYaw));
        matrices.scale(scale, scale, scale);
        // Lift model so lowest vertex sits exactly at renderY (feet on ground)
        matrices.translate(0.0, -mesh.getMinY(), 0.0);

        RenderType rt = isFading ? getTranslucentLayer() : getCutoutLayer();
        VertexConsumer consumer = consumers.getBuffer(rt);
        if (isFading) {
            mesh.renderTriangles(matrices.last(), consumer, light, overlay, alphaInt);
        } else {
            mesh.render(matrices.last(), consumer, light, overlay, alphaInt);
        }

        matrices.popPose();
    }

    // ── RenderType (lazy) ────────────────────────────────────────────────────

    private static RenderType getCutoutLayer() {
        if (renderLayerCutout == null) renderLayerCutout = RenderTypes.entityCutout(TEXTURE_ID);
        return renderLayerCutout;
    }

    private static RenderType getTranslucentLayer() {
        if (renderLayerTranslucent == null) renderLayerTranslucent = RenderTypes.entityTranslucent(TEXTURE_ID);
        return renderLayerTranslucent;
    }

    // ── Texture ──────────────────────────────────────────────────────────────

    private void buildTexture(Minecraft mc) {
        try {
            Identifier fileId = Identifier.fromNamespaceAndPath("example-addon", "textures/entity/tung_tung.png");
            try (var stream = mc.getResourceManager().getResourceOrThrow(fileId).open()) {
                NativeImage img = NativeImage.read(stream);
                mc.getTextureManager().register(TEXTURE_ID,
                    new DynamicTexture(() -> "tung_tung_companion", img));
            }
        } catch (Exception ignored) {}

        // Baked volumetric SDF atlas (8x4 grid of 32 Z-slices, 256x128 total) for the
        // smoke dissolve shader. Copied INTO the one texture registered by
        // registerTextures() -- never re-registered: PostPass$TextureInput caches the
        // AbstractTexture INSTANCE at chain-compile time, and TextureManager.register()
        // closes the previous instance, so re-registering here crashed the next fade
        // with "Texture view does not exist" (crash-2026-07-03_03.26.48).
        if (!sdfLoaded && smokeSdfTexture != null) {
            try {
                Identifier sdfId = Identifier.fromNamespaceAndPath("example-addon", "textures/entity/tung_tung_sdf.png");
                try (var stream = mc.getResourceManager().getResourceOrThrow(sdfId).open()) {
                    NativeImage src = NativeImage.read(stream);
                    NativeImage dst = smokeSdfTexture.getPixels();
                    if (dst != null && src.getWidth() == dst.getWidth() && src.getHeight() == dst.getHeight()) {
                        dst.copyFrom(src);
                        smokeSdfTexture.upload();
                        sdfLoaded = true;
                    }
                    src.close();
                }
            } catch (Exception ignored) {}
        }
    }

    // ── OBJ Mesh ─────────────────────────────────────────────────────────────

    private void loadMesh(Minecraft mc) {
        try {
            Identifier meshId = Identifier.fromNamespaceAndPath("example-addon", "models/tung_tung.obj");
            try (var stream = mc.getResourceManager().getResourceOrThrow(meshId).open()) {
                mesh = ObjMesh.load(stream);
            }
        } catch (Exception e) {
            // Mesh failed to load
        }
    }

    /**
     * Writes this frame's camera-ray corners / tung position / fade alpha / time into
     * smokeParamsTexture via the standard DynamicTexture upload path (same as
     * BetterChams' paramsTexture) -- replaces a prior implementation that reflected
     * into PostChain's private `persistentTargets` field and wrote through
     * writeToTexture(), silently failing whenever that field/target-key didn't match
     * (caught and swallowed, unlogged), which is why the smoke shader never actually
     * received real data. `smokeChain` is unused now -- kept in the signature so the
     * MixinLevelRenderer call site doesn't need touching.
     */
    public void updateSmokeParams(Minecraft mc, net.minecraft.client.renderer.PostChain smokeChain) {
        if (smokeParamsTexture == null) return;
        com.mojang.blaze3d.platform.NativeImage img = smokeParamsTexture.getPixels();
        if (img == null) return;

        float aspect = (float)mc.getWindow().getWidth() / mc.getWindow().getHeight();
        float fovY = (float)Math.toRadians(mc.options.fov().get());
        float halfH = (float)Math.tan(fovY / 2.0);
        float halfW = halfH * aspect;

        org.joml.Vector3fc fwd = mc.gameRenderer.getMainCamera().forwardVector();
        org.joml.Vector3fc upV = mc.gameRenderer.getMainCamera().upVector();
        org.joml.Vector3fc leftV = mc.gameRenderer.getMainCamera().leftVector();

        org.joml.Vector3f tl = new org.joml.Vector3f(fwd).add(new org.joml.Vector3f(upV).mul(halfH)).add(new org.joml.Vector3f(leftV).mul(halfW));
        org.joml.Vector3f tr = new org.joml.Vector3f(fwd).add(new org.joml.Vector3f(upV).mul(halfH)).sub(new org.joml.Vector3f(leftV).mul(halfW));
        org.joml.Vector3f bl = new org.joml.Vector3f(fwd).sub(new org.joml.Vector3f(upV).mul(halfH)).add(new org.joml.Vector3f(leftV).mul(halfW));
        org.joml.Vector3f br = new org.joml.Vector3f(fwd).sub(new org.joml.Vector3f(upV).mul(halfH)).sub(new org.joml.Vector3f(leftV).mul(halfW));
        
        tl.normalize(); tr.normalize(); bl.normalize(); br.normalize();
        
        setFloat(img, 0, tl.x(), -2, 2); setFloat(img, 1, tl.y(), -2, 2); setFloat(img, 2, tl.z(), -2, 2);
        setFloat(img, 3, tr.x(), -2, 2); setFloat(img, 4, tr.y(), -2, 2); setFloat(img, 5, tr.z(), -2, 2);
        setFloat(img, 6, bl.x(), -2, 2); setFloat(img, 7, bl.y(), -2, 2); setFloat(img, 8, bl.z(), -2, 2);
        setFloat(img, 9, br.x(), -2, 2); setFloat(img, 10, br.y(), -2, 2); setFloat(img, 11, br.z(), -2, 2);
        
        Vec3 camPos = mc.gameRenderer.getMainCamera().position();
        float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double renderX = prevPosX + (posX - prevPosX) * tickDelta;
        double renderY = prevPosY + (posY - prevPosY) * tickDelta + 1.0;
        double renderZ = prevPosZ + (posZ - prevPosZ) * tickDelta;
        
        setFloat(img, 12, (float)(renderX - camPos.x), -64, 64);
        setFloat(img, 13, (float)(renderY - camPos.y), -64, 64);
        setFloat(img, 14, (float)(renderZ - camPos.z), -64, 64);
        setFloat(img, 15, smokeFadeAlpha, 0, 1);
        setFloat(img, 16, (System.currentTimeMillis() % 1000000L) / 1000f, 0, 1000);

        // Body yaw the mesh itself is rotated by when rendered normally (see
        // onWorldRender: Axis.YP.rotationDegrees(180f - renderYaw)). The SDF atlas is
        // baked in the mesh's OWN local space, but the raymarch below was sampling it
        // in camera/world-aligned space with no yaw applied at all -- it only lined up
        // with the visible model when renderYaw happened to be 0, so in practice the
        // smoke almost never intersected the SDF's "surface" band and rendered nothing.
        float renderYawInterp = prevBodyYaw + Mth.wrapDegrees(bodyYaw - prevBodyYaw) * tickDelta;
        setFloat(img, 17, renderYawInterp, -180, 180);

        smokeParamsTexture.upload();
    }
    
    private void setFloat(com.mojang.blaze3d.platform.NativeImage img, int index, float val, float min, float max) {
        int x = index % 8;
        int y = index / 8;
        float normalized = Math.max(0f, Math.min(1f, (val - min) / (max - min)));
        int v = (int)(normalized * 16777215f);
        int r = v & 0xFF;
        int g = (v >> 8) & 0xFF;
        int b = (v >> 16) & 0xFF;
        int color = (0xFF << 24) | (b << 16) | (g << 8) | r;
        img.setPixel(x, y, color);
    }
}
