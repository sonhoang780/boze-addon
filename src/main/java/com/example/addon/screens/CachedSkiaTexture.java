package com.example.addon.screens;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Objects;
import java.util.function.Consumer;

import io.github.humbleui.skija.Canvas;

/**
 * A {@link FrameTexture} that only re-rasters its Skija content when a content
 * key (or the pixel size) actually changes.
 *
 * <p>Skija renders on the CPU (via {@link SkijaOverlay}); Minecraft's own GL
 * backend uploads the result as a texture and blits it through the normal GUI
 * pipeline. Nothing but Minecraft ever touches the GPU, so this is immune to the
 * GL-state-ownership conflict that hard-crashed the direct-GPU Skija approach in
 * the 26.1.2 deferred render pipeline (Skija {@code DirectContext} vs blaze3d
 * {@code GpuDevice}).
 *
 * <p>The 60→10fps regression came from doing the full raster + pixel readback +
 * GPU upload <em>every frame</em>. Here you pass a {@code key} describing the
 * current visual content; if it matches the previous render, that whole chain is
 * skipped and the cached texture is reused. Steady-state per-frame cost is then
 * just one blit.
 */
public final class CachedSkiaTexture {

    private final FrameTexture tex;
    private Object lastKey = null;
    private int lastW = -1, lastH = -1;

    public CachedSkiaTexture(String key) {
        this.tex = new FrameTexture(key);
    }

    /**
     * Re-rasters only if {@code (key, wPx, hPx)} differ from the last successful
     * render. {@code key} must capture everything that affects the drawn pixels
     * (size, colours, text, scale, animated values, …). Use {@code null}-safe
     * value types (String, boxed numbers) so {@link Objects#equals} compares by value.
     */
    public void render(int wPx, int hPx, Object key, Consumer<Canvas> drawer) {
        if (wPx <= 0 || hPx <= 0) return;
        if (tex.ready() && wPx == lastW && hPx == lastH && Objects.equals(key, lastKey)) {
            return; // cache hit — reuse the already-uploaded texture
        }
        NativeImage ni = SkijaOverlay.render(wPx, hPx, drawer);
        if (ni == null) return;
        boolean ok = tex.uploadNative(ni);
        ni.close();
        if (ok) { lastKey = key; lastW = wPx; lastH = hPx; }
    }

    public void blit(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        tex.blit(ctx, x, y, w, h);
    }

    public void blit(GuiGraphicsExtractor ctx, int x, int y, int w, int h, float alpha) {
        tex.blit(ctx, x, y, w, h, alpha);
    }

    public boolean ready() { return tex.ready(); }

    public void dispose() {
        tex.dispose();
        lastKey = null;
        lastW = lastH = -1;
    }
}
