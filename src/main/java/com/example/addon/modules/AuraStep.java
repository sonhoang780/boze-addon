package com.example.addon.modules;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Water/fire decal trail rendered on the ground behind the player as they walk.
 * Plain world-space quads via LevelRenderEvents + PoseStack (same rendering path
 * TungTungSahur uses for its mesh) -- MC's own vertex pipeline does the
 * world->screen transform, no manual camera/depth math. The earlier PostChain
 * full-screen version (see git history) never appeared in-game and was dropped
 * rather than debugged blind.
 *
 * Per-pixel look (ripples, flicker, fresnel-ish flare) comes from a custom
 * fragment shader (shaders/core/aurastep.fsh) on a RenderPipeline built by
 * extending vanilla's ENTITY_SNIPPET -- reuses core/entity.vsh UNCHANGED
 * (matrices/fog/lighting/lightmap/overlay all proven vanilla code), only the
 * fragment shader differs. GameTime (from the Globals uniform block, already
 * bound by every shader) drives the animation instead of a hand-rolled uniform.
 * ponytail: GameTime's exact scale is unconfirmed against a live client (see
 * aurastep.fsh) -- tune the multiplier there if animation speed looks off.
 */
public class AuraStep extends AddonModule {
    public static final AuraStep INSTANCE = new AuraStep();

    public enum Mode { Water, Fire }

    public final ModeOption<Mode> mode = new ModeOption<>(this, "Mode", "Footstep decal style.", Mode.Water);
    public final SliderOption radius = new SliderOption(this, "Radius", "Decal radius in blocks.", 0.5, 0.15, 1.5, 0.05);
    public final SliderOption trailLifetime = new SliderOption(this, "TrailLifetime",
        "How long each footstep decal lingers before fading out (seconds).", 1.0, 0.1, 5.0, 0.1);
    // renderFireFootprints() plants ONE footprint sprite per recorded trail point --
    // this spacing IS the footprint spacing, not a curve-resolution knob. A small
    // value (this used to default to 0.15) means a new footstep every ~0.15 blocks;
    // at normal walk speed (~4.3 blocks/s) that's ~28 spawns/sec, which overlaps into
    // the solid smeared blur reported as "too many footprints, doesn't read as feet".
    // Default now matches a real human stride so Fire mode reads as discrete steps.
    // Water's ribbon doesn't care about this at all (continuous mesh, live head point
    // fills any gap every frame regardless of spacing).
    public final SliderOption stepDistance = new SliderOption(this, "Spacing",
        "Distance between footprints in blocks (Fire mode only). Higher = fewer, more spread out.", 0.65, 0.3, 1.5, 0.05);
    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("example-addon", "aurastep_white");
    private static final Identifier FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("example-addon", "core/aurastep");
    private static RenderType waterLayer, fireLayer;

    private static final class Footstep {
        final double x, y, z;
        final long spawnMs;
        final double sideX, sideZ; // lateral (left/right) offset, alternated per step -- real footsteps zigzag, not one straight line
        Footstep(double x, double y, double z, long spawnMs, double sideX, double sideZ) {
            this.x = x; this.y = y; this.z = z; this.spawnMs = spawnMs; this.sideX = sideX; this.sideZ = sideZ;
        }
    }

    private final ArrayDeque<Footstep> trail = new ArrayDeque<>();
    private double lastSpawnX, lastSpawnY, lastSpawnZ;
    private boolean hasLastSpawn = false;
    private boolean nextStepLeft = true;

    public AuraStep() {
        super("AuraStep", "Water/fire decal trail that follows the player while walking.");
    }

