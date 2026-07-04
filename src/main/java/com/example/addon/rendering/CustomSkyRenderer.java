package com.example.addon.rendering;

import com.example.addon.modules.CustomSky;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compiles/renders the user's chosen sky (image or arbitrary .frag shader) into an
 * OFFSCREEN texture (CustomSky.SKY_TEXTURE) via raw GL -- same proven pattern as
 * ChamsCustomShader (BetterChams' Shader fill mode, which IS already wired up and
 * running in production, confirming raw LWJGL GL11/20/30 calls still work fine on
 * 26.1.2 for mod-owned offscreen rendering). The actual on-screen compositing (only
 * over true sky pixels, via depth test) happens in a normal PostChain pass
 * (assets/example-addon/post_effect/custom_sky.json) that samples this texture --
 * that part stays 100% within the modern, frame-graph-safe pipeline. This class never
 * draws directly to the screen/main framebuffer.
 */
public class CustomSkyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomSkyRenderer.class);


    private static int customProgram = -1;
    private static int imageProgram = -1;
    private static int skyImageTexture = -1;
    private static int fbo = -1;
    private static int vao = -1;
    private static int vbo = -1;

    // texCoord is standard normalized UV in [0,1] (matches what any shader author
    // assumes from that name -- Shadertoy/GLSL convention). Do NOT hand out raw
    // clip-space [-1,1] under this name: a custom shader (e.g. vangogh_sky.frag)
    // that reasonably does its own "texCoord * 2.0 - 1.0" on top of an
    // already-[-1,1] value doubles the range to [-3,1], warping/scaling the ray
    // direction non-uniformly across the screen -- reported as nauseating
    // scaling/distortion while turning the camera.
    private static final String VERTEX_SHADER =
        "#version 330 core\n" +
        "layout(location = 0) in vec3 Position;\n" +
        "out vec2 texCoord;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(Position, 1.0);\n" +
        "    texCoord = Position.xy * 0.5 + 0.5;\n" +
        "}\n";

    private static final String FRAG_HEADER =
        "#version 330 core\n" +
        "uniform mat4 u_InverseProj;\n" +
        "uniform mat4 u_InverseView;\n" +
        "uniform vec2 u_Resolution;\n" +
        "uniform float u_Time;\n" +
        "in vec2 texCoord;\n" +
        "out vec4 fragColor;\n";

    // Built-in equirectangular-sample shader for Image mode -- NOT user-provided, so
    // it can rely on the same u_InverseProj/u_InverseView ray reconstruction as the
    // custom-shader path without needing to process arbitrary user code.
    // DEBUG (systematic-debugging Phase 3, single-variable test): when true, output the
    // computed equirect UV as color instead of sampling u_SkyTexture -- isolates "UV math
    // degenerate" from "texture sampling broken" for the CustomSky Image black-sky bug.
    // Flip back to false once root cause confirmed.
    private static final boolean DEBUG_UV_MODE = false;

    private static final String IMAGE_FRAG =
        FRAG_HEADER +
        "uniform sampler2D u_SkyTexture;\n" +
        "void main() {\n" +
        "    vec4 clip = vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);\n" +
        "    vec4 viewPos = u_InverseProj * clip;\n" +
        "    viewPos = vec4(viewPos.xy, -1.0, 0.0);\n" +
        "    vec3 dir = normalize((u_InverseView * viewPos).xyz);\n" +
        "    float u = atan(dir.z, dir.x) / 6.28318530718 + 0.5;\n" +
        "    float v = acos(clamp(dir.y, -1.0, 1.0)) / 3.14159265359;\n" +
        (DEBUG_UV_MODE ?
        "    fragColor = vec4(u, v, 0.0, 1.0);\n" :
        "    fragColor = texture(u_SkyTexture, vec2(u, v));\n") +
        "}\n";

    public static void loadCustomShader(Path path) {
        try {
            String userCode = new String(Files.readAllBytes(path));

            // Bedrock Edition RenderDragon/BGFX shaderpacks (.sc sources renamed to
            // .frag) use a completely different shading dialect -- "#include
            // <bgfx_shader.sh>"/"$input"/"$output" directives desktop GLSL can't
            // resolve at all. Not fixable by wrapping like Shadertoy dumps; fail with
            // a clear reason instead of a cryptic "unexpected $undefined" GLSL parse error.
            if (userCode.contains("bgfx_shader.sh") || userCode.matches("(?s).*\\$input\\b.*")) {
                dev.boze.api.utility.ChatHelper.sendMsg("CustomSky", "§c" + path.getFileName() + " §7is a Bedrock Edition (BGFX) shaderpack file, not a desktop GLSL shader -- not supported.");
                return;
            }

            userCode = userCode.replaceAll("(?m)^[ \\t]*#version\\s+.*$", "");

            java.util.regex.Pattern outPattern = java.util.regex.Pattern.compile("(?m)^[ \\t]*out\\s+vec4\\s+(\\w+)\\s*;");
            java.util.regex.Matcher m = outPattern.matcher(userCode);
            while (m.find()) {
                String varName = m.group(1);
                userCode = userCode.replace(m.group(0), "");
                if (!varName.equals("fragColor")) {
                    userCode = userCode.replaceAll("(?<![\\w.])\\b" + java.util.regex.Pattern.quote(varName) + "\\b", "fragColor");
                }
                m = outPattern.matcher(userCode);
            }

            // Legacy Boze/downloaded shaders often re-declare uniforms our FRAG_HEADER
            // already provides (u_Resolution, u_Time, etc.) under the SAME name -- GLSL
            // treats a second "uniform <type> name;" for an existing name as a hard
            // "conflicts with previous declaration" compile error (e.g. "sky (2).frag").
            // Strip the user's own declaration line for each reserved name; the
            // header's copy still applies.
            for (String reserved : new String[] { "u_InverseProj", "u_InverseView", "u_Resolution", "u_Time", "u_IsImage", "u_SkyTexture" }) {
                userCode = userCode.replaceAll(
                    "(?m)^[ \\t]*uniform\\s+\\w+\\s+" + java.util.regex.Pattern.quote(reserved) + "\\s*;[ \\t]*(//[^\\n]*)?$", "");
            }

            // Shadertoy dumps (the most common .frag found online) define
            // mainImage(out vec4, in vec2) + iTime/iResolution instead of main().
            // Wrap them so they compile against our uniforms.
            if (userCode.contains("mainImage") && !userCode.matches("(?s).*void\\s+main\\s*\\(.*")) {
                userCode =
                    "#define iTime u_Time\n" +
                    "#define iResolution vec3(u_Resolution, 1.0)\n" +
                    userCode +
                    "\nvoid main() {\n" +
                    "    vec2 fragCoord = texCoord * u_Resolution;\n" +
                    "    mainImage(fragColor, fragCoord);\n" +
                    "}\n";
            }

            // Hybrid shaders (procedural-vs-background-photo, e.g. downloaded/AI-written
            // "if (u_IsImage) sample photo else draw procedural sky" templates) expect
            // these two names to exist regardless of mode -- declare them for every
            // custom shader, not just the built-in IMAGE_FRAG, and bind whatever image
            // is currently loaded (if any) so such shaders compile and can react to it.
            String fullFrag = FRAG_HEADER +
                "uniform bool u_IsImage;\n" +
                "uniform sampler2D u_SkyTexture;\n" +
                userCode;
            int newProg = compileProgram(VERTEX_SHADER, fullFrag);
            if (newProg != -1) {
                if (customProgram != -1) GL20.glDeleteProgram(customProgram);
                customProgram = newProg;
                dev.boze.api.utility.ChatHelper.sendMsg("CustomSky", "§aLoaded shader: " + path.getFileName());
            } else {
                // Old program intentionally kept so the sky doesn't blank out, but the
                // user must know their file was rejected -- silent failure here is why
                // "every .frag renders the same starry sky" was reported as a bug.
                dev.boze.api.utility.ChatHelper.sendMsg("CustomSky", "§cShader compile failed: " + path.getFileName() + " §7(see latest.log; previous shader kept)");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load sky shader", e);
            dev.boze.api.utility.ChatHelper.sendMsg("CustomSky", "§cFailed to read shader: " + path.getFileName());
        }
    }

    public static void loadImage(Path path) {
        Minecraft.getInstance().execute(() -> {
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.IntBuffer w = stack.mallocInt(1);
                java.nio.IntBuffer h = stack.mallocInt(1);
                java.nio.IntBuffer comp = stack.mallocInt(1);

                java.nio.ByteBuffer image = org.lwjgl.stb.STBImage.stbi_load(path.toString(), w, h, comp, 4);
                if (image != null) {
                    if (skyImageTexture != -1) GL11.glDeleteTextures(skyImageTexture);
                    skyImageTexture = GL11.glGenTextures();

                    // Save/restore binding: raw glBindTexture behind blaze3d's state
                    // cache desyncs subsequent vanilla draws.
                    int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, skyImageTexture);
                    // Vanilla NativeImage uploads set GL_UNPACK_ROW_LENGTH/SKIP_* for
                    // sub-region uploads and may leave them nonzero; stbi buffers are
                    // tightly packed, so reset unpack state or rows read shifted.
                    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w.get(0), h.get(0), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);
                    // The sky quad covers the whole screen from a source image seen at a
                    // fraction of its 360x180 coverage (e.g. ~70deg FOV out of 360deg), so
                    // many image texels map to one screen pixel -- classic minification.
                    // A single mip level + bilinear-only MIN_FILTER aliases/shimmers on
                    // high-frequency content (star points) as the camera turns, which read
                    // as "pixelated/low-res" and janky despite a healthy frame rate.
                    // Mipmapping + trilinear MIN_FILTER fixes both.
                    GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                    if (org.lwjgl.opengl.GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                        float maxAniso = GL11.glGetFloat(org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                        GL11.glTexParameterf(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, maxAniso);
                    }

                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);

                    org.lwjgl.stb.STBImage.stbi_image_free(image);

                    if (imageProgram == -1) {
                        imageProgram = compileProgram(VERTEX_SHADER, IMAGE_FRAG);
                    }
                    if (imageProgram == -1) {
                        dev.boze.api.utility.ChatHelper.sendMsg("CustomSky", "§cImage shader compile failed (see latest.log)");
                    } else {
                        dev.boze.api.utility.ChatHelper.sendMsg("CustomSky", "§aLoaded image: " + path.getFileName() + " (" + w.get(0) + "x" + h.get(0) + ")");
                    }
                } else {
                    LOGGER.error("Failed to load sky image: " + org.lwjgl.stb.STBImage.stbi_failure_reason());
                    dev.boze.api.utility.ChatHelper.sendMsg("CustomSky", "§cImage load failed: " + org.lwjgl.stb.STBImage.stbi_failure_reason());
                }
            }
        });
    }

    private static int compileProgram(String vert, String frag) {
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(v, vert);
        GL20.glCompileShader(v);
        if (GL20.glGetShaderi(v, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("Sky Vert error: {}", GL20.glGetShaderInfoLog(v, 1024));
            return -1;
        }

        int f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(f, frag);
        GL20.glCompileShader(f);
        if (GL20.glGetShaderi(f, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.error("Sky Frag error: {}", GL20.glGetShaderInfoLog(f, 1024));
            return -1;
        }

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            LOGGER.error("Sky Link error: {}", GL20.glGetProgramInfoLog(p, 1024));
            return -1;
        }

        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        return p;
    }

    /**
     * Renders the active program (shader or image) into CustomSky.SKY_TEXTURE, an
     * offscreen ChamsImageTexture-backed FBO -- called every tick from CustomSky.onTick,
     * same as ChamsCustomShader.renderCustomShader() is called from BetterChams'
     * onTickPre. Never touches the main framebuffer.
     */
    public static void tick() {
        CustomSky.Mode mode = (CustomSky.Mode) CustomSky.INSTANCE.mode.getValue();
        int program = mode == CustomSky.Mode.Image ? imageProgram : customProgram;
        if (mode == CustomSky.Mode.Off || program == -1) return;

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        CustomSky.SKY_TEXTURE.resizeForShader(width, height);
        int outTexId = CustomSky.SKY_TEXTURE.getRawTextureId();
        if (outTexId == -1) return;

        if (fbo == -1) fbo = GL30.glGenFramebuffers();

        int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outTexId, 0);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        GL20.glUseProgram(program);

        int locInvProj = GL20.glGetUniformLocation(program, "u_InverseProj");
        if (locInvProj != -1) {
            org.joml.Matrix4f proj = new org.joml.Matrix4f().perspective(
                mc.options.fov().get().floatValue() * 0.017453292F,
                (float) width / (float) height,
                0.05F, 1024.0F
            );
            org.joml.Matrix4f invProj = proj.invert();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.FloatBuffer buf = stack.mallocFloat(16);
                invProj.get(buf);
                GL20.glUniformMatrix4fv(locInvProj, false, buf);
            }
        }

        int locInvView = GL20.glGetUniformLocation(program, "u_InverseView");
        if (locInvView != -1) {
            float pitch = mc.player != null ? mc.player.getXRot() : 0.0f;
            float yaw = mc.player != null ? mc.player.getYRot() : 0.0f;
            org.joml.Matrix4f view = new org.joml.Matrix4f()
                .rotationX(pitch * 0.017453292F)
                .rotateY(yaw * 0.017453292F);
            org.joml.Matrix4f invView = view.invert();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                java.nio.FloatBuffer buf = stack.mallocFloat(16);
                invView.get(buf);
                GL20.glUniformMatrix4fv(locInvView, false, buf);
            }
        }

        int locRes = GL20.glGetUniformLocation(program, "u_Resolution");
        if (locRes != -1) GL20.glUniform2f(locRes, width, height);

        int locTime = GL20.glGetUniformLocation(program, "u_Time");
        if (locTime != -1) GL20.glUniform1f(locTime, (System.currentTimeMillis() % 10000000L) / 1000.0f);

        // This is the Mio client's documented "Skybox Custom Shaders" uniform set
        // (uniform vec4 u_Color // SkyColor; vec2 u_Resolution; vec2 u_Mouse // yaw/pitch
        // angles; float u_Scale; float u_Time) -- confirmed against Mio's own docs, not
        // guessed. u_Color defaults to (0,0,0,0) if unset, so a shader that multiplies
        // its final alpha by u_Color.a (like the reference 4sXfDj port) stayed fully
        // transparent -- the compositor's mix(base, sky, sky.a) then kept 100% vanilla
        // sky no matter what the shader drew. Default to opaque white so it's visible.
        int locColor = GL20.glGetUniformLocation(program, "u_Color");
        if (locColor != -1) GL20.glUniform4f(locColor, 1.0f, 1.0f, 1.0f, 1.0f);
        int locMouse = GL20.glGetUniformLocation(program, "u_Mouse");
        if (locMouse != -1) {
            // "Yaw and pitch angles relative to Screen": these 2D-noise skybox shaders
            // have no real 3D ray reconstruction (unlike our IMAGE_FRAG/BEDROCK_SHADER) --
            // they fake camera rotation by offsetting a screen-space UV with this value.
            // Mio's own source isn't available to us, and community ports disagree on
            // scale: "sky (2).frag" ADDS u_Mouse directly into a [0,1] UV, so raw radians
            // (up to +-pi) overshot it wildly -- reported as nauseating scroll speed.
            // "shader (4).frag" instead divides by u_Resolution first, so it's insensitive
            // to this scale either way. Using a heavily damped turn-fraction here as a
            // best-effort compromise; report back if a specific shader still feels wrong
            // and we can special-case or expose a sensitivity option.
            float yawFrac = (mc.player != null ? mc.player.getYRot() : 0.0f) / 360.0f;
            float pitchFrac = (mc.player != null ? mc.player.getXRot() : 0.0f) / 360.0f;
            float sensitivity = 0.15f;
            GL20.glUniform2f(locMouse, yawFrac * sensitivity, pitchFrac * sensitivity);
        }
        int locScale = GL20.glGetUniformLocation(program, "u_Scale");
        if (locScale != -1) GL20.glUniform1f(locScale, 1.0f);

        int locIsImage = GL20.glGetUniformLocation(program, "u_IsImage");
        if (locIsImage != -1) GL20.glUniform1i(locIsImage, mode == CustomSky.Mode.Image ? 1 : 0);

        int prevActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevBoundTex = -1;
        int prevSampler = -1;
        {
            // Bind for BOTH modes now: hybrid custom shaders (mode==Shader) can declare
            // u_SkyTexture/u_IsImage to react to whatever image is currently loaded, same
            // as the built-in IMAGE_FRAG. glGetUniformLocation returns -1 harmlessly for
            // shaders that don't reference it.
            int locTex = GL20.glGetUniformLocation(program, "u_SkyTexture");
            if (locTex != -1) {
                GL20.glUniform1i(locTex, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                prevBoundTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, skyImageTexture != -1 ? skyImageTexture : 0);
                // MC 26.1.2's GPU device abstraction pairs textures with separate
                // GpuSampler objects (glBindSampler), which override a texture's own
                // glTexParameteri filter/wrap/compare settings on that unit. Whatever
                // sampler object vanilla last bound to unit 0 (e.g. a shadow/depth
                // comparison sampler) silently governs our sample instead of the
                // LINEAR/REPEAT params we set on skyImageTexture -- clear it so the
                // texture object's own parameters apply, like legacy fixed-function GL.
                prevSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
                GL33.glBindSampler(0, 0);
            }
        }

        drawQuad();

        if (prevSampler != -1) GL33.glBindSampler(0, prevSampler);

        // Restore texture state so blaze3d's cached bindings stay in sync
        if (prevBoundTex != -1) GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBoundTex);
        GL13.glActiveTexture(prevActiveTex);

        GL20.glUseProgram(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
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
}
