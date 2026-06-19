package com.example.addon.modules;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/**
 * Performs the MusicHUD liquid-glass render pass using MC 1.21's GPU API —
 * the same technique used by ReGlass (github.com/RedxAx/ReGlass).
 *
 * Lifecycle per frame:
 *   1. MusicHUD.drawBackground() calls setWidget() to register the widget rect.
 *   2. GameRenderer.renderBlur() mixin calls render() which runs:
 *        blur-X  →  blur-Y  →  glass refraction pass (writes to main FBO).
 *   3. HudRenderCallback continues, drawing Skia decorative layers (shadow, rim, glint)
 *      on top of the refracted glass.
 */
public final class LiquidGlassHud {

    public static final LiquidGlassHud INSTANCE = new LiquidGlassHud();

    // Widget rect in FBO pixels (y = 0 at bottom).  Set by MusicHUD each frame.
    private float wx, wy, ww, wh, wcr;
    private volatile boolean active = false;
    private boolean resourcesFailed = false;

    // Blur radius (pixels).  Matches a "light frosted" look; 0 = pure refraction.
    private static final int BLUR_RADIUS = 6;
    private static final int MAX_BLUR_TAPS = 64;

    // Uniform buffer flags: MAP_WRITE + UNIFORM
    private static final int UBO_USAGE = GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_UNIFORM;
    // Texture usage: readable as sampler + writeable as render target
    private static final int TEX_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

    // GPU resources
    private RenderPipeline glassPipeline;
    private RenderPipeline blurPipeline;

    private GpuBuffer quadVB;            // static fullscreen quad vertex buffer
    private GpuBuffer samplerInfoUbo;    // vec2 OutSize + vec2 InSize = 16 bytes
    private GpuBuffer widgetUniformsUbo; // vec4 + 4 floats = 32 bytes
    private GpuBuffer blurConfigUboX;
    private GpuBuffer blurConfigUboY;

    private GpuTexture blurTempTex;      // intermediate H-blur target
    private GpuTextureView blurTempView;
    private GpuTexture blurredTex;       // final blurred texture (Sampler0 in glass pass)
    private GpuTextureView blurredView;

    private LiquidGlassHud() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called from MusicHUD.drawBackground() before the Skia decorative layers.
     * guiX/Y/W/H are in GUI (logical) pixels; scaleFactor converts to FBO pixels.
     */
    public void setWidget(float guiX, float guiY, float guiW, float guiH,
                          float cornerRadius, float scaleFactor, int fbH) {
        float fbW   = guiW * scaleFactor;
        float fbHpx = guiH * scaleFactor;
        float fbX   = guiX * scaleFactor;
        // GUI y=0 at top; FBO y=0 at bottom
        float fbY   = fbH - (guiY * scaleFactor) - fbHpx;
        this.wx  = fbX;
        this.wy  = fbY;
        this.ww  = fbW;
        this.wh  = fbHpx;
        this.wcr = cornerRadius * scaleFactor;
        this.active = true;
    }

    public boolean isActive() { return active && !resourcesFailed; }

