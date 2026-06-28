package com.example.addon.modules;

import com.example.addon.render.ObjMesh;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
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
import com.mojang.blaze3d.systems.RenderSystem;

public class TungTungSahur extends AddonModule {
    public static final TungTungSahur INSTANCE = new TungTungSahur();

    private static final Identifier TEXTURE_ID =
        Identifier.fromNamespaceAndPath("example-addon", "tung_tung_companion");

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
            if (INSTANCE.getState() || INSTANCE.fadingOut) INSTANCE.onWorldRender(ctx);
        });
    }

    public TungTungSahur() {
        super("TungTungSahur", "TUNG TUNG");
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
        }
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

    @EventHandler
    private void onTick(EventTick.Pre event) {
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
        mesh.render(matrices.last(), consumer, light, overlay, alphaInt);

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

    private NativeImage sdfImg;

    private void buildTexture(Minecraft mc) {
        try {
            Identifier fileId = Identifier.fromNamespaceAndPath("example-addon", "textures/entity/tung_tung.png");
            try (var stream = mc.getResourceManager().getResourceOrThrow(fileId).open()) {
                NativeImage img = NativeImage.read(stream);
                mc.getTextureManager().register(TEXTURE_ID,
                    new DynamicTexture(() -> "tung_tung_companion", img));
            }
        } catch (Exception ignored) {}
        
        try {
            Identifier sdfId = Identifier.fromNamespaceAndPath("example-addon", "textures/entity/tung_tung_sdf.png");
            try (var stream = mc.getResourceManager().getResourceOrThrow(sdfId).open()) {
                sdfImg = NativeImage.read(stream);
            }
        } catch (Exception ignored) {}
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

    private static java.lang.reflect.Field persistentTargetsField = null;
    static {
        try {
            persistentTargetsField = net.minecraft.client.renderer.PostChain.class.getDeclaredField("persistentTargets");
            persistentTargetsField.setAccessible(true);
        } catch (Exception e) {}
    }

    private com.mojang.blaze3d.platform.NativeImage smokeParamsImg;

    public void updateSmokeParams(Minecraft mc, net.minecraft.client.renderer.PostChain smokeChain) {
        if (smokeParamsImg == null) {
            smokeParamsImg = new com.mojang.blaze3d.platform.NativeImage(8, 3, false);
        }
        com.mojang.blaze3d.platform.NativeImage img = smokeParamsImg;
        
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
        
        try {
            if (persistentTargetsField != null) {
                java.util.Map<net.minecraft.resources.Identifier, com.mojang.blaze3d.pipeline.RenderTarget> targets = 
                    (java.util.Map<net.minecraft.resources.Identifier, com.mojang.blaze3d.pipeline.RenderTarget>) persistentTargetsField.get(smokeChain);
                if (targets != null) {
                    com.mojang.blaze3d.pipeline.RenderTarget target = targets.get(net.minecraft.resources.Identifier.withDefaultNamespace("params_sampler"));
                    if (target == null) {
                        target = targets.get(net.minecraft.resources.Identifier.fromNamespaceAndPath("example-addon", "params_sampler"));
                    }
                    if (target != null && target.getColorTexture() != null) {
                        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().writeToTexture(target.getColorTexture(), img);
                    }
                    
                    com.mojang.blaze3d.pipeline.RenderTarget sdfTarget = targets.get(net.minecraft.resources.Identifier.withDefaultNamespace("sdf_sampler"));
                    if (sdfTarget == null) {
                        sdfTarget = targets.get(net.minecraft.resources.Identifier.fromNamespaceAndPath("example-addon", "sdf_sampler"));
                    }
                    if (sdfTarget != null && sdfTarget.getColorTexture() != null && sdfImg != null) {
                        com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().writeToTexture(sdfTarget.getColorTexture(), sdfImg);
                    }
                }
            }
        } catch (Exception e) {}
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
