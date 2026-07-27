package com.example.addon.gui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.addon.screens.SkiaGlState
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12C
import java.nio.ByteBuffer

/**
 * CPU-raster bridge: ImageComposeScene renders each frame to an offscreen Skia Image
 * (software surface, not MC's GL context -- ImageComposeScene has no constructor param
 * for an external GL context, verified via javap on the resolved jar, not assumed), then
 * this uploads the pixels into a GL texture ComposeScreen can blit. Only runs while a
 * ComposeScreen is open, so the per-frame CPU copy doesn't touch normal gameplay FPS.
 */
class ComposeGuiBridge(
    private var width: Int,
    private var height: Int,
    private var density: Float,
    content: @Composable () -> Unit,
) {
    private var scene = ImageComposeScene(
        width = width,
        height = height,
        density = Density(density),
        coroutineContext = Dispatchers.Unconfined,
        content = content,
    )

    private var texture = -1
    private var pixelBuf: ByteBuffer? = null
    private var glState: SkiaGlState? = null

    private fun ensureTexture() {
        if (texture != -1) return
        texture = GL11C.glGenTextures()
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE)
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE)
    }

    /** Resizes the underlying scene (e.g. on Screen#resize or a GUI-scale change). Recreates the scene -- ImageComposeScene has no resize(). */
    fun resize(newWidth: Int, newHeight: Int, newDensity: Float, content: @Composable () -> Unit) {
        if (newWidth == width && newHeight == height && newDensity == density) return
        width = newWidth
        height = newHeight
        density = newDensity
        scene.close()
        scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(density),
            coroutineContext = Dispatchers.Unconfined,
            content = content,
        )
    }

    /**
     * Renders one Compose frame and uploads it into the GL texture. Returns the texture id.
     *
     * Wrapped in SkiaGlState (same push/pop this codebase already uses around every other
     * raw-GL draw squeezed into MC's own frame -- SkijaBackdropBlur, AbstractSkiaPipRenderer):
     * without it, glTexImage2D crashed the NVIDIA driver with EXCEPTION_ACCESS_VIOLATION.
     * Root cause: MC's own pipeline (GL_ARB_buffer_storage/DSA) can leave a
     * GL_PIXEL_UNPACK_BUFFER bound from earlier in the same frame -- with one bound,
     * glTexImage2D's `pixels` argument is read as a BYTE OFFSET into that GPU buffer, not a
     * client-memory pointer, so our ByteBuffer's address got reinterpreted as a garbage
     * offset. SkiaGlState.push() unbinds it (and resets pack/unpack pixel-store state) before
     * we touch GL, pop() restores whatever MC actually had.
     */
    fun renderFrame(nanoTime: Long): Int {
        val state = glState ?: SkiaGlState().also { glState = it }
        state.push()
        try {
            renderFrameUnsafe(nanoTime)
        } finally {
            state.pop()
        }
        return texture
    }

    private fun renderFrameUnsafe(nanoTime: Long) {
        ensureTexture()
        val image: Image = scene.render(nanoTime)

        // Recomputed every call (not cached) -- caching this as a val tied to the ORIGINAL
        // constructor width/height went stale across resize() (which replaces `scene` but
        // never touched a cached ImageInfo), so a post-resize frame uploaded a buffer sized
        // for the OLD dimensions while telling glTexImage2D the NEW ones: a native
        // out-of-bounds read in the GL driver (EXCEPTION_ACCESS_VIOLATION inside
        // nvoglv64.dll, reproduced during nglTexImage2D). Recomputing is cheap (a value
        // object, no allocation of real pixel storage) and removes that whole class of bug.
        val rgbaInfo = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val rowBytes = width * 4
        val expectedBytes = rowBytes * height

        // Fast path: peekPixels() is a zero-copy VIEW into the raster this ImageComposeScene
        // already produced (verified via javap on skiko-awt 0.150.1 -- Image.peekPixels only
        // succeeds for a raster/non-GPU-backed image, which this bridge's `image` always is,
        // per its own CPU-raster design, see class doc). Skipping straight to its buffer
        // avoids the Bitmap.allocPixels() + image.readPixels(bitmap) below entirely -- that's
        // a full native pixel blit into a freshly allocated surface, the single most expensive
        // step per frame at this panel's resolution. Only trusted when the pixmap's format
        // ALREADY matches exactly what glTexImage2D below expects (RGBA_8888/PREMUL, tightly
        // packed rowBytes) -- any mismatch falls through to the proven-safe Bitmap path,
        // which forces the conversion via readPixels(rgbaInfo, ...) instead. Correctness over
        // speed if anything doesn't line up exactly.
        val pixmap = image.peekPixels()
        if (pixmap != null) {
            val info = pixmap.info
            if (info.colorType == ColorType.RGBA_8888 && info.colorAlphaType == ColorAlphaType.PREMUL
                && pixmap.rowBytes == rowBytes) {
                val raw = pixmap.buffer.bytes
                if (raw.size == expectedBytes) {
                    uploadToGl(raw)
                    return
                }
            }
        }

        val bitmap = Bitmap()
        bitmap.use {
            if (!it.allocPixels(rgbaInfo)) return
            if (!image.readPixels(it)) return
            val raw = it.readPixels(rgbaInfo, rowBytes, 0, 0) ?: return
            // Defensive: a size mismatch here means the following glTexImage2D would read
            // past the buffer's end in native code -- skip the frame instead of crashing.
            if (raw.size != expectedBytes) return
            uploadToGl(raw)
        }
    }

    private fun uploadToGl(raw: ByteArray) {
        var buf = pixelBuf
        if (buf == null || buf.capacity() < raw.size) {
            buf = ByteBuffer.allocateDirect(raw.size)
            pixelBuf = buf
        }
        buf.clear()
        buf.put(raw)
        buf.flip()

        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture)
        GL11C.glTexImage2D(
            GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA8, width, height, 0,
            GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf,
        )
    }

    fun sceneRef() = scene

    fun dispose() {
        scene.close()
        if (texture != -1) {
            GL11C.glDeleteTextures(texture)
            texture = -1
        }
    }
}
