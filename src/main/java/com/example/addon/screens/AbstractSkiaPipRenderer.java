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
 * Shared GPU-Skija rendering logic for {@link SkiaPaintedState} elements, registered
 * into Minecraft's own Picture-in-Picture mechanism ({@code GuiRenderState.addPicturesInPictureState}).
 *
 * <p><b>Why one concrete subclass + one state record type PER CONSUMER, instead of a
 * single shared renderer/state pair for everything:</b> {@code PictureInPictureRenderer}
 * holds exactly ONE shared {@code texture}/{@code textureView} field per renderer
 * INSTANCE (verified via javap on the 26.1.2 jar) -- {@code prepare()} renders the
 * current state into that shared texture and immediately queues a {@code BlitRenderState}
 * referencing that SAME texture VIEW OBJECT via {@code GuiRenderState.addBlitToCurrentLayer}.
 * That queued blit is a deferred draw command (for Z-order sorting against the rest of
 * the GUI), not an immediate pixel copy. When two DIFFERENT elements both dispatch
 * through the same renderer instance in the same frame (MC dispatches states to
 * renderers by matching {@code state.getClass()} to {@code renderer.getRenderStateClass()}),
 * the second element's render overwrites the shared texture's pixels before the first
 * element's already-queued blit is ever executed at draw time -- so both blits end up
 * showing whichever element rendered LAST. This was reproduced exactly as predicted:
 * MusicHUD (persistent HUD) and GifHUD's drop-shadow (also HUD, added later) both used
 * the same shared {@code SkiaPipRenderer}/{@code SkiaPipState}, and whichever rendered
 * second each frame stomped the other's content; EBookReader's page (screen-based,
 * visible alongside the persistent MusicHUD) did the same to both.
 *
 * <p>Each subclass below gets its own instance (own texture field) and its own state
 * record type (so MC's dispatch never conflates two consumers into one instance).
 */
public abstract class AbstractSkiaPipRenderer<T extends SkiaPaintedState> extends PictureInPictureRenderer<T> {

    private static final int GL_RGBA8 = 0x8058;

    private DirectContext ctx;
    private SkiaGlState glState;
    private int fboId = -1, stencilRbo = -1;
    private int attachedGlId = -1, attachedW = -1, attachedH = -1;

    private static final class BorrowedImage {
        final Image image; final int glId;
        BorrowedImage(Image image, int glId) { this.image = image; this.glId = glId; }
    }
    private final Map<Object, BorrowedImage> borrowed = new HashMap<>();

    protected AbstractSkiaPipRenderer(MultiBufferSource.BufferSource bufferSource) {
        super(bufferSource);
    }

    @Override
    protected boolean textureIsReadyToBlit(T state) { return false; }

    @Override
    protected void renderToTexture(T state, PoseStack matrices) {
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
            System.err.println("[" + getClass().getSimpleName() + "] paint error: " + t);
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
        return borrowFromGlId(id, glId, gpu.getWidth(0), gpu.getHeight(0), false);
    }

    /**
     * Same zero-copy borrow as {@link #borrowTexture(Identifier)}, for a raw GL texture
     * ID that ISN'T registered in Minecraft's TextureManager under any Identifier (e.g.
     * an externally-owned texture from a library like MCEF) — WebBrowserPipRenderer's
     * only consumer. {@code cacheKey} just needs to be stable/unique per source (the
     * external owner's own identity is fine, e.g. the MCEFBrowser instance).
     *
     * @param opaque true for a non-transparent MCEFBrowser -- its alpha byte isn't
     *               guaranteed to be 255 (CEF doesn't bother filling it meaningfully
     *               once the browser was created non-transparent), and reading it as
     *               real UNPREMUL alpha washed the whole page out translucent over
     *               whatever's behind the tile/screen. OPAQUE tells Skia to ignore the
     *               alpha byte entirely and treat every pixel as fully opaque.
     */
    public Image borrowTexture(Object cacheKey, int glId, int w, int h, boolean opaque) {
        if (ctx == null || cacheKey == null || glId <= 0) return null;
        return borrowFromGlId(cacheKey, glId, w, h, opaque);
    }

    private Image borrowFromGlId(Object cacheKey, int glId, int w, int h, boolean opaque) {
        BorrowedImage cached = borrowed.get(cacheKey);
        if (cached != null && cached.glId == glId) return cached.image;
        if (cached != null) cached.image.close();

        GLTextureInfo info = new GLTextureInfo(GL11C.GL_TEXTURE_2D, glId, GL_RGBA8);
        try (BackendTexture bt = BackendTexture.makeGL(w, h, false, info)) {
            // UNPREMUL: Minecraft uploads PNG/NativeImage textures with straight alpha.
            Image img = Image.borrowTextureFrom(ctx, bt, SurfaceOrigin.TOP_LEFT,
                ColorType.RGBA_8888, opaque ? ColorAlphaType.OPAQUE : ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
            if (img == null) return null;
            borrowed.put(cacheKey, new BorrowedImage(img, glId));
            return img;
        }
    }
}
