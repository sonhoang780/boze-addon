package com.example.addon.screens;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.RRect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11C;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persistent GPU-Skija HUD layer, modelled on Lyzev/Skimi.
 *
 * <p>One {@link DirectContext} + {@link Surface} wrapping the default framebuffer
 * (FBO 0) are created once and reused every frame (recreated only on resize). All
 * registered {@link Drawer}s draw with GPU-Skija commands directly onto that surface
 * at the very end of the frame (just before the buffer swap), so there is NO CPU
 * raster, NO pixel readback and NO per-frame texture upload — the source of the FPS
 * drops in the old offscreen-raster-then-blit approach.
 *
 * <p>Each frame's draw is wrapped in {@link SkiaGlState#push()}/{@link SkiaGlState#pop()}
 * so Skija never corrupts Minecraft's own rendering.
 */
public final class SkiaHud {

    /** A consumer that paints onto the shared end-of-frame Skija canvas (framebuffer pixels). */
    public interface Drawer {
        void draw(DirectContext ctx, Canvas canvas, int fbW, int fbH);
    }

    private static final List<Drawer> DRAWERS = new CopyOnWriteArrayList<>();

    /** Temporary self-test: draws shapes to confirm the layer renders + MC stays intact. */
    public static boolean debugTest = false;

    private static DirectContext ctx;
    private static BackendRenderTarget rt;
    private static Surface surface;
    private static int width, height;
    private static SkiaGlState glState;
    private static boolean failed = false;

    private static final class BorrowedImage {
        final Image image;
        final int glId, w, h;
        BorrowedImage(Image image, int glId, int w, int h) { this.image = image; this.glId = glId; this.w = w; this.h = h; }
    }
    private static final Map<Identifier, BorrowedImage> BORROWED = new HashMap<>();

    private SkiaHud() {}

    public static void register(Drawer d) {
        if (d != null && !DRAWERS.contains(d)) DRAWERS.add(d);
    }

    public static void unregister(Drawer d) {
        DRAWERS.remove(d);
    }

    /** Called from MixinMinecraftClient at the end of the frame, before RenderSystem.flipFrame. */
    public static void onEndFrame() {
        if (failed) return;
        if (DRAWERS.isEmpty() && !debugTest) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getMainRenderTarget() == null) return;
        int w = mc.getMainRenderTarget().width;
        int h = mc.getMainRenderTarget().height;
        if (w <= 0 || h <= 0) return;

        try {
            ensure(w, h);
            if (surface == null) return;
            if (glState == null) glState = new SkiaGlState();

            glState.push();
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            ctx.resetGLAll();

            Canvas canvas = surface.getCanvas();
            if (debugTest) drawTest(canvas);
            for (Drawer d : DRAWERS) {
                // A drawer that throws between canvas.save() and canvas.restore() (e.g. a
                // mid-frame NPE from a not-yet-loaded texture) would otherwise leave the
                // canvas's save-stack permanently off-by-one — every subsequent frame's
                // transforms/clips then drift further, until content visibly flies off to
                // a corner. restoreToCount() unwinds back to this drawer's own pre-call
                // depth unconditionally, so one bad frame can never accumulate.
                int saveCount = canvas.getSaveCount();
                try {
                    d.draw(ctx, canvas, w, h);
                } catch (Throwable t) {
                    System.err.println("[SkiaHud] drawer error: " + t);
                    t.printStackTrace();
                } finally {
                    canvas.restoreToCount(saveCount);
                }
            }

            ctx.flushAndSubmit(false);
            glState.pop();
        } catch (Throwable t) {
            System.err.println("[SkiaHud] disabled after fatal error: " + t);
            t.printStackTrace();
            failed = true;
        }
    }

    private static void ensure(int w, int h) {
        if (ctx == null) ctx = DirectContext.makeGL();
        if (surface != null && width == w && height == h) return;
        if (surface != null) { surface.close(); surface = null; }
        if (rt != null) { rt.close(); rt = null; }
        // fbId 0 = the default framebuffer (the window's back buffer); GR_GL_RGBA8 = 0x8058.
        rt = BackendRenderTarget.makeGL(w, h, 0, 8, 0, 0x8058);
        surface = Surface.wrapBackendRenderTarget(ctx, rt, SurfaceOrigin.BOTTOM_LEFT,
                ColorType.RGBA_8888, ColorSpace.getSRGB());
        width = w; height = h;
    }

    /**
     * Borrows a Minecraft-managed GPU texture (a registered resource PNG or a
     * {@code DynamicTexture}) as a read-only Skija {@link Image}, with zero copy and
     * zero CPU involvement (HumbleUI/Skija#116 backend-texture interop). Cached per
     * {@link Identifier} and only re-wrapped if the underlying GL texture id changes
     * (e.g. a DynamicTexture re-registered under the same id after a track change).
     * Must be called from inside a {@link Drawer#draw} (i.e. while {@code ctx} is live).
     */
    public static Image borrowTexture(Identifier id) {
        if (ctx == null || id == null) return null;
        Minecraft mc = Minecraft.getInstance();
        AbstractTexture tex = mc.getTextureManager().getTexture(id);
        if (tex == null) return null;
        GpuTexture gpu = tex.getTexture();
        if (!(gpu instanceof GlTexture glTex)) return null;
        int glId = glTex.glId();
        if (glId <= 0) return null;

        BorrowedImage cached = BORROWED.get(id);
        if (cached != null && cached.glId == glId) return cached.image;

        if (cached != null) cached.image.close();
        int w = gpu.getWidth(0), h = gpu.getHeight(0);
        GLTextureInfo info = new GLTextureInfo(GL11C.GL_TEXTURE_2D, glId, 0x8058 /* GL_RGBA8 */);
        try (BackendTexture bt = BackendTexture.makeGL(w, h, false, info)) {
            // UNPREMUL: Minecraft uploads PNG/NativeImage textures with straight (not
            // premultiplied) alpha. Telling Skija PREMUL here is what made transparent
            // corners (e.g. vinyl.png's round mask) render as a solid white square instead
            // of clipping to a circle — alpha was correct, but treated as already
            // colour-premultiplied so the stored RGB leaked through regardless of alpha.
            Image img = Image.borrowTextureFrom(ctx, bt, SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
            if (img == null) return null;
            BORROWED.put(id, new BorrowedImage(img, glId, w, h));
            return img;
        }
    }

    /** Minimal proof-of-life: a translucent rounded panel + bright bar, top-left. */
    private static void drawTest(Canvas canvas) {
        try (Paint bg = new Paint(); Paint bar = new Paint()) {
            bg.setColor(0x99000000);
            bar.setColor(0xFF33DDFF);
            canvas.drawRRect(RRect.makeXYWH(20, 20, 360, 70, 12), bg);
            canvas.drawRRect(RRect.makeXYWH(34, 44, 120, 22, 6), bar);
        }
    }
}
