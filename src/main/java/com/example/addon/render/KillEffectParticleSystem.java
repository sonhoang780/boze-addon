package com.example.addon.render;

import com.example.addon.render.ModelCubeCapture.OrientedCube;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.boze.api.render.ClientColor;
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
 * One-shot "ghost dust" burst that replaces the vanilla death animation for a player.
 * KillEffect (the module) captures the dying player's actual posed cubes once, via
 * MixinModelFeatureRenderer's TAIL inject, then calls {@link #spawn} -- each cube coughs
 * out a cloud of glowing dots that fly outward, slow to a drift (drag, NO gravity), and
 * fade over the module's Duration.
 *
 * Same crash-avoidance rules as GelParticleSystem: draws from its OWN dedicated
 * RenderType on the AFTER_TRANSLUCENT_TERRAIN world hook (never a shared debug
 * RenderType from inside a nested render stage), and grabs exactly ONE buffer for the
 * one pass so there is no interleaved "Not building!" hazard.
 *
 * Coordinate space: particle positions are stored WORLD-ABSOLUTE (unlike Gel's, which
 * are already camera-relative). A burst is spawned from OrientedCubes that ARE
 * camera-relative, so spawn() adds that frame's camPos back once; renderAll() then
 * subtracts each frame's camPos to draw -- so the dust stays put in the world as the
 * camera moves, which a fixed camera-relative store would not.
 */
public class KillEffectParticleSystem {
    public static final KillEffectParticleSystem INSTANCE = new KillEffectParticleSystem();

    private static final Identifier GLOW_TEXTURE_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/gel_particle.png");
    private static final Identifier SHARED_FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("example-addon", "core/capturemark");

    // Dot billboard half-size in blocks. Bigger than Gel's near-invisible fill dots --
    // these are the whole effect, meant to read as distinct drifting motes, not a haze.
    private static final float DOT_RADIUS = 0.055f;
    // Fraction of speed retained per second. 0.06 => ~94% shed in the first second, so
    // the initial burst is snappy and then coasts into a slow ghost drift. No gravity.
    private static final float DRAG_PER_SEC = 0.06f;
    // Cap the physics step so a stutter / alt-tab pause doesn't teleport every mote.
    private static final float MAX_DT = 0.1f;

    private static RenderType glowLayer;

    private static final class Mote {
        final Vector3f pos = new Vector3f();  // world-absolute
        final Vector3f vel = new Vector3f();
        float age;
        float maxAge;
    }

    private static final class Burst {
        final List<Mote> motes = new ArrayList<>();
        final int r, g, b;
        Burst(ClientColor tint) { this.r = tint.getRed(); this.g = tint.getGreen(); this.b = tint.getBlue(); }
    }

    private final List<Burst> bursts = new ArrayList<>();
    private final Random random = new Random();
    private long lastNanos = 0L;

