package com.example.addon.screens;

import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Saves/restores the full OpenGL state around a Skija draw so that Skija's GPU
 * rendering does not corrupt Minecraft's own rendering.
 *
 * <p>Direct port of Lyzev/Skimi's {@code State}/{@code States} (itself adapted from
 * imgui-java). The set of saved state is load-bearing: in particular the
 * {@code glDepthMask} save/restore fixes MC text flickering and buttons/HUD
 * disappearing after a window resize when an external GL renderer runs each frame.
 */
public final class SkiaGlState {

    private final int glVersion;

    private int lastActiveTexture, lastProgram, lastTexture, lastSampler;
    private int lastArrayBuffer, lastVertexArrayObject;
    private final int[] lastPolygonMode = new int[2];
    private final int[] lastViewport = new int[4];
    private final int[] lastScissorBox = new int[4];
    private int lastBlendSrcRgb, lastBlendDstRgb, lastBlendSrcAlpha, lastBlendDstAlpha;
    private int lastBlendEquationRgb, lastBlendEquationAlpha;
    private boolean lastEnableBlend, lastEnableCullFace, lastEnableDepthTest;
    private boolean lastEnableStencilTest, lastEnableScissorTest, lastEnablePrimitiveRestart;
    private boolean lastDepthMask;

    private int lastPixelUnpackBufferBinding;
    private int lastPackSwapBytes, lastPackLsbFirst, lastPackRowLength, lastPackSkipPixels, lastPackSkipRows, lastPackAlignment;
    private int lastUnpackSwapBytes, lastUnpackLsbFirst, lastUnpackAlignment, lastUnpackRowLength, lastUnpackSkipPixels, lastUnpackSkipRows;
    private int lastPackImageHeight, lastPackSkipImages, lastUnpackImageHeight, lastUnpackSkipImages;

    public SkiaGlState() {
        int major = glGetInteger(GL_MAJOR_VERSION);
        int minor = glGetInteger(GL_MINOR_VERSION);
        this.glVersion = major * 100 + minor * 10;
    }

    /** Saves the current GL state and sets a neutral pixel-store state for Skija. */
    public void push() {
        lastActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        lastProgram = glGetInteger(GL_CURRENT_PROGRAM);
        lastTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        if (glVersion >= 330 || GL.getCapabilities().GL_ARB_sampler_objects) {
            lastSampler = glGetInteger(GL_SAMPLER_BINDING);
        }
        lastArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        lastVertexArrayObject = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        glGetIntegerv(GL_POLYGON_MODE, lastPolygonMode);
        glGetIntegerv(GL_VIEWPORT, lastViewport);
        glGetIntegerv(GL_SCISSOR_BOX, lastScissorBox);
        lastBlendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        lastBlendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        lastBlendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        lastBlendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        lastBlendEquationRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
        lastBlendEquationAlpha = glGetInteger(GL_BLEND_EQUATION_ALPHA);
        lastEnableBlend = glIsEnabled(GL_BLEND);
        lastEnableCullFace = glIsEnabled(GL_CULL_FACE);
        lastEnableDepthTest = glIsEnabled(GL_DEPTH_TEST);
        lastEnableStencilTest = glIsEnabled(GL_STENCIL_TEST);
        lastEnableScissorTest = glIsEnabled(GL_SCISSOR_TEST);
        if (glVersion >= 310) lastEnablePrimitiveRestart = glIsEnabled(GL_PRIMITIVE_RESTART);
        lastDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);

        lastPixelUnpackBufferBinding = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

        lastPackSwapBytes = glGetInteger(GL_PACK_SWAP_BYTES);
        lastPackLsbFirst = glGetInteger(GL_PACK_LSB_FIRST);
        lastPackRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        lastPackSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        lastPackSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        lastPackAlignment = glGetInteger(GL_PACK_ALIGNMENT);

        lastUnpackSwapBytes = glGetInteger(GL_UNPACK_SWAP_BYTES);
        lastUnpackLsbFirst = glGetInteger(GL_UNPACK_LSB_FIRST);
        lastUnpackAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        lastUnpackRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        lastUnpackSkipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        lastUnpackSkipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);

        if (glVersion >= 120) {
            lastPackImageHeight = glGetInteger(GL_PACK_IMAGE_HEIGHT);
            lastPackSkipImages = glGetInteger(GL_PACK_SKIP_IMAGES);
            lastUnpackImageHeight = glGetInteger(GL_UNPACK_IMAGE_HEIGHT);
            lastUnpackSkipImages = glGetInteger(GL_UNPACK_SKIP_IMAGES);
        }

        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    }

    /** Restores the state saved by {@link #push()}. */
    public void pop() {
        glUseProgram(lastProgram);
        glBindTexture(GL_TEXTURE_2D, lastTexture);
        if (glVersion >= 330 || GL.getCapabilities().GL_ARB_sampler_objects) {
            glBindSampler(0, lastSampler);
        }
        glActiveTexture(lastActiveTexture);
        glBindVertexArray(lastVertexArrayObject);
        glBindBuffer(GL_ARRAY_BUFFER, lastArrayBuffer);
        glBlendEquationSeparate(lastBlendEquationRgb, lastBlendEquationAlpha);
        glBlendFuncSeparate(lastBlendSrcRgb, lastBlendDstRgb, lastBlendSrcAlpha, lastBlendDstAlpha);
        if (lastEnableBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (lastEnableCullFace) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        if (lastEnableDepthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (lastEnableStencilTest) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
        if (lastEnableScissorTest) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        if (glVersion >= 310) {
            if (lastEnablePrimitiveRestart) glEnable(GL_PRIMITIVE_RESTART); else glDisable(GL_PRIMITIVE_RESTART);
        }
        glPolygonMode(GL_FRONT_AND_BACK, lastPolygonMode[0]);
        glViewport(lastViewport[0], lastViewport[1], lastViewport[2], lastViewport[3]);
        glScissor(lastScissorBox[0], lastScissorBox[1], lastScissorBox[2], lastScissorBox[3]);

        glPixelStorei(GL_PACK_SWAP_BYTES, lastPackSwapBytes);
        glPixelStorei(GL_PACK_LSB_FIRST, lastPackLsbFirst);
        glPixelStorei(GL_PACK_ROW_LENGTH, lastPackRowLength);
        glPixelStorei(GL_PACK_SKIP_PIXELS, lastPackSkipPixels);
        glPixelStorei(GL_PACK_SKIP_ROWS, lastPackSkipRows);
        glPixelStorei(GL_PACK_ALIGNMENT, lastPackAlignment);

        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, lastPixelUnpackBufferBinding);
        glPixelStorei(GL_UNPACK_SWAP_BYTES, lastUnpackSwapBytes);
        glPixelStorei(GL_UNPACK_LSB_FIRST, lastUnpackLsbFirst);
        glPixelStorei(GL_UNPACK_ALIGNMENT, lastUnpackAlignment);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, lastUnpackRowLength);
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, lastUnpackSkipPixels);
        glPixelStorei(GL_UNPACK_SKIP_ROWS, lastUnpackSkipRows);

        if (glVersion >= 120) {
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, lastPackImageHeight);
            glPixelStorei(GL_PACK_SKIP_IMAGES, lastPackSkipImages);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, lastUnpackImageHeight);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, lastUnpackSkipImages);
        }

        // Critical: restore depth-write mask, else MC text flickers and HUD/buttons
        // vanish after a resize.
        glDepthMask(lastDepthMask);
    }
}
