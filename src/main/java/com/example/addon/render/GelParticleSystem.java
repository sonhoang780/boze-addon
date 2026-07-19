package com.example.addon.render;

import com.example.addon.modules.BetterChams;
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
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Gel FillMode's Brownian noise particles: real per-part AABB (from
 * ModelPart.getExtentsForGui, driven by the entity's actual posed model -- see
 * MixinModelFeatureRenderer), a small pool of particles drifting inside each part's
 * box, plus a wireframe box per part (replaces the old shell-ring shader effect).
 *
 * update() (called from MixinModelFeatureRenderer's TAIL inject) does ONLY
 * physics/bounds bookkeeping -- no GL calls. Actual drawing happens later, from
 * renderAll() on the SAME LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN hook every other
 * world-space decal in this addon uses (AuraStep, TargetESP, Trails). Drawing used to
 * happen directly inside the mixin via bufferSource.getBuffer(RenderTypes.debugQuads()/
 * lines()) -- those are the SAME shared RenderType singletons vanilla's own debug
 * renderer (and Baritone's path renderer) use with their own buffer lifecycle,
 * independent of the per-entity-feature BufferSource ModelFeatureRenderer hands out;
 * pulling them from inside that nested pipeline stage hit a buffer already
 * ended/not-yet-started by that other code, crashing with
 * "IllegalStateException: Not building!" (2026-07-13 crash report). Every custom
 * world-space draw in this addon uses its OWN dedicated RenderPipeline/RenderType
 * instead (TargetESP's capturemark, AuraStep's water/fire layers) precisely to avoid
 * this class of collision -- Gel's particles/outline box now do the same.
 *
 * Coordinate space note: ModelPart.getExtentsForGui's consumer receives points ALREADY
 * transformed by whatever PoseStack it's given (verified against its bytecode --
 * lambda$getExtentsForGui$0 calls pose.pose().transformPosition(...) per vertex), and
 * that PoseStack (submit.pose(), passed in from MixinModelFeatureRenderer) is the
 * same camera-relative convention used throughout entity rendering this frame -- the
 * same convention AuraStep/TargetESP manually recreate by subtracting camPos from
 * absolute entity coordinates before feeding ctx.poseStack(). Stored particle/AABB
 * positions here are therefore already camera-relative and usable directly against
 * ctx.poseStack() in renderAll(), with no further camPos subtraction.
 */
public class GelParticleSystem {
    public static final GelParticleSystem INSTANCE = new GelParticleSystem();

    private static final float DOT_RADIUS = 0.0225f / 16f * 1.3f; // 2026-07-16: halved from 1/8, then +30% same day
    // Fixed particle count per body part (density slider removed) -- particles are
    // already per-part, not pooled/shared across the whole model OR across other
    // entities. A previous version gated spawning behind a GLOBAL_CAP shared by every
    // tracked entity's parts -- raising density then just made the shared budget fill
    // up faster, starving later entities (read as "getting sparser" even though
    // density went up, user report 2026-07-16). Removed: each part now always reaches
    // its own PARTICLES_PER_PART regardless of how many other entities are chammed.
    private static final int PARTICLES_PER_PART = 50;
    // Damped, not fully elastic -- an undamped bounce at max speed inside a thin box
    // (torso/arm Z-depth is a few cm) ping-pongs wall-to-wall almost every tick,
    // which is what produced a spiky look in an earlier version. Damping lets
    // particles settle into gentler drift instead of perpetual fast oscillation.
    private static final float BOUNCE_DAMP = 0.35f;
    // Keeps particles a little inside the box face instead of riding the exact edge.
    private static final float BOX_MARGIN = 0.12f;
    // An actively-rendered entity refreshes lastSeenNanos EVERY frame, so anything
    // quiet for a quarter second is genuinely gone (despawned/out of range) -- the
    // old 5s value kept a despawned dummy's outline hanging in the air for 5 whole
    // seconds even after the render-side sweep fix.
    private static final long STALE_NANOS = 250_000_000L; // 0.25s with no update -> drop entity
    // Crystal has no spectator/out-of-range flicker case (that's what the 250ms
    // player grace window exists for -- see the 3rd/1st person comment in
    // renderAll()) -- a destroyed crystal simply stops calling updateCrystal()
    // forever, so any positive grace period just leaves the wireframe cage
    // floating/spinning at its last pose for that long after the crystal is gone
    // (user clip, 2026-07-16: "outline complex thừa... xoay không bounce, tồn tại
    // thêm vài tích tắc"). One tick's worth is enough to absorb frame jitter.
    private static final long CRYSTAL_STALE_NANOS = 50_000_000L; // 0.05s

    // GelUuidCarrier's stashed UUID lives on the SHARED per-entity render state, read
    // by every renderModel call that fires for that state this frame -- not just the
    // one normal draw. When a 3rd-party ESP (Boze's own "Outline Complex") ALSO poses
    // and submits the same model this frame (its own highlight/x-ray render pass), our
    // TAIL inject fires again with THAT pass's PoseStack, which is not the usual
    // camera-relative world pose -- captured as a legit-looking but wildly wrong cube,
    // rendered as huge rays shooting across the screen (user clip, 2026-07-16: reported
    // specifically when BetterChams Complex + Boze ESP Complex are both on). Fix:
    // reject any captured geometry whose world position lands far from where the
    // entity ACTUALLY is (looked up by UUID) -- a legitimate part/cube is always within
    // a couple blocks of the entity's own position, a foreign pass's pose never is.
    private static final float MAX_PART_DIST_SQ = 16.0f;   // 4 blocks -- generous for a tall mob's head/feet spread
    private static final float MAX_CUBE_DIST_SQ = 9.0f;    // 3 blocks -- crystal cubes hug the entity origin tightly

    // The position check above only rejects a cube whose CENTER (mat's translation
    // column) is far from the entity -- it does NOT catch a cube whose center is fine
    // but whose rotation/scale (mat's 3x3 basis) is garbage. That's the actual shape
    // of the still-reported ray bug (2026-07-16 follow-up: rays persisted after the
    // position check alone): a foreign renderModel pass's pose can have a basis vector
    // stretched to a huge magnitude while its translation still lands near the real
    // entity, so the CENTER passes the distance check but every vertex reached by
    // transforming localMin/localMax through that stretched basis flies off to a huge
    // world coordinate -- rendered as a long thin ray from roughly the entity's own
    // position. A real model cube's basis vectors are always unit-length-ish (model
    // rotation/translation only, no legitimate scale beyond baby-mob ~0.5x or a giant
    // slightly-scaled boss); anything past this is never a real cube pose.
    private static final float MAX_BASIS_LEN_SQ = 64.0f; // 8x -- generous over any legit model scale

    private static boolean hasSaneScale(org.joml.Matrix4f mat) {
        float sxSq = mat.m00() * mat.m00() + mat.m10() * mat.m10() + mat.m20() * mat.m20();
        float sySq = mat.m01() * mat.m01() + mat.m11() * mat.m11() + mat.m21() * mat.m21();
        float szSq = mat.m02() * mat.m02() + mat.m12() * mat.m12() + mat.m22() * mat.m22();
        return Float.isFinite(sxSq) && Float.isFinite(sySq) && Float.isFinite(szSq)
            && sxSq <= MAX_BASIS_LEN_SQ && sySq <= MAX_BASIS_LEN_SQ && szSq <= MAX_BASIS_LEN_SQ;
    }

    // Direct, mechanism-agnostic guard on top of hasSaneScale/the position check:
    // measures the ACTUAL rendered diagonal of the captured cube (localMin to
    // localMax, through the real oc.mat) instead of inferring from basis magnitude.
    // Catches the same failure family regardless of root cause -- a foreign pass's
    // billboard rotation, a still-unidentified ghost entity, or anything else that
    // slips past the other checks -- because it measures the exact geometry that
    // would be drawn, not a proxy for it. No real body-part or crystal-frame cube
    // (raw model units, always under ~1.5 blocks) is anywhere close to this bound
    // (user clip, 2026-07-16: rays reaching into the sky while standing close to/
    // inside a model, still visible after the scale-magnitude guard alone).
    private static final float MAX_CUBE_DIAGONAL_SQ = 6.25f; // 2.5 blocks

    private static boolean hasSaneDiagonal(org.joml.Matrix4f mat, Vector3f localMin, Vector3f localMax) {
        Vector3f c0 = mat.transformPosition(localMin.x, localMin.y, localMin.z, new Vector3f());
        Vector3f c1 = mat.transformPosition(localMax.x, localMax.y, localMax.z, new Vector3f());
        if (!c0.isFinite() || !c1.isFinite()) return false;
        return c0.distanceSquared(c1) <= MAX_CUBE_DIAGONAL_SQ;
    }

    // Bigger-box-wrapping-the-real-box look from the reference image -- expand
    // outward by this fraction of each axis's own extent (not a fixed world size, so
    // a thin arm and a wide torso both get a proportionally similar-looking gap).
    private static final float OUTLINE_BOX_EXPAND = 0.10f; // was 0.28 -- user: gap too wide
    private static final float BOX_LINE_HALF_THICKNESS = 0.002f; // 1/4 of the old 0.008 (user: "làm mỏng lại còn 1/4")

    // Reference (2026-07-15, multi-angle): PLAYER Complex = one clean wireframe box
    // per body part, axis-aligned to that part's own orientation -- NO 45° facet, NO
    // nested double box (that earlier attempt was flat-out wrong). CRYSTAL Complex =
    // several interpenetrating cubes rotated relative to each other (the "gem/merkaba"
    // star), which is the ONLY place a rotation belongs. Connector struts (tried
    // 2026-07-14) were also wrong and removed.

    private static final Identifier GLOW_TEXTURE_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/gel_particle.png");
    private static final Identifier WHITE_TEXTURE_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/white.png");
    // Reused verbatim from TargetESP's capture-mark pipeline: samples Sampler0,
    // premultiplies by alpha (so the fade reads correctly with additive blending
    // regardless of what blend function the pipeline itself uses), discards near-zero
    // texels. Generic enough for both the glow dot and the flat white outline box --
    // only the bound texture and blend function differ between the two pipelines.
    private static final Identifier SHARED_FRAGMENT_SHADER_ID = Identifier.fromNamespaceAndPath("example-addon", "core/capturemark");
    private static RenderType glowLayer;
    private static RenderType boxLayer;
    private static RenderType boxGlowLayer;

    private static final String[] PART_NAMES = { "head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg" };

    // Comet-trail history length -- each particle keeps its last few positions so
    // movement leaves a tapering streak (bigger/brighter head, smaller/dimmer tail)
    // instead of a bare dot that pops between positions when the part moves (user
    // reference, 2026-07-16: "mỗi hạt particles luôn có 1 đuôi đằng sau như sao chổi,
    // hạt ở đầu luôn là hạt to nhất").
    private static final int TRAIL_LEN = 4;

    private static final class Particle {
        final Vector3f pos = new Vector3f();
        final Vector3f vel = new Vector3f();
        // trail[0] = most recent PAST position (one tick behind pos), trail[TRAIL_LEN-1]
        // = oldest. Initialized to the spawn position so the first frames don't flash
        // a streak from the origin -- see spawnOrTrim.
        final Vector3f[] trail = new Vector3f[TRAIL_LEN];
        Particle() {
            for (int i = 0; i < TRAIL_LEN; i++) trail[i] = new Vector3f();
        }
    }

    /**
     * One cube of a part, with the FULL pose matrix captured at render time --
     * unlike an AABB, this keeps the part's rotation, so the wireframe boxes tilt
     * with a nodding head / swinging arm exactly like the reference image
     * (2026-07-13: boxes there clearly follow each part's orientation; the
     * axis-aligned AABB version drew upright boxes on a tilted head). Local
     * min/max are in model-space units (Cube.minX..maxZ are raw 1/16-pixel units,
     * verified against Vertex.worldX()'s bytecode which divides by 16 -- so these
     * are pre-divided here once at capture).
     */
    private static final class OrientedCube {
        final org.joml.Matrix4f mat = new org.joml.Matrix4f();
        final Vector3f localMin = new Vector3f();
        final Vector3f localMax = new Vector3f();
    }

    private static final class PartState {
        // Shrunk (BOX_MARGIN) -- used to spawn/bounce particles, see updateBounds.
        final Vector3f min = new Vector3f();
        final Vector3f max = new Vector3f();
        boolean hasBounds = false;
        final java.util.List<Particle> particles = new java.util.ArrayList<>();
        // Oriented per-cube boxes for the outline wireframe (see OrientedCube).
        final java.util.List<OrientedCube> orientedCubes = new java.util.ArrayList<>();
        // Box center from the PREVIOUS frame -- see updateBounds's rigid-translate
        // comment (particle-drift-to-one-side fix).
        final Vector3f lastCenter = new Vector3f();
        boolean hasLastCenter = false;
    }

    private static final class EntityState {
        final Map<String, PartState> parts = new HashMap<>();
        long lastSeenNanos;
        long lastTickNanos;
        // Draw-freshness gate -- see CrystalState.frameStamp's comment. Simple mode
        // never has this class of bug because it has NO cross-frame geometry cache at
        // all (just a color marker vanilla's own outline pass re-resolves from scratch
        // every frame); Complex's cage is the one thing here with persistent state, so
        // it's the one thing that can go stale/foreign. Gating the DRAW (not just the
        // eventual sweep) on "captured this exact frame" closes that gap for players.
        long frameStamp = -1;
    }

    // Crystal Complex = wireframe boxes around the REAL EndCrystalModel cubes (the two
    // serrated frames outerGlass/innerGlass + the core `cube`), captured the same way
    // as a player's part cubes -- NOT a made-up box (user correction 2026-07-15: "bọc
    // outline cho 2 cục cube răng cưa bên ngoài và cả cục cube lõi bên trong"). No dust.
    private static final class CrystalState {
        final java.util.List<OrientedCube> cubes = new java.util.ArrayList<>();
        long lastSeenNanos;
        // Draw-freshness gate: renderAll() only draws `cubes` when frameStamp equals
        // the CURRENT frameCounter -- i.e. captured during the entity-render phase of
        // THIS exact frame, not any earlier one. This is the actual structural fix for
        // "why does Simple mode never show this bug": Simple has no persisted
        // geometry cache at all, it's fully re-derived from vanilla's own outline pass
        // every single frame, so there's no window for stale/foreign data to sit and
        // get drawn. Complex's cage is cached across frames (particles need that
        // continuity), which is the one thing that CAN go stale or get clobbered by a
        // foreign capture -- previously the only thing standing between a bad capture
        // and the screen was the staleness SWEEP (up to CRYSTAL_STALE_NANOS old), which
        // still drew whatever sat in the map for that whole window. Requiring
        // same-frame freshness to draw at all removes that window entirely (user
        // question, 2026-07-16: "vì sao Simple Mode không bị, chỉ Complex mới bị").
        long frameStamp = -1;
    }

    // Bumped once per renderAll() -- all updateCrystal calls between two renderAll
    // runs (i.e. within one frame's entity submits) see the same value.
    private static long frameCounter;

    private final Map<UUID, EntityState> perEntity = new HashMap<>();
    private final Map<UUID, CrystalState> perCrystal = new HashMap<>();
    private final Random random = new Random();

    static {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(ctx -> {
            if (BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.outlineMode.getValue() == BetterChams.OutlineMode.Complex) {
                INSTANCE.renderAll(ctx);
            }
        });
    }

    /** Physics/bounds only -- called from MixinModelFeatureRenderer's TAIL inject. No GL calls here; see renderAll(). */
    public void update(UUID entityId, HumanoidModel<?> model, PoseStack basePose) {
        // The entity must actually still be in the world to trust this call at all --
        // see trackedEntityPos's use in updateCrystal for why. Bailing BEFORE touching
        // lastSeenNanos means a truly-gone entity's state (if any) ages out via the
        // normal staleness sweep in renderAll() instead of being kept alive forever by
        // a foreign renderModel call that keeps refreshing it.
        Vector3f entityPos = trackedEntityPos(entityId);
        if (entityPos == null) return;

        long now = System.nanoTime();
        // Stale-entity sweeping lives in renderAll() (runs every frame regardless of
        // which entities still render), not here -- see its comment.
        EntityState es = perEntity.computeIfAbsent(entityId, k -> new EntityState());
        // Per-entity dt -- shared across all chammed entities used to mean entity B's
        // dt reflected the tiny gap since entity A's call moments earlier the SAME
        // frame rather than time since entity B was last ticked.
        float dt = es.lastTickNanos == 0L ? 0f : (float) Math.min(0.1, (now - es.lastTickNanos) / 1_000_000_000.0);
        es.lastTickNanos = now;
        es.lastSeenNanos = now;
        es.frameStamp = frameCounter;

        float speed = (float) (double) BetterChams.INSTANCE.gelSpeed.getValue();

        var camPosD = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        Vector3f camPos = new Vector3f((float) camPosD.x, (float) camPosD.y, (float) camPosD.z);

        for (String partName : PART_NAMES) {
            ModelPart part = partForName(model, partName);
            if (part == null) continue;
            PartState ps = es.parts.computeIfAbsent(partName, k -> new PartState());
            updateBounds(part, basePose, ps, camPos, entityPos);
            if (!ps.hasBounds) continue;
            spawnOrTrim(ps);
            tick(ps, dt, speed);
        }
    }

    /** World-absolute position of the actual tracked entity, or null if it's not currently loaded. */
    private static Vector3f trackedEntityPos(UUID id) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        var entity = level.getEntity(id);
        if (entity == null) return null;
        return new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
    }

    /**
     * Camera-RELATIVE position of the tracked entity, computed by subtracting camPos in
     * DOUBLE precision first, only casting the (small) result to float. 2026-07-18 fix,
     * "Complex mode outline méo xẹo ở toạ độ xa gốc" (user tested at x=19999998): the world-
     * absolute path above casts a huge coordinate straight to float, which loses several
     * units of precision at that magnitude BEFORE any subtraction happens -- two
     * independently-rounded huge floats (camera pos, entity pos) don't recover precision
     * when subtracted later. Doing the subtraction first, in double, then casting keeps the
     * result exact (it's small) regardless of how far from spawn the entity actually is.
     */
    private static Vector3f trackedEntityPosRelative(UUID id, Vec3 camPosD) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        var entity = level.getEntity(id);
        if (entity == null) return null;
        return new Vector3f((float) (entity.getX() - camPosD.x), (float) (entity.getY() - camPosD.y), (float) (entity.getZ() - camPosD.z));
    }

    /**
     * Crystal wireframe bookkeeping -- walks the REAL posed EndCrystalModel cubes (its
     * two serrated frames + core, exactly the geometry vanilla draws), keyed by the
     * crystal's UUID. Called from MixinModelFeatureRenderer's TAIL, where the model is
     * already posed/spun for this frame (same hook + timing as the player's update()).
     * No GL calls here; see renderAll().
     */
    public void updateCrystal(UUID crystalId, net.minecraft.client.model.object.crystal.EndCrystalModel model, PoseStack basePose) {
        // A dead/removed crystal is not in the level anymore -- level.getEntity(id)
        // returns null forever after that (never comes back). Bailing HERE, before
        // touching cs.lastSeenNanos, is what actually fixes the leftover cage: without
        // this, any foreign renderModel call still carrying this crystal's stashed
        // GelUuidCarrier UUID (e.g. Boze ESP redrawing a ghost at the last known
        // position) kept refreshing lastSeenNanos forever, defeating
        // CRYSTAL_STALE_NANOS entirely (user clip, 2026-07-16: cage still there after
        // the crystal visibly popped). Now a gone crystal simply stops updating and
        // ages out of the sweep within CRYSTAL_STALE_NANOS like normal.
        Vec3 camPosD = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        Vector3f entityPos = trackedEntityPosRelative(crystalId, camPosD);
        if (entityPos == null) return;

        // outerGlass ALONE -- and that is deliberate, not an omission.
        //
        // EndCrystalModel's parts are NESTED, not siblings (verified via javap on this
        // project's own remapped 26.1.2 jar, EndCrystalModel.<init>):
        //     root -> outer_glass  PartPose.offset(0, 24, 0)
        //             -> inner_glass  PartPose.ZERO, scale 0.875
        //                -> cube
        // and ModelPart.visit() recurses (translateAndRotate(pose) -> visit own cubes
        // -> children.forEach). So this ONE call already captures outerGlass AND
        // innerGlass AND cube, each through its correct parent chain -- covering the
        // "outline cho 2 cục cube răng cưa bên ngoài và cả cục cube lõi bên trong"
        // request in full.
        //
        // There used to be a second captureCubes(model.innerGlass, basePose, ...) call
        // here. THAT was the crystal ghost-cage bug: passing a CHILD part the ROOT
        // pose applies innerGlass's own transform (PartPose.ZERO + spin) directly to
        // the root, silently dropping outerGlass's y=24 + bob parent translate -- so
        // innerGlass and cube got captured a SECOND time down at y~0, right on the
        // base block. EndCrystalModel.setupAnim puts the bob exclusively on
        // outerGlass.y (`outerGlass.y += bob/2`) and gives innerGlass/cube rotation
        // only, which is exactly why that duplicate spun but never bounced -- matching
        // every reported symptom (user, 2026-07-16: "outline thừa này có xoay theo
        // crystal, nhưng không bounce theo crystal").
        java.util.List<OrientedCube> captured = new java.util.ArrayList<>();
        captureCubes(model.outerGlass, basePose, captured, entityPos);

        CrystalState cs = perCrystal.computeIfAbsent(crystalId, k -> new CrystalState());
        cs.lastSeenNanos = System.nanoTime();
        cs.cubes.clear();
        cs.cubes.addAll(captured);
        cs.frameStamp = frameCounter;
    }

    /**
     * Captures a part's cubes as CAMERA-RELATIVE OrientedCubes (2026-07-18 fix, "Complex
     * mode méo xẹo xa gốc toạ độ" -- see trackedEntityPosRelative's comment). oc.mat is
     * cubePose.pose() UNMODIFIED: vanilla's own pose is already camera-relative and small,
     * so there is nothing to "fold in" here -- the crystal cage is only ever drawn the SAME
     * frame it's captured (frameStamp-gated in renderAll), it never needs to survive a
     * camera move like particles do, so it never needed the world-absolute round-trip that
     * was breaking down at extreme coordinates. entityPos must be camera-relative too (same
     * frame's camPos already subtracted by the caller) for the distance check below to
     * compare like with like.
     */
    private static void captureCubes(ModelPart part, PoseStack basePose, java.util.List<OrientedCube> out, Vector3f entityPos) {
        part.visit(basePose, (cubePose, path, index, cube) -> {
            OrientedCube oc = new OrientedCube();
            oc.mat.set(cubePose.pose());
            if (!Float.isFinite(oc.mat.m30()) || !Float.isFinite(oc.mat.m31()) || !Float.isFinite(oc.mat.m32())) return;
            if (!hasSaneScale(oc.mat)) return; // see MAX_BASIS_LEN_SQ's comment (ray bug)
            // Sanity bound only -- reject a cube captured nowhere near the real
            // crystal. NOT the ghost-cage fix (that was a nested-part capture bug, see
            // updateCrystal); the ghost sat well within this radius, which is exactly
            // why no distance/scale guard here ever caught it. No absolute y-floor
            // either: one culled the real cage at the low point of the bob (user
            // correction, 2026-07-16).
            if (entityPos != null) {
                float dx = oc.mat.m30() - entityPos.x, dy = oc.mat.m31() - entityPos.y, dz = oc.mat.m32() - entityPos.z;
                if (dx * dx + dy * dy + dz * dz > MAX_CUBE_DIST_SQ) return;
            }
            oc.localMin.set(cube.minX / 16f, cube.minY / 16f, cube.minZ / 16f);
            oc.localMax.set(cube.maxX / 16f, cube.maxY / 16f, cube.maxZ / 16f);
            if (!hasSaneDiagonal(oc.mat, oc.localMin, oc.localMax)) return; // see MAX_CUBE_DIAGONAL_SQ's comment (ray bug)
            out.add(oc);
        });
    }

    private static ModelPart partForName(HumanoidModel<?> m, String name) {
        return switch (name) {
            case "head" -> m.head;
            case "body" -> m.body;
            case "rightArm" -> m.rightArm;
            case "leftArm" -> m.leftArm;
            case "rightLeg" -> m.rightLeg;
            case "leftLeg" -> m.leftLeg;
            default -> null;
        };
    }

    /**
     * basePose (submit.pose()) is CAMERA-RELATIVE -- it shifts every single frame as
     * the camera moves, even if the entity itself stands still (walking forward
     * moves the 3rd-person camera too). Bounds get recomputed fresh from it every
     * frame so that alone was fine, but stored PARTICLE positions/velocities persist
     * ACROSS frames -- reusing a camera-relative number cached from last frame as
     * this frame's coordinate silently added "how far the camera moved" as bogus
     * drift, which read as "particles moving in the direction I move" (user report,
     * 2026-07-13; not tied to the model at all, since it's actually decoupled from
     * where the model's silhouette gets drawn on screen). Fix: convert everything to
     * true WORLD-ABSOLUTE coordinates here (add camPos once), matching how
     * AuraStep/TargetESP treat entity positions -- renderAll() re-subtracts the
     * CURRENT frame's camPos right before building vertices, never caching that part.
     */
    private static void updateBounds(ModelPart part, PoseStack basePose, PartState ps, Vector3f camPos, Vector3f entityPos) {
        float[] min = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        float[] max = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        part.getExtentsForGui(basePose, corner -> {
            min[0] = Math.min(min[0], corner.x()); max[0] = Math.max(max[0], corner.x());
            min[1] = Math.min(min[1], corner.y()); max[1] = Math.max(max[1], corner.y());
            min[2] = Math.min(min[2], corner.z()); max[2] = Math.max(max[2], corner.z());
        });
        if (min[0] > max[0]) { ps.hasBounds = false; ps.orientedCubes.clear(); return; } // empty part (no cubes), e.g. hat

        // Reject a degenerate/garbage pose before it ever reaches rendering: a real
        // body part is under ~1 block on every axis, so NaN/Infinite corners or a
        // wildly oversized box (stale render state, bad matrix from an entity that
        // isn't actually being posed this frame) is never legitimate. Left unguarded
        // this produced huge wireframe lines radiating from a point and swinging with
        // the camera whenever it looked at empty space near no tracked entity (user
        // clip, 2026-07-14).
        boolean finite = Float.isFinite(min[0]) && Float.isFinite(min[1]) && Float.isFinite(min[2])
            && Float.isFinite(max[0]) && Float.isFinite(max[1]) && Float.isFinite(max[2]);
        if (!finite || (max[0] - min[0]) > 4f || (max[1] - min[1]) > 4f || (max[2] - min[2]) > 4f) {
            ps.hasBounds = false; ps.orientedCubes.clear(); return;
        }

        min[0] += camPos.x; max[0] += camPos.x;
        min[1] += camPos.y; max[1] += camPos.y;
        min[2] += camPos.z; max[2] += camPos.z;

        // Reject a normal-SIZED but wrongly-POSITIONED box -- the size guard above
        // only catches a wildly oversized box, not a plausible-sized one captured from
        // a foreign renderModel pass at a location nowhere near the real entity (Boze
        // ESP "Outline Complex" cross-talk, see MAX_PART_DIST_SQ's comment).
        if (entityPos != null) {
            float ccx = (min[0] + max[0]) * 0.5f, ccy = (min[1] + max[1]) * 0.5f, ccz = (min[2] + max[2]) * 0.5f;
            float dx = ccx - entityPos.x, dy = ccy - entityPos.y, dz = ccz - entityPos.z;
            if (dx * dx + dy * dy + dz * dz > MAX_PART_DIST_SQ) {
                ps.hasBounds = false; ps.orientedCubes.clear(); return;
            }
        }

        // Oriented cubes for the outline wireframe: visit() hands us each cube's
        // pose (with the part's full rotation, unlike the flattened AABB above)
        // plus its local extents. Cube.minX..maxZ are raw model-pixel units --
        // divide by 16 to match the pose matrix's space (same 1/16 Vertex.worldX()
        // applies before vanilla transforms polygon vertices by this same matrix).
        // NOT folding camPos in anymore (2026-07-18 fix, "Complex mode méo xẹo xa gốc
        // toạ độ" -- user tested at x=19999998): oc.mat is cubePose.pose() UNMODIFIED,
        // i.e. still camera-relative and small/precise, same as trackedEntityPosRelative's
        // comment explains for the crystal path. These cubes are only ever drawn the SAME
        // frame they're captured (frameStamp-gated in renderAll), so -- unlike particles,
        // which persist across frames and genuinely need world-absolute storage -- they
        // never needed the round-trip through a huge, lossy-at-extreme-coordinates float
        // translation in the first place.
        ps.orientedCubes.clear();
        part.visit(basePose, (cubePose, path, index, cube) -> {
            OrientedCube oc = new OrientedCube();
            oc.mat.set(cubePose.pose());
            // Same degenerate-pose guard as the part-level AABB above, applied per-cube:
            // a garbage matrix's translation column won't be finite.
            if (!Float.isFinite(oc.mat.m30()) || !Float.isFinite(oc.mat.m31()) || !Float.isFinite(oc.mat.m32())) return;
            if (!hasSaneScale(oc.mat)) return; // see MAX_BASIS_LEN_SQ's comment (ray bug)
            oc.localMin.set(cube.minX / 16f, cube.minY / 16f, cube.minZ / 16f);
            oc.localMax.set(cube.maxX / 16f, cube.maxY / 16f, cube.maxZ / 16f);
            if (!hasSaneDiagonal(oc.mat, oc.localMin, oc.localMax)) return; // see MAX_CUBE_DIAGONAL_SQ's comment (ray bug)
            ps.orientedCubes.add(oc);
        });

        // Shrink inward by BOX_MARGIN of each axis's extent -- see BOX_MARGIN's comment.
        float mx = (max[0] - min[0]) * BOX_MARGIN, my = (max[1] - min[1]) * BOX_MARGIN, mz = (max[2] - min[2]) * BOX_MARGIN;
        ps.min.set(min[0] + mx, min[1] + my, min[2] + mz);
        ps.max.set(max[0] - mx, max[1] - my, max[2] - mz);
        ps.hasBounds = true;

        // Rigid-translate existing particles by the box's own frame-to-frame motion
        // BEFORE this frame's Brownian tick/bounce runs. Particles are stored as
        // world-absolute state that persists across frames, but nothing was ever
        // moving them along with the entity's bulk motion (walking/strafing) --
        // only their own tiny Brownian jitter did. So walking right left them
        // behind in world space while the box (recomputed fresh from the entity
        // every frame) moved out from under them, and they'd pile up bounced
        // against the box's trailing wall -- "hạt bị dạt thẳng về 1 bên" (user
        // report, 2026-07-13). Translating them by the center's own delta first
        // makes them ride along with the body rigidly, with Brownian drift still
        // layered on top within the box exactly as before.
        Vector3f center = new Vector3f(ps.min).add(ps.max).mul(0.5f);
        if (ps.hasLastCenter) {
            Vector3f delta = new Vector3f(center).sub(ps.lastCenter);
            if (delta.lengthSquared() > 0f) {
                // Trail history must ride along too, same reason as pos itself
                // (comment above) -- otherwise walking stretches every particle's
                // comet tail into a long false streak lagging behind the body instead
                // of a short one from its own Brownian drift.
                for (Particle p : ps.particles) {
                    p.pos.add(delta);
                    for (Vector3f t : p.trail) t.add(delta);
                }
            }
        }
        ps.lastCenter.set(center);
        ps.hasLastCenter = true;
    }

    private void spawnOrTrim(PartState ps) {
        // Fixed count PER PART (user 2026-07-15/16: "density ... cho mỗi bộ phận,
        // không phải cả body", "không phải share toàn server") -- no cross-part or
        // cross-entity budget; every part always reaches PARTICLES_PER_PART on its own.
        int target = PARTICLES_PER_PART;

        while (ps.particles.size() < target) {
            Particle p = new Particle();
            p.pos.set(
                lerp(ps.min.x, ps.max.x, random.nextFloat()),
                lerp(ps.min.y, ps.max.y, random.nextFloat()),
                lerp(ps.min.z, ps.max.z, random.nextFloat())
            );
            for (Vector3f t : p.trail) t.set(p.pos); // no streak-from-origin on spawn
            ps.particles.add(p);
        }
        while (ps.particles.size() > target) {
            ps.particles.remove(ps.particles.size() - 1);
        }
    }

    private void tick(PartState ps, float dt, float speed) {
        if (dt <= 0f) return;
        for (Particle p : ps.particles) {
            // Push the CURRENT (pre-move) position into the trail history before
            // moving -- trail[0] becomes "one tick ago", shifting the rest back.
            for (int i = TRAIL_LEN - 1; i > 0; i--) p.trail[i].set(p.trail[i - 1]);
            p.trail[0].set(p.pos);

            p.vel.x += (random.nextFloat() - 0.5f) * speed * dt;
            p.vel.y += (random.nextFloat() - 0.5f) * speed * dt;
            p.vel.z += (random.nextFloat() - 0.5f) * speed * dt;
            float maxSpeed = speed * 0.5f + 0.01f;
            float len = p.vel.length();
            if (len > maxSpeed && len > 1e-5f) p.vel.mul(maxSpeed / len);

            p.pos.x += p.vel.x * dt;
            p.pos.y += p.vel.y * dt;
            p.pos.z += p.vel.z * dt;

            if (p.pos.x < ps.min.x || p.pos.x > ps.max.x) { p.vel.x = -p.vel.x * BOUNCE_DAMP; p.pos.x = clamp(p.pos.x, ps.min.x, ps.max.x); }
            if (p.pos.y < ps.min.y || p.pos.y > ps.max.y) { p.vel.y = -p.vel.y * BOUNCE_DAMP; p.pos.y = clamp(p.pos.y, ps.min.y, ps.max.y); }
            if (p.pos.z < ps.min.z || p.pos.z > ps.max.z) { p.vel.z = -p.vel.z * BOUNCE_DAMP; p.pos.z = clamp(p.pos.z, ps.min.z, ps.max.z); }
        }
    }

    // ── Rendering (LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN, safe world-space hook) ──

    private void renderAll(LevelRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        MultiBufferSource bufferSource = ctx.bufferSource();
        if (bufferSource == null) return;

        PoseStack.Pose pose = ctx.poseStack().last();
        Camera camera = mc.gameRenderer.getMainCamera();
        Vector3f camUp = new Vector3f(camera.upVector());
        Vector3f camLeft = new Vector3f(camera.leftVector());

        ClientColor fillTint = BetterChams.INSTANCE.fillColor.getValue().color;
        ClientColor outlineTint = BetterChams.INSTANCE.outlineTint.getValue().color;
        var camPosD = camera.position();
        Vector3f camPos = new Vector3f((float) camPosD.x, (float) camPosD.y, (float) camPosD.z);

        // Sweep HERE, every frame -- sweeping only inside update() meant a vanished
        // entity (dummy despawned, player out of range) left its outline/particles
        // frozen in the air forever, because update() only runs for entities still
        // being rendered: with no other chammed entity around, nothing ever swept the
        // dead state. (User repro 2026-07-13: dummy off -> outline stays; going 3rd
        // person chams SELF, whose update() call finally ran the sweep.) Also skip
        // (not just eventually sweep) anything not seen very recently, so leftovers
        // never draw even during the grace window.
        long now = System.nanoTime();
        perEntity.entrySet().removeIf(e -> now - e.getValue().lastSeenNanos > STALE_NANOS);

        // Self stops being posed the instant the camera goes first-person
        // (MixinAvatarRenderer's early return) -- but its entry only aged out via the
        // STALE_NANOS grace window above (250ms), so the box/dust visibly flashed for
        // a tick before vanishing on every 3rd->1st person switch (user report,
        // 2026-07-15). Self simply has no entry to draw while in first person, so drop
        // it immediately instead of waiting on staleness.
        if (mc.options.getCameraType().isFirstPerson()) {
            perEntity.remove(mc.player.getUUID());
        }

        // A crystal's cage must vanish the SAME frame the crystal dies -- any grace
        // window at all reads as a leftover cage floating over the explosion (user
        // report, 2026-07-16: "crystal và outline complex không mất đồng thời").
        // Existence check first (exact, same-frame); the staleness window stays only
        // as a fallback for states that stop updating without the entity dying.
        perCrystal.entrySet().removeIf(e -> {
            var ent = mc.level.getEntity(e.getKey());
            if (ent == null || ent.isRemoved()) return true;
            return now - e.getValue().lastSeenNanos > CRYSTAL_STALE_NANOS;
        });
        // NOTE: frameCounter is bumped at the END of this method, AFTER the draw loops
        // below compare es/cs.frameStamp against it -- update()/updateCrystal() calls
        // during this frame's entity-render phase (which runs BEFORE renderAll) stamped
        // the CURRENT (pre-increment) value, so the draw loops must still see that same
        // value here. Bumping it now would make every fresh capture this frame compare
        // as stale.

        // Two strictly SEQUENTIAL passes, one RenderType each. ctx.bufferSource() is
        // an immediate BufferSource: it only builds ONE buffer at a time, and
        // requesting a buffer for a DIFFERENT RenderType ends (flushes) the previous
        // one -- grabbing both consumers up front and interleaving writes left the
        // glow consumer pointing at an already-ended BufferBuilder ("Not building!"
        // crash on F5, 2026-07-13: first person has no chammed entities so nothing
        // was ever emitted; third person hit the dead consumer immediately).
        VertexConsumer glowVc = bufferSource.getBuffer(getGlowLayerXray());
        Vector3f relPos = new Vector3f();
        for (EntityState es : perEntity.values()) {
            for (PartState ps : es.parts.values()) {
                for (Particle p : ps.particles) {
                    // p.pos/p.trail are world-absolute (see updateBounds's comment) --
                    // subtract THIS frame's camPos to get this frame's camera-relative
                    // point. emitComet draws the tapering tail then the (biggest,
                    // brightest) head on top.
                    emitComet(glowVc, pose, p, camPos, relPos, camUp, camLeft, fillTint);
                }
            }
        }



        // Complex wireframe line thickness follows the Outline Radius slider (user
        // 2026-07-15). Two passes per box: a WIDE, dim, ADDITIVE bloom underlay (gives
        // the Complex outline the soft glow the Simple silhouette outline gets -- the
        // lines were flat/glow-less before) then the crisp line on top.
        float outlineRadius = (float) (double) BetterChams.INSTANCE.outlineRadius.getValue();
        float crispHalf = BOX_LINE_HALF_THICKNESS * outlineRadius;

        // The edge glow pass is the InnerGlow of Complex mode -- so it must read the
        // SAME glow options that drive glow_resolve.fsh's halo/rim, instead of
        // hardcoded numbers. First pass only wired Glow Intensity (alpha) -- Glow
        // Thickness still did nothing to the cage because bloomHalf (the ribbon's
        // WIDTH) was a flat crispHalf*4 constant, never reading the slider at all
        // (user report, 2026-07-16: "Glow Intensity có tác dụng, Glow Thickness thì
        // không"). glowThickness is a screen-space px radius (default 12, range
        // 1..64) -- normalize against that default so width scales the same
        // direction as the shader's rim (bigger Thickness = wider glow), keeping the
        // old visual at the default value.
        BetterChams bc = BetterChams.INSTANCE;
        float thicknessScale = (float) (double) bc.glowThickness.getValue() / 12.0f;
        float bloomHalf = crispHalf * 4.0f * thicknessScale;

        boolean edgeGlowOn = bc.glowOn() && bc.innerGlow.getValue();
        int bloomAlpha = edgeGlowOn ? Math.round(150f * (float) (double) bc.glowIntensity.getValue()) : 0;
        if (bloomAlpha > 0) {
            ClientColor glowTint = bc.outlineColor.getValue().color;
            VertexConsumer boxGlowVc = bufferSource.getBuffer(getBoxGlowLayer());
            for (EntityState es : perEntity.values()) {
                if (es.frameStamp != frameCounter) continue; // see EntityState.frameStamp's comment
                for (PartState ps : es.parts.values()) {
                    for (OrientedCube oc : ps.orientedCubes) {
                        renderOrientedBox(boxGlowVc, pose, oc, 0.0f, camPos, glowTint, bloomHalf, bloomAlpha, true);
                    }
                }
            }
            for (CrystalState cs : perCrystal.values()) {
                if (cs.frameStamp != frameCounter) continue; // see CrystalState.frameStamp's comment
                for (OrientedCube oc : cs.cubes) {
                    renderOrientedBox(boxGlowVc, pose, oc, 0.0f, camPos, glowTint, bloomHalf, bloomAlpha, true);
                }
            }
        }

        VertexConsumer boxVc = bufferSource.getBuffer(getBoxLayer());
        // Player: ONE clean box per part cube, axis-aligned to the part. No facet, no nest.
        for (EntityState es : perEntity.values()) {
            if (es.frameStamp != frameCounter) continue;
            for (PartState ps : es.parts.values()) {
                for (OrientedCube oc : ps.orientedCubes) {
                    renderOrientedBox(boxVc, pose, oc, 0.0f, camPos, outlineTint, crispHalf, 235, false);
                }
            }
        }
        // Crystal: one wireframe box per serrated frame (core cube skipped -- it sits
        // at model y=0 while the frames sit at y=24, so capturing it drew a stray box
        // down on the base block, "outline thừa ở dưới" user report 2026-07-15).
        for (CrystalState cs : perCrystal.values()) {
            if (cs.frameStamp != frameCounter) continue;
            for (OrientedCube oc : cs.cubes) {
                renderOrientedBox(boxVc, pose, oc, 0.0f, camPos, outlineTint, crispHalf, 235, false);
            }
        }

        frameCounter++;
    }

    /**
     * A real soft-glow sprite (dashbloom-style radial gradient texture, additive
     * blend) instead of a hand-built billboard shape -- same technique as TargetESP's
     * capture mark, just swapped to gel_particle.png and camera up/left vectors for
     * the billboard instead of a fixed yaw/pitch counter-rotation (2026-07-13 user
     * request: "tech giống TargetESP"). size/alpha parameterized (2026-07-16) so the
     * same sprite draws both the comet head and its shrinking/fading tail segments.
     */
    private static void emitGlowDot(VertexConsumer vc, PoseStack.Pose pose, Vector3f c, Vector3f camUp, Vector3f camLeft, ClientColor tint, float size, int alpha) {
        Vector3f u = new Vector3f(camUp).mul(size);
        Vector3f l = new Vector3f(camLeft).mul(size);
        Vector3f v0 = new Vector3f(c).sub(u).sub(l);
        Vector3f v1 = new Vector3f(c).sub(u).add(l);
        Vector3f v2 = new Vector3f(c).add(u).add(l);
        Vector3f v3 = new Vector3f(c).add(u).sub(l);
        int light = 15728880; // full-bright, same as TargetESP -- a glow reads the same regardless of ambient light
        int overlay = OverlayTexture.NO_OVERLAY;
        int r = tint.getRed(), g = tint.getGreen(), b = tint.getBlue();
        vc.addVertex(pose, v0.x, v0.y, v0.z).setColor(r, g, b, alpha).setUv(0.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v1.x, v1.y, v1.z).setColor(r, g, b, alpha).setUv(1.0F, 1.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v2.x, v2.y, v2.z).setColor(r, g, b, alpha).setUv(1.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
        vc.addVertex(pose, v3.x, v3.y, v3.z).setColor(r, g, b, alpha).setUv(0.0F, 0.0F).setOverlay(overlay).setLight(light).setNormal(pose, 0, 0, 1);
    }

    /**
     * Comet look: tail segments (oldest first -- smallest/dimmest) drawn behind, then
     * the head (current position) drawn biggest and full-alpha on top. Uses the
     * particle's own recent-position history (Particle.trail), not a separate
     * simulation -- so the tail always traces exactly where the dot actually was.
     */
    private static void emitComet(VertexConsumer vc, PoseStack.Pose pose, Particle p, Vector3f camPos,
                                   Vector3f relPos, Vector3f camUp, Vector3f camLeft, ClientColor tint) {
        for (int i = TRAIL_LEN - 1; i >= 0; i--) {
            float age = (i + 1f) / (TRAIL_LEN + 1f); // 0 = just behind head, 1 = oldest/tail tip
            float size = DOT_RADIUS * (1.0f - 0.65f * age);
            int alpha = Math.round(255 * (1.0f - age));
            relPos.set(p.trail[i]).sub(camPos);
            emitGlowDot(vc, pose, relPos, camUp, camLeft, tint, size, alpha);
        }
        relPos.set(p.pos).sub(camPos);
        emitGlowDot(vc, pose, relPos, camUp, camLeft, tint, DOT_RADIUS, 255);
    }

    /**
     * One oriented wireframe box (12 thin quads, own dedicated pipeline/texture --
     * see the class doc for why RenderTypes.lines()/debugQuads() crashed). All 4
     * corners of each edge-quad are built in the cube's LOCAL space and pushed
     * through the SAME oc.mat transform together, so the whole thin quad rotates
     * rigidly with the part.
     *
     * Thickness used to be computed in WORLD space via cross(edgeDir, camForward) --
     * camera-facing so line width looked consistent from any angle, but edgeDir
     * itself rotates with the part's animation (arm swing). Whenever a swinging
     * edge swept past being parallel to camForward, the cross product's magnitude
     * collapsed toward zero and the code fell back to a hardcoded (0,1,0)
     * perpendicular -- a visible instantaneous snap/flip that read as the outline
     * "stretching"/jaggy during the swing (user report, 2026-07-13). A LOCAL,
     * animation-independent perpendicular can never degenerate like that.
     */
    private static void renderOrientedBox(VertexConsumer vc, PoseStack.Pose pose, OrientedCube oc,
                                           float expand, Vector3f camPos, ClientColor tint, float t, int alpha, boolean gradient) {
        float cx = (oc.localMin.x + oc.localMax.x) * 0.5f;
        float cy = (oc.localMin.y + oc.localMax.y) * 0.5f;
        float cz = (oc.localMin.z + oc.localMax.z) * 0.5f;
        float hx = (oc.localMax.x - oc.localMin.x) * 0.5f * (1.0f + expand);
        float hy = (oc.localMax.y - oc.localMin.y) * 0.5f * (1.0f + expand);
        float hz = (oc.localMax.z - oc.localMin.z) * 0.5f * (1.0f + expand);

        // Every edge is a flat butt-capped ribbon offset +-t along its OWN
        // perpendicular axis, with NO corner miter -- at a shared corner (3 edges
        // meet), each ribbon's endpoint overshoots the geometric corner point by t in
        // its own perpendicular direction. That overshoot is invisible at small t, but
        // Outline Radius scales t with no relation to the box's own size, so on a
        // small part (head) at a high Radius the overshoot becomes a visible square
        // flare poking past the model at every corner (user screenshot, 2026-07-16).
        // Capping t to a fraction of the box's own smallest half-extent keeps the
        // overshoot imperceptibly small on any box, at any Radius setting, without a
        // full miter-joint implementation.
        float maxT = Math.min(hx, Math.min(hy, hz)) * 0.35f;
        t = Math.min(t, Math.max(maxT, 0.0005f));

        // Each edge gets its glow ribbon in TWO orthogonal local planes (a "+"
        // cross-section), not one: a single flat ribbon disappears whenever the
        // camera looks along its plane (seen edge-on its screen width collapses to
        // ~0), which is exactly the "some edges have no InnerGlow" report
        // (2026-07-16, circled screenshot) -- the circled edges were the ones being
        // viewed edge-on. With two perpendicular ribbons at least one always faces
        // the camera enough to read. Crisp pass (gradient=false) stays single-ribbon,
        // symmetric (a real wireframe line is centered ON the edge).
        //
        // The gradient (glow) ribbons are SIGNED to point INWARD (toward the box's own
        // center cx/cy/cz), one direction per corner instead of the same fixed (0,t,0)/
        // (0,0,t) for all 4 edges of a face regardless of which side of the box they're
        // on. The old fixed-direction version drew a full symmetric +-p "+" cross at
        // EVERY edge, so exactly half of that cross always pointed away from the model
        // into open air -- visibly wrong glow sticking out past the silhouette (user
        // screenshot, 2026-07-16: "inner glow bên ngoài model: sai"). emitLocalEdge's
        // gradient branch now draws a ONE-SIDED taper (centerline -> the signed point),
        // so with the correct inward sign every glow ribbon stays inside the box.
        // X-varying edges (perp along local Y and Z), at all 4 (y,z) corners.
        edgePair(vc, pose, oc, camPos, cx-hx,cy-hy,cz-hz, cx+hx,cy-hy,cz-hz, 0,t,0, 0,0,t, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx-hx,cy-hy,cz+hz, cx+hx,cy-hy,cz+hz, 0,t,0, 0,0,-t, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx-hx,cy+hy,cz-hz, cx+hx,cy+hy,cz-hz, 0,-t,0, 0,0,t, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx-hx,cy+hy,cz+hz, cx+hx,cy+hy,cz+hz, 0,-t,0, 0,0,-t, tint, alpha, gradient);
        // Y-varying edges (perp along local X and Z).
        edgePair(vc, pose, oc, camPos, cx-hx,cy-hy,cz-hz, cx-hx,cy+hy,cz-hz, t,0,0, 0,0,t, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx-hx,cy-hy,cz+hz, cx-hx,cy+hy,cz+hz, t,0,0, 0,0,-t, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx+hx,cy-hy,cz-hz, cx+hx,cy+hy,cz-hz, -t,0,0, 0,0,t, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx+hx,cy-hy,cz+hz, cx+hx,cy+hy,cz+hz, -t,0,0, 0,0,-t, tint, alpha, gradient);
        // Z-varying edges (perp along local X and Y).
        edgePair(vc, pose, oc, camPos, cx-hx,cy-hy,cz-hz, cx-hx,cy-hy,cz+hz, t,0,0, 0,t,0, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx-hx,cy+hy,cz-hz, cx-hx,cy+hy,cz+hz, t,0,0, 0,-t,0, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx+hx,cy-hy,cz-hz, cx+hx,cy-hy,cz+hz, -t,0,0, 0,t,0, tint, alpha, gradient);
        edgePair(vc, pose, oc, camPos, cx+hx,cy+hy,cz-hz, cx+hx,cy+hy,cz+hz, -t,0,0, 0,-t,0, tint, alpha, gradient);
    }

    /**
     * One edge, one SYMMETRIC ribbon for crisp (centered on the true edge -- see
     * renderOrientedBox's comment); two orthogonal ONE-SIDED ribbons for gradient,
     * each already signed to point inward by the caller.
     */
    private static void edgePair(VertexConsumer vc, PoseStack.Pose pose, OrientedCube oc, Vector3f camPos,
                                  float ax, float ay, float az, float bx, float by, float bz,
                                  float p1x, float p1y, float p1z, float p2x, float p2y, float p2z,
                                  ClientColor tint, int alpha, boolean gradient) {
        emitLocalEdge(vc, pose, oc, camPos, ax, ay, az, bx, by, bz, p1x, p1y, p1z, tint, alpha, gradient);
        if (gradient) {
            emitLocalEdge(vc, pose, oc, camPos, ax, ay, az, bx, by, bz, p2x, p2y, p2z, tint, alpha, true);
        }
    }

    /**
     * gradient=false: original flat single quad, constant alpha corner to corner --
     * used for the crisp core pass, which must stay sharp (see renderAll's comment on
     * why the crisp pass is excluded from the gradient).
     *
     * gradient=true (glow/bloom pass only): a per-EDGE InnerGlow-style linear falloff
     * across the line's OWN thickness -- bright at the centerline, fading linearly to
     * 0 at both outer edges of the quad's width. Split into two half-width quads
     * sharing the centerline column so GPU vertex-color interpolation does the actual
     * linear blend (same "mix(a,b,t)" math as glow_resolve.fsh's InnerGlow, just
     * applied across line thickness instead of screen-space silhouette depth). Fixes
     * "only a few edges look glowy" (user report, 2026-07-16): before this, an
     * isolated edge with no other edge overlapping it on screen had nothing to make
     * it read as glowing -- the flat quad's constant alpha only LOOKED bright where
     * many translucent edges happened to stack on top of each other (the crystal's
     * two interpenetrating frames, center of the merkaba). Every edge now carries its
     * own self-contained glow independent of overlap.
     */
    private static void emitLocalEdge(VertexConsumer vc, PoseStack.Pose pose, OrientedCube oc, Vector3f camPos,
                                       float ax, float ay, float az, float bx, float by, float bz,
                                       float px, float py, float pz, ClientColor tint, int alpha, boolean gradient) {
        int light = 15728880;
        int overlay = OverlayTexture.NO_OVERLAY;
        int r = tint.getRed(), g = tint.getGreen(), b2 = tint.getBlue();

        // NOT subtracting camPos anymore (2026-07-18 fix): oc.mat is camera-relative
        // already (see updateBounds/captureCubes' capture-time comments) -- subtracting
        // the huge world-absolute camPos here would now WRONGLY shift every vertex by the
        // camera's own position instead of correcting anything.
        if (!gradient) {
            Vector3f v0 = oc.mat.transformPosition(ax - px, ay - py, az - pz, new Vector3f());
            Vector3f v1 = oc.mat.transformPosition(ax + px, ay + py, az + pz, new Vector3f());
            Vector3f v2 = oc.mat.transformPosition(bx + px, by + py, bz + pz, new Vector3f());
            Vector3f v3 = oc.mat.transformPosition(bx - px, by - py, bz - pz, new Vector3f());
            vc.addVertex(pose, v0.x, v0.y, v0.z).setColor(r, g, b2, alpha).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            vc.addVertex(pose, v1.x, v1.y, v1.z).setColor(r, g, b2, alpha).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            vc.addVertex(pose, v2.x, v2.y, v2.z).setColor(r, g, b2, alpha).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            vc.addVertex(pose, v3.x, v3.y, v3.z).setColor(r, g, b2, alpha).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
            return;
        }

        // Small length-end inset only (down from 0.22 -- see edgePair's comment for
        // why the corner blob was actually a WRONG-DIRECTION-overlap bug, not a
        // length-reaches-the-vertex bug; this remaining sliver just avoids exact
        // z-fighting where multiple ribbons share the true corner point). User report
        // (2026-07-16): the old 0.22 inset made glow visibly vanish near every vertex.
        float insetFrac = 0.04f;
        float iax = ax + (bx - ax) * insetFrac, iay = ay + (by - ay) * insetFrac, iaz = az + (bz - az) * insetFrac;
        float ibx = bx - (bx - ax) * insetFrac, iby = by - (by - ay) * insetFrac, ibz = bz - (bz - az) * insetFrac;

        // ONE-SIDED taper: centerline (alpha=full) -> the signed p point (alpha=0).
        // px/py/pz already point INWARD (toward the box's own center) -- set by the
        // caller in renderOrientedBox, one sign per corner instead of a single fixed
        // direction shared by all 4 edges of a face. Drawing only this one side (not
        // the old symmetric -p..+p pair) is what keeps every glow ribbon inside the
        // model instead of half of it sticking out into open air (user screenshot,
        // 2026-07-16: "inner glow bên ngoài model: sai").
        Vector3f vAm = oc.mat.transformPosition(iax, iay, iaz, new Vector3f());
        Vector3f vA1 = oc.mat.transformPosition(iax + px, iay + py, iaz + pz, new Vector3f());
        Vector3f vB1 = oc.mat.transformPosition(ibx + px, iby + py, ibz + pz, new Vector3f());
        Vector3f vBm = oc.mat.transformPosition(ibx, iby, ibz, new Vector3f());

        vc.addVertex(pose, vAm.x, vAm.y, vAm.z).setColor(r, g, b2, alpha).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, vA1.x, vA1.y, vA1.z).setColor(r, g, b2, 0).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, vB1.x, vB1.y, vB1.z).setColor(r, g, b2, 0).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, vBm.x, vBm.y, vBm.z).setColor(r, g, b2, alpha).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(pose, 0, 1, 0);
    }

    /** Shared with Trails (same soft-glow dot sprite) -- one pipeline, two users. */
    public static RenderType getGlowLayer() {
        if (glowLayer != null) return glowLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/gel_particle"))
            .withFragmentShader(SHARED_FRAGMENT_SHADER_ID)
            .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", GLOW_TEXTURE_ID)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        glowLayer = RenderType.create("gel_particle", setup);
        return glowLayer;
    }

    /**
     * Dedicated pipeline for Gel's OWN particles -- NOT shared with Trails (getGlowLayer
     * above), because the fix needed here (no depth test) would wrongly change Trails'
     * behavior too if applied to the shared layer. The box wireframe (getBoxLayer/
     * getBoxGlowLayer) already draws through walls (ALWAYS_PASS) by design, but the
     * particle dots still used the depth-tested shared layer -- so most of a part's
     * particles (drifting through the part's actual volume, i.e. usually BEHIND the
     * model's own near-facing skin from outside) got depth-occluded by the real model
     * geometry. Standing outside read as "sparse" (only near-facing particles survive
     * the depth test); standing inside the model put the camera past nearly all of
     * that occluding geometry, so depth test trivially passed for everything and it
     * read as "dense" -- same particle count both times, only visibility changed (user
     * report, 2026-07-16). Matching the box layers' ALWAYS_PASS makes particle count
     * the only thing that determines density, consistently from any viewpoint.
     */
    private static RenderType glowLayerXray;
    private static RenderType getGlowLayerXray() {
        if (glowLayerXray != null) return glowLayerXray;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/gel_particle_xray"))
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
        glowLayerXray = RenderType.create("gel_particle_xray", setup);
        return glowLayerXray;
    }

    private static RenderType getBoxLayer() {
        if (boxLayer != null) return boxLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/gel_outline_box"))
            .withFragmentShader(SHARED_FRAGMENT_SHADER_ID)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            // ALWAYS (no depth test): every box edge draws through the model body and
            // through the box's own near faces -- the user wants ALL edges of each part
            // box visible at once, none hidden behind the body like a dashed back-edge
            // (2026-07-15). Depth WRITE stays off (2nd arg false) so it doesn't corrupt
            // the depth buffer for anything drawn after.
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", WHITE_TEXTURE_ID)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        boxLayer = RenderType.create("gel_outline_box", setup);
        return boxLayer;
    }

    /**
     * Soft glow underlay for the wireframe -- gives each Complex box edge a bloom.
     * TRANSLUCENT, not ADDITIVE: crystal's outerGlass+innerGlass are two big
     * near-overlapping boxes, so dozens of edge quads stack at the same screen pixels.
     * Additive blending SUMS every overlap, so OutlineColor (a pastel pink, all
     * channels already high) blew straight past 255 and clipped to solid white
     * ("Outline trắng" user report, 2026-07-15). Translucent composites instead of
     * summing -- repeated overlap converges toward the tint color, never overshoots it.
     */
    private static RenderType getBoxGlowLayer() {
        if (boxGlowLayer != null) return boxGlowLayer;
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("example-addon", "pipeline/gel_outline_box_glow"))
            .withFragmentShader(SHARED_FRAGMENT_SHADER_ID)
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withCull(false)
            .build();
        RenderSetup setup = RenderSetup.builder(pipeline)
            .withTexture("Sampler0", WHITE_TEXTURE_ID)
            .useLightmap()
            .useOverlay()
            .createRenderSetup();
        boxGlowLayer = RenderType.create("gel_outline_box_glow", setup);
        return boxGlowLayer;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
