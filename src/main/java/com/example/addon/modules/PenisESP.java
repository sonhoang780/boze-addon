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
import dev.boze.api.option.ToggleOption;
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
import net.minecraft.world.entity.player.Player;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Ported from XatzClient-Jigsaw's PenisESP (1.8.9 GLU Cylinder/Sphere immediate-mode
 * -- me/xatzdevelopments/xatz/client/modules/PenisESP.java), rebuilt as generated
 * quad meshes on the same LevelRenderEvents pipeline TargetESP/Bubble use.
 *
 * <p>Twist over the original: model size is derived from the machine's TIMEZONE --
 * full size at England's GMT (UTC+0), shrinking the further the local offset drifts
 * from it. A machine in Japan (Asia/Tokyo) additionally gets the model covered in
 * black censor-mosaic boxes, as tradition demands.
 */
public class PenisESP extends AddonModule {
    public static final PenisESP INSTANCE = new PenisESP();

    public final ToggleOption animation = new ToggleOption(this, "Animation", "Pendulum wobble like the original.", true);

    private static RenderType layer;

    // Computed once -- the machine's zone doesn't change mid-session.
    private static final float TZ_SCALE;
    private static final boolean CENSORED;
    static {
        ZoneId zone = ZoneId.systemDefault();
        double offsetHours = ZonedDateTime.now(zone).getOffset().getTotalSeconds() / 3600.0;
        // 1.0 at GMT, linearly down as the offset drifts from it; floor keeps the
        // model visible even at UTC+14 (offsets span -12..+14, |9|h Japan -> ~0.52).
        TZ_SCALE = (float) Mth.clamp(1.0 - Math.abs(offsetHours) / 14.0 * 0.75, 0.25, 1.0);
        String id = zone.getId();
        CENSORED = id.equals("Asia/Tokyo") || id.equals("Japan") || id.equals("JST");

        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            if (INSTANCE.getState()) INSTANCE.onWorldRender(ctx);
        });
    }

    // Original swings ClientSettings.pspin between -50 and 50; a sine over wall time
    // gives the same pendulum read without the stateful counters.
    private static final double SWING_PERIOD_MS = 1400.0;

    public PenisESP() {
        super("PenisESP", "What do you think this does?");
    }

    private void onWorldRender(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        MultiBufferSource consumers = ctx.bufferSource();
        if (consumers == null) return;

        float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var camPos = mc.gameRenderer.getMainCamera().position();
        PoseStack matrices = ctx.poseStack();
        VertexConsumer c = consumers.getBuffer(getLayer());
        int overlay = OverlayTexture.NO_OVERLAY;

        float swing = animation.getValue()
            ? (float) (Math.sin(System.currentTimeMillis() * (Math.PI * 2.0 / SWING_PERIOD_MS)) * 25.0)
            : 0f;

        for (Player player : mc.level.players()) {
            if (player == mc.player && mc.options.getCameraType().isFirstPerson()) continue;
            if (player.isRemoved()) continue;

            double px = Mth.lerp(tickDelta, player.xo, player.getX()) - camPos.x;
            double py = Mth.lerp(tickDelta, player.yo, player.getY()) - camPos.y;
            double pz = Mth.lerp(tickDelta, player.zo, player.getZ()) - camPos.z;

            int light = LevelRenderer.getLightCoords(mc.level,
                BlockPos.containing(player.getX(), player.getY(), player.getZ()));

            matrices.pushPose();
            // Hip height + body (not head) yaw, matching the original's -rotationYaw
            // spin around Y before drawing along +Z (GLU cylinders extend along +Z,
            // which is also MC's yaw-0 forward).
            matrices.translate(px, py + player.getBbHeight() / 2.0 - 0.225, pz);
            matrices.mulPose(Axis.YP.rotationDegrees(-Mth.lerp(tickDelta, player.yBodyRotO, player.yBodyRot)));
            if (player.isShiftKeyDown()) matrices.mulPose(Axis.XP.rotationDegrees(35f));
            matrices.mulPose(Axis.XP.rotationDegrees(swing * 0.4f));
            matrices.mulPose(Axis.YP.rotationDegrees(swing));
            // The original GLU port placed the balls at z=-0.05 -- right at the pivot,
            // i.e. INSIDE the player's own body depth (a player's hitbox is ~0.3 blocks
            // deep, centered on this same pivot), so they visibly clipped into the torso/
            // leg mesh instead of hanging free in front of it. Push the whole assembly
            // forward (local +Z, already the body's facing direction from the yBodyRot
            // spin above) clear of that depth before drawing anything.
            matrices.translate(0f, 0f, 0.14f);
            matrices.scale(TZ_SCALE, TZ_SCALE, TZ_SCALE);

            PoseStack.Pose pose = matrices.last();

            // Shaft: GLU cylinder base r=0.1 -> top r=0.11, len 0.4, from z=0.075.
            emitCylinder(pose, c, light, overlay, 0.10f, 0.11f, 0.075f, 0.475f, 255, 140, 255);

            // Balls: spheres r=0.14, separated a bit more than the original's ±0.08
            // (0.16 apart vs 0.28 combined radius -- deeply fused into one blob) so they
            // read as two touching spheres instead of a single peanut-shaped lump.
            emitSphereAt(matrices, c, light, overlay, -0.105f, 0f, -0.05f, 0.14f, 255, 217, 255);
            emitSphereAt(matrices, c, light, overlay, 0.105f, 0f, -0.05f, 0.14f, 255, 217, 255);

            // Tip: sphere r=0.13 at (0, 0, 0.54).
            emitSphereAt(matrices, c, light, overlay, 0f, 0f, 0.54f, 0.13f, 255, 60, 60);

            if (CENSORED) {
                // A box's flat corners either poke out past the round shaft/balls or cut
                // into them (the "tấm đâm xuyên" complaint) since its cross-section is
                // square, not round. A cylindrical sleeve (radius 0.16, bigger than every
                // part it covers: shaft 0.11, balls 0.14, tip 0.13) sheathes the whole
                // model uniformly with no corners to clip through, from just behind the
                // balls to just past the tip.
                emitMosaicCylinder(pose, c, light, overlay, 0.16f, -0.20f, 0.68f, 20, 10);
            }

            matrices.popPose();
        }
    }

    private static void emitSphereAt(PoseStack matrices, VertexConsumer c, int light, int overlay,
                                     float x, float y, float z, float r, int cr, int cg, int cb) {
        matrices.pushPose();
        matrices.translate(x, y, z);
        matrices.scale(r, r, r);
        BubbleMesh.render(matrices.last(), c, light, overlay, cr, cg, cb, 255);
        matrices.popPose();
    }

    /** Open tube along +Z from z0 (radius r0) to z1 (radius r1); ends are covered by the spheres. */
    private static void emitCylinder(PoseStack.Pose pose, VertexConsumer c, int light, int overlay,
                                     float r0, float r1, float z0, float z1, int cr, int cg, int cb) {
        final int segments = 24;
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments;
            double a1 = Math.PI * 2.0 * (i + 1) / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            cylVertex(pose, c, light, overlay, c0 * r0, s0 * r0, z0, c0, s0, cr, cg, cb);
            cylVertex(pose, c, light, overlay, c1 * r0, s1 * r0, z0, c1, s1, cr, cg, cb);
            cylVertex(pose, c, light, overlay, c1 * r1, s1 * r1, z1, c1, s1, cr, cg, cb);
            cylVertex(pose, c, light, overlay, c0 * r1, s0 * r1, z1, c0, s0, cr, cg, cb);
        }
    }

    private static void cylVertex(PoseStack.Pose pose, VertexConsumer c, int light, int overlay,
                                  float x, float y, float z, float nx, float ny, int cr, int cg, int cb) {
        c.addVertex(pose, x, y, z).setColor(cr, cg, cb, 255).setUv(0f, 0f)
            .setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, 0f);
    }

    /**
     * Cylindrical mosaic sleeve of constant radius r from z0 to z1, wrapping fully
     * around the shaft/balls/tip -- circular cross-section so it never pokes past or
     * cuts into the round parts underneath (a box's flat corners did, at any radius
     * that tried to cover both the thin shaft and the wider balls). Each of the
     * segments x alongZ quads is a solid gray/black tile picked by hashing its own
     * grid cell -- a real pixel-mosaic checkerboard, not one flat block of color.
     */
    private static void emitMosaicCylinder(PoseStack.Pose pose, VertexConsumer c, int light, int overlay,
                                           float r, float z0, float z1, int segments, int alongZ) {
        for (int i = 0; i < alongZ; i++) {
            float za = z0 + (z1 - z0) * i / alongZ, zb = z0 + (z1 - z0) * (i + 1) / alongZ;
            for (int j = 0; j < segments; j++) {
                double a0 = Math.PI * 2.0 * j / segments;
                double a1 = Math.PI * 2.0 * (j + 1) / segments;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                int gray = mosaicGray(i, j);
                mosaicTile(pose, c, light, overlay, gray,
                    c0 * r, s0 * r, za, c1 * r, s1 * r, za, c1 * r, s1 * r, zb, c0 * r, s0 * r, zb,
                    c0, s0, 0);
            }
        }
    }

    /** Deterministic per-tile grayscale (mostly near-black, a few mid-gray) -- looks like a real censor mosaic, not solid black. */
    private static int mosaicGray(int i, int j) {
        int h = (i * 73856093) ^ (j * 19349663);
        h = (h ^ (h >>> 13)) * 0x27d4eb2f;
        int bucket = (h >>> 24) & 3; // 0..3
        return switch (bucket) {
            case 0 -> 8;
            case 1 -> 55;
            case 2 -> 20;
            default -> 90;
        };
    }

    private static void mosaicTile(PoseStack.Pose pose, VertexConsumer c, int light, int overlay, int gray,
                                   float ax, float ay, float az, float bx, float by, float bz,
                                   float dx, float dy, float dz, float ex, float ey, float ez,
                                   float nx, float ny, float nz) {
        c.addVertex(pose, ax, ay, az).setColor(gray, gray, gray, 255).setUv(0f, 0f).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
        c.addVertex(pose, bx, by, bz).setColor(gray, gray, gray, 255).setUv(1f, 0f).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
        c.addVertex(pose, dx, dy, dz).setColor(gray, gray, gray, 255).setUv(1f, 1f).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
        c.addVertex(pose, ex, ey, ez).setColor(gray, gray, gray, 255).setUv(0f, 1f).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
    }

    private static RenderType getLayer() {
        if (layer != null) return layer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/penisesp"))
            .withVertexShader(Identifier.fromNamespaceAndPath("example-addon", "core/bubble"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("example-addon", "core/penisesp"))
            .withVertexFormat(DefaultVertexFormat.ENTITY, VertexFormat.Mode.QUADS)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline).createRenderSetup();
        layer = RenderType.create("penisesp", setup);
        return layer;
    }
}