    /** Called from the GameRenderer mixin; executes the GPU passes. */
    public void render() {
        if (!active || resourcesFailed) return;
        active = false;

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        int w = mainFb.textureWidth;
        int h = mainFb.textureHeight;

        try {
            ensureResources(w, h);
            uploadSamplerInfo(w, h);
            uploadWidgetUniforms();

            GpuSampler linearSampler = RenderSystem.getSamplerCache().get(
                    com.mojang.blaze3d.textures.FilterMode.LINEAR);

            var ce      = RenderSystem.getDevice().createCommandEncoder();
            var idxInfo = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
            var ib      = idxInfo.getIndexBuffer(4);
            var it      = idxInfo.getIndexType();

            // ── Pass 1: Horizontal Gaussian blur (mainFb → blurTempTex) ──
            uploadBlurConfig(blurConfigUboX, 1f, 0f);
            try (RenderPass pass = ce.createRenderPass(
                    () -> "musichud blur-X", blurTempView, OptionalInt.empty())) {
                pass.setPipeline(blurPipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("SamplerInfo", samplerInfoUbo);
                pass.setUniform("Config",      blurConfigUboX);
                pass.bindTexture("DiffuseSampler", mainFb.getColorAttachmentView(), linearSampler);
                pass.setVertexBuffer(0, quadVB);
                pass.setIndexBuffer(ib, it);
                pass.drawIndexed(0, 0, 6, 1);
            }

            // ── Pass 2: Vertical Gaussian blur (blurTempTex → blurredTex) ──
            uploadBlurConfig(blurConfigUboY, 0f, 1f);
            try (RenderPass pass = ce.createRenderPass(
                    () -> "musichud blur-Y", blurredView, OptionalInt.empty())) {
                pass.setPipeline(blurPipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("SamplerInfo", samplerInfoUbo);
                pass.setUniform("Config",      blurConfigUboY);
                pass.bindTexture("DiffuseSampler", blurTempView, linearSampler);
                pass.setVertexBuffer(0, quadVB);
                pass.setIndexBuffer(ib, it);
                pass.drawIndexed(0, 0, 6, 1);
            }

            // ── Pass 3: Liquid-glass refraction (blurredTex → mainFb, interior only) ──
            try (RenderPass pass = ce.createRenderPass(
                    () -> "musichud liquid-glass",
                    mainFb.getColorAttachmentView(),
                    OptionalInt.empty(),
                    mainFb.useDepthAttachment ? mainFb.getDepthAttachmentView() : null,
                    OptionalDouble.empty())) {
                pass.setPipeline(glassPipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("SamplerInfo",    samplerInfoUbo);
                pass.setUniform("WidgetUniforms", widgetUniformsUbo);
                pass.bindTexture("Sampler0",      blurredView, linearSampler);
                pass.setVertexBuffer(0, quadVB);
                pass.setIndexBuffer(ib, it);
                pass.drawIndexed(0, 0, 6, 1);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            resourcesFailed = true;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void ensureResources(int w, int h) {
        if (glassPipeline == null) {
            glassPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.of("musichud", "pipeline/liquid_glass_hud"))
                    .withVertexShader(Identifier.of("musichud", "core/blit_fullscreen"))
                    .withFragmentShader(Identifier.of("musichud", "program/liquid_glass_hud"))
                    .withUniform("Projection",     UniformType.UNIFORM_BUFFER)
                    .withUniform("SamplerInfo",    UniformType.UNIFORM_BUFFER)
                    .withUniform("WidgetUniforms", UniformType.UNIFORM_BUFFER)
                    .withSampler("Sampler0")
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.QUADS)
                    .build();
            RenderSystem.getDevice().precompilePipeline(glassPipeline);
        }
        if (blurPipeline == null) {
            blurPipeline = RenderPipeline.builder()
                    .withLocation(Identifier.of("musichud", "pipeline/blur"))
                    .withVertexShader(Identifier.of("musichud", "core/blit_fullscreen"))
                    .withFragmentShader(Identifier.of("musichud", "program/blur"))
                    .withUniform("Projection",  UniformType.UNIFORM_BUFFER)
                    .withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
                    .withUniform("Config",      UniformType.UNIFORM_BUFFER)
                    .withSampler("DiffuseSampler")
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.QUADS)
                    .build();
            RenderSystem.getDevice().precompilePipeline(blurPipeline);
        }

        if (quadVB == null) {
            // Fullscreen quad: 4 vertices as vec3 Position (x in [0,1], y in [0,1], z=0.5)
            // Matches blit_fullscreen.vsh which converts [0,1]→NDC[-1,1]
            ByteBuffer vbData = ByteBuffer.allocateDirect(4 * 3 * 4).order(ByteOrder.LITTLE_ENDIAN);
            vbData.putFloat(0f).putFloat(0f).putFloat(0.5f); // BL
            vbData.putFloat(1f).putFloat(0f).putFloat(0.5f); // BR
            vbData.putFloat(1f).putFloat(1f).putFloat(0.5f); // TR
            vbData.putFloat(0f).putFloat(1f).putFloat(0.5f); // TL
            vbData.flip();
            quadVB = RenderSystem.getDevice().createBuffer(
                    () -> "musichud quad VB", GpuBuffer.USAGE_VERTEX, vbData);
        }

        if (samplerInfoUbo == null)
            samplerInfoUbo = RenderSystem.getDevice().createBuffer(
                    () -> "musichud SamplerInfo", UBO_USAGE, 16L);
        if (widgetUniformsUbo == null)
            widgetUniformsUbo = RenderSystem.getDevice().createBuffer(
                    () -> "musichud WidgetUniforms", UBO_USAGE, 32L);
        // BlurConfig: vec4 Params (16 bytes) + 65 floats each padded to 16 = 16 + 65*16 = 1056
        long blurCfgSize = 16L + (MAX_BLUR_TAPS + 1) * 16L;
        if (blurConfigUboX == null)
            blurConfigUboX = RenderSystem.getDevice().createBuffer(
                    () -> "musichud BlurCfgX", UBO_USAGE, blurCfgSize);
        if (blurConfigUboY == null)
            blurConfigUboY = RenderSystem.getDevice().createBuffer(
                    () -> "musichud BlurCfgY", UBO_USAGE, blurCfgSize);

        if (blurTempTex == null || blurTempTex.getWidth(0) != w || blurTempTex.getHeight(0) != h) {
            if (blurTempTex != null) { blurTempView.close(); blurTempTex.close(); }
            blurTempTex  = RenderSystem.getDevice().createTexture("musichud blurTemp", TEX_USAGE, TextureFormat.RGBA8, w, h, 1, 1);
            blurTempView = RenderSystem.getDevice().createTextureView(blurTempTex);
        }
        if (blurredTex == null || blurredTex.getWidth(0) != w || blurredTex.getHeight(0) != h) {
            if (blurredTex != null) { blurredView.close(); blurredTex.close(); }
            blurredTex  = RenderSystem.getDevice().createTexture("musichud blurred", TEX_USAGE, TextureFormat.RGBA8, w, h, 1, 1);
            blurredView = RenderSystem.getDevice().createTextureView(blurredTex);
        }
    }

    private void uploadSamplerInfo(int w, int h) {
        try (var map = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(samplerInfoUbo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(map.data());
            b.putVec2((float) w, (float) h);
            b.putVec2((float) w, (float) h);
        }
    }

    private void uploadWidgetUniforms() {
        try (var map = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(widgetUniformsUbo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(map.data());
            b.putVec4(wx, wy, ww, wh); // Rect
            b.putFloat(wcr);           // CornerRadius
            b.putFloat(20.0f);         // RefThickness (ReGlass default)
            b.putFloat(1.4f);          // IOR          (ReGlass default)
            b.putFloat(7.0f);          // RefDispersion (ReGlass default)
        }
    }

    private static float[] gaussianWeights(int radius) {
        radius = Math.max(0, Math.min(radius, MAX_BLUR_TAPS));
        if (radius == 0) return new float[]{1f};
        float sigma = radius / 3.0f;
        float[] weights = new float[radius + 1];
        float   sum     = 0f;
        for (int i = 0; i <= radius; i++) {
            weights[i] = (float) Math.exp(-0.5 * i * i / ((double) sigma * sigma));
            sum += (i == 0) ? weights[i] : 2f * weights[i];
        }
        for (int i = 0; i <= radius; i++) weights[i] /= sum;
        return weights;
    }

    private void uploadBlurConfig(GpuBuffer ubo, float dx, float dy) {
        float[] weights = gaussianWeights(BLUR_RADIUS);
        try (var map = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(ubo, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(map.data());
            b.putVec4(dx, dy, (float) BLUR_RADIUS, 0f);
            for (int i = 0; i <= MAX_BLUR_TAPS; i++) {
                float w = (i <= BLUR_RADIUS) ? weights[i] : 0f;
                b.putFloat(w);
                b.align(16);
            }
        }
    }
}
