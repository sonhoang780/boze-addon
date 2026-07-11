package com.example.addon.modules;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ColorOption;
import meteordevelopment.orbit.EventHandler;
import dev.boze.api.render.ClientColor;
import dev.boze.api.render.ColorMaker;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Spinning capture-mark billboard over the crosshair-targeted entity -- ported from
 * VCore's TargerESP "Cube" mode (vcore/utility/render/animation/CaptureMark.java),
 * the module that references assets/vcore/textures/markers/capture.png. Uses the
 * crosshair pick (already computed by vanilla every frame, boze/mc set this for us)
 * instead of porting VCore's separate Aura-target tracking, since this addon has no
 * equivalent standing "current aura target" field to hook.
 */
public class TargetESP extends AddonModule {
    public static final TargetESP INSTANCE = new TargetESP();

    public final ColorOption markColor = new ColorOption(this, "MarkColor", "Tint for the capture mark.", ColorMaker.staticColor(255, 255, 255), 1.0f);

    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/markers/capture.png");
    private static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("example-addon", "core/capturemark");
    private static RenderType markLayer;

    // Ported 1:1 from CaptureMark's tick()/espValue: a spin speed that accelerates,
    // caps, then reverses -- the pendulum-like swing VCore's mark has, instead of a
    // plain constant spin.
    private float espValue = 1.0F;
    private float prevEspValue;
    private float espSpeed = 1.0F;
    private boolean flipSpeed;

    public TargetESP() {
        super("TargetESP", "Spinning capture mark over the crosshair target.");
    }

    static {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            if (INSTANCE.getState()) INSTANCE.onWorldRender(ctx);
        });
    }

    @Override
    public void onDisable() {
        espValue = 1.0F;
        prevEspValue = 0.0F;
        espSpeed = 1.0F;
        flipSpeed = false;
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        prevEspValue = espValue;
        espValue += espSpeed;
        if (espSpeed > 25.0F) flipSpeed = true;
        if (espSpeed < -25.0F) flipSpeed = false;
        espSpeed += flipSpeed ? -0.5F : 0.5F;
    }

    private void onWorldRender(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Entity target = mc.crosshairPickEntity;
        if (!(target instanceof LivingEntity living) || living.isRemoved()) return;

        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;

        float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();

        double tPosX = Mth.lerp(tickDelta, living.xo, living.getX()) - camPos.x;
        double tPosY = Mth.lerp(tickDelta, living.yo, living.getY()) - camPos.y;
        double tPosZ = Mth.lerp(tickDelta, living.zo, living.getZ()) - camPos.z;

        // Position FIRST (plain world-relative-to-camera translate, no rotation yet
        // active so it isn't dragged off by anything) -- THEN rotate to face the
        // camera. VCore's original order (rotate, translate, counter-rotate) relies
        // on the old Yarn MatrixStack's compose convention; ported literally onto
        // Mojang's PoseStack (different compose order) it rotates the translation
        // itself, throwing the mark far off from the entity whenever the camera
        // has any pitch/yaw away from due-south-level.
        PoseStack matrices = ctx.poseStack();
        matrices.pushPose();
        matrices.translate(tPosX, tPosY + living.getEyeHeight(living.getPose()) / 2.0F, tPosZ);
        matrices.mulPose(Axis.YP.rotationDegrees(-camera.yRot()));
        matrices.mulPose(Axis.XP.rotationDegrees(camera.xRot()));
        matrices.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(tickDelta, prevEspValue, espValue)));
        matrices.translate(-0.75, -0.75, -0.01);

        ClientColor tint = markColor.getValue().color;
        // Full-bright (sky=15, block=15 packed: 15<<20 | 15<<4) instead of the entity's
        // real world lightmap coords -- an additive-blended glow mark is meant to read
        // the same regardless of ambient light (matches how it looks as a raw PNG), not
        // get dimmed to gray at night/underground like a lit surface would.
        int light = 15728880;
        int overlay = OverlayTexture.NO_OVERLAY;
        VertexConsumer c = consumers.getBuffer(getLayer());
        PoseStack.Pose pose = matrices.last();

        c.addVertex(pose, 0.0F, 1.5F, 0.0F).setColor(tint.getRed(), tint.getGreen(), tint.getBlue(), 255).setUv(0.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        c.addVertex(pose, 1.5F, 1.5F, 0.0F).setColor(tint.getRed(), tint.getGreen(), tint.getBlue(), 255).setUv(1.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        c.addVertex(pose, 1.5F, 0.0F, 0.0F).setColor(tint.getRed(), tint.getGreen(), tint.getBlue(), 255).setUv(1.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        c.addVertex(pose, 0.0F, 0.0F, 0.0F).setColor(tint.getRed(), tint.getGreen(), tint.getBlue(), 255).setUv(0.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);

        matrices.popPose();
    }

    private static RenderType getLayer() {
        if (markLayer != null) return markLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/capturemark"))
            .withFragmentShader(FRAGMENT_SHADER_ID)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withDepthStencilState(new DepthStencilState(com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", TEXTURE_ID)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        markLayer = RenderType.create("capturemark", setup);
        return markLayer;
    }
}
