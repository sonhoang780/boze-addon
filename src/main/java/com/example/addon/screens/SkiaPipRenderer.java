package com.example.addon.screens;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders {@link SkiaPipState} elements with GPU Skija, registered into Minecraft's own
 * Picture-in-Picture mechanism ({@code GuiRenderState.addPicturesInPictureState}).
 *
 * <p>{@code prepare()} (in the base class) sets {@code RenderSystem.outputColorTextureOverride}
 * to this element's own private offscreen {@code GpuTextureView} before calling
 * {@link #renderToTexture}, then later blits that texture into the GUI batch at the
 * element's correctly-sorted Z position via the normal {@code BlitRenderState} path —
 * the SAME mechanism builtin renderers (entity heads, item previews, book pages) use.
 * That's what gives this Skija content correct Z-ordering relative to whatever Screen
 * is open, instead of the always-on-top behaviour of drawing at the literal end of frame.
 *
 * <p>We don't need access to the base class's private texture fields: we just read the
 * public {@code RenderSystem.outputColorTextureOverride} it already set, unwrap its GL
 * id (same {@code GlTexture.glId()} technique {@code SkijaBackdropBlur} uses), wrap our
 * own FBO + stencil renderbuffer around it, and draw with Skija exactly as before.
 * {@link #textureIsReadyToBlit} always returns false so every element redraws every
 * frame — there is no CPU raster step here to make caching worthwhile.
 */
public final class SkiaPipRenderer extends PictureInPictureRenderer<SkiaPipState> {

    /** The single registered instance (see the GameRenderer constructor mixin). */
    public static volatile SkiaPipRenderer ACTIVE;

    private static final int GL_RGBA8 = 0x8058;

    private DirectContext ctx;
    private SkiaGlState glState;
    private int fboId = -1, stencilRbo = -1;
    private int attachedGlId = -1, attachedW = -1, attachedH = -1;

    private static final class BorrowedImage {
        final Image image; final int glId;
        BorrowedImage(Image image, int glId) { this.image = image; this.glId = glId; }
    }
    private final Map<Identifier, BorrowedImage> borrowed = new HashMap<>();

    public SkiaPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
        ACTIVE = this;
    }

    @Override
    public Class<SkiaPipState> getRenderStateClass() { return SkiaPipState.class; }

    @Override
    protected String getTextureLabel() { return "skia_pip"; }

    @Override
    protected boolean textureIsReadyToBlit(SkiaPipState state) { return false; }

    @Override
    protected void renderToTexture(SkiaPipState state, PoseStack matrices) {
        GpuTextureView colorView = RenderSystem.outputColorTextureOverride;
        if (colorView == null) return;
        GpuTexture colorTex = colorView.texture();
        if (!(colorTex instanceof GlTexture glTex)) return;
        int glId = glTex.glId();
        if (glId <= 0) return;
        int pw = colorTex.getWidth(0), ph = colorTex.getHeight(0);
        if (pw <= 0 || ph <= 0) return;

        // Full GL state save/restore (not just the FBO binding): Skija's drawing issues
        // raw glViewport/glUseProgram/glBindTexture/glBindVertexArray/blend calls that
        // would otherwise leak into whichever PictureInPictureRenderer runs next in the
        // SAME preparePictureInPicture() pass (e.g. OversizedItemRenderer for an item
        // with a 3D-rendered icon) — that leaked state is what made some inventory items
        // render invisible right after MusicHUD's panel was prepared.
        if (glState == null) glState = new SkiaGlState();
        glState.push();
        int savedFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        try {
            if (ctx == null) ctx = DirectContext.makeGL();
            ctx.resetAll();
            ensureFbo(glId, pw, ph);
            if (fboId == -1) return;

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
            try (BackendRenderTarget rt = BackendRenderTarget.makeGL(pw, ph, 0, 8, fboId, GL_RGBA8);
                 Surface surface = Surface.wrapBackendRenderTarget(ctx, rt, SurfaceOrigin.BOTTOM_LEFT,
                     ColorType.RGBA_8888, ColorSpace.getSRGB(), null)) {
                if (surface == null) return;
                Canvas canvas = surface.getCanvas();
                canvas.clear(0);

                float scale = (float) Minecraft.getInstance().getWindow().getGuiScale();
                canvas.save();
                try {
                    canvas.scale(scale, scale);
                    canvas.translate(-state.x0(), -state.y0());
                    state.painter().accept(canvas);
                } finally {
                    canvas.restore();
                }
                ctx.flushAndSubmit(false);
            }
        } catch (Throwable t) {
            System.err.println("[SkiaPipRenderer] paint error: " + t);
            t.printStackTrace();
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, savedFbo);
            glState.pop();
        }
    }

    private void ensureFbo(int glId, int w, int h) {
        if (fboId != -1 && attachedGlId == glId && attachedW == w && attachedH == h) return;
        destroyFbo();

        fboId = GL30C.glGenFramebuffers();
        stencilRbo = GL30C.glGenRenderbuffers();
        int saved = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
            GL11C.GL_TEXTURE_2D, glId, 0);
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, stencilRbo);
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_STENCIL_INDEX8, w, h);
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT,
            GL30C.GL_RENDERBUFFER, stencilRbo);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, saved);

        attachedGlId = glId; attachedW = w; attachedH = h;
    }

    private void destroyFbo() {
        if (fboId != -1) { GL30C.glDeleteFramebuffers(fboId); fboId = -1; }
        if (stencilRbo != -1) { GL30C.glDeleteRenderbuffers(stencilRbo); stencilRbo = -1; }
        attachedGlId = attachedW = attachedH = -1;
    }

    /**
     * Borrows a Minecraft-managed GPU texture (resource PNG or DynamicTexture) as a
     * read-only Skija {@link Image} — zero copy, zero CPU. Must be called from within
     * a painter callback (i.e. while {@code ctx} is live).
     */
    public Image borrowTexture(Identifier id) {
        if (ctx == null || id == null) return null;
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(id);
        if (tex == null) return null;
        GpuTexture gpu = tex.getTexture();
        if (!(gpu instanceof GlTexture glTex)) return null;
        int glId = glTex.glId();
        if (glId <= 0) return null;

        BorrowedImage cached = borrowed.get(id);
        if (cached != null && cached.glId == glId) return cached.image;
        if (cached != null) cached.image.close();

        int w = gpu.getWidth(0), h = gpu.getHeight(0);
        GLTextureInfo info = new GLTextureInfo(GL11C.GL_TEXTURE_2D, glId, GL_RGBA8);
        try (BackendTexture bt = BackendTexture.makeGL(w, h, false, info)) {
            // UNPREMUL: Minecraft uploads PNG/NativeImage textures with straight alpha.
            Image img = Image.borrowTextureFrom(ctx, bt, SurfaceOrigin.TOP_LEFT,
                ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
            if (img == null) return null;
            borrowed.put(id, new BorrowedImage(img, glId));
            return img;
        }
    }
}
