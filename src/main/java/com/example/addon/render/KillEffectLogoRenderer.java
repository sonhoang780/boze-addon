package com.example.addon.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * KillEffect's Logo mode: a flat card textured with the Boze logo that pops out of the
 * dying player's chest, falls under gravity, bounces off the ground once or twice
 * (damped), keeps spinning around world +Y the whole time, then fades out once it has
 * settled. Rendering setup mirrors KillEffectParticleSystem (same LevelRenderEvents
 * hook, same world-absolute-position-then-subtract-camPos pattern) but with a real
 * textured TRANSLUCENT-blended quad instead of an additive glow dot.
 */
public class KillEffectLogoRenderer {
    public static final KillEffectLogoRenderer INSTANCE = new KillEffectLogoRenderer();

    private static final Identifier LOGO_TEXTURE_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/boze_logo.png");
    private static final Identifier LOGO_FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("example-addon", "core/killeffect_logo");

    // Tuned for a snappy, readable bounce regardless of the module's Duration slider --
    // the physics settles in well under a second; Duration only controls how long the
    // logo then lingers/fades once resting.
    private static final float GRAVITY = 14.0f;
    private static final float BOUNCE_DAMP = 0.45f;
    private static final int MAX_BOUNCES = 2;
    private static final float SETTLE_VEL = 0.4f;
    // Fraction of maxAge before fading is even allowed to start -- keeps the drop/bounce
    // fully opaque and only dissolves it afterward, never mid-bounce.
    private static final float FADE_START_FRAC = 0.35f;

    private static RenderType logoLayer;

    private static final class LogoInstance {
        final Vector3f pos = new Vector3f(); // world-absolute
        final Vector3f vel = new Vector3f();
        float groundY;
        int bounces;
        boolean settled;
        float age;
        float maxAge;
        float size;
        float spinDegPerSec;
    }

    private final List<LogoInstance> instances = new ArrayList<>();
    private final Random random = new Random();
    private long lastNanos = 0L;

