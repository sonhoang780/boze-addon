package com.example.addon.rendering;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real dual-Kawase glow blur: downsample entity_outline (full -> half -> quarter
 * res) then upsample back (quarter -> half -> full), writing the result into
 * GLOW_TEXTURE for entity_outline.json's glow_resolve pass to read as an
 * external "location" input.
 *
 * <p>Why raw GL instead of JSON PostChain passes (the old approach): a PostChain
 * internal target's width/height (PostChainConfig$InternalTarget, verified via
 * javap on the 26.1.2 jar) is a fixed Optional&lt;Integer&gt; resolved once when
 * the chain loads -- there is no fractional/"half of main" declaration, so a
 * real downsampled target can't track the window size dynamically from JSON
 * alone. The old 4-pass approach instead grew the sample OFFSET at constant
 * full resolution (PassScale 1/2/4/8), which is a cheap approximation but shows
 * visible blocky/kernel-shaped artifacts once the silhouette is magnified
 * (zoomed in) far enough for the fixed pixel-radius taps to become individually
 * resolvable. True mip-style down/upsampling lets hardware bilinear filtering
 * smooth the result at each resolution change, which is what actually reads as
 * "glow" instead of "blocky rings" -- and it's cheaper too (far fewer texel
 * reads at half/quarter resolution).
 */
