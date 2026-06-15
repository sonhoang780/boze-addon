package com.example.addon.shader;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL32C;

import com.mojang.blaze3d.opengl.GlStateManager;

public class KawasePostRenderer {

    private static final FullScreenMesh mesh = new FullScreenMesh();

    static {
        mesh.begin();
        mesh.quad(
            mesh.vec2(-1, -1).next(),
            mesh.vec2(-1,  1).next(),
            mesh.vec2( 1,  1).next(),
            mesh.vec2( 1, -1).next()
        );
        mesh.end();
    }

    public static void beginRender() { mesh.beginRender(); }
    public static void render()      { mesh.render(); }
    public static void endRender()   { mesh.endRender(); }

    private static class FullScreenMesh {

        private final int vao, vbo, ibo;

        private ByteBuffer vertices;
        private long verticesPointerStart, verticesPointer;
        private ByteBuffer indices;
        private long indicesPointer;
        private int vertexI, indicesCount;
        private boolean building, beganRendering;

        FullScreenMesh() {
            int stride = 8; // 2 floats × 4 bytes

            vertices = BufferUtils.createByteBuffer(stride * 256 * 4);
            verticesPointerStart = memAddress0(vertices);

            indices = BufferUtils.createByteBuffer(3 * 512 * 4);
            indicesPointer = memAddress0(indices);

            vao = GlStateManager._glGenVertexArrays();
            GlStateManager._glBindVertexArray(vao);

            vbo = GlStateManager._glGenBuffers();
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);

            ibo = GlStateManager._glGenBuffers();
            GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);

            GlStateManager._enableVertexAttribArray(0);
            GlStateManager._vertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);

            GlStateManager._glBindVertexArray(0);
            GlStateManager._glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        void begin() {
            if (building) return;
            verticesPointer = verticesPointerStart;
            vertexI = 0;
            indicesCount = 0;
            building = true;
        }

        FullScreenMesh vec2(double x, double y) {
            memPutFloat(verticesPointer,     (float) x);
            memPutFloat(verticesPointer + 4, (float) y);
            verticesPointer += 8;
            return this;
        }

        int next() { return vertexI++; }

        void quad(int i1, int i2, int i3, int i4) {
            long p = indicesPointer + indicesCount * 4L;
            memPutInt(p,      i1); memPutInt(p +  4, i2); memPutInt(p +  8, i3);
            memPutInt(p + 12, i3); memPutInt(p + 16, i4); memPutInt(p + 20, i1);
            indicesCount += 6;
        }

        void end() {
            if (!building) return;
            if (indicesCount > 0) {
                GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, vbo);
                GlStateManager._glBufferData(GL_ARRAY_BUFFER,
                    vertices.limit((int)(verticesPointer - verticesPointerStart)), GL_STATIC_DRAW);
                GlStateManager._glBindBuffer(GL_ARRAY_BUFFER, 0);

                GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
                GlStateManager._glBufferData(GL_ELEMENT_ARRAY_BUFFER,
                    indices.limit(indicesCount * 4), GL_STATIC_DRAW);
                GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
            }
            building = false;
        }

        void beginRender() {
            GlStateManager._disableCull();
            beganRendering = true;
        }

        void render() {
            if (building) end();
            if (indicesCount <= 0) return;

            // setDefaults() dalam SoarClient memanggil u_Proj dan u_ModelView
            // Blur shaders kita tidak pakai uniform ini — skip
            if (KawaseShader.BOUND != null) KawaseShader.BOUND.setDefaults();

            GlStateManager._glBindVertexArray(vao);
            GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
            GL32C.glDrawElements(GL32C.GL_TRIANGLES, indicesCount, GL_UNSIGNED_INT, 0);
            GlStateManager._glBindVertexArray(0);
            GlStateManager._glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        void endRender() {
            beganRendering = false;
        }
    }
}