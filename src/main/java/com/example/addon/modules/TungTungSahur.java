package com.example.addon.modules;

import com.example.addon.render.TungTungModel;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * TungTungSahur — 3D companion modelled after the "Tung Tung Tung Sahur" meme character.
 * Thin cylindrical body, very long arms, oversized head with big round eyes.
 *
 * Architecture:
 *   WorldRenderEvents.AFTER_ENTITIES  → render the model (registered once at class load).
 *   EventTick.Pre                     → update follow position + limb animation each tick.
 *
 * Sub-tick interpolation: prevPos* is saved at the start of each tick, and the
 * render lerps between prevPos and pos using tickDelta → smooth 60 fps motion.
 */
public class TungTungSahur extends AddonModule {
    public static final TungTungSahur INSTANCE = new TungTungSahur();

    private static final Identifier TEXTURE_ID =
        Identifier.of("example-addon", "tung_tung_companion");

    private static final SoundEvent SCREAM_SOUND =
        SoundEvent.of(Identifier.of("example-addon", "scream"));

    public final ToggleOption scream = new ToggleOption(this, "Scream", "Phát âm thanh scream.ogg khi tắt module.", false);

    private static RenderLayer renderLayer;

    // ── Follow state ──────────────────────────────────────────────────────────
    private double  posX, posY, posZ;
    private double  prevPosX, prevPosY, prevPosZ;   // for sub-tick interpolation
    private float   bodyYaw;
    private float   prevBodyYaw;
    private float   limbAngle;
    private float   limbDistance;
    private float   ageInTicks;
    private boolean isFlying;
    private boolean initialized;
    private TungTungModel model;

    static {
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            if (INSTANCE.getState()) INSTANCE.onWorldRender(ctx);
        });
    }

    public TungTungSahur() {
        super("TungTungSahur", "3D companion that follows you.");
    }

    @Override
    public void onEnable() {
        initialized  = false;
        limbAngle    = 0f;
        limbDistance = 0f;
        ageInTicks   = 0f;

        ModelPart root = TungTungModel.getTexturedModelData().createModel();
        model = new TungTungModel(root);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) buildTexture(mc);
    }

    @Override
    public void onDisable() {
        if (scream.getValue()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.getSoundManager() != null)
                mc.getSoundManager().play(PositionedSoundInstance.ui(SCREAM_SOUND, 1.0f, 4.0f));
        }
        model       = null;
        initialized = false;
    }

    // ── Tick: update follow position + limb animation ─────────────────────────

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || model == null) return;

        ageInTicks++;

        double yawRad  = Math.toRadians(mc.player.getYaw());
        double sinYaw  = Math.sin(yawRad);
        double cosYaw  = Math.cos(yawRad);
        // forward = (-sin, +cos); screen-left = (cos, sin). Place the companion ahead-LEFT of the
        // player so it sits in the left of view (the circled spot) instead of dead-centre in front.
        double fwd  = 1.8;   // a bit ahead so it stays on-screen
        double side = 2.2;   // to the player's left
        double targetX = mc.player.getX() - sinYaw * fwd + cosYaw * side;
        double targetY = mc.player.getY();
        double targetZ = mc.player.getZ() + cosYaw * fwd + sinYaw * side;

        if (!initialized) {
            posX = targetX; posY = targetY; posZ = targetZ;
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            bodyYaw = mc.player.getYaw();
            prevBodyYaw = bodyYaw;
            initialized = true;
        } else {
            // Save previous positions before update for sub-tick interpolation
            prevPosX = posX; prevPosY = posY; prevPosZ = posZ;
            prevBodyYaw = bodyYaw;

            double k = 0.15;
            posX += (targetX - posX) * k;
            posY += (targetY - posY) * k;
            posZ += (targetZ - posZ) * k;
        }

        isFlying = mc.player.isGliding();
        Vec3d vel = mc.player.getVelocity();
        if (isFlying) {
            limbDistance = 0f;
        } else {
            float speed = (float) Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            limbDistance = Math.min(speed * 3.5f, 1.0f);
            if (limbDistance > 0.01f) limbAngle += 0.6f;
        }

        float dYaw = MathHelper.wrapDegrees(mc.player.getYaw() - bodyYaw);
        bodyYaw += dYaw * 0.15f;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void onWorldRender(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || model == null || !initialized) return;

        VertexConsumerProvider consumers = ctx.consumers();
        if (consumers == null) return;

        MatrixStack matrices = ctx.matrices();
        Vec3d camPos = mc.gameRenderer.getCamera().getCameraPos();

        float tickDelta = mc.getRenderTickCounter().getTickProgress(false);

        // Sub-tick interpolated position
        double renderX   = prevPosX + (posX - prevPosX) * tickDelta;
        double renderY   = prevPosY + (posY - prevPosY) * tickDelta;
        double renderZ   = prevPosZ + (posZ - prevPosZ) * tickDelta;
        float  renderYaw = prevBodyYaw + MathHelper.wrapDegrees(bodyYaw - prevBodyYaw) * tickDelta;

        int light   = WorldRenderer.getLightmapCoordinates(mc.world,
                          BlockPos.ofFloored(renderX, renderY, renderZ));
        int overlay = OverlayTexture.DEFAULT_UV;

        matrices.push();
        matrices.translate(renderX - camPos.x, renderY - camPos.y, renderZ - camPos.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - renderYaw));
        matrices.scale(-0.5f, -0.5f, 0.5f);
        // 128×128 model: head(16)+body(24)+legs(20)=60px at 1/32 block = 1.875 blocks
        matrices.translate(0f, -2.75f, 0f);

        model.animate(limbAngle, limbDistance, ageInTicks + tickDelta, isFlying);

        VertexConsumer consumer = consumers.getBuffer(getEntityCutoutLayer());
        model.render(matrices, consumer, light, overlay);

        matrices.pop();
    }

    // ── RenderLayer (lazy) ────────────────────────────────────────────────────

    private static RenderLayer getEntityCutoutLayer() {
        if (renderLayer == null) {
            renderLayer = RenderLayer.of("tung_tung_companion",
                RenderSetup.builder(RenderPipelines.ENTITY_CUTOUT)
                    .texture("Sampler0", TEXTURE_ID)
                    .useLightmap()
                    .useOverlay()
                    .build());
        }
        return renderLayer;
    }

    // ── File-based texture (baked from the real Sketchfab GLB model) ─────────

    private void buildTexture(MinecraftClient mc) {
        try {
            Identifier fileId = Identifier.of("example-addon", "textures/entity/tung_tung.png");
            try (var stream = mc.getResourceManager().getResourceOrThrow(fileId).getInputStream()) {
                NativeImage img = NativeImage.read(stream);
                mc.getTextureManager().registerTexture(TEXTURE_ID,
                    new NativeImageBackedTexture(() -> "tung_tung_companion", img));
            }
        } catch (Exception ignored) { }
    }
}
