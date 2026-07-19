package com.example.addon.modules;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ColorOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.render.ClientColor;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayDeque;

/**
 * Standalone module (not tied to BetterChams/Gel -- see 2026-07-13 design decision):
 * a continuous glowing ribbon behind the local player, spawned as they move or turn
 * the camera quickly in third person. Grew out of a happy-accident bug in BetterChams'
 * Gel particle trail (a stretched quad-strip that looked cool but was visually broken
 * -- see GelParticleSystem's history), but this is deliberately its own thing:
 * independent module, own render hook, no shared state with Gel.
 *
 * Emits from multiple points around the body (head, shoulders, feet), not just one
 * center point -- a single trail line read as too sparse/unconvincing (user report,
 * 2026-07-13: "phải cho trails phát ra từ các đỉnh của model"). Each emitter offset
 * is in player-local space (rotated by the player's own yaw each spawn), so the
 * emission points stay attached to the body as it turns, and keeps its own aging
 * trail independently.
 *
 * Render mechanism mirrors AuraStep's proven world-space decal trail (same
 * LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN hook, same age-out-by-lifetime ArrayDeque
 * pattern) -- reused rather than re-invented, though the actual ribbon geometry is its
 * own: AuraStep's renderRibbon locks its width-perpendicular to the XZ (ground) plane,
 * correct for footsteps but wrong here since Trails' emitters (head/shoulders/feet)
 * move freely in full 3D, not just along the ground. Was previously a separate soft-
 * glow billboard PER point (discrete dots reading as a dashed line, "vệt nét đứt" --
 * user wanted a continuous, smoothly-connected strip instead, 2026-07-16). Now each
 * emitter's history is one connected camera-facing quad-strip: consecutive points share
 * an edge, width perpendicular computed from the segment direction crossed with the
 * camera's own forward vector (billboard ribbon, same family of technique as
 * GelParticleSystem's oriented-box edges), alpha faded per-vertex by age for the taper.
 * Uses a flat white additive texture (own dedicated pipeline below), NOT
 * GelParticleSystem's gel_particle.png radial-gradient sprite -- that texture's round
 * falloff would cut a connected strip into a row of separate blobs instead of one
 * smooth line.
 */
public class Trails extends AddonModule {
    // Player-local offsets (x = right, y = up from feet, z = forward), rotated by
    // yaw at spawn time. Rough humanoid silhouette corners -- not tied to the real
    // per-tick pose (Trails is deliberately independent of BetterChams/Gel's
    // per-part ModelPart access), just enough spread to read as "from the model"
    // rather than one point.
    //
    // MUST be declared before INSTANCE: static field initializers run top-to-bottom
    // in declaration order, and `new Trails()` (INSTANCE's initializer) reads this
    // array's length in the constructor -- with INSTANCE declared first, that read
    // happened before this array itself was assigned, crashing every launch with
    // "Cannot read the array length because EMITTER_OFFSETS is null" (2026-07-13).
    private static final float[][] EMITTER_OFFSETS = {
        { 0.0f, 1.7f, 0.0f },   // head
        { -0.28f, 1.3f, 0.0f }, // left shoulder
        { 0.28f, 1.3f, 0.0f },  // right shoulder
        { -0.18f, 0.05f, 0.0f },// left foot
        { 0.18f, 0.05f, 0.0f }, // right foot
    };

    public static final Trails INSTANCE = new Trails();

    public final SliderOption trailLifetime = new SliderOption(this, "Trail Lifetime",
        "How long each trail dot lingers before fading out (seconds).", 0.6, 0.1, 3.0, 0.05);
    public final SliderOption dotSize = new SliderOption(this, "Dot Size",
        "Radius of each trail dot.", 0.06, 0.02, 0.2, 0.01);
    public final SliderOption moveThreshold = new SliderOption(this, "Move Threshold",
        "Minimum distance moved (blocks) before spawning a new dot.", 0.15, 0.02, 1.0, 0.01);
    public final SliderOption yawThreshold = new SliderOption(this, "Yaw Threshold",
        "Minimum camera yaw change (degrees) before spawning a new dot, even while standing still.", 8.0, 1.0, 45.0, 1.0);
    public final ColorOption color = new ColorOption(this, "Color",
        "Trail dot color.", dev.boze.api.render.ColorMaker.staticColor(255, 255, 255), 1.0f);

    private static final class TrailPoint {
        final double x, y, z;
        final long spawnMs;
        TrailPoint(double x, double y, double z, long spawnMs) { this.x = x; this.y = y; this.z = z; this.spawnMs = spawnMs; }
    }

