package com.example.addon.modules.webbrowser.mcef;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * Ported from com.cinemamod.mcef.MCEFDirectTexture -- wraps a browser's already-uploaded
 * raw GL texture ID as an AbstractTexture (no re-upload/copy). class_1044 -> AbstractTexture,
 * class_10868 -> GlTexture (both verified via javap on this project's own minecraft-merged
 * jar: AbstractTexture.texture/.textureView fields, GlTexture.glId()/protected 8-arg ctor).
 */
public class MCEFDirectTexture extends AbstractTexture {
    private int width;
    private int height;

    public void setDirectTextureId(int textureId, int width, int height) {
        if (textureView != null) {
            textureView.close();
            textureView = null;
        }
        texture = null;
        if (textureId > 0) {
            texture = new DirectGlTexture(textureId, width, height);
            textureView = RenderSystem.getDevice().createTextureView(texture);
            this.width = width;
            this.height = height;
        } else {
            this.width = 0;
            this.height = 0;
        }
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    @Override
    public void close() {
        if (textureView != null) {
            textureView.close();
            textureView = null;
        }
        texture = null;
    }

    private static final class DirectGlTexture extends GlTexture {
        private final int width;
        private final int height;

        protected DirectGlTexture(int textureId, int width, int height) {
            super(4, "MCEF Direct Texture", TextureFormat.RGBA8, width, height, 1, 1, textureId);
            this.width = width;
            this.height = height;
            this.closed = false;
        }

        @Override
        public void close() {}

        @Override
        public int getWidth(int mipLevel) { return width >> mipLevel; }

        @Override
        public int getHeight(int mipLevel) { return height >> mipLevel; }
    }
}
