package com.example.addon.shader;

import static org.lwjgl.opengl.GL20C.*;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.lwjgl.BufferUtils;

import com.mojang.blaze3d.opengl.GlStateManager;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

public class KawaseShader {

    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);

    public static KawaseShader BOUND;

    private final int id;
    private final Object2IntMap<String> uniformLocations = new Object2IntOpenHashMap<>();
    private final String namespace;

    public KawaseShader(String namespace, String vertPath, String fragPath) {
        this.namespace = namespace;

        int vert = GlStateManager.glCreateShader(GL_VERTEX_SHADER);
        GlStateManager.glShaderSource(vert, read(vertPath));
        String vertErr = compile(vert);
        if (vertErr != null) throw new RuntimeException("Vert shader error (" + vertPath + "): " + vertErr);

        int frag = GlStateManager.glCreateShader(GL_FRAGMENT_SHADER);
        GlStateManager.glShaderSource(frag, read(fragPath));
        String fragErr = compile(frag);
        if (fragErr != null) throw new RuntimeException("Frag shader error (" + fragPath + "): " + fragErr);

        id = GlStateManager.glCreateProgram();
        GlStateManager.glAttachShader(id, vert);
        GlStateManager.glAttachShader(id, frag);
        GlStateManager.glLinkProgram(id);

        if (GlStateManager.glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE)
            throw new RuntimeException("Program link error: " + GlStateManager.glGetProgramInfoLog(id, 512));

        GlStateManager.glDeleteShader(vert);
        GlStateManager.glDeleteShader(frag);
    }

    private String read(String path) {
        try {
            return IOUtils.toString(
                MinecraftClient.getInstance().getResourceManager()
                    .getResource(Identifier.of(namespace, "shaders/" + path))
                    .orElseThrow(() -> new IllegalStateException("Shader not found: " + path))
                    .getInputStream(),
                StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new IllegalStateException("Could not read shader: " + path, e);
        }
    }

    private String compile(int shader) {
        GlStateManager.glCompileShader(shader);
        if (GlStateManager.glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE)
            return GlStateManager.glGetShaderInfoLog(shader, 512);
        return null;
    }

    public void bind() {
        GlStateManager._glUseProgram(id);
        BOUND = this;
    }

    private int loc(String name) {
        if (uniformLocations.containsKey(name)) return uniformLocations.getInt(name);
        int l = GlStateManager._glGetUniformLocation(id, name);
        uniformLocations.put(name, l);
        return l;
    }

    public void set(String name, int v)                { GlStateManager._glUniform1i(loc(name), v); }
    public void set(String name, double v)             { glUniform1f(loc(name), (float) v); }
    public void set(String name, double v1, double v2) { glUniform2f(loc(name), (float) v1, (float) v2); }

    // setDefaults() không cần thiết cho blur shaders — blur.vert dùng pos trực tiếp
    // Giữ lại rỗng để tương thích nếu KawasePostRenderer gọi
    public void setDefaults() { /* blur shaders không cần projection matrix */ }
}