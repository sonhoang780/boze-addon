package com.example.addon.render;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jump Flooding Algorithm distance field, replacing the old dual-Kawase blur
 * pyramid (see docs/superpowers/specs/2026-07-04-jfa-glow-design.md).
 *
 * <p>Why: the Kawase pyramid wrote a BLURRED ALPHA field into an 8-bit texture,
 * and glow_resolve.fsh's halo remap stretched a thin slice of that 256-level
 * range into the full visible gradient -- reading as concentric bands. This
 * computes an EXACT per-pixel distance to the nearest addon-drawn silhouette
 * pixel instead: halo/flare/outline all become continuous functions of a real
 * float distance, so there is nothing left to band.
 *
 * <p>Computes TWO independent fields per frame -- hand-class and entity-class
 * (players/crystals/self) -- not one combined field with a handness bit. Glow
 * and Flare apply a DIFFERENT radius formula per class (hand un-scales the
 * global world-distance scale back to its own on-screen size; entities don't),
 * and a single nearest-seed-wins field made that formula flip discontinuously
 * right at the Voronoi boundary between a hand and a farther entity -- biting a
 * round notch out of whichever class's halo/flare had the larger radius
 * (2026-07-04). With two independent exact distances, glow_resolve.fsh computes
 * each class's contribution on its own geometry and combines via max(), so
 * neither class's effect is ever cut off by the other's territory.
 *
 * <p>Pipeline per frame, run twice (once per class, see render()/renderOneClass()):
 * seed pass (half-res) marks texels whose corresponding full-res silhouette pixel
 * is one WE drew AND belongs to the requested class (green-250 entity marker or
 * blue-250 hand marker, see BetterChams.ENTITY_OUTLINE_COLOR / HAND_OUTLINE_COLOR)
 * with their own full-res pixel coords; jump passes (decreasing power-of-two
 * steps, plus a JFA+2 refinement) propagate the nearest same-class seed outward;
 * a finalize pass samples the settled half-res field at full resolution and
 * writes (distance, validity) into two of GlowBlur's existing 8-bit GLOW_TEXTURE's
 * four channels (hand -> r,g; entity -> b,a; via glColorMask) -- same texture id,
 * same JSON "location" wiring, so no post_effect JSON changes were needed.
 * Distance is clamped to [0,255]px, comfortably above BetterChams' own
 * 18%-of-screen-height radius cap.
 */
public class JfaField {
    private static final Logger LOGGER = LoggerFactory.getLogger(JfaField.class);

    private static int seedProgram = -1;
    private static int jumpProgram = -1;
    private static int finalizeProgram = -1;
    private static int fbo = -1;
    private static int vao = -1, vbo = -1;

    // Ping-pong half-res buffers: r=seedX(px), g=seedY(px), b=handness(0/1), a=valid(0/1).
    private static int[] pingPong = {-1, -1};
    private static int halfAllocW = -1, halfAllocH = -1;
    private static int fullAllocW = -1, fullAllocH = -1;

    private static final String VERTEX_SHADER =
        "#version 330 core\n" +
        "layout(location = 0) in vec3 Position;\n" +
        "out vec2 texCoord;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(Position, 1.0);\n" +
        "    texCoord = Position.xy * 0.5 + 0.5;\n" +
        "}\n";

    // Marks half-res texels whose underlying full-res silhouette pixel is one this
    // addon drew AND belongs to the requested class (WantHand: 1.0 = hand marker
    // blue-250, 0.0 = entity marker green-250). Called TWICE per frame (see render()),
    // once per class, so each class gets its OWN independent distance field --
    // necessary because Glow/Flare apply a per-class radius formula (hand un-scales
    // the shrunk-by-distant-entity global scale, entity does not); a single combined
    // nearest-seed field made that formula flip discontinuously right at the Voronoi
    // boundary between a hand and a far entity, biting a round notch out of the
    // hand's halo exactly where entity's (smaller, distance-shrunk) radius took over
    // (2026-07-04). PURE WHITE (255/255/255) is explicitly REJECTED as foreign
    // content (Boze BlockHighlight, F3-era buffer content) in both classes.
    private static final String SEED_FRAG =
        "#version 330 core\n" +
        "uniform sampler2D SilSampler;\n" +
        "uniform vec2 FullSize;\n" +
        "uniform float WantHand;\n" +
        "in vec2 texCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    ivec2 fullCoord = ivec2(texCoord * FullSize);\n" +
        "    vec4 c = texelFetch(SilSampler, fullCoord, 0);\n" +
        "    bool isHand = c.a > 0.0 && c.r > 0.97 && c.g > 0.995 && c.b > 0.95 && c.b < 0.995;\n" +
        "    bool isEntity = c.a > 0.0 && c.r > 0.97 && c.b > 0.995 && c.g > 0.95 && c.g < 0.995;\n" +
        "    bool ours = (WantHand > 0.5) ? isHand : isEntity;\n" +
        "    if (!ours) { fragColor = vec4(0.0); return; }\n" +
        "    fragColor = vec4(vec2(fullCoord), 0.0, 1.0);\n" +
        "}\n";

    // Standard JFA jump step: each texel keeps whichever of its 9 (3x3, step-scaled)
    // neighbors carries the nearest still-valid seed, comparing true full-res
    // pixel distance (seed coords are stored in full-res px, not half-res).
    private static final String JUMP_FRAG =
        "#version 330 core\n" +
        "uniform sampler2D PrevSampler;\n" +
        "uniform vec2 TexelStep;\n" +
        "uniform float StepPx;\n" +
        "uniform vec2 FullSize;\n" +
        "in vec2 texCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 fullPos = texCoord * FullSize;\n" +
        "    vec4 best = vec4(0.0);\n" +
        "    float bestD = 1e20;\n" +
        "    for (int dy = -1; dy <= 1; dy++) {\n" +
        "        for (int dx = -1; dx <= 1; dx++) {\n" +
        "            vec2 uv = texCoord + vec2(float(dx), float(dy)) * StepPx * TexelStep;\n" +
        "            vec4 c = texture(PrevSampler, uv);\n" +
        "            if (c.a < 0.5) continue;\n" +
        "            float d = length(fullPos - c.rg);\n" +
        "            if (d < bestD) { bestD = d; best = c; }\n" +
        "        }\n" +
        "    }\n" +
        "    fragColor = best;\n" +
        "}\n";

    // Upsamples the settled half-res field to full resolution. Called TWICE per frame
    // (once per class, see render()), each time writing into a DIFFERENT half of
    // GLOW_TEXTURE's 4 channels via glColorMask: hand -> (r=dist,g=valid), entity ->
    // (b=dist,a=valid). No more per-pixel "handness" ownership bit -- each class's
    // distance is independently exact, so glow_resolve.fsh can compute each class's
    // halo/flare with its own radius formula and combine them (no single winner to
    // discontinuously flip formulas at a Voronoi boundary, see SEED_FRAG's comment).
    //
    // MANUAL 4-tap gather, NOT hardware bilinear: interpolating SEED COORDINATES
    // between two texels that hold two DIFFERENT seeds yields a phantom coordinate
    // near neither seed, so the distance spikes/dips along the exact Voronoi
    // boundary between two same-class silhouettes (2026-07-04). Taking min-distance
    // over the 4 nearest half-res texels' actual seeds gives the true nearest-seed
    // distance everywhere, boundary included.
    private static final String FINALIZE_FRAG =
        "#version 330 core\n" +
        "uniform sampler2D SeedSampler;\n" +
        "uniform vec2 FullSize;\n" +
        "uniform vec2 HalfSize;\n" +
        "in vec2 texCoord;\n" +
        "out vec4 fragColor;\n" +
        "void main() {\n" +
        "    vec2 fragPx = texCoord * FullSize;\n" +
        "    vec2 st = texCoord * HalfSize - 0.5;\n" +
        "    ivec2 base = ivec2(floor(st));\n" +
        "    float bestD = 1e20;\n" +
        "    bool found = false;\n" +
        "    for (int dy = 0; dy <= 1; dy++) {\n" +
        "        for (int dx = 0; dx <= 1; dx++) {\n" +
        "            ivec2 tc = clamp(base + ivec2(dx, dy), ivec2(0), ivec2(HalfSize) - 1);\n" +
        "            vec4 s = texelFetch(SeedSampler, tc, 0);\n" +
        "            if (s.a < 0.5) continue;\n" +
        "            float d = length(fragPx - s.rg);\n" +
        "            if (d < bestD) { bestD = d; found = true; }\n" +
        "        }\n" +
        "    }\n" +
        "    float distNorm = found ? clamp(bestD / 255.0, 0.0, 1.0) : 0.0;\n" +
        "    float validF = found ? 1.0 : 0.0;\n" +
        "    fragColor = vec4(distNorm, validF, distNorm, validF);\n" +
        "}\n";

    private static int compileProgram(String vert, String frag, String label) {
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(v, vert);
        GL20.glCompileShader(v);
        if (GL20.glGetShaderi(v, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("JfaField {} vert error: {}", label, GL20.glGetShaderInfoLog(v, 1024));
            return -1;
        }
        int f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(f, frag);
        GL20.glCompileShader(f);
        if (GL20.glGetShaderi(f, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("JfaField {} frag error: {}", label, GL20.glGetShaderInfoLog(f, 1024));
            return -1;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            LOGGER.error("JfaField {} link error: {}", label, GL20.glGetProgramInfoLog(p, 1024));
            return -1;
        }
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        return p;
    }

    private static int allocFloatTexture(int w, int h) {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        // RGBA16F: seed coords are stored in full-res px (up to ~1920/1080), which a
        // half-float represents exactly for any integer under 2048 -- 8-bit (as the
        // old Kawase pyramid used) can't hold a screen-space coordinate at all.
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.FloatBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        return tex;
    }

    private static void ensureResources(int fullW, int fullH) {
        if (seedProgram == -1) seedProgram = compileProgram(VERTEX_SHADER, SEED_FRAG, "seed");
        if (jumpProgram == -1) jumpProgram = compileProgram(VERTEX_SHADER, JUMP_FRAG, "jump");
        if (finalizeProgram == -1) finalizeProgram = compileProgram(VERTEX_SHADER, FINALIZE_FRAG, "finalize");
        if (fbo == -1) fbo = GL30.glGenFramebuffers();

        fullAllocW = fullW;
        fullAllocH = fullH;
        int halfW = Math.max(1, fullW / 2), halfH = Math.max(1, fullH / 2);
        if (halfAllocW != halfW || halfAllocH != halfH) {
            halfAllocW = halfW;
            halfAllocH = halfH;
            for (int i = 0; i < pingPong.length; i++) {
                if (pingPong[i] != -1) GL11.glDeleteTextures(pingPong[i]);
                pingPong[i] = allocFloatTexture(halfW, halfH);
            }
        }
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

    private static void bindSource(int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        // Clear any leftover GpuSampler bound to unit 0 by blaze3d's modern
        // texture/sampler abstraction (same fix as GlowBlur/CustomSkyRenderer) --
        // a stale sampler silently overrides our texture's own filter params.
        GL33.glBindSampler(0, 0);
    }

    /** Powers-of-two (descending) covering the requested full-res reach, half-res-texel units. */
    private static int[] jumpSteps(double radiusPxFull) {
        int halfReach = Math.max(1, (int) Math.ceil(radiusPxFull / 2.0));
        int start = Integer.highestOneBit(halfReach);
        if (start < halfReach) start <<= 1;
        start = Math.min(start, 128);
        int count = Integer.numberOfTrailingZeros(start) + 1;
        int[] steps = new int[count];
        int s = start;
        for (int i = 0; i < count; i++) { steps[i] = s; s >>= 1; }
        return steps;
    }

    /** Reads entity_outline's raw silhouette, computes TWO independent JFA distance
     * fields (hand-class, entity-class), writes both into GlowBlur.GLOW_TEXTURE's
     * 4 channels: r=handDist, g=handValid, b=entityDist, a=entityValid. Two classes
     * because Glow/Flare apply a DIFFERENT radius formula per class (hand un-scales
     * the shrunk-by-distant-entity global scale, entity does not) -- a single
     * combined nearest-seed field made that formula flip discontinuously right at
     * the Voronoi boundary between a hand and a far entity, biting a round notch
     * out of the hand's halo exactly where entity's (smaller) radius took over
     * (2026-07-04). Running the full seed->jump->finalize pipeline twice (reusing
     * the same ping-pong buffers sequentially) keeps each class's distance exact
     * and lets glow_resolve.fsh compute+combine them independently. */
    // True once GLOW_TEXTURE holds an all-invalid (cleared) field; lets clearField()
    // be a no-op on every empty frame after the first instead of re-clearing.
    private static boolean fieldCleared = false;

    /**
     * Empty-silhouette fast path: instead of the full seed->jump->finalize chain,
     * clear GLOW_TEXTURE once to zero (validity flags 0 in all 4 channels), which the
     * resolve pass reads as "no seed found" for both classes. Idempotent per streak.
     */
    public static void clearField() {
        if (fieldCleared) return;
        int outTex = GlowBlur.GLOW_TEXTURE.getRawTextureId();
        if (outTex == -1 || fbo == -1) { fieldCleared = true; return; } // never rendered yet: nothing stale
        int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (prevScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        int[] prevColorMask = new int[4];
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, prevColorMask);
        GL11.glColorMask(true, true, true, true);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outTex, 0);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glColorMask(prevColorMask[0] != 0, prevColorMask[1] != 0, prevColorMask[2] != 0, prevColorMask[3] != 0);
        if (prevScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
        fieldCleared = true;
    }

    public static void render(RenderTarget outlineTarget) {
        if (!(outlineTarget.getColorTexture() instanceof GlTexture glTex)) return;
        int fullW = outlineTarget.width, fullH = outlineTarget.height;
        if (fullW <= 0 || fullH <= 0) return;

        GlowBlur.GLOW_TEXTURE.resizeForShader(fullW, fullH);
        int outTex = GlowBlur.GLOW_TEXTURE.getRawTextureId();
        if (outTex == -1) return;

        ensureResources(fullW, fullH);
        if (seedProgram == -1 || jumpProgram == -1 || finalizeProgram == -1) return;
        fieldCleared = false; // field is about to hold real data again

        int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevBoundTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        // CRITICAL: this hook runs right after world/translucent/particle rendering,
        // which leaves GL_BLEND enabled. Every pass below is a plain full-screen
        // REPLACE (seed/jump write raw seed data, finalize writes distance/valid) --
        // none of it is meant to blend with whatever was already in the target.
        // Leaving blending on meant each pass's output got blended with the
        // PREVIOUS FRAME's leftover content in that same texel instead of replacing
        // it outright: seed/jump data never fully reset frame-to-frame, and neither
        // did GLOW_TEXTURE (shared by Glow AND Flare), which is why both effects left
        // a trailing "never erased" ghost as objects moved (2026-07-04). Every prior
        // attempt to fix this by clearing entityOutlineTarget (the SILHOUETTE input)
        // was fixing the wrong buffer -- the corruption lives entirely inside this
        // private pipeline, downstream of that input.
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        if (prevBlend) GL11.glDisable(GL11.GL_BLEND);
        int[] prevColorMask = new int[4];
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, prevColorMask);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        // Shared jump-step schedule (same reach for both classes this frame).
        // "JFA+2": two extra refinement passes at step=2 then step=1 after the main
        // power-of-two descent fixes the classic JFA mis-assignment error along a
        // Voronoi boundary between two same-class seed clusters (standard citation:
        // Rong & Tan, "Jump Flooding in GPU with Applications to Voronoi Diagram").
        double radiusPx = com.example.addon.modules.BetterChams.fieldRadiusPxForBlur;
        int[] mainSteps = jumpSteps(radiusPx);
        int[] steps = new int[mainSteps.length + 2];
        System.arraycopy(mainSteps, 0, steps, 0, mainSteps.length);
        steps[mainSteps.length] = 2;
        steps[mainSteps.length + 1] = 1;

        // Run the full pipeline once per class. writeToBA selects which half of
        // GLOW_TEXTURE's 4 channels the finalize pass is allowed to touch.
        renderOneClass(glTex, outTex, fullW, fullH, steps, true, false);   // hand -> r,g
        renderOneClass(glTex, outTex, fullW, fullH, steps, false, true);  // entity -> b,a

        GL11.glColorMask(prevColorMask[0] != 0, prevColorMask[1] != 0, prevColorMask[2] != 0, prevColorMask[3] != 0);
        GL20.glUseProgram(0);
        if (prevBlend) GL11.glEnable(GL11.GL_BLEND);
        GL33.glBindSampler(0, prevSampler);
        GL13.glActiveTexture(prevActiveTex);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBoundTex);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    /** One class's full seed->jump->finalize pass. FBO must already be bound. */
    private static void renderOneClass(GlTexture glTex, int outTex, int fullW, int fullH,
                                        int[] steps, boolean wantHand, boolean writeToBA) {
        GL11.glViewport(0, 0, halfAllocW, halfAllocH);
        // The seed/jump passes write full RGBA into the PRIVATE ping-pong buffers (not
        // GLOW_TEXTURE) and must never be masked -- only the LAST class processed left
        // glColorMask narrowed (see below), so without this reset the second class's
        // seed pass would silently drop its own alpha (validity) writes and inherit
        // the FIRST class's leftover valid/invalid pattern in that channel, making the
        // second class's glow only "valid" wherever the first class's silhouette was
        // (looked like Glow/Flare only appearing where the hand overlapped the entity,
        // 2026-07-04).
        GL11.glColorMask(true, true, true, true);

        // Seed: full-res silhouette (this class only) -> half-res pingPong[0].
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, pingPong[0], 0);
        GL20.glUseProgram(seedProgram);
        setVec2(seedProgram, "FullSize", fullW, fullH);
        setFloat(seedProgram, "WantHand", wantHand ? 1.0f : 0.0f);
        setSampler(seedProgram, "SilSampler");
        bindSource(glTex.glId());
        drawQuad();

        // Jump passes, ping-ponging between the two half-res buffers.
        int src = 0;
        for (int step : steps) {
            int dst = 1 - src;
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, pingPong[dst], 0);
            GL20.glUseProgram(jumpProgram);
            setVec2(jumpProgram, "TexelStep", 1.0f / halfAllocW, 1.0f / halfAllocH);
            setFloat(jumpProgram, "StepPx", step);
            setVec2(jumpProgram, "FullSize", fullW, fullH);
            setSampler(jumpProgram, "PrevSampler");
            bindSource(pingPong[src]);
            drawQuad();
            src = dst;
        }

        // Finalize: settled half-res field -> full-res GLOW_TEXTURE, masked to this
        // class's two channels only (the other class's channels, written by the
        // other renderOneClass call, are left untouched by the mask).
        GL11.glViewport(0, 0, fullW, fullH);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outTex, 0);
        GL20.glUseProgram(finalizeProgram);
        setVec2(finalizeProgram, "FullSize", fullW, fullH);
        setVec2(finalizeProgram, "HalfSize", halfAllocW, halfAllocH);
        setSampler(finalizeProgram, "SeedSampler");
        bindSource(pingPong[src]);
        GL11.glColorMask(!writeToBA, !writeToBA, writeToBA, writeToBA);
        drawQuad();
    }

    private static void setSampler(int program, String name) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc != -1) GL20.glUniform1i(loc, 0);
    }

    private static void setVec2(int program, String name, float x, float y) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc != -1) GL20.glUniform2f(loc, x, y);
    }

    private static void setFloat(int program, String name, float v) {
        int loc = GL20.glGetUniformLocation(program, name);
        if (loc != -1) GL20.glUniform1f(loc, v);
    }
}
