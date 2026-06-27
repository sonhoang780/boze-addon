package com.example.addon.screens;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import com.mojang.blaze3d.platform.NativeImage;

import java.util.function.Consumer;

/**
 * Renders a Skija drawing into an OFFSCREEN raster (CPU) surface and copies the
 * pixels straight into a Minecraft NativeImage — no PNG encode/decode round-trip
 * (which was the bottleneck) and no GL context access (immune to Sodium issues).
 *
 * The returned NativeImage is the caller's to upload and then close.
 */
public final class SkijaOverlay {

    private SkijaOverlay() {}

    public static NativeImage render(int w, int h, Consumer<Canvas> drawer) {
        if (w <= 0 || h <= 0) return null;
        try (Surface surface = Surface.makeRasterN32Premul(w, h)) {
            Canvas canvas = surface.getCanvas();
            canvas.clear(0); // transparent
            drawer.accept(canvas);

            // Read back as straight (unpremultiplied) RGBA bytes — byte order R,G,B,A.
            ImageInfo info = new ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL);
            try (Bitmap bmp = new Bitmap()) {
                bmp.allocPixels(info);
                if (!surface.readPixels(bmp, 0, 0)) return null;
                byte[] px = bmp.readPixels();
                if (px == null) return null;

                NativeImage ni = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                // NativeImage's RGBA format stores pixels as R,G,B,A bytes in native
                // memory (little-endian ABGR int) — exactly Skija's RGBA_8888 byte
                // order. So we blast the whole pixel block straight into the native
                // buffer in one bulk copy instead of looping setPixel() per pixel,
                // which was the CPU bottleneck behind the per-frame raster cost.
                long ptr = ni.getPointer();
                int bytes = Math.min(px.length, w * h * 4);
                java.nio.ByteBuffer dst = org.lwjgl.system.MemoryUtil.memByteBuffer(ptr, bytes);
                dst.put(px, 0, bytes);
                return ni;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
