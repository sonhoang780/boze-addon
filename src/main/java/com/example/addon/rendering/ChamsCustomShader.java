package com.example.addon.rendering;

import com.example.addon.modules.BetterChams;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class ChamsCustomShader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChamsCustomShader.class);

    public static final Identifier OUTLINE_TEX_ID = Identifier.fromNamespaceAndPath("example-addon", "textures/effect/betterchamsoutline.png");

    private static int program = -1;
    public static int fillFbo = -1;
    public static int outlineFbo = -1;
    
    private static int uSizeLoc, uTimeLoc, uFillColorLoc, uOutlineColorLoc, uIsOutlineLoc;
    // Legacy Boze uniforms
    private static int uResolutionLoc, uColorLoc, uScaleLoc, uMouseLoc;

    private static final String VERTEX_SHADER = 
        "#version 330\n" +
        "layout(location = 0) in vec3 Position;\n" +
        "out vec2 texCoord;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(Position, 1.0);\n" +
        "    texCoord = Position.xy * 0.5 + 0.5;\n" +
        "}\n";

    private static final String FRAG_HEADER = 
        "#version 330\n" +
        "uniform vec2 u_Size;\n" +
        "uniform float u_Time;\n" +
        "uniform vec4 u_FillColor;\n" +
        "uniform vec4 u_OutlineColor;\n" +
        "uniform int u_IsOutline;\n" +
        "uniform bool u_Image;\n" +
        "uniform sampler2D u_Overlay;\n" +
        "uniform float u_OverlayAlpha;\n" +
        "#define iResolution vec3(u_Size, 1.0)\n" +
        "#define iTime u_Time\n" +
        "out vec4 fragColor;\n";

    // Minimal header for standalone shaders (void main()) that have their own uniforms.
    // Only provides #version and the fragColor output — avoids duplicate declarations.
    private static final String FRAG_HEADER_STANDALONE = 
        "#version 330\n" +
        "out vec4 fragColor;\n";

    public static void loadShader(Path path) {
        try {
            String userCode = new String(Files.readAllBytes(path));
            
            // Detect shader type
            boolean hasMain     = userCode.matches("(?s).*\\bvoid\\s+main\\s*\\(.*");
            boolean isShadertoy = userCode.contains("void mainImage");
            
            String fullFrag;
            if (hasMain) {
                // Standalone shader (e.g. sky.frag, legacy Boze shaders).
                // Strip its own #version line so ours takes precedence.
                userCode = userCode.replaceAll("(?m)^[ \\t]*#version\\s+.*$", "");
                
                // Strip any "out vec4 <name>;" declarations and rename <name>→fragColor
                // so they don't conflict with our fragColor output.
                java.util.regex.Pattern outPattern = java.util.regex.Pattern.compile("(?m)^[ \\t]*out\\s+vec4\\s+(\\w+)\\s*;");
                java.util.regex.Matcher m = outPattern.matcher(userCode);
                while (m.find()) {
                    String varName = m.group(1);
                    userCode = userCode.replace(m.group(0), ""); // Always remove the declaration!
                    if (!varName.equals("fragColor")) {
                        userCode = userCode.replaceAll("(?<![\\w.])\\b" + java.util.regex.Pattern.quote(varName) + "\\b", "fragColor");
                    }
                    // restart search after replacement
                    m = outPattern.matcher(userCode);
                }
                
                // Use minimal header only — the shader provides its own uniforms
                fullFrag = FRAG_HEADER_STANDALONE + userCode;
            } else {
                // BetterChams-format  : vec4 mainImage(bool isOutline)
                // ShaderToy-format    : void mainImage(out vec4 O, vec2 I)
                String footer = isShadertoy ?
                    "\nvoid main() {\n" +
                    "    mainImage(fragColor, gl_FragCoord.xy);\n" +
                    "}\n" :
                    "\nvoid main() {\n" +
                    "    fragColor = mainImage(u_IsOutline != 0);\n" +
                    "}\n";
                fullFrag = FRAG_HEADER + userCode + footer;
            }

            if (program != -1) {
                GL20.glDeleteProgram(program);
            }
            
            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, VERTEX_SHADER);
            GL20.glCompileShader(vs);
            if (GL20.glGetShaderi(vs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                LOGGER.error("Vertex shader compile error: " + GL20.glGetShaderInfoLog(vs));
                return;
            }
            
            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, fullFrag);
            GL20.glCompileShader(fs);
            if (GL20.glGetShaderi(fs, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                LOGGER.error("Fragment shader compile error: " + GL20.glGetShaderInfoLog(fs));
                return;
            }
            
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glLinkProgram(program);
            
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                LOGGER.error("Shader link error: " + GL20.glGetProgramInfoLog(program));
                return;
            }
            
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            uSizeLoc = GL20.glGetUniformLocation(program, "u_Size");
            uTimeLoc = GL20.glGetUniformLocation(program, "u_Time");
            uFillColorLoc = GL20.glGetUniformLocation(program, "u_FillColor");
            uOutlineColorLoc = GL20.glGetUniformLocation(program, "u_OutlineColor");
            uIsOutlineLoc = GL20.glGetUniformLocation(program, "u_IsOutline");
            
            uResolutionLoc = GL20.glGetUniformLocation(program, "u_Resolution");
            uColorLoc = GL20.glGetUniformLocation(program, "u_Color");
            uScaleLoc = GL20.glGetUniformLocation(program, "u_Scale");
            uMouseLoc = GL20.glGetUniformLocation(program, "u_Mouse");
            
            LOGGER.info("Successfully compiled custom shader: " + path.getFileName());
        } catch (Exception e) {
            LOGGER.error("Failed to load shader file", e);
        }
    }

    public static void renderCustomShader() {
        if (BetterChams.INSTANCE.fillMode.getValue() != BetterChams.FillMode.Shader || program == -1) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        BetterChams.CHAMS_TEXTURE.resizeForShader(width, height);
        BetterChams.OUTLINE_TEXTURE.resizeForShader(width, height);

        int fillTexId = BetterChams.CHAMS_TEXTURE.getRawTextureId();
        int outlineTexId = BetterChams.OUTLINE_TEXTURE.getRawTextureId();

        if (fillTexId == -1 || outlineTexId == -1) return;

        if (fillFbo == -1) {
            fillFbo = GL30.glGenFramebuffers();
        }
        if (outlineFbo == -1) {
            outlineFbo = GL30.glGenFramebuffers();
        }

        int previousFBO = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        GL20.glUseProgram(program);

        GL20.glUniform2f(uSizeLoc, width, height);
        float time = (System.currentTimeMillis() % 1000000) / 1000f;
        GL20.glUniform1f(uTimeLoc, time);
        
        int fillC = BetterChams.INSTANCE.fillColor.getValue().color.getPacked();
        float fr = ((fillC >> 16) & 0xFF) / 255f;
        float fg = ((fillC >> 8) & 0xFF) / 255f;
        float fb = (fillC & 0xFF) / 255f;
        float fa = ((fillC >> 24) & 0xFF) / 255f;
        GL20.glUniform4f(uFillColorLoc, fr, fg, fb, fa);
        if (uColorLoc != -1) GL20.glUniform4f(uColorLoc, fr, fg, fb, fa);
        
        int outC = BetterChams.INSTANCE.outlineColor.getValue().color.getPacked();
        float or = ((outC >> 16) & 0xFF) / 255f;
        float og = ((outC >> 8) & 0xFF) / 255f;
        float ob = (outC & 0xFF) / 255f;
        float oa = ((outC >> 24) & 0xFF) / 255f;
        GL20.glUniform4f(uOutlineColorLoc, or, og, ob, oa);
        
        if (uResolutionLoc != -1) GL20.glUniform2f(uResolutionLoc, width, height);
        if (uScaleLoc != -1) GL20.glUniform1f(uScaleLoc, 1.0f);
        if (uMouseLoc != -1) GL20.glUniform2f(uMouseLoc, 0.0f, 0.0f);

        // Draw Fill
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fillFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, fillTexId, 0);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL20.glUniform1i(uIsOutlineLoc, 0);
        drawQuad();

        // Draw Outline
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outlineFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outlineTexId, 0);
        GL11.glViewport(0, 0, width, height);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL20.glUniform1i(uIsOutlineLoc, 1);
        drawQuad();

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFBO);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL20.glUseProgram(0);
    }

    private static int vao = -1;
    private static int vbo = -1;

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
