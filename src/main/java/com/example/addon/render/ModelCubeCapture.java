package com.example.addon.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared per-cube geometry capture, extracted verbatim from GelParticleSystem's
 * crystal path so KillEffectParticleSystem can reuse the exact same sanity guards
 * (added after several real ray/ghost-cage bugs -- see the constants below) instead
 * of copying them. GelParticleSystem keeps its own inlined copies untouched: its two
 * capture paths differ subtly (the player path in updateBounds skips MAX_CUBE_DIST_SQ)
 * and it's a 1000-line battle-tuned file, so this is the canonical shared version for
 * NEW callers, not a forced refactor of the working one.
 *
 * Convention (identical to GelParticleSystem.captureCubes): oc.mat is cubePose.pose()
 * UNMODIFIED -- vanilla's own pose is already camera-relative and small/precise, so
 * there is nothing to fold in. entityPosRel (if non-null) must ALSO be camera-relative
 * (same frame's camPos subtracted) for the distance guard to compare like with like.
 */
public final class ModelCubeCapture {
    private ModelCubeCapture() {}

    // See GelParticleSystem for the full history behind each bound (ray bug, ghost cage).
    private static final float MAX_CUBE_DIST_SQ = 9.0f;      // 3 blocks -- cube center must hug the entity
    private static final float MAX_BASIS_LEN_SQ = 64.0f;     // 8x -- generous over any legit model scale
    private static final float MAX_CUBE_DIAGONAL_SQ = 6.25f; // 2.5 blocks -- actual rendered diagonal cap

    /** One captured cube: full camera-relative pose + model-space (already /16) extents. */
    public static final class OrientedCube {
        public final Matrix4f mat = new Matrix4f();
        public final Vector3f localMin = new Vector3f();
        public final Vector3f localMax = new Vector3f();
    }

    private static boolean hasSaneScale(Matrix4f mat) {
        float sxSq = mat.m00() * mat.m00() + mat.m10() * mat.m10() + mat.m20() * mat.m20();
        float sySq = mat.m01() * mat.m01() + mat.m11() * mat.m11() + mat.m21() * mat.m21();
        float szSq = mat.m02() * mat.m02() + mat.m12() * mat.m12() + mat.m22() * mat.m22();
        return Float.isFinite(sxSq) && Float.isFinite(sySq) && Float.isFinite(szSq)
            && sxSq <= MAX_BASIS_LEN_SQ && sySq <= MAX_BASIS_LEN_SQ && szSq <= MAX_BASIS_LEN_SQ;
    }

    private static boolean hasSaneDiagonal(Matrix4f mat, Vector3f localMin, Vector3f localMax) {
        Vector3f c0 = mat.transformPosition(localMin.x, localMin.y, localMin.z, new Vector3f());
        Vector3f c1 = mat.transformPosition(localMax.x, localMax.y, localMax.z, new Vector3f());
        if (!c0.isFinite() || !c1.isFinite()) return false;
        return c0.distanceSquared(c1) <= MAX_CUBE_DIAGONAL_SQ;
    }

    /** Walk one part's cubes, appending each sane OrientedCube to {@code out}. */
    public static void captureCubes(ModelPart part, PoseStack basePose, List<OrientedCube> out, Vector3f entityPosRel) {
        part.visit(basePose, (cubePose, path, index, cube) -> {
            OrientedCube oc = new OrientedCube();
            oc.mat.set(cubePose.pose());
            if (!Float.isFinite(oc.mat.m30()) || !Float.isFinite(oc.mat.m31()) || !Float.isFinite(oc.mat.m32())) return;
            if (!hasSaneScale(oc.mat)) return;
            if (entityPosRel != null) {
                float dx = oc.mat.m30() - entityPosRel.x, dy = oc.mat.m31() - entityPosRel.y, dz = oc.mat.m32() - entityPosRel.z;
                if (dx * dx + dy * dy + dz * dz > MAX_CUBE_DIST_SQ) return;
            }
            oc.localMin.set(cube.minX / 16f, cube.minY / 16f, cube.minZ / 16f);
            oc.localMax.set(cube.maxX / 16f, cube.maxY / 16f, cube.maxZ / 16f);
            if (!hasSaneDiagonal(oc.mat, oc.localMin, oc.localMax)) return;
            out.add(oc);
        });
    }

    /** All six standard humanoid parts of a posed model, camera-relative. */
    public static List<OrientedCube> captureHumanoid(HumanoidModel<?> model, PoseStack basePose, Vector3f entityPosRel) {
        List<OrientedCube> out = new ArrayList<>();
        ModelPart[] parts = { model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg };
        for (ModelPart p : parts) {
            if (p != null) captureCubes(p, basePose, out, entityPosRel);
        }
        return out;
    }
}
