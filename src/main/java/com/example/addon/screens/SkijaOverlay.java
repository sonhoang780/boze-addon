package com.example.addon.screens;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import net.minecraft.client.texture.NativeImage;

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
                int p = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++, p += 4) {
                        int r = px[p]     & 0xFF;
                        int g = px[p + 1] & 0xFF;
                        int b = px[p + 2] & 0xFF;
                        int a = px[p + 3] & 0xFF;
                        ni.setColorArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                    }
                }
                return ni;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
