package com.example.addon.modules;

import com.example.addon.render.BubbleMesh;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ColorOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.render.ClientColor;
import dev.boze.api.render.ColorMaker;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Soap bubble enclosing the player (3rd person only -- in 1st person the camera
 * sits inside it, the film would smear the whole screen). Every SpawnInterval
 * seconds a small bubble detaches from the top half, inherits the player's
 * velocity (Newton: it keeps the momentum it had), then rises under buoyancy
 * against air drag with a light sideways wobble, fading out over SmallLifetime.
 *
 * Big bubble physics: spring-damper on a local offset. Player acceleration
 * shoves the bubble the opposite way (inertia), the spring pulls it back to
 * center, damping stops it oscillating forever. It also spins slowly around
 * the vertical axis -- visible because the thin-film pattern is anchored to
 * the sphere's UVs and rotates with the mesh.
 *
 * Rendering: BubbleMesh UV-sphere + custom bubble.vsh/bubble.fsh pipeline
 * (same RenderPipeline machinery as AuraStep, but with its own vertex shader
 * so the fragment stage gets real view-space normals for fresnel/iridescence).
 */
public class Bubble extends AddonModule {
    public static final Bubble INSTANCE = new Bubble();

    public final SliderOption radius = new SliderOption(this, "Radius", "Big bubble radius (blocks).", 1.4, 0.8, 2.5, 0.05);
    public final SliderOption spawnInterval = new SliderOption(this, "SpawnInterval", "Seconds between detached small bubbles.", 5.0, 1.0, 15.0, 0.5);
    public final SliderOption smallLifetime = new SliderOption(this, "SmallLifetime", "Seconds a detached bubble lives before fading out.", 3.0, 1.0, 8.0, 0.5);
    public final SliderOption spin = new SliderOption(this, "Spin", "Big bubble rotation speed (revolutions per minute).", 6.0, 0.0, 30.0, 0.5);
    public final ColorOption bubbleColor = new ColorOption(this, "BubbleColor", "Tint color for the bubble film.", ColorMaker.staticColor(255, 255, 255), 1.0f);

    private static RenderType bubbleLayer;

    // ── Big bubble spring-damper state ──
    private static final double SPRING_K = 14.0;   // pull back to center
    private static final double DAMPING = 5.0;     // kill oscillation
    private static final double INERTIA_GAIN = 0.9; // how hard player accel shoves the bubble
    private final Vec3[] offsetState = { Vec3.ZERO, Vec3.ZERO }; // [offset, offsetVel]
    private Vec3 prevPlayerPos = null;
    private Vec3 prevPlayerVel = Vec3.ZERO;
    private long prevFrameMs = 0;
    private float spinAngleDeg = 0;

    // ── Detached small bubbles ──
    private static final class SmallBubble {
        Vec3 pos, vel;
        final long spawnMs;
        final double scale;
        final double wobblePhase;
        SmallBubble(Vec3 pos, Vec3 vel, long spawnMs, double scale, double wobblePhase) {
            this.pos = pos; this.vel = vel; this.spawnMs = spawnMs; this.scale = scale; this.wobblePhase = wobblePhase;
        }
    }

    private final List<SmallBubble> smallBubbles = new ArrayList<>();
    private final Random random = new Random();

    // ── Pre-detach budding (bulge on the big bubble before a small one pinches off) ──
    private static final long BUD_WINDOW_MS = 500;
    private Vec3 nextSpawnDir = Vec3.ZERO; // unit vector, mesh-local space
    private long nextSpawnMs = 0;
    private double nextSpawnScale = 0.18; // decided up front so the bulge can swell to THIS size, not an unrelated fixed amount
    private static final long GROW_MS = 150; // detached bubble grows to full size over this long

    public Bubble() {
        super("Bubble", "Soap bubble around you; small ones detach and float away.");
    }

    @Override
    public void onEnable() {
        offsetState[0] = Vec3.ZERO;
        offsetState[1] = Vec3.ZERO;
        prevPlayerPos = null;
        prevFrameMs = 0;
        smallBubbles.clear();
        pickNextSpawn(System.currentTimeMillis());
    }

