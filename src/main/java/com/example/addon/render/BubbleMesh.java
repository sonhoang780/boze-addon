package com.example.addon.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Procedurally generated unit UV-sphere (radius 1, centered at origin), emitted as
 * QUADS for an ENTITY-format VertexConsumer. Built once, reused for the big player
 * bubble and every detached small bubble (scaled via PoseStack). No OBJ file --
 * a sphere is 20 lines of trig, not an asset.
 */
public final class BubbleMesh {
    private static final int RINGS = 24;
    private static final int SEGMENTS = 32;

    // 4 corners per quad, 8 floats per vertex: pos(3) uv(2) normal(3)
    private static final float[] DATA;
    private static final int QUAD_COUNT = RINGS * SEGMENTS;

    static {
        DATA = new float[QUAD_COUNT * 4 * 8];
        int w = 0;
        for (int i = 0; i < RINGS; i++) {
            double theta0 = Math.PI * i / RINGS;
            double theta1 = Math.PI * (i + 1) / RINGS;
            for (int j = 0; j < SEGMENTS; j++) {
                double phi0 = 2.0 * Math.PI * j / SEGMENTS;
                double phi1 = 2.0 * Math.PI * (j + 1) / SEGMENTS;
                // quad corners: (theta0,phi0) (theta0,phi1) (theta1,phi1) (theta1,phi0)
                w = putVertex(w, theta0, phi0, i, j);
                w = putVertex(w, theta0, phi1, i, j + 1);
                w = putVertex(w, theta1, phi1, i + 1, j + 1);
                w = putVertex(w, theta1, phi0, i + 1, j);
            }
        }
    }

    private static int putVertex(int w, double theta, double phi, int ring, int seg) {
        float x = (float) (Math.sin(theta) * Math.cos(phi));
        float y = (float) Math.cos(theta);
        float z = (float) (Math.sin(theta) * Math.sin(phi));
        DATA[w++] = x; DATA[w++] = y; DATA[w++] = z;
        DATA[w++] = (float) seg / SEGMENTS;   // u
        DATA[w++] = (float) ring / RINGS;     // v
        DATA[w++] = x; DATA[w++] = y; DATA[w++] = z; // unit sphere: normal == position
        return w;
    }

    private BubbleMesh() {}

    /** Emit the whole sphere with a uniform RGBA tint (alpha carries fade). */
    public static void render(PoseStack.Pose pose, VertexConsumer c, int light, int overlay, int r, int g, int b2, int alpha) {
        renderBudded(pose, c, light, overlay, r, g, b2, alpha, 0, 0, 0, 0f, 0f);
    }

    /**
     * Same as {@link #render}, but bulges vertices near budDir (unit vector, mesh
     * local space) outward before they'd pinch off into a detached small bubble --
     * budAmount 0 = untouched sphere, 1 = fully pinched teardrop about to release.
     * The cone narrows and the bump sharpens as budAmount rises, so the surface
     * reads as a bud swelling then necking off, not just a static lump. bulgeHeight
     * is the peak bump size as a fraction of THIS mesh's own radius -- callers
     * should size it to match the eventual detached bubble's radius/thisRadius,
     * so the swell tops out at the size of the bubble it's about to become instead
     * of an unrelated fixed fraction of the whole sphere.
     */
    public static void renderBudded(PoseStack.Pose pose, VertexConsumer c, int light, int overlay,
                                     int r, int g, int b2, int alpha,
                                     double budDirX, double budDirY, double budDirZ, float budAmount, float bulgeHeight) {
        boolean budding = budAmount > 0.001f;
        double sharpness = 1.0 + budAmount * 4.0;
        for (int v = 0; v < QUAD_COUNT * 4; v++) {
            int b = v * 8;
            float px = DATA[b], py = DATA[b + 1], pz = DATA[b + 2];
            float nx = DATA[b + 5], ny = DATA[b + 6], nz = DATA[b + 7];
            if (budding) {
                double dot = px * budDirX + py * budDirY + pz * budDirZ;
                double cone = Math.max(0.0, (dot - 0.55) / 0.45); // smoothstep-ish falloff past ~55deg
                cone = cone * cone * (3.0 - 2.0 * cone);
                double bump = Math.pow(cone, sharpness) * budAmount * bulgeHeight;
                float scale = (float) (1.0 + bump);
                px *= scale; py *= scale; pz *= scale;
            }
            c.addVertex(pose, px, py, pz)
                .setColor(r, g, b2, alpha)
                .setUv(DATA[b + 3], DATA[b + 4])
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
        }
    }
}