    static {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> INSTANCE.renderAll(ctx));
    }

    /**
     * Fire one spinning logo card popping out of {@code spawnPos} (chest height), falling
     * and bouncing on {@code groundY} (the player's feet level at death). Called once per
     * death (Logo mode).
     */
    public void spawn(Vector3f spawnPos, float groundY, float size, float spinDegPerSec, float durationSec) {
        LogoInstance inst = new LogoInstance();
        inst.pos.set(spawnPos);
        inst.groundY = groundY;
        // Pop up and slightly sideways out of the chest before gravity takes over.
        inst.vel.set((random.nextFloat() * 2f - 1f) * 1.2f, 2.6f, (random.nextFloat() * 2f - 1f) * 1.2f);
        inst.size = size;
        inst.spinDegPerSec = spinDegPerSec;
        inst.maxAge = durationSec;
        instances.add(inst);
    }

    private void renderAll(LevelRenderContext ctx) {
        if (instances.isEmpty()) { lastNanos = 0L; return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        MultiBufferSource bufferSource = ctx.bufferSource();
        if (bufferSource == null) return;

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min((now - lastNanos) / 1.0e9f, 0.1f);
        lastNanos = now;

        Iterator<LogoInstance> it = instances.iterator();
        while (it.hasNext()) {
            LogoInstance inst = it.next();
            inst.age += dt;
            if (inst.age >= inst.maxAge) { it.remove(); continue; }
            step(inst, dt);
        }
        if (instances.isEmpty()) return;

        PoseStack.Pose pose = ctx.poseStack().last();
        Camera camera = mc.gameRenderer.getMainCamera();
        var camPosD = camera.position();
        Vector3f camPos = new Vector3f((float) camPosD.x, (float) camPosD.y, (float) camPosD.z);

        VertexConsumer vc = bufferSource.getBuffer(getLogoLayer());
        Vector3f rel = new Vector3f();
        for (LogoInstance inst : instances) {
            float lifeFrac = inst.maxAge <= 0f ? 1f : inst.age / inst.maxAge;
            float fadeIn = Math.min(lifeFrac / 0.05f, 1f);
            float t = Math.max(0f, (lifeFrac - FADE_START_FRAC) / (1f - FADE_START_FRAC));
            float fadeOut = 1f - t * t; // stays solid until FADE_START_FRAC, eases out after
            int alpha = Math.round(255f * fadeIn * fadeOut);
            if (alpha <= 0) continue;

            rel.set(inst.pos).sub(camPos);
            float angleRad = (float) Math.toRadians(inst.age * inst.spinDegPerSec);
            emitLogoQuad(vc, pose, rel, angleRad, inst.size, alpha);
        }
    }

    private static void step(LogoInstance inst, float dt) {
        if (dt <= 0f || inst.settled) return;
        inst.vel.y -= GRAVITY * dt;
        inst.pos.x += inst.vel.x * dt;
        inst.pos.y += inst.vel.y * dt;
        inst.pos.z += inst.vel.z * dt;

        if (inst.pos.y <= inst.groundY && inst.vel.y < 0f) {
            inst.pos.y = inst.groundY;
            if (inst.bounces >= MAX_BOUNCES || Math.abs(inst.vel.y) < SETTLE_VEL) {
                inst.settled = true;
                inst.vel.set(0f, 0f, 0f);
            } else {
                inst.vel.y = -inst.vel.y * BOUNCE_DAMP;
                inst.vel.x *= 0.6f;
                inst.vel.z *= 0.6f;
                inst.bounces++;
            }
        }
    }

    /**
     * Flat quad spinning around world +Y, centered on {@code c} (already camera-relative).
     * Cull is OFF in the pipeline (needed so the spin is visible from both sides through a
     * single quad) -- emitting a SECOND, reverse-wound quad on top of the same geometry
     * here was the earlier "vỡ" (broken/flickering) bug: two coplanar translucent quads at
     * the identical depth double-blend and z-fight against each other every frame. One
     * quad is correct and sufficient.
     */
    private static void emitLogoQuad(VertexConsumer vc, PoseStack.Pose pose, Vector3f c, float angleRad, float size, int alpha) {
        float cos = (float) Math.cos(angleRad), sin = (float) Math.sin(angleRad);
        // Right vector sweeps the XZ plane as it spins; up stays fixed vertical -- reads as
        // a card spinning around a vertical axis from any camera angle.
        Vector3f right = new Vector3f(cos, 0f, sin).mul(size);
        Vector3f up = new Vector3f(0f, size, 0f);
        Vector3f normal = new Vector3f(-sin, 0f, cos);

        Vector3f v0 = new Vector3f(c).sub(right).sub(up);
        Vector3f v1 = new Vector3f(c).add(right).sub(up);
        Vector3f v2 = new Vector3f(c).add(right).add(up);
        Vector3f v3 = new Vector3f(c).sub(right).add(up);

        int light = 15728880;
        int overlay = OverlayTexture.NO_OVERLAY;
        // U swapped (see KillEffectMemeRenderer#emitBillboard's comment) -- same left/right
        // mirror bug applies here (right/up are camera vectors there, right/up here are the
        // spin-basis vectors, but the +/- sign convention issue is identical).
        vc.addVertex(pose, v0.x, v0.y, v0.z).setColor(255, 255, 255, alpha).setUv(1.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
        vc.addVertex(pose, v1.x, v1.y, v1.z).setColor(255, 255, 255, alpha).setUv(0.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
        vc.addVertex(pose, v2.x, v2.y, v2.z).setColor(255, 255, 255, alpha).setUv(0.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
        vc.addVertex(pose, v3.x, v3.y, v3.z).setColor(255, 255, 255, alpha).setUv(1.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
    }

    /** TRANSLUCENT (real alpha compositing), no depth write so it never z-fights nearby geometry. */
    private static RenderType getLogoLayer() {
        if (logoLayer != null) return logoLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/kill_effect_logo"))
            .withFragmentShader(LOGO_FRAGMENT_SHADER_ID)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", LOGO_TEXTURE_ID)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        logoLayer = RenderType.create("kill_effect_logo", setup);
        return logoLayer;
    }
}
