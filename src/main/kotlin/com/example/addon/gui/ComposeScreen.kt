@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.example.addon.gui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import com.example.addon.mixin.GuiGraphicsExtractorAccessor
import com.example.addon.screens.ComposeContentPipRenderer
import com.example.addon.screens.ComposeContentPipState
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.Paint
import io.github.humbleui.types.Rect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Base for addon screens whose content is a Compose composable instead of hand-drawn
 * GuiGraphics calls. CPU-raster bridge (see ComposeGuiBridge): ComposeGuiBridge uploads
 * each Compose frame into a plain (not TextureManager-registered) GL texture, which this
 * queues through the SAME Skia PiP mechanism WebBrowserPipRenderer uses for MCEF's own
 * externally-owned texture -- see AbstractSkiaPipRenderer's class doc.
 */
abstract class ComposeScreen(title: Component) : Screen(title) {

    abstract val panelX: Int
    abstract val panelY: Int
    abstract val panelWidth: Int
    abstract val panelHeight: Int

    abstract val content: @Composable () -> Unit

    private var bridge: ComposeGuiBridge? = null

    // Compose renders at PHYSICAL pixel resolution (logical size * GUI scale), same fix
    // WebBrowser.java already needed for MCEF's texture (see its resize() comment) -- a
    // texture captured at LOGICAL size and then upscaled onto a higher-DPI physical canvas
    // reads as blurry (reproduced: text/panel visibly soft at GUI scale > 1). `density` is
    // exposed so subclasses can convert Compose-space layout positions (e.g. icon slots from
    // onGloballyPositioned, which report physical-pixel coordinates) back to MC's logical
    // screen space.
    protected var density: Float = 1f
        private set

    private fun currentGuiScale(): Float = Minecraft.getInstance().window.guiScale.toFloat()

