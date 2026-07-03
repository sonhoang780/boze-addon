package com.example.addon.screens;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Owns a single persistent Minecraft texture, updated each frame by copying a
 * source NativeImage into it and re-uploading (cheap GPU memcpy). Drawn via the
 * normal DrawContext, so it composites with the rest of the GUI without touching
 * the live GL framebuffer (which conflicts with Sodium / the 1.21.11 pipeline).
 */
public final class FrameTexture {

    private static int counter = 0;
    private final Identifier id;
    private DynamicTexture tex;
    private NativeImage owned;   // the texture's backing image (texture owns/closes it)
    private int width = 0, height = 0;
    private boolean registered = false;

    public FrameTexture(String key) {
        this.id = Identifier.fromNamespaceAndPath("bozemenu", key + "_" + (counter++));
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
                owned.copyFrom(src);
                tex = new DynamicTexture(() -> "bozemenu/" + id.getPath(), owned);
                Minecraft.getInstance().getTextureManager().register(id, tex);
                width = w; height = h; registered = true;
            } else {
                owned.copyFrom(src);
            }
            tex.upload();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Stretches the full texture into the destination rect (GUI units). */
    public void blit(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        if (!registered || width <= 0 || height <= 0) return;
        ctx.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, id,
            x, y, 0f, 0f, w, h, width, height, width, height);
    }

    /** Same as {@link #blit} but modulates the texture alpha (0=invisible, 1=opaque). */
    public void blit(GuiGraphicsExtractor ctx, int x, int y, int w, int h, float alpha) {
        if (!registered || width <= 0 || height <= 0 || alpha <= 0f) return;
        // 13-arg blit: last int is ARGB color tint (white + given alpha).
        int color = (Math.min(255, (int)(alpha * 255f)) << 24) | 0x00FFFFFF;
        ctx.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, id,
            x, y, 0f, 0f, w, h, width, height, width, height, color);
    }

    public void dispose() {
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(id);
            registered = false;
        }
        tex = null;
        owned = null;
        width = height = 0;
    }
}
