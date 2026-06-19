package com.example.addon.screens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

/**
 * Owns a single persistent Minecraft texture, updated each frame by copying a
 * source NativeImage into it and re-uploading (cheap GPU memcpy). Drawn via the
 * normal DrawContext, so it composites with the rest of the GUI without touching
 * the live GL framebuffer (which conflicts with Sodium / the 1.21.11 pipeline).
 */
public final class FrameTexture {

    private final Identifier id;
    private NativeImageBackedTexture tex;
    private NativeImage owned;   // the texture's backing image (texture owns/closes it)
    private int width = 0, height = 0;
    private boolean registered = false;

    public FrameTexture(String key) {
        this.id = Identifier.of("bozemenu", key);
    }

    public int width()     { return width; }
    public int height()    { return height; }
    public boolean ready() { return registered; }

    /**
     * Copies {@code src} into this texture's backing image and uploads it.
     * {@code src} is NOT taken over — the caller still owns it.
     */
    public boolean uploadNative(NativeImage src) {
        if (src == null) return false;
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (tex == null || width != w || height != h) {
                // (re)create at the new size; registering replaces+closes the old texture.
                owned = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                tex = new NativeImageBackedTexture(() -> "bozemenu/" + id.getPath(), owned);
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
                width = w; height = h; registered = true;
            }
            owned.copyFrom(src);
            tex.upload();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Stretches the full texture into the destination rect (GUI units). */
    public void blit(DrawContext ctx, int x, int y, int w, int h) {
        if (!registered || width <= 0 || height <= 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id,
            x, y, 0f, 0f, w, h, width, height, width, height);
    }

    /** Same as {@link #blit} but modulates the texture alpha (0=invisible, 1=opaque). */
    public void blit(DrawContext ctx, int x, int y, int w, int h, float alpha) {
        if (!registered || width <= 0 || height <= 0 || alpha <= 0f) return;
        // 13-arg drawTexture: last int is ARGB color tint (white + given alpha).
        int color = (Math.min(255, (int)(alpha * 255f)) << 24) | 0x00FFFFFF;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id,
            x, y, 0f, 0f, w, h, width, height, width, height, color);
    }

    public void dispose() {
        if (registered) {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
            registered = false;
        }
        tex = null;
        owned = null;
        width = height = 0;
    }
}