    override fun init() {
        super.init()
        density = currentGuiScale()
        bridge = ComposeGuiBridge(
            (panelWidth * density).roundToInt().coerceAtLeast(1),
            (panelHeight * density).roundToInt().coerceAtLeast(1),
            density, content,
        )
    }

    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        density = currentGuiScale()
        bridge?.resize(
            (panelWidth * density).roundToInt().coerceAtLeast(1),
            (panelHeight * density).roundToInt().coerceAtLeast(1),
            density, content,
        )
    }

    // 26.1.2 render entry point: extractRenderState(GuiGraphicsExtractor,...), not render(GuiGraphics,...).
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        tickScrollMomentum()
        val b = bridge
        if (b != null) {
            val tex = b.renderFrame(System.nanoTime())
            val x0 = panelX; val y0 = panelY
            val x1 = panelX + panelWidth; val y1 = panelY + panelHeight
            val physW = (panelWidth * density).roundToInt().coerceAtLeast(1)
            val physH = (panelHeight * density).roundToInt().coerceAtLeast(1)
            (ctx as GuiGraphicsExtractorAccessor).guiRenderState.addPicturesInPictureState(
                ComposeContentPipState(
                    { canvas -> paint(canvas, tex, physW, physH, x0, y0, panelWidth, panelHeight) },
                    x0, y0, x1, y1,
                )
            )
        }
        // Native MC draws (e.g. real item icons) queued AFTER the compose PiP state land on
        // top of it -- both go through the same GuiRenderState, insertion order = draw order.
        drawNativeOverlay(ctx)
        super.extractRenderState(ctx, mouseX, mouseY, delta)
    }

    /** Override to draw MC-native elements (e.g. ctx.item()) aligned to Compose-computed layout positions. */
    protected open fun drawNativeOverlay(ctx: GuiGraphicsExtractor) {}

    // texW/texH = the GL texture's real (physical) pixel size, for borrowTexture's BackendTexture
    // description; x/y/dstW/dstH = the LOGICAL on-screen destination rect (AbstractSkiaPipRenderer
    // already pre-scales the canvas to physical, so this draw call stays in logical coordinates,
    // same contract WebBrowserPipState/GifContentPipState's painters use).
    private fun paint(canvas: Canvas, tex: Int, texW: Int, texH: Int, x: Int, y: Int, dstW: Int, dstH: Int) {
        val renderer = ComposeContentPipRenderer.ACTIVE ?: return
        val img: Image = renderer.borrowTexture(this, tex, texW, texH, false) ?: return
        Paint().use { paint ->
            canvas.drawImageRect(img, Rect.makeXYWH(x.toFloat(), y.toFloat(), dstW.toFloat(), dstH.toFloat()), paint)
        }
    }

    override fun removed() {
        super.removed()
        bridge?.dispose()
        bridge = null
    }

    override fun isPauseScreen() = false

    // ── Input forwarding: MC event objects (26.1.2's InputWithModifiers records) -> Compose
    // pointer/key events on the ImageComposeScene, in panel-local coordinates. ──

    // MC gives logical coordinates; the scene is authored in physical pixels (density above) --
    // scale before forwarding so pointer hit-testing lines up with what's actually drawn.
    private fun local(x: Double, y: Double) = Offset(((x - panelX) * density).toFloat(), ((y - panelY) * density).toFloat())

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX, mouseY)
        bridge?.sceneRef()?.sendPointerEvent(PointerEventType.Move, local(mouseX, mouseY))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        bridge?.sceneRef()?.sendPointerEvent(
            PointerEventType.Press, local(event.x(), event.y()), button = PointerButton(event.button())
        )
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        bridge?.sceneRef()?.sendPointerEvent(
            PointerEventType.Release, local(event.x(), event.y()), button = PointerButton(event.button())
        )
        return super.mouseReleased(event)
    }

    // Smoothing lives HERE, not inside Compose's own scroll machinery: an earlier attempt used
    // a NestedScrollConnection calling animateScrollBy() on the same LazyListState the gesture
    // itself was already scrolling -- two concurrent claims on that state's MutatorMutex
    // (the raw gesture dispatch vs. our own animation) fought and cancelled each other,
    // reproduced as scrolling not working AT ALL. This instead never touches Compose's scroll
    // internals: each real wheel notch is queued, then released as several smaller
    // PointerEventType.Scroll events over the following frames (exponential decay) -- the
    // exact same forwarding call that already worked, just spread over time instead of fired
    // once. LazyColumn sees an ordinary sequence of small scrolls, nothing Compose-internal to
    // conflict with.
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f

    private fun tickScrollMomentum() {
        if (pendingScrollX == 0f && pendingScrollY == 0f) return
        val stepX = pendingScrollX * SCROLL_DECAY
        val stepY = pendingScrollY * SCROLL_DECAY
        pendingScrollX -= stepX
        pendingScrollY -= stepY
        if (abs(pendingScrollX) < SCROLL_STOP_EPSILON) pendingScrollX = 0f
        if (abs(pendingScrollY) < SCROLL_STOP_EPSILON) pendingScrollY = 0f
        bridge?.sceneRef()?.sendPointerEvent(
            PointerEventType.Scroll, local(lastMouseX, lastMouseY),
            scrollDelta = Offset(-stepX, -stepY),
        )
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        lastMouseX = mouseX
        lastMouseY = mouseY
        pendingScrollX += horizontalAmount.toFloat()
        pendingScrollY += verticalAmount.toFloat()
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private companion object {
        // Fraction of the pending scroll released each frame -- lower = longer/smoother trail.
        const val SCROLL_DECAY = 0.35f
        const val SCROLL_STOP_EPSILON = 0.02f
    }

    // Backspace/Delete/Enter/Escape/arrows: BasicTextField's default key handling dispatches
    // on these Key constants. Everything else (letters/digits/symbols) arrives via charTyped
    // below instead -- GLFW keyPressed doesn't carry the actual typed/shifted character.
    private fun composeKey(glfwKey: Int): Key? = when (glfwKey) {
        GLFW.GLFW_KEY_BACKSPACE -> Key.Backspace
        GLFW.GLFW_KEY_DELETE -> Key.Delete
        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> Key.Enter
        GLFW.GLFW_KEY_ESCAPE -> Key.Escape
        GLFW.GLFW_KEY_LEFT -> Key.DirectionLeft
        GLFW.GLFW_KEY_RIGHT -> Key.DirectionRight
        GLFW.GLFW_KEY_UP -> Key.DirectionUp
        GLFW.GLFW_KEY_DOWN -> Key.DirectionDown
        GLFW.GLFW_KEY_HOME -> Key.MoveHome
        GLFW.GLFW_KEY_END -> Key.MoveEnd
        GLFW.GLFW_KEY_TAB -> Key.Tab
        else -> null
    }

    override fun keyPressed(event: net.minecraft.client.input.KeyEvent): Boolean {
        val key = composeKey(event.key())
        if (key != null) {
            bridge?.sceneRef()?.sendKeyEvent(ComposeKeyEvent(key = key, type = KeyEventType.KeyDown))
        }
        return super.keyPressed(event)
    }

    override fun keyReleased(event: net.minecraft.client.input.KeyEvent): Boolean {
        val key = composeKey(event.key())
        if (key != null) {
            bridge?.sceneRef()?.sendKeyEvent(ComposeKeyEvent(key = key, type = KeyEventType.KeyUp))
        }
        return super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        bridge?.sceneRef()?.sendKeyEvent(
            ComposeKeyEvent(key = Key.Unknown, type = KeyEventType.KeyDown, codePoint = event.codepoint())
        )
        return super.charTyped(event)
    }
}