    static {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> INSTANCE.renderAll(ctx));
    }

    /**
     * Fire one burst. {@code cubes} are camera-relative (from ModelCubeCapture against
     * this frame's pose); {@code camPos} is this frame's camera world position, used to
     * lift the spawn points into world space. Called once per death.
     */
    public void spawn(List<OrientedCube> cubes, Vector3f camPos, ClientColor tint, int perCube, float explodeSpeed, float durationSec) {
        if (cubes.isEmpty() || perCube <= 0) return;
        Burst burst = new Burst(tint);
        Vector3f localSample = new Vector3f();
        Vector3f worldRel = new Vector3f();
        Vector3f dir = new Vector3f();
        for (OrientedCube oc : cubes) {
            for (int i = 0; i < perCube; i++) {
                // Sample a random point INSIDE the cube's local box, transform through the
                // cube's own pose -> camera-relative, add camPos -> world absolute. Sampling
                // the volume (not just the center) makes the cloud take the model's shape.
                localSample.set(
                    lerp(oc.localMin.x, oc.localMax.x, random.nextFloat()),
                    lerp(oc.localMin.y, oc.localMax.y, random.nextFloat()),
                    lerp(oc.localMin.z, oc.localMax.z, random.nextFloat())
                );
                oc.mat.transformPosition(localSample, worldRel);
                if (!worldRel.isFinite()) continue;

                Mote m = new Mote();
                m.pos.set(worldRel).add(camPos);
                // Uniform random direction on the sphere, +-25% speed jitter so the cloud
                // doesn't expand as one rigid shell.
                dir.set(random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f);
                if (dir.lengthSquared() < 1.0e-6f) dir.set(0f, 1f, 0f);
                dir.normalize().mul(explodeSpeed * (0.75f + random.nextFloat() * 0.5f));
                m.vel.set(dir);
                m.maxAge = durationSec;
                burst.motes.add(m);
            }
        }
        if (!burst.motes.isEmpty()) bursts.add(burst);
    }

    private void renderAll(LevelRenderContext ctx) {
        if (bursts.isEmpty()) { lastNanos = 0L; return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        MultiBufferSource bufferSource = ctx.bufferSource();
        if (bufferSource == null) return;

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min((now - lastNanos) / 1.0e9f, MAX_DT);
        lastNanos = now;

        step(dt);
        if (bursts.isEmpty()) return;

        PoseStack.Pose pose = ctx.poseStack().last();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vector3f camUp = new Vector3f(camera.upVector());
        Vector3f camLeft = new Vector3f(camera.leftVector());
        var camPosD = camera.position();
        Vector3f camPos = new Vector3f((float) camPosD.x, (float) camPosD.y, (float) camPosD.z);

        VertexConsumer vc = bufferSource.getBuffer(getGlowLayer());
        Vector3f rel = new Vector3f();
        for (Burst burst : bursts) {
            for (Mote m : burst.motes) {
                float lifeFrac = m.maxAge <= 0f ? 1f : m.age / m.maxAge;
                if (lifeFrac >= 1f) continue;
                float fade = 1f - lifeFrac;
                int alpha = Math.round(255f * fade * fade);   // ease-out: lingers bright, then drops
                if (alpha <= 0) continue;
                float size = DOT_RADIUS * (0.6f + 0.4f * fade); // slight shrink as it dies
                rel.set(m.pos).sub(camPos);
                emitGlowDot(vc, pose, rel, camUp, camLeft, burst.r, burst.g, burst.b, size, alpha);
            }
        }
    }

    private void step(float dt) {
        if (dt <= 0f) return;
        float dragMul = (float) Math.pow(DRAG_PER_SEC, dt);
        Iterator<Burst> bit = bursts.iterator();
        while (bit.hasNext()) {
            Burst burst = bit.next();
            Iterator<Mote> it = burst.motes.iterator();
            while (it.hasNext()) {
                Mote m = it.next();
                m.age += dt;
                if (m.age >= m.maxAge) { it.remove(); continue; }
                m.pos.x += m.vel.x * dt;
                m.pos.y += m.vel.y * dt;
                m.pos.z += m.vel.z * dt;
                m.vel.mul(dragMul);
            }
            if (burst.motes.isEmpty()) bit.remove();
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** Camera-facing additive billboard, mirror of GelParticleSystem.emitGlowDot. */
    private static void emitGlowDot(VertexConsumer vc, PoseStack.Pose pose, Vector3f c, Vector3f camUp, Vector3f camLeft, int r, int g, int b, float size, int alpha) {
        Vector3f u = new Vector3f(camUp).mul(size);
        Vector3f l = new Vector3f(camLeft).mul(size);
        Vector3f v0 = new Vector3f(c).sub(u).sub(l);
        Vector3f v1 = new Vector3f(c).sub(u).add(l);
        Vector3f v2 = new Vector3f(c).add(u).add(l);
        Vector3f v3 = new Vector3f(c).add(u).sub(l);
        int light = 15728880;
        int overlay = OverlayTexture.NO_OVERLAY;
        vc.addVertex(pose, v0.x, v0.y, v0.z).setColor(r, g, b, alpha).setUv(0.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v1.x, v1.y, v1.z).setColor(r, g, b, alpha).setUv(1.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v2.x, v2.y, v2.z).setColor(r, g, b, alpha).setUv(1.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v3.x, v3.y, v3.z).setColor(r, g, b, alpha).setUv(0.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
    }

    /** ADDITIVE, no depth test/write, no cull -- viewpoint-independent glow, same as Gel's xray layer. */
    private static RenderType getGlowLayer() {
        if (glowLayer != null) return glowLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/kill_effect_dust"))
            .withFragmentShader(SHARED_FRAGMENT_SHADER_ID)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", GLOW_TEXTURE_ID)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        glowLayer = RenderType.create("kill_effect_dust", setup);
        return glowLayer;
    }
}