    private void pickNextSpawn(long now) {
        double theta = random.nextDouble() * Math.PI * 0.5; // upper half only
        double phi = random.nextDouble() * Math.PI * 2.0;
        nextSpawnDir = new Vec3(Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi));
        nextSpawnMs = now + (long) (spawnInterval.getValue() * 1000.0);
        nextSpawnScale = 0.12 + random.nextDouble() * 0.12;
    }

    @Override
    public void onDisable() {
        smallBubbles.clear();
    }

    static {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            if (INSTANCE.getState()) INSTANCE.onWorldRender(ctx);
        });
    }

    private void onWorldRender(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;

        long now = System.currentTimeMillis();
        double dt = prevFrameMs == 0 ? 0.016 : Math.min((now - prevFrameMs) / 1000.0, 0.1);
        prevFrameMs = now;

        float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 playerPos = new Vec3(
            Mth.lerp(tickDelta, mc.player.xo, mc.player.getX()),
            Mth.lerp(tickDelta, mc.player.yo, mc.player.getY()) + mc.player.getBbHeight() * 0.5,
            Mth.lerp(tickDelta, mc.player.zo, mc.player.getZ()));

        // ── Physics: inertia shove + spring-damper ──
        Vec3 playerVel = prevPlayerPos == null ? Vec3.ZERO : playerPos.subtract(prevPlayerPos).scale(1.0 / dt);
        Vec3 playerAccel = playerVel.subtract(prevPlayerVel).scale(1.0 / dt);
        prevPlayerPos = playerPos;
        prevPlayerVel = playerVel;

        Vec3 offset = offsetState[0], offsetVel = offsetState[1];
        offsetVel = offsetVel
            .add(playerAccel.scale(-INERTIA_GAIN * dt))       // shoved opposite to acceleration
            .add(offset.scale(-SPRING_K * dt))                // spring back to center
            .add(offsetVel.scale(-DAMPING * dt));             // damping
        offset = offset.add(offsetVel.scale(dt));
        double maxOff = radius.getValue() * 0.45;
        if (offset.length() > maxOff) offset = offset.normalize().scale(maxOff);
        offsetState[0] = offset;
        offsetState[1] = offsetVel;

        spinAngleDeg += (float) (spin.getValue() * 6.0 * dt); // rpm -> deg/s
        spinAngleDeg %= 360f;

        // ── Bud growing on the big bubble's surface, then pinches off ──
        float budAmount = (float) Mth.clamp(1.0 - (nextSpawnMs - now) / (double) BUD_WINDOW_MS, 0.0, 1.0);

        if (now >= nextSpawnMs) {
            double r = radius.getValue();
            Vec3 surface = nextSpawnDir;
            Vec3 spawnPos = playerPos.add(offset).add(surface.scale(r));
            Vec3 spawnVel = playerVel.scale(0.6).add(surface.scale(0.4)); // inherit momentum + gentle outward push
            // Same scale the bulge was swelling toward -- no size jump at handoff.
            smallBubbles.add(new SmallBubble(spawnPos, spawnVel, now,
                nextSpawnScale, random.nextDouble() * Math.PI * 2.0));
            pickNextSpawn(now);
            budAmount = 0f;
        }

        // ── Integrate small bubbles ──
        long lifeMs = (long) (smallLifetime.getValue() * 1000.0);
        for (Iterator<SmallBubble> it = smallBubbles.iterator(); it.hasNext(); ) {
            SmallBubble b = it.next();
            if (now - b.spawnMs > lifeMs) { it.remove(); continue; }
            double wob = (now - b.spawnMs) / 1000.0 * 2.2 + b.wobblePhase;
            Vec3 accel = new Vec3(Math.sin(wob) * 0.25, 1.1, Math.cos(wob * 0.8) * 0.25) // buoyancy + wobble
                .add(b.vel.scale(-0.9)); // air drag
            b.vel = b.vel.add(accel.scale(dt));
            b.pos = b.pos.add(b.vel.scale(dt));
        }

        // ── Render ──
        Vec3 camPos = mc.gameRenderer.getMainCamera().position();
        PoseStack matrices = ctx.poseStack();
        VertexConsumer c = consumers.getBuffer(getBubbleLayer());
        int overlay = OverlayTexture.NO_OVERLAY;
        int light = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(playerPos.x, playerPos.y, playerPos.z));

        ClientColor tint = bubbleColor.getValue().color;
        int cr = tint.getRed(), cg = tint.getGreen(), cb = tint.getBlue();

        boolean thirdPerson = !mc.options.getCameraType().isFirstPerson();
        if (thirdPerson) {
            Vec3 center = playerPos.add(offset);
            matrices.pushPose();
            matrices.translate(center.x - camPos.x, center.y - camPos.y, center.z - camPos.z);
            matrices.mulPose(Axis.YP.rotationDegrees(spinAngleDeg));
            float r = radius.getValue().floatValue();
            matrices.scale(r, r, r);
            // Bud direction is in mesh-local (pre-scale/pre-rotate) space, so undo the
            // spin here to find where nextSpawnDir sits relative to the mesh's own axes.
            Vec3 localBud = nextSpawnDir.yRot((float) -Math.toRadians(spinAngleDeg));
            // Bulge height is fraction of the BIG bubble's own radius -- cap it so the
            // swelling tops out at the actual size the small bubble will detach as,
            // instead of a fixed fraction that swelled to ~90% of the whole bubble's
            // radius regardless of how tiny the eventual small bubble was.
            float bulgeHeight = (float) (nextSpawnScale / radius.getValue());
            BubbleMesh.renderBudded(matrices.last(), c, light, overlay, cr, cg, cb, 255,
                localBud.x, localBud.y, localBud.z, budAmount, bulgeHeight);
            matrices.popPose();
        }

        // Small bubbles render in BOTH camera modes (they're outside the camera).
        for (SmallBubble b : smallBubbles) {
            long age = now - b.spawnMs;
            int alpha = (int) Mth.clamp(255 * (1.0 - age / (double) lifeMs), 0, 255);
            if (alpha <= 2) continue;
            matrices.pushPose();
            matrices.translate(b.pos.x - camPos.x, b.pos.y - camPos.y, b.pos.z - camPos.z);
            matrices.mulPose(Axis.YP.rotationDegrees(spinAngleDeg * 2.5f));
            // Grows from the pinched-off bud size up to full size instead of popping in.
            float growT = (float) Mth.clamp(age / (double) GROW_MS, 0.0, 1.0);
            growT = growT * growT * (3f - 2f * growT);
            float s = (float) b.scale * growT;
            matrices.scale(s, s, s);
            int bl = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(b.pos.x, b.pos.y, b.pos.z));
            BubbleMesh.render(matrices.last(), c, bl, overlay, cr, cg, cb, alpha);
            matrices.popPose();
        }
    }

    private static RenderType getBubbleLayer() {
        if (bubbleLayer != null) return bubbleLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/bubble"))
            .withVertexShader(Identifier.fromNamespaceAndPath("example-addon", "core/bubble"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("example-addon", "core/bubble"))
            .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withCull(false) // both halves of the film visible
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .sortOnUpload()
            .createRenderSetup();
        bubbleLayer = RenderType.create("bubble", setup);
        return bubbleLayer;
    }
}
