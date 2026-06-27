package com.example.addon.modules;

import com.example.addon.screens.SkiaGlState;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.awt.Color;

/**
 * Real Skija backdrop blur for MusicHUD "Blur" mode, using the Skija 0.143.17
 * backend-texture interop added in HumbleUI/Skija#116.
 *
 * <p>The old 1.21.11 approach (Skija {@code saveLayer} with a backdrop blur filter,
 * drawing onto the live framebuffer via {@code bindSkiaFbo}) crashes in 26.1.2: doing
 * GPU-Skija during the GUI <em>extraction</em> phase conflicts with blaze3d's GL-state
 * ownership. Instead this <em>borrows</em> Minecraft's main render-target texture as a
 * Skia {@link Image} and blurs it with a Skia paint — the technique lyzev demonstrated
 * on 26.1/26.2 in that PR.
 *
 * <p>Crucially it runs at the <em>submission</em> phase (the {@code processBlurEffect}
 * hook, where MC itself runs its blur post-process and raw GL is valid and the
 * framebuffer is the near-final image), not during extraction — which is what makes
 * GPU-Skija here safe where the earlier attempt crashed.
 */
public final class SkijaBackdropBlur {

    public static final SkijaBackdropBlur INSTANCE = new SkijaBackdropBlur();

    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_TEXTURE_2D = GL11C.GL_TEXTURE_2D;

    // Panel rect in framebuffer pixels, TOP-LEFT origin (Skia user space).
    private float px, py, pw, ph, pr;
    private float blurSigma = 4f;
    private float darkness = 0.35f;
    private volatile boolean active = false;
    private boolean failed = false;

    private DirectContext ctx;
    private SkiaGlState glState;
    private int drawFbo = -1;
    private int stencilRbo = -1;
    private int attachedColorId = -1;
    private int fbW = 0, fbH = 0;

    private SkijaBackdropBlur() {}

    public boolean isActive() { return active && !failed; }

    /**
     * @param guiX/Y/W/H  panel rect in GUI (logical) pixels
     * @param cornerRadius  corner radius in GUI pixels
     * @param scaleFactor   GUI scale (logical→framebuffer pixels)
     * @param blurRadiusPx  Gaussian sigma in framebuffer pixels (Blur Intensity slider)
     * @param darkness      dark-tint alpha over the blurred backdrop (0..1)
     */
    public void setWidget(float guiX, float guiY, float guiW, float guiH,
                          float cornerRadius, float scaleFactor,
                          float blurRadiusPx, float darkness) {
        this.px = guiX * scaleFactor;
        this.py = guiY * scaleFactor;
        this.pw = guiW * scaleFactor;
        this.ph = guiH * scaleFactor;
        this.pr = cornerRadius * scaleFactor;
        this.blurSigma = Math.max(0.5f, blurRadiusPx);
        this.darkness = Math.max(0f, Math.min(1f, darkness));
        this.active = true;
    }

    /** Called from MixinGameRenderer.processBlurEffect (submission phase) when active. */
    public void render() {
        if (!active || failed) return;
        active = false;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainFb = mc.getMainRenderTarget();
        int w = mainFb.width, h = mainFb.height;

        GpuTexture colorTex = mainFb.getColorTexture();
        if (!(colorTex instanceof GlTexture glColor)) return;
        int mcColorId = glColor.glId();
        if (mcColorId <= 0) return;

        // Full GL state save/restore: Skija's drawing issues raw glViewport/glUseProgram/
        // glBindTexture/glBindVertexArray/blend calls. Restoring only the blend func (as
        // this used to) isn't enough — anything else MC renders right after this hook
        // (e.g. other GUI elements processed later in the same submission) can inherit
        // the leaked state.
        if (glState == null) glState = new SkiaGlState();
        glState.push();
        int savedFbo = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING);
        try {
            if (ctx == null) ctx = DirectContext.makeGL();
            ctx.resetAll();

            ensureTarget(mcColorId, w, h);

            // ── Borrow MC's main color texture as a read-only Skia image (the blur source) ──
            GLTextureInfo srcInfo = new GLTextureInfo(GL_TEXTURE_2D, mcColorId, GL_RGBA8);
            try (BackendTexture srcTex = BackendTexture.makeGL(w, h, false, srcInfo);
                 Image fbImage = Image.borrowTextureFrom(ctx, srcTex, SurfaceOrigin.BOTTOM_LEFT,
                         ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.getSRGB(), null);
                 // ── Wrap that same color texture (via our own FBO) as the Skia draw target ──
                 BackendRenderTarget rt = BackendRenderTarget.makeGL(w, h, 0, 8, drawFbo, GL_RGBA8);
                 Surface surface = Surface.wrapBackendRenderTarget(ctx, rt, SurfaceOrigin.BOTTOM_LEFT,
                         ColorType.RGBA_8888, ColorSpace.getSRGB(), null)) {

                if (fbImage == null || surface == null) return;
                Canvas canvas = surface.getCanvas();

                RRect panel = RRect.makeXYWH(px, py, pw, ph, pr);
                canvas.save();
                canvas.clipRRect(panel, ClipMode.INTERSECT, true);

                // Blur a region a bit larger than the panel so the Gaussian has real
                // neighbouring pixels (no dark edge fade); the clip trims it to the panel.
                float pad = blurSigma * 3f;
                float sx = Math.max(0, px - pad), sy = Math.max(0, py - pad);
                float ex = Math.min(w, px + pw + pad), ey = Math.min(h, py + ph + pad);
                Rect region = Rect.makeLTRB(sx, sy, ex, ey);

                try (Paint blurPaint = new Paint();
                     ImageFilter blur = ImageFilter.makeBlur(blurSigma, blurSigma, FilterTileMode.CLAMP)) {
                    blurPaint.setImageFilter(blur);
                    canvas.drawImageRect(fbImage, region, region, SamplingMode.LINEAR, blurPaint, true);
                }

                // Dark frosted tint over the blurred backdrop.
                try (Paint tint = new Paint()) {
                    tint.setColor(new Color(15, 15, 15, Math.round(darkness * 255f)).getRGB());
                    tint.setAntiAlias(true);
                    canvas.drawRRect(panel, tint);
                }

                canvas.restore();
                ctx.flushAndSubmit(false);
            }
        } catch (Throwable t) {
            System.err.println("[SkijaBackdropBlur] disabled after error: " + t);
            t.printStackTrace();
            failed = true;
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, savedFbo);
            glState.pop();
        }
    }

    /** (Re)creates our own FBO with MC's color texture + a stencil buffer (for AA clip). */
    private void ensureTarget(int mcColorId, int w, int h) {
        if (drawFbo != -1 && attachedColorId == mcColorId && fbW == w && fbH == h) return;
        destroyTarget();

        drawFbo = GL30C.glGenFramebuffers();
        stencilRbo = GL30C.glGenRenderbuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, drawFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, mcColorId, 0);
        GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, stencilRbo);
        GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER, GL30C.GL_STENCIL_INDEX8, w, h);
        GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT,
                GL30C.GL_RENDERBUFFER, stencilRbo);

        attachedColorId = mcColorId;
        fbW = w; fbH = h;
    }

    private void destroyTarget() {
        if (drawFbo != -1) { GL30C.glDeleteFramebuffers(drawFbo); drawFbo = -1; }
        if (stencilRbo != -1) { GL30C.glDeleteRenderbuffers(stencilRbo); stencilRbo = -1; }
        attachedColorId = -1;
        fbW = fbH = 0;
    }
}