    private final ArrayDeque<TrailPoint>[] trails = new ArrayDeque[EMITTER_OFFSETS.length];
    private double lastX, lastY, lastZ;
    private float lastYaw;
    private boolean hasLast = false;

    public Trails() {
        super("Trails", "Dashed trail of fading dots behind you while moving or turning in 3rd person.");
        for (int i = 0; i < trails.length; i++) trails[i] = new ArrayDeque<>();
    }

    @Override
    public void onDisable() {
        for (ArrayDeque<TrailPoint> t : trails) t.clear();
        hasLast = false;
    }

    static {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            if (INSTANCE.getState()) INSTANCE.onWorldRender(ctx);
        });
    }

    private void onWorldRender(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // Third-person only (per design request) -- first person has no visible body
        // behind the camera for a trail to make sense relative to.
        if (mc.options.getCameraType().isFirstPerson()) { hasLast = false; return; }

        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;

        float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double renderX = net.minecraft.util.Mth.lerp(tickDelta, player.xo, player.getX());
        double renderY = net.minecraft.util.Mth.lerp(tickDelta, player.yo, player.getY());
        double renderZ = net.minecraft.util.Mth.lerp(tickDelta, player.zo, player.getZ());
        float yaw = player.getYRot();
        long now = System.currentTimeMillis();

        if (!hasLast) {
            lastX = renderX; lastY = renderY; lastZ = renderZ; lastYaw = yaw;
            hasLast = true;
        }

        double dx = renderX - lastX, dy = renderY - lastY, dz = renderZ - lastZ;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float yawDelta = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - lastYaw));

        if (moved >= moveThreshold.getValue() || yawDelta >= yawThreshold.getValue()) {
            // Rotate each local offset by the player's current yaw and spawn one
            // point per emitter, all at this same instant.
            double yawRad = Math.toRadians(yaw);
            float sin = (float) Math.sin(yawRad), cos = (float) Math.cos(yawRad);
            for (int i = 0; i < EMITTER_OFFSETS.length; i++) {
                float[] off = EMITTER_OFFSETS[i];
                // Minecraft yaw: 0 = south (+Z), rotates toward -X as yaw increases.
                double wx = renderX - off[0] * cos - off[2] * sin;
                double wz = renderZ - off[0] * sin + off[2] * cos;
                double wy = renderY + off[1];
                trails[i].addLast(new TrailPoint(wx, wy, wz, now));
            }
            lastX = renderX; lastY = renderY; lastZ = renderZ; lastYaw = yaw;
        }

        long lifetimeMs = (long) (trailLifetime.getValue() * 1000.0);
        long cutoffMs = now - lifetimeMs;
        boolean anyLeft = false;
        for (ArrayDeque<TrailPoint> t : trails) {
            while (!t.isEmpty() && t.peekFirst().spawnMs <= cutoffMs) t.pollFirst();
            if (!t.isEmpty()) anyLeft = true;
        }
        if (!anyLeft) return;

        // Vec3 (double), NOT cast to float here (2026-07-18 fix, same bug class as
        // BetterChams' GelParticleSystem -- "Complex mode méo xẹo xa gốc toạ độ"):
        // renderRibbon below subtracts this from each point's own double world-absolute
        // position FIRST, only casting the (small) camera-relative delta to float. Casting
        // the huge world-absolute camera position to float before that subtraction loses
        // precision at extreme coordinates (float32 ULP grows past ~1 unit beyond ~16M) --
        // two independently-rounded huge floats don't recover precision when subtracted.
        Vec3 camPos = mc.gameRenderer.getMainCamera().position();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vector3f camForward = new Vector3f(camera.forwardVector());

        // Own dedicated pipeline (flat white + additive), NOT RenderTypes.debugQuads()
        // (shared debug RenderTypes crash with "Not building!" pulled from this
        // BufferSource, see GelParticleSystem's class doc) and NOT GelParticleSystem's
        // gel_particle.png radial-gradient sprite either -- see this class's doc for
        // why that texture is wrong for a connected strip.
        VertexConsumer vc = consumers.getBuffer(getRibbonLayer());
        ClientColor tint = color.getValue().color;
        float halfWidth = dotSize.getValue().floatValue();
        PoseStack.Pose pose = ctx.poseStack().last();

        for (ArrayDeque<TrailPoint> t : trails) {
            renderRibbon(vc, pose, t, camPos, camForward, halfWidth, tint, lifetimeMs, now);
        }
    }

    /**
     * Connects one emitter's whole point history into a single camera-facing
     * quad-strip -- consecutive points share an edge (no gaps, no per-point round
     * falloff cutting it into blobs), width perpendicular = segmentDir x camForward
     * (billboard-style, degenerates gracefully to camUp when a segment is
     * near-parallel to the view direction, same family of fallback GelParticleSystem's
     * oriented-box edges already use). Alpha faded per-VERTEX by that point's own age
     * -- unlike the old dots (additive pipeline, age baked into RGB because vertex
     * alpha was ignored), this pipeline is TRANSLUCENT precisely so a real alpha taper
     * works along the strip's length.
     */
    private static void renderRibbon(VertexConsumer vc, PoseStack.Pose pose, ArrayDeque<TrailPoint> t,
                                      Vec3 camPos, Vector3f camForward, float halfWidth, ClientColor tint,
                                      long lifetimeMs, long now) {
        int n = t.size();
        if (n < 2) return;
        TrailPoint[] pts = t.toArray(new TrailPoint[0]); // oldest..newest (ArrayDeque iteration order)

        int light = 15728880;
        int overlay = OverlayTexture.NO_OVERLAY;
        int r = tint.getRed(), g = tint.getGreen(), b = tint.getBlue();

        Vector3f a = new Vector3f(), bpos = new Vector3f(), segDir = new Vector3f(), perp = new Vector3f();
        for (int i = 0; i < n - 1; i++) {
            TrailPoint pa = pts[i], pb = pts[i + 1];
            // Subtract camPos in DOUBLE first (both operands double, small result), only
            // THEN cast to float -- not the other way around (see camPos field's comment).
            a.set((float) (pa.x - camPos.x), (float) (pa.y - camPos.y), (float) (pa.z - camPos.z));
            bpos.set((float) (pb.x - camPos.x), (float) (pb.y - camPos.y), (float) (pb.z - camPos.z));
            segDir.set(bpos).sub(a);
            float segLen = segDir.length();
            if (segLen < 1e-4f) continue;
            segDir.div(segLen);

            perp.set(segDir).cross(camForward);
            float perpLen = perp.length();
            if (perpLen < 1e-4f) {
                // Segment nearly parallel to the view ray -- cross product degenerates
                // near zero. Fall back to a fixed world-up perpendicular rather than
                // let a near-zero vector normalize into numerical noise (same class of
                // instability GelParticleSystem's box-edge comment already documents).
                perp.set(0, 1, 0).cross(segDir);
                perpLen = perp.length();
                if (perpLen < 1e-4f) perp.set(1, 0, 0); else perp.div(perpLen);
            } else {
                perp.div(perpLen);
            }
            perp.mul(halfWidth);

            int alphaA = (int) net.minecraft.util.Mth.clamp(255f * (1.0f - (now - pa.spawnMs) / (float) lifetimeMs), 0, 255);
            int alphaB = (int) net.minecraft.util.Mth.clamp(255f * (1.0f - (now - pb.spawnMs) / (float) lifetimeMs), 0, 255);
            if (alphaA <= 2 && alphaB <= 2) continue;

            float ax0 = a.x - perp.x, ay0 = a.y - perp.y, az0 = a.z - perp.z;
            float ax1 = a.x + perp.x, ay1 = a.y + perp.y, az1 = a.z + perp.z;
            float bx0 = bpos.x - perp.x, by0 = bpos.y - perp.y, bz0 = bpos.z - perp.z;
            float bx1 = bpos.x + perp.x, by1 = bpos.y + perp.y, bz1 = bpos.z + perp.z;

            vc.addVertex(pose, ax0, ay0, az0).setColor(r, g, b, alphaA).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            vc.addVertex(pose, ax1, ay1, az1).setColor(r, g, b, alphaA).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            vc.addVertex(pose, bx1, by1, bz1).setColor(r, g, b, alphaB).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            vc.addVertex(pose, bx0, by0, bz0).setColor(r, g, b, alphaB).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
        }
    }

    private static final Identifier WHITE_TEXTURE_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/white.png");
    private static final Identifier SHARED_FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("example-addon", "core/capturemark");
    private static RenderType ribbonLayer;

    /** TRANSLUCENT (real per-vertex alpha taper, see renderRibbon's doc), flat white, no depth write -- same pipeline shape as GelParticleSystem's box layers. */
    private static RenderType getRibbonLayer() {
        if (ribbonLayer != null) return ribbonLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/trails_ribbon"))
            .withFragmentShader(SHARED_FRAGMENT_SHADER_ID)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", WHITE_TEXTURE_ID)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        ribbonLayer = RenderType.create("trails_ribbon", setup);
        return ribbonLayer;
    }
}
