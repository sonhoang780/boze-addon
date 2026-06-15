package com.example.addon.shader;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30C.*;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.minecraft.client.MinecraftClient;

public class KawaseFramebuffer {

    public int id;
    public int texture;
    public final double sizeMulti;
    public int width, height;

    public KawaseFramebuffer(double sizeMulti) {
        this.sizeMulti = sizeMulti;
        init();
    }

    public KawaseFramebuffer() {
        this.sizeMulti = 1.0;
        init();
    }

    private void init() {
        var window = MinecraftClient.getInstance().getWindow();

        id = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, id);

        texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GlStateManager._pixelStore(GL_UNPACK_ALIGNMENT, 4);

        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        width  = (int)(window.getFramebufferWidth()  * sizeMulti);
        height = (int)(window.getFramebufferHeight() * sizeMulti);

        // Allocate texture memory — GL_RGB matching SoarClient
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, width, height, 0,
                     GL_RGB, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GlStateManager._glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                                               GL_TEXTURE_2D, texture, 0);

        // Unbind — trả về FBO 0 (screen default)
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void bind() {
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, id);
    }

    public void setViewport() {
        GlStateManager._viewport(0, 0, width, height);
    }

    /** Trả về FBO 0 — KawaseBlur.bindMainFramebuffer() sẽ bind đúng main FBO sau */
    public void unbind() {
        GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void resize() {
        GlStateManager._glDeleteFramebuffers(id);
        GlStateManager._deleteTexture(texture);
        init();
    }

    public void delete() {
        GlStateManager._glDeleteFramebuffers(id);
        GlStateManager._deleteTexture(texture);
    }
}