public class GlowBlur {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlowBlur.class);

    public static final Identifier GLOW_TEX_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/chamsglow.png");
    public static final ChamsImageTexture GLOW_TEXTURE = new ChamsImageTexture();

    private static int downProgram = -1;
    private static int upProgram = -1;
    private static int fbo = -1;
    // Mip-style pyramid /2../64: depth picked per frame from the requested blur
    // radius, so big Glow Thickness gets a genuinely wider (still smooth) blur
    // instead of the sparse-kernel noise the old fixed half+quarter couldn't cover.
    private static final int MAX_LEVELS = 6;
    private static final int[] levelTexs = new int[MAX_LEVELS];
    static { java.util.Arrays.fill(levelTexs, -1); }
    private static int allocW = -1, allocH = -1; // full-res size these were allocated for
    private static int vao = -1, vbo = -1;

    private static final String VERTEX_SHADER =
        "#version 330 core\n" +
        "layout(location = 0) in vec3 Position;\n" +
        "out vec2 texCoord;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(Position, 1.0);\n" +
        "    texCoord = Position.xy * 0.5 + 0.5;\n" +
        "}\n";

    // Simple 4-tap box downsample: sampling at quarter-texel diagonal offsets makes
    // hardware bilinear filtering average exactly the 4 source texels under each new
    // (coarser) texel, which is the standard/cheap Kawase downsample step.
    private static final String DOWN_FRAG =
        "#version 330 core\n" +
        "uniform sampler2D InSampler;\n" +
        "uniform vec2 InSize;\n" +
        "in vec2 texCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 o = 1.0 / InSize;\n" +
        "    vec4 sum = texture(InSampler, texCoord + vec2(-o.x, -o.y))\n" +
        "             + texture(InSampler, texCoord + vec2( o.x, -o.y))\n" +
        "             + texture(InSampler, texCoord + vec2(-o.x,  o.y))\n" +
        "             + texture(InSampler, texCoord + vec2( o.x,  o.y));\n" +
        "    fragColor = sum * 0.25;\n" +
        "}\n";

    // Same 8-tap diamond kernel as the old glow_pass.fsh, just parameterized on the
    // (now much lower-res) source's own texel size instead of a growing PassScale.
    private static final String UP_FRAG =
        "#version 330 core\n" +
        "uniform sampler2D InSampler;\n" +
        "uniform vec2 InSize;\n" +
        "in vec2 texCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 o = 1.0 / InSize;\n" +
        "    vec4 sum = texture(InSampler, texCoord + vec2(-o.x, -o.y)) * 1.0\n" +
        "             + texture(InSampler, texCoord + vec2( o.x, -o.y)) * 1.0\n" +
        "             + texture(InSampler, texCoord + vec2(-o.x,  o.y)) * 1.0\n" +
        "             + texture(InSampler, texCoord + vec2( o.x,  o.y)) * 1.0\n" +
        "             + texture(InSampler, texCoord + vec2( 0.0,      -o.y * 2.0)) * 2.0\n" +
        "             + texture(InSampler, texCoord + vec2(-o.x * 2.0,  0.0))      * 2.0\n" +
        "             + texture(InSampler, texCoord + vec2( o.x * 2.0,  0.0))      * 2.0\n" +
        "             + texture(InSampler, texCoord + vec2( 0.0,       o.y * 2.0)) * 2.0;\n" +
        "    fragColor = sum / 12.0;\n" +
        "}\n";

    public static void registerTextures() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            GLOW_TEXTURE.init();
            mc.getTextureManager().register(GLOW_TEX_ID, GLOW_TEXTURE);
        });
    }

    private static int compileProgram(String vert, String frag) {
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(v, vert);
        GL20.glCompileShader(v);
        if (GL20.glGetShaderi(v, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("GlowBlur vert error: {}", GL20.glGetShaderInfoLog(v, 1024));
            return -1;
        }
        int f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(f, frag);
        GL20.glCompileShader(f);
        if (GL20.glGetShaderi(f, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("GlowBlur frag error: {}", GL20.glGetShaderInfoLog(f, 1024));
            return -1;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            LOGGER.error("GlowBlur link error: {}", GL20.glGetProgramInfoLog(p, 1024));
            return -1;
        }
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        return p;
    }

    private static void ensureResources(int fullW, int fullH) {
        if (downProgram == -1) downProgram = compileProgram(VERTEX_SHADER, DOWN_FRAG);
        if (upProgram == -1) upProgram = compileProgram(VERTEX_SHADER, UP_FRAG);
        if (fbo == -1) fbo = GL30.glGenFramebuffers();

        if (allocW != fullW || allocH != fullH) {
            allocW = fullW;
            allocH = fullH;
            for (int i = 0; i < MAX_LEVELS; i++) {
                if (levelTexs[i] != -1) GL11.glDeleteTextures(levelTexs[i]);
                int w = Math.max(1, fullW >> (i + 1));
                int h = Math.max(1, fullH >> (i + 1));
                levelTexs[i] = allocTexture(w, h);
            }
        }
    }

    private static int allocTexture(int w, int h) {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        return tex;
    }

    private static void drawPass(int program, int srcTex, int srcW, int srcH, int dstTex, int dstW, int dstH) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, dstTex, 0);
        GL11.glViewport(0, 0, dstW, dstH);

        GL20.glUseProgram(program);
        int locSampler = GL20.glGetUniformLocation(program, "InSampler");
        if (locSampler != -1) GL20.glUniform1i(locSampler, 0);
        int locSize = GL20.glGetUniformLocation(program, "InSize");
        if (locSize != -1) GL20.glUniform2f(locSize, srcW, srcH);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
        // Clear any leftover GpuSampler object bound to unit 0 by blaze3d's modern
        // texture/sampler-pair abstraction -- same fix as CustomSkyRenderer's Image
        // mode: a stale sampler (e.g. a shadow-comparison sampler) silently overrides
        // our texture's own LINEAR filter params and can sample as all-black.
        GL33.glBindSampler(0, 0);

        drawQuad();
    }

    private static void drawQuad() {
        if (vao == -1) {
            vao = GL30.glGenVertexArrays();
            vbo = GL20.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
            float[] verts = {
                -1.0f, -1.0f, 0.0f,
                 1.0f, -1.0f, 0.0f,
                -1.0f,  1.0f, 0.0f,
                 1.0f,  1.0f, 0.0f
            };
            GL20.glBufferData(GL20.GL_ARRAY_BUFFER, verts, GL20.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
            GL20.glEnableVertexAttribArray(0);
            GL30.glBindVertexArray(0);
        }
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL30.glBindVertexArray(0);
    }

    /** Maps a blur radius in full-res pixels to a pyramid depth. */
    private static int radiusToDepth(double radiusPx) {
        if (radiusPx <= 6)  return 2;
        if (radiusPx <= 12) return 3;
        if (radiusPx <= 24) return 4;
        if (radiusPx <= 48) return 5;
        return 6;
    }

    /** Reads entity_outline's raw silhouette, blurs it via down/upsample, writes GLOW_TEXTURE. */
    public static void render(RenderTarget outlineTarget) {
        if (!(outlineTarget.getColorTexture() instanceof GlTexture glTex)) return;
        int fullW = outlineTarget.width, fullH = outlineTarget.height;
        if (fullW <= 0 || fullH <= 0) return;

        GLOW_TEXTURE.resizeForShader(fullW, fullH);
        int outTex = GLOW_TEXTURE.getRawTextureId();
        if (outTex == -1) return;

        blur(glTex.glId(), fullW, fullH, outTex,
            radiusToDepth(com.example.addon.modules.BetterChams.fieldRadiusPxForBlur));
    }

    /** 2-level compatibility entry point (CustomSky bloom). */
    public static void blur(int srcTex, int fullW, int fullH, int dstTex) {
        blur(srcTex, fullW, fullH, dstTex, 2);
    }

    /**
     * Generic entry point: down/upsample-blurs srcTex (fullW x fullH) into dstTex
     * (must already be allocated at fullW x fullH) through `depth` pyramid levels.
     */
    public static void blur(int srcTex, int fullW, int fullH, int dstTex, int depth) {
        ensureResources(fullW, fullH);
        if (downProgram == -1 || upProgram == -1) return;
        depth = Math.max(1, Math.min(MAX_LEVELS, depth));

        int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevBoundTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        int srcW = fullW, srcH = fullH, src = srcTex;
        for (int i = 0; i < depth; i++) {
            int w = Math.max(1, fullW >> (i + 1)), h = Math.max(1, fullH >> (i + 1));
            drawPass(downProgram, src, srcW, srcH, levelTexs[i], w, h);
            src = levelTexs[i]; srcW = w; srcH = h;
        }
        for (int i = depth - 2; i >= 0; i--) {
            int w = Math.max(1, fullW >> (i + 1)), h = Math.max(1, fullH >> (i + 1));
            drawPass(upProgram, src, srcW, srcH, levelTexs[i], w, h);
            src = levelTexs[i]; srcW = w; srcH = h;
        }
        drawPass(upProgram, src, srcW, srcH, dstTex, fullW, fullH);

        GL20.glUseProgram(0);
        GL33.glBindSampler(0, prevSampler);
        GL13.glActiveTexture(prevActiveTex);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBoundTex);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
}