    @Override
    public void onDisable() {
        trail.clear();
        hasLastSpawn = false;
        nextStepLeft = true;
    }

    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            NativeImage img = new NativeImage(1, 1, false);
            img.setPixel(0, 0, 0xFFFFFFFF);
            mc.getTextureManager().register(TEXTURE_ID, new DynamicTexture(() -> "aurastep-white", img));
        });
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

        float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double renderX = Mth.lerp(tickDelta, mc.player.xo, mc.player.getX());
        double renderY = Mth.lerp(tickDelta, mc.player.yo, mc.player.getY());
        double renderZ = Mth.lerp(tickDelta, mc.player.zo, mc.player.getZ());
        long now = System.currentTimeMillis();

        // Spawn a new trail decal once the player has moved far enough since the last one.
        double dx = renderX - lastSpawnX, dz = renderZ - lastSpawnZ;
        if (!hasLastSpawn || Math.sqrt(dx * dx + dz * dz) >= stepDistance.getValue()) {
            double sideX = 0, sideZ = 0;
            double moveLen = Math.sqrt(dx * dx + dz * dz);
            if (moveLen > 1e-6) {
                double sign = nextStepLeft ? 1.0 : -1.0;
                double sideAmount = radius.getValue() * 0.7;
                sideX = (-dz / moveLen) * sideAmount * sign;
                sideZ = (dx / moveLen) * sideAmount * sign;
                nextStepLeft = !nextStepLeft;
            }
            trail.addLast(new Footstep(renderX, renderY, renderZ, now, sideX, sideZ));
            lastSpawnX = renderX; lastSpawnY = renderY; lastSpawnZ = renderZ;
            hasLastSpawn = true;
        }

        long lifetimeMs = (long) (trailLifetime.getValue() * 1000.0);
        long cutoffMs = now - lifetimeMs;
        // Only drop a point once the ONE AFTER it is also past the fade cutoff --
        // the segment between two expired points is fully invisible either way.
        // The single oldest surviving point is kept even once it's expired so
        // renderRibbon can interpolate the tail's exact cut position against it,
        // instead of the tail retracting in whole STEP_DISTANCE (0.35 block) jumps
        // every time a point got dropped outright.
        while (trail.size() >= 2) {
            java.util.Iterator<Footstep> it = trail.iterator();
            it.next();
            if (it.next().spawnMs <= cutoffMs) trail.pollFirst(); else break;
        }
        if (trail.isEmpty()) return;

        var camPos = mc.gameRenderer.getMainCamera().position();
        PoseStack matrices = ctx.poseStack();
        float r = radius.getValue().floatValue();
        boolean fire = mode.getValue() == Mode.Fire;
        int overlay = OverlayTexture.NO_OVERLAY;
        VertexConsumer c = consumers.getBuffer(getLayer(fire));

        if (fire) {
            renderFireFootprints(mc, matrices, c, camPos, r, lifetimeMs, now, overlay,
                renderX, renderY, renderZ);
        } else {
            renderRibbon(mc, matrices, c, camPos, r, lifetimeMs, cutoffMs, now, overlay,
                renderX, renderY, renderZ, false);
        }
    }

    /**
     * One CONTINUOUS ribbon for both modes (the "ladder" look came from separate
     * disconnected quads). Three things make it seamless:
     * 1. Miter joins -- each trail point gets ONE perpendicular (average of its two
     *    adjacent segments' perpendiculars), and neighboring segments share those
     *    exact edge vertices, so there is no gap or overlap at corners.
     * 2. A live head point at the player's CURRENT interpolated position every
     *    frame -- the ribbon hugs the player smoothly between footstep spawns
     *    instead of stepping forward 0.35 blocks at a time.
     * 3. Cumulative distance as UV.x -- the shader pattern is a continuous
     *    function of arc length along the whole trail, not restarted 0..1 per
     *    segment, so flames/waves flow across segment boundaries.
     */
    private void renderRibbon(Minecraft mc, PoseStack matrices, VertexConsumer c, net.minecraft.world.phys.Vec3 camPos,
                               float halfWidth, long lifetimeMs, long cutoffMs, long now, int overlay,
                               double headX, double headY, double headZ, boolean fire) {
        // Points: whole trail + live head at the player's current position.
        int n = trail.size() + 1;
        if (n < 2) return;
        double[] px = new double[n], py = new double[n], pz = new double[n];
        long[] spawn = new long[n];
        int k = 0;
        for (Footstep f : trail) { px[k] = f.x; py[k] = f.y; pz[k] = f.z; spawn[k] = f.spawnMs; k++; }
        px[k] = headX; py[k] = headY; pz[k] = headZ; spawn[k] = now;

        // Tail interpolation: if the oldest point is already past the fade cutoff,
        // slide it forward along the segment to point[1] to the EXACT position
        // where its age would hit the cutoff (spawn == cutoffMs), instead of
        // rendering it at its true, stale recorded spot. Symmetric to the "live
        // head" fix -- the tail now recedes continuously frame-to-frame (and all
        // the way up to the player's feet once they stop moving) instead of
        // snapping back in whole recorded-step increments.
        if (spawn[0] <= cutoffMs && n >= 2) {
            double frac = (spawn[1] == spawn[0]) ? 1.0
                : Mth.clamp((double) (cutoffMs - spawn[0]) / (double) (spawn[1] - spawn[0]), 0.0, 1.0);
            px[0] = Mth.lerp(frac, px[0], px[1]);
            py[0] = Mth.lerp(frac, py[0], py[1]);
            pz[0] = Mth.lerp(frac, pz[0], pz[1]);
            spawn[0] = cutoffMs;
        }

        // Per-point miter perpendicular (XZ plane) = normalized average of the
        // UNIT directions of the segments before and after the point (a proper
        // bisector, not a raw-vector average -- averaging raw deltas let a short
        // segment next to a long one skew the bisector). On a sharp turn (player
        // reverses direction between two footsteps) the two unit dirs nearly
        // cancel; normalizing that near-zero sum is numerically unstable and used
        // to flip the perpendicular almost at random -- the "star/fin" glitch the
        // water screenshot showed. Past a turn-sharpness threshold we bevel
        // instead: fall back to the outgoing segment's own perpendicular, which
        // stays well-defined at any angle.
        double[] perpX = new double[n], perpZ = new double[n];
        for (int i = 0; i < n; i++) {
            double inX = 0, inZ = 0, outX = 0, outZ = 0;
            if (i > 0) {
                double dx = px[i] - px[i - 1], dz = pz[i] - pz[i - 1];
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 1e-6) { inX = dx / len; inZ = dz / len; }
            }
            if (i < n - 1) {
                double dx = px[i + 1] - px[i], dz = pz[i + 1] - pz[i];
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 1e-6) { outX = dx / len; outZ = dz / len; }
            }
            double sumX = inX + outX, sumZ = inZ + outZ;
            double sumLen = Math.sqrt(sumX * sumX + sumZ * sumZ);
            double dirX, dirZ;
            if (sumLen < 0.35) { // sharp turn -- bevel via one segment instead of the unstable bisector
                if (outX != 0 || outZ != 0) { dirX = outX; dirZ = outZ; }
                else if (inX != 0 || inZ != 0) { dirX = inX; dirZ = inZ; }
                else { dirX = 1; dirZ = 0; }
            } else {
                dirX = sumX / sumLen; dirZ = sumZ / sumLen;
            }
            perpX[i] = -dirZ;
            perpZ[i] = dirX;
        }

        // Cumulative arc length -> UV.x (1 uv unit per block).
        double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            double dx = px[i] - px[i - 1], dz = pz[i] - pz[i - 1];
            cum[i] = cum[i - 1] + Math.sqrt(dx * dx + dz * dz);
        }

        matrices.pushPose();
        matrices.translate(px[0] - camPos.x, 0, pz[0] - camPos.z);
        PoseStack.Pose pose = matrices.last();
        int cr = fire ? 255 : 255, cg = fire ? 255 : 255, cb = 255; // tint left to the shader

        for (int i = 0; i < n - 1; i++) {
            double segLen = cum[i + 1] - cum[i];
            if (segLen < 1e-4) continue;
            int aA = (int) Mth.clamp(255 * (1.0f - (now - spawn[i]) / (float) lifetimeMs), 0, 255);
            int aB = (int) Mth.clamp(255 * (1.0f - (now - spawn[i + 1]) / (float) lifetimeMs), 0, 255);
            if (aA <= 2 && aB <= 2) continue;

            int lightA = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(px[i], py[i], pz[i]));
            int lightB = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(px[i + 1], py[i + 1], pz[i + 1]));
            float u0 = (float) cum[i], u1 = (float) cum[i + 1];

            // Local coords relative to px[0]/pz[0] (world Y kept absolute, minus camY via vertex Y).
            float ax = (float) (px[i] - px[0]), az = (float) (pz[i] - pz[0]);
            float bx = (float) (px[i + 1] - px[0]), bz = (float) (pz[i + 1] - pz[0]);
            float ay = (float) (py[i] - camPos.y + 0.02), by = (float) (py[i + 1] - camPos.y + 0.02);
            float apx = (float) (perpX[i] * halfWidth), apz = (float) (perpZ[i] * halfWidth);
            float bpx = (float) (perpX[i + 1] * halfWidth), bpz = (float) (perpZ[i + 1] * halfWidth);

            c.addVertex(pose, ax - apx, ay, az - apz).setColor(cr, cg, cb, aA).setUv(u0, 0).setOverlay(overlay).setLight(lightA).setNormal(pose, 0, 1, 0);
            c.addVertex(pose, ax + apx, ay, az + apz).setColor(cr, cg, cb, aA).setUv(u0, 1).setOverlay(overlay).setLight(lightA).setNormal(pose, 0, 1, 0);
            c.addVertex(pose, bx + bpx, by, bz + bpz).setColor(cr, cg, cb, aB).setUv(u1, 1).setOverlay(overlay).setLight(lightB).setNormal(pose, 0, 1, 0);
            c.addVertex(pose, bx - bpx, by, bz - bpz).setColor(cr, cg, cb, aB).setUv(u1, 0).setOverlay(overlay).setLight(lightB).setNormal(pose, 0, 1, 0);
        }
        matrices.popPose();
    }

    /**
     * Fire mode: a small vertical tuft planted at each footstep, offset
     * alternately left/right of the walked line (real footsteps zigzag, they
     * don't sit on one straight center line). A SINGLE camera-facing billboard
     * still reads as a flat card even though it always faces the camera -- one
     * flat quad has zero parallax with itself, so it can never look like it has
     * depth no matter how it's angled. Fixed world-space quads (not
     * camera-facing at all) is the standard cheap volumetric trick -- vanilla
     * renders every flower/tall-grass block the same way -- because walking
     * around them shows the planes sliding past each other, a real parallax
     * cue a single billboard cannot produce.
     *
     * THREE planes at 60 degree spacing (star cross, 6 quads/footstep) instead
     * of the old two-plane 90 degree cross (4 quads) -- one more silhouette
     * angle so there's no viewing direction where the flame collapses down to
     * a single thin edge-on plane.
     *
     * The ground glow decal is a SEPARATE pass (renderFireGlow) on its own
     * RenderType/buffer -- see the comment in onWorldRender for why this can't
     * be interleaved with the fire quads above in one loop.
     */
    private void renderFireFootprints(Minecraft mc, PoseStack matrices, VertexConsumer c,
                                       net.minecraft.world.phys.Vec3 camPos,
                                       float halfWidth, long lifetimeMs, long now, int overlay,
                                       double headX, double headY, double headZ) {
        float height = halfWidth * 2.4f;
        int cr = 255, cg = 255, cb = 255; // tint left to the shader
        // Fixed world-space directions at 0/60/120 degrees, NOT camera-facing --
        // the whole point is the planes DON'T all always face you, so their
        // relative motion as you walk around reads as depth.
        float[][] dirs = {
            { halfWidth, 0f },
            { (float) (halfWidth * 0.5), (float) (halfWidth * 0.8660254) },
            { (float) (halfWidth * -0.5), (float) (halfWidth * 0.8660254) },
        };

        for (Iterator<Footstep> it = trailIteratorPlusHead(headX, headY, headZ, now); it.hasNext(); ) {
            Footstep f = it.next();
            int alpha = (int) Mth.clamp(255 * (1.0f - (now - f.spawnMs) / (float) lifetimeMs), 0, 255);
            if (alpha <= 2) continue;

            double fx = f.x + f.sideX, fz = f.z + f.sideZ;
            int light = LevelRenderer.getLightCoords(mc.level, BlockPos.containing(fx, f.y, fz));
            float px = (float) (fx - camPos.x), pz = (float) (fz - camPos.z);
            float py = (float) (f.y - camPos.y);

            matrices.pushPose();
            PoseStack.Pose pose = matrices.last();
            for (float[] d : dirs) {
                float dx = d[0], dz = d[1];
                c.addVertex(pose, px - dx, py, pz - dz).setColor(cr, cg, cb, alpha).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
                c.addVertex(pose, px + dx, py, pz + dz).setColor(cr, cg, cb, alpha).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
                c.addVertex(pose, px + dx, py + height, pz + dz).setColor(cr, cg, cb, alpha).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
                c.addVertex(pose, px - dx, py + height, pz - dz).setColor(cr, cg, cb, alpha).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            }
            matrices.popPose();
        }
    }



    /** Trail footsteps followed by one synthetic "live head" footstep at the player's current position. */
    private Iterator<Footstep> trailIteratorPlusHead(double headX, double headY, double headZ, long now) {
        java.util.ArrayList<Footstep> all = new java.util.ArrayList<>(trail);
        all.add(new Footstep(headX, headY, headZ, now, 0, 0));
        return all.iterator();
    }

    private static RenderType getLayer(boolean fire) {
        if (fire) {
            if (fireLayer == null) fireLayer = buildLayer("aurastep_fire", "pipeline/aurastep_fire",
                FRAGMENT_SHADER_ID, TEXTURE_ID, BlendFunction.TRANSLUCENT, true);
            return fireLayer;
        } else {
            if (waterLayer == null) waterLayer = buildLayer("aurastep_water", "pipeline/aurastep_water",
                FRAGMENT_SHADER_ID, TEXTURE_ID, BlendFunction.TRANSLUCENT, false);
            return waterLayer;
        }
    }

    private static RenderType buildLayer(String name, String pipelineLocation, Identifier fragmentShader,
                                          Identifier texture, BlendFunction blend, boolean fireVariant) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", pipelineLocation))
            .withFragmentShader(fragmentShader)
            .withShaderDefine("PER_FACE_LIGHTING")
            .withColorTargetState(new ColorTargetState(blend))
            .withCull(false);
        if (fireVariant) builder.withShaderDefine("AURASTEP_FIRE");
        RenderPipeline pipeline = builder.build();

        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", texture)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        return RenderType.create(name, setup);
    }
}
