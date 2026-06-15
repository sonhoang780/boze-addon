package com.example.addon.shader.impl;

import com.example.addon.shader.KawaseFramebuffer;
import com.example.addon.shader.KawasePostRenderer;
import com.example.addon.shader.KawaseShader;

import it.unimi.dsi.fastutil.ints.IntDoubleImmutablePair;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

import com.mojang.blaze3d.opengl.GlStateManager;

public class KawaseBlur {

    public static final KawaseBlur MUSIC_HUD_BLUR = new KawaseBlur();

    private static final IntDoubleImmutablePair[] STRENGTHS = {
        IntDoubleImmutablePair.of(1, 1.25), IntDoubleImmutablePair.of(1, 2.25),
        IntDoubleImmutablePair.of(2, 2.0),  IntDoubleImmutablePair.of(2, 3.0),
        IntDoubleImmutablePair.of(2, 4.25), IntDoubleImmutablePair.of(3, 2.5),
        IntDoubleImmutablePair.of(3, 3.25), IntDoubleImmutablePair.of(3, 4.25),
        IntDoubleImmutablePair.of(3, 5.5),  IntDoubleImmutablePair.of(4, 3.25),
        IntDoubleImmutablePair.of(4, 4.0),  IntDoubleImmutablePair.of(4, 5.0),
        IntDoubleImmutablePair.of(4, 6.0),  IntDoubleImmutablePair.of(4, 7.25),
        IntDoubleImmutablePair.of(4, 8.25), IntDoubleImmutablePair.of(5, 4.5),
        IntDoubleImmutablePair.of(5, 5.25), IntDoubleImmutablePair.of(5, 6.25),
        IntDoubleImmutablePair.of(5, 7.25), IntDoubleImmutablePair.of(5, 8.5)
    };

    private static KawaseShader shaderDown, shaderUp, shaderPassthrough;
    private final KawaseFramebuffer[] fbos = new KawaseFramebuffer[6];
    private boolean initialized = false;

    // ─────────────────────────────────────────────────────────────
    // LẤY GL TEXTURE INT TỪ GpuTexture (1.21.11)
    // GpuTexture là abstraction mới — cần gọi getGlId() hoặc tương đương
    // ─────────────────────────────────────────────────────────────
    public static int getMainTextureId() {
        try {
            Object fb  = MinecraftClient.getInstance().getFramebuffer();
            Object tex = fb.getClass().getMethod("getColorAttachment").invoke(fb);
            if (tex instanceof Integer) return (Integer) tex;

            // GpuTexture 1.21.x — thử các method trả về GL int ID
            for (String mName : new String[]{"getGlId", "getGlHandle", "getGlTexture",
                                             "getTextureId", "getId", "getHandle"}) {
                try {
                    java.lang.reflect.Method m = tex.getClass().getMethod(mName);
                    if (m.getReturnType() == int.class) return (int) m.invoke(tex);
                } catch (NoSuchMethodException ignored) {}
            }
            // Fallback: scan tất cả method trả về int, không có param
            for (java.lang.reflect.Method m : tex.getClass().getMethods()) {
                if (m.getReturnType() == int.class && m.getParameterCount() == 0) {
                    try { return (int) m.invoke(tex); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    // Bind lại main framebuffer bằng cách lấy FBO int ID qua reflection
    public static void bindMainFramebuffer() {
        int fboId = 0;
        try {
            Object fb = MinecraftClient.getInstance().getFramebuffer();
            // Thử field "fbo" trước (yarn mapping)
            for (String fname : new String[]{"fbo", "framebufferId", "id"}) {
                try {
                    java.lang.reflect.Field f = fb.getClass().getField(fname);
                    if (f.getType() == int.class) { fboId = f.getInt(fb); break; }
                } catch (NoSuchFieldException ignored) {}
            }
            // Nếu không tìm thấy qua field, thử getDeclaredFields
            if (fboId == 0) {
                for (java.lang.reflect.Field f : fb.getClass().getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        int v = f.getInt(fb);
                        if (v > 0) { fboId = v; break; }
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }

        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        GlStateManager._viewport(0, 0,
            MinecraftClient.getInstance().getWindow().getFramebufferWidth(),
            MinecraftClient.getInstance().getWindow().getFramebufferHeight());
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────

    public void resize() {
        for (int i = 0; i < fbos.length; i++) {
            if (fbos[i] != null) fbos[i].resize();
            else fbos[i] = new KawaseFramebuffer(1.0 / Math.pow(2, i));
        }
    }

    /**
     * Chạy Kawase blur toàn màn hình.
     * Kết quả blit vào main framebuffer — HUD vẽ lên sau.
     * Gọi từ BlurMixin TRƯỚC InGameHud.render().
     */
    public void draw(int radius) {
        // Lazy init shader
        if (shaderDown == null) {
            shaderDown        = new KawaseShader("musichud", "blur.vert",        "blur_down.frag");
            shaderUp          = new KawaseShader("musichud", "blur.vert",        "blur_up.frag");
            shaderPassthrough = new KawaseShader("musichud", "passthrough.vert", "passthrough.frag");
        }
        // Lazy init FBO
        if (!initialized) {
            for (int i = 0; i < fbos.length; i++)
                fbos[i] = new KawaseFramebuffer(1.0 / Math.pow(2, i));
            initialized = true;
        }

        int r = Math.max(1, Math.min(radius, STRENGTHS.length));
        IntDoubleImmutablePair s = STRENGTHS[r - 1];
        int    iterations = s.leftInt();
        double offset     = s.rightDouble();

        int mainTex = getMainTextureId();
        if (mainTex == 0) return; // reflection fail → skip

        // ── Bắt đầu chuỗi render offline (không render lên screen) ──
        KawasePostRenderer.beginRender();

        // Pass xuống: mainTex → fbo[0] → fbo[1] → ... → fbo[iterations]
        renderToFbo(fbos[0], mainTex, shaderDown, offset);
        for (int i = 0; i < iterations; i++)
            renderToFbo(fbos[i + 1], fbos[i].texture, shaderDown, offset);

        // Pass lên: fbo[iterations] → ... → fbo[1] → fbo[0]
        for (int i = iterations; i >= 1; i--)
            renderToFbo(fbos[i - 1], fbos[i].texture, shaderUp, offset);

        // Blit kết quả blur (fbo[0]) về main framebuffer
        bindMainFramebuffer();
        shaderPassthrough.bind();
        bindTexture(fbos[0].texture);
        shaderPassthrough.set("uTexture", 0);
        KawasePostRenderer.render(); // render quad full-screen lên main fb

        KawasePostRenderer.endRender();

        // Restore cull state
        GlStateManager._enableCull();
    }

    public int getTexture() {
        return (fbos[0] != null) ? fbos[0].texture : 0;
    }

    // ─────────────────────────────────────────────────────────────
    // PRIVATE
    // ─────────────────────────────────────────────────────────────

    private void renderToFbo(KawaseFramebuffer target, int srcTex, KawaseShader shader, double offset) {
        target.bind();
        target.setViewport();
        shader.bind();
        bindTexture(srcTex);
        shader.set("uTexture",       0);
        shader.set("uHalfTexelSize", 0.5 / target.width, 0.5 / target.height);
        shader.set("uOffset",        offset);
        KawasePostRenderer.render();
    }

    private static void bindTexture(int tex) {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._bindTexture(tex);
    }
}