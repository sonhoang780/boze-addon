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
    boolean fadingOut    = false;
    private long fadeStartMs  = 0L;
    private int  smokeCounter = 0;

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
            smokeCounter = 0;
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
            if (alpha <= 0f) {
                fadingOut   = false;
                mesh        = null;
                initialized = false;
                return;
            }
            alphaInt = Math.max(1, (int)(alpha * 255));

            // Emit rising smoke particles every 4th render call
            smokeCounter++;
            if (smokeCounter % 4 == 0) {
                double smokeX = posX + (Math.random() - 0.5) * 0.6;
                double smokeY = posY + 0.5 + Math.random() * 0.8;
                double smokeZ = posZ + (Math.random() - 0.5) * 0.6;
                mc.level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    smokeX, smokeY, smokeZ, 0.0, 0.04, 0.0);
            }
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

    private void buildTexture(Minecraft mc) {
        try {
            Identifier fileId = Identifier.fromNamespaceAndPath("example-addon", "textures/entity/tung_tung.png");
            try (var stream = mc.getResourceManager().getResourceOrThrow(fileId).open()) {
                NativeImage img = NativeImage.read(stream);
                mc.getTextureManager().register(TEXTURE_ID,
                    new DynamicTexture(() -> "tung_tung_companion", img));
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
            // Mesh failed to load — module will remain inactive (mesh == null check in render/tick)
        }
    }
}
