package com.example.addon.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal OBJ loader. Parses v/vt/vn/f lines, triangulates fan polygons,
 * and emits degenerate quads (v0,v1,v2,v2) for QUADS-mode VertexConsumers.
 */
public class ObjMesh {

    // Per-vertex packed data: x,y,z, u,v, nx,ny,nz (8 floats)
    private final float[] data;
    private final int triangleCount;
    private final float minY;

    private ObjMesh(float[] data, int triangleCount, float minY) {
        this.data = data;
        this.triangleCount = triangleCount;
        this.minY = minY;
    }

    public static ObjMesh load(InputStream in) throws IOException {
        List<float[]> pos  = new ArrayList<>();
        List<float[]> uvs  = new ArrayList<>();
        List<float[]> nrms = new ArrayList<>();

        // Packed [posIdx, uvIdx, nrmIdx] per triangle-vertex
        List<int[]> verts = new ArrayList<>();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("v ")) {
                    String[] t = line.substring(2).trim().split("\\s+");
                    pos.add(new float[]{f(t[0]), f(t[1]), f(t[2])});
                } else if (line.startsWith("vt ")) {
                    String[] t = line.substring(3).trim().split("\\s+");
                    uvs.add(new float[]{f(t[0]), 1f - f(t[1])}); // flip V for OpenGL
                } else if (line.startsWith("vn ")) {
                    String[] t = line.substring(3).trim().split("\\s+");
                    nrms.add(new float[]{f(t[0]), f(t[1]), f(t[2])});
                } else if (line.startsWith("f ")) {
                    String[] tokens = line.substring(2).trim().split("\\s+");
                    int[][] fv = new int[tokens.length][3];
                    for (int i = 0; i < tokens.length; i++) {
                        String[] p = tokens[i].split("/");
                        fv[i][0] = Integer.parseInt(p[0]) - 1;
                        fv[i][1] = (p.length > 1 && !p[1].isEmpty()) ? Integer.parseInt(p[1]) - 1 : 0;
                        fv[i][2] = (p.length > 2 && !p[2].isEmpty()) ? Integer.parseInt(p[2]) - 1 : 0;
                    }
                    // Fan triangulation for quads/ngons
                    for (int i = 1; i < fv.length - 1; i++) {
                        verts.add(fv[0]);
                        verts.add(fv[i]);
                        verts.add(fv[i + 1]);
                    }
                }
            }
        }

        boolean hasUv  = !uvs.isEmpty();
        boolean hasNrm = !nrms.isEmpty();
        float   minY   = Float.MAX_VALUE;
        float[] data   = new float[verts.size() * 8];
        for (int i = 0; i < verts.size(); i++) {
            int[] ref  = verts.get(i);
            float[] p  = pos.get(ref[0]);
            float[] uv = hasUv  ? uvs.get(ref[1])  : new float[]{0f, 0f};
            float[] n  = hasNrm ? nrms.get(ref[2]) : new float[]{0f, 1f, 0f};
            int b = i * 8;
            data[b]   = p[0];  data[b+1] = p[1];  data[b+2] = p[2];
            data[b+3] = uv[0]; data[b+4] = uv[1];
            data[b+5] = n[0];  data[b+6] = n[1];  data[b+7] = n[2];
            if (p[1] < minY) minY = p[1];
        }

        return new ObjMesh(data, verts.size() / 3, minY == Float.MAX_VALUE ? 0f : minY);
    }

    /** Lowest Y vertex in model space. Use to lift model so feet touch ground. */
    public float getMinY() { return minY; }

    /** Emit all triangles as degenerate quads (v0,v1,v2,v2) for QUADS-mode consumers. */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay) {
        render(pose, consumer, light, overlay, 255);
    }

    /** Same as render() but with explicit alpha (0–255) for fade effects. */
    public void render(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int alpha) {
        for (int tri = 0; tri < triangleCount; tri++) {
            emit(pose, consumer, light, overlay, tri * 3,     alpha);
            emit(pose, consumer, light, overlay, tri * 3 + 1, alpha);
            emit(pose, consumer, light, overlay, tri * 3 + 2, alpha);
            emit(pose, consumer, light, overlay, tri * 3 + 2, alpha); // degenerate 4th vertex
        }
    }

    private void emit(PoseStack.Pose pose, VertexConsumer c, int light, int overlay, int vi, int alpha) {
        int b = vi * 8;
        c.addVertex(pose, data[b], data[b+1], data[b+2])
            .setColor(255, 255, 255, alpha)
            .setUv(data[b+3], data[b+4])
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(pose, data[b+5], data[b+6], data[b+7]);
    }

    private static float f(String s) { return Float.parseFloat(s); }

    public int getTriangleCount() { return triangleCount; }
}
