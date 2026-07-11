package com.example.addon.modules.webbrowser.mcef;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Ported from com.cinemamod.mcef.MCEFRenderer -- see CefUtil's class doc for why. */
public class MCEFRenderer {
    private final boolean transparent;
    private GpuTexture texture;
    private int textureWidth = 0;
    private int textureHeight = 0;
    private final Identifier textureLocation;
    private MCEFDirectTexture directTexture;
    private boolean textureRegistered = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureLocation = Identifier.fromNamespaceAndPath("mcef", "browser_" + uniqueId);
    }

    public void initialize() {
        directTexture = new MCEFDirectTexture();
        Minecraft.getInstance().getTextureManager().register(textureLocation, directTexture);
        textureRegistered = true;
    }

    public GpuTexture getTexture() { return texture; }
    public Identifier getTextureLocation() { return textureLocation; }
    public boolean isTextureReady() { return texture != null && textureRegistered && directTexture != null; }

    public int getTextureID() {
        if (texture instanceof GlTexture glTexture) return glTexture.glId();
        return 0;
    }

    public int getTextureWidth() { return textureWidth; }
    public int getTextureHeight() { return textureHeight; }
    public boolean isTransparent() { return transparent; }

    protected void cleanup() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (textureRegistered && textureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(textureLocation);
            textureRegistered = false;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        if (texture == null || textureWidth != width || textureHeight != height) {
            if (texture != null) texture.close();
            String label = "MCEF Browser Texture " + width + "x" + height;
            texture = RenderSystem.getDevice().createTexture(label, 5, TextureFormat.RGBA8, width, height, 1, 1);
            // GpuTexture lost setTextureFilter/setAddressMode in this API revision --
            // filter/wrap moved to a separate GpuSampler object consulted by Minecraft's
            // OWN render pipeline (RenderType/GpuTextureView draws), which this browser
            // texture never goes through: it's consumed via WebBrowserPipRenderer's
            // Skija zero-copy Image.borrowTextureFrom (raw GL id), not context.blit.
            // Set the params directly on the GL object instead -- a true GL-level
            // property every consumer (Skija included) actually respects.
            if (texture instanceof GlTexture glTex) {
                GlStateManager._bindTexture(glTex.glId());
                GlStateManager._texParameter(3553, 10241, 9729); // GL_TEXTURE_MIN_FILTER = GL_LINEAR
                GlStateManager._texParameter(3553, 10240, 9729); // GL_TEXTURE_MAG_FILTER = GL_LINEAR
                GlStateManager._texParameter(3553, 10242, 33071); // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
                GlStateManager._texParameter(3553, 10243, 33071); // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
            }
            textureWidth = width;
            textureHeight = height;
            if (directTexture != null && texture instanceof GlTexture glTexture) {
                directTexture.setDirectTextureId(glTexture.glId(), width, height);
            }
        }
        if (texture instanceof GlTexture glTexture) {
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._pixelStore(3314, width);
            GlStateManager._pixelStore(3316, 0);
            GlStateManager._pixelStore(3315, 0);
            GL12.glTexImage2D(3553, 0, 6408, width, height, 0, 32993, 33639, buffer);
        }
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        if (texture instanceof GlTexture glTexture) {
            GlStateManager._bindTexture(glTexture.glId());
            GL12.glTexSubImage2D(3553, 0, x, y, width, height, 32993, 33639, buffer);
        }
    }
}
