package com.example.addon.gui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.addon.modules.GameAnimation
import com.example.addon.modules.InventoryCleaner
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.lwjgl.glfw.GLFW

/**
 * Compose migration of the old hand-drawn WhitelistEditorScreen (deleted -- this is the
 * only whitelist editor screen now). Item icons are real MC-rendered icons drawn as a native overlay
 * (see drawNativeOverlay): ctx.item() is MC's own GL-batched item renderer, tied to MC's
 * frame render state -- it can't run inside ImageComposeScene's plain Skia software canvas,
 * so each row reports its icon slot's on-screen position via onGloballyPositioned and the
 * native overlay draws the real icon there, on top of the Compose-rendered panel.
 */
// ctx.item() always renders MC's native item icon at a fixed 16px -- there is no size
// parameter on any overload (verified via javap: the 3rd int on the 4-arg overload is a
// render-variance seed, not a size). Shrinking below that is done via the GL pose matrix
// in drawIcons (confirmed working live). ICON_DISPLAY_DP is shared between that pose
// scale and ItemRow's reserved Box size so the two stay in lockstep.
private const val ICON_DISPLAY_DP = 10

// Reads InventoryCleaner's user-configurable ColorOption (Boze's standard settings UI, same
// as VersionHUD/BetterChams/etc already expose) fresh each call -- cheap, and lets a live
// color change show up next recomposition without any extra plumbing.
private fun accentColor(alphaFraction: Float = 1f): Color {
    val v = InventoryCleaner.INSTANCE.whitelistAccentColor.value
    val packed = v.color?.packed ?: 0xFFFFFF
    val alpha = ((v.fillOpacity * alphaFraction).coerceIn(0f, 1f) * 255).toInt()
    // MASK the top byte before OR-ing in alpha, don't just OR it in directly: getPacked()'s
    // own javadoc says "0xRRGGBB" (no alpha bits) but empirically it comes back with the top
    // byte already 0xFF -- OR-ing alpha against an existing 0xFF byte always yields 0xFF
    // regardless of the computed value (OR with all-1s is a no-op), which is exactly why every
    // fill rendered fully opaque no matter what alphaFraction was passed ("alpha 255 luôn").
    return Color((packed and 0x00FFFFFF) or (alpha shl 24))
}

class WhitelistEditorComposeScreen : ComposeScreen(Component.literal("Whitelist Editor")) {

    companion object {
        // width * 2/3, height * 2.5 from the previous 260x170
        private const val PANEL_W = 173
        private const val PANEL_H = 425
        // Clearance kept below the panel so it doesn't run into the hotbar HUD row.
        private const val BOTTOM_MARGIN = 26
    }

    override val panelX: Int get() = (this.width - PANEL_W) / 2

    // GameAnimation.track() has a real-time side effect on every call -- reading it from
    // multiple call sites per frame (PiP paint, mouseClicked/mouseMoved input forwarding,
    // drawNativeOverlay) ticks the animation independently each time, so the position used to
    // hit-test a click could differ from the position actually rendered that same frame
    // (concrete symptom: clicks on the search box landed on the wrong physical-space Offset
    // and never focused it). Fix: tick exactly ONCE per rendered frame here in
    // extractRenderState (before super, so its own PiP-paint read of panelY sees the same
    // value everything else this frame will), cache it, and have the panelY getter return the
    // cached value only -- a pure read, safe from every other call site including input
    // handlers that fire between frames.
    private var animatedPanelY: Int = 0
    override val panelY: Int get() = animatedPanelY

    override val panelWidth: Int get() = PANEL_W
    override val panelHeight: Int get() = PANEL_H

    // Panel-local icon slot positions, updated live by ItemRow's onGloballyPositioned every
    // Compose layout pass -- read back here to align the native item-icon overlay.
    private val leftIconPositions = mutableMapOf<Item, ComposeRect>()
    private val rightIconPositions = mutableMapOf<Item, ComposeRect>()

    // Each LazyColumn's own visible viewport rect (its onGloballyPositioned bounds), used to
    // cull icon draws below -- see drawIcons for why this is needed.
    private var leftListViewport: ComposeRect? = null
    private var rightListViewport: ComposeRect? = null

    // Search text, typed by hand instead of through BasicTextField's own input session --
    // see charTyped/keyPressed overrides below for why.
    private val searchQuery = mutableStateOf("")

    override val content: @Composable () -> Unit = {
        WhitelistEditorContent(
            leftIconPositions, rightIconPositions,
            { leftListViewport = it }, { rightListViewport = it },
            searchQuery,
        )
    }

    // BasicTextField never accepted typed characters on this bridge even though it was
    // reachably focused (cursor visibly blinked -- confirmed live) -- ImageComposeScene is
    // the CPU-raster OFFSCREEN scene ComposeGuiBridge documents itself as using (see that
    // file's class doc: "not MC's GL context", built for rendering, not a real windowed
    // scene with an OS-level IME/text-input session attached). BasicTextField's actual
    // character-insertion path depends on that platform text-input service; without a real
    // window behind this scene, that service never does anything, so sendKeyEvent's codePoint
    // reached the scene (focus/pointer/rendering all work, proving the plumbing up to that
    // point is fine) but never turned into an edit. Bypassing that whole subsystem: append/
    // backspace `searchQuery` directly from MC's own key events, plain Compose state mutation
    // (same mechanism the whitelist add/remove `version` bump already relies on, proven
    // working) -- no IME session required at all.
    override fun charTyped(event: net.minecraft.client.input.CharacterEvent): Boolean {
        val cp = event.codepoint()
        if (cp >= 0x20 && cp != 0x7F) {
            searchQuery.value += String(Character.toChars(cp))
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: net.minecraft.client.input.KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_BACKSPACE && searchQuery.value.isNotEmpty()) {
            searchQuery.value = searchQuery.value.dropLast(1)
        }
        return super.keyPressed(event)
    }

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        // PANEL_H (425) can exceed the window's logical height at lower GUI scales --
        // plain centering then either goes negative (panel top pushed above the screen,
        // overlapping the title with other HUD elements) or leaves the panel bottom running
        // into the hotbar HUD row near the screen's bottom edge (BOTTOM_MARGIN's worth of
        // clearance, matching the ~24px vanilla hotbar occupies). Clamp into [0, maxY] so
        // top AND bottom both stay clear when the window is tall enough for both; when it
        // isn't (window shorter than PANEL_H + margins), anchoring to the top at least keeps
        // the title/search reachable, at the cost of the bottom still running off-screen --
        // an inherent tradeoff of a fixed PANEL_H taller than the available window.
        val maxY = maxOf(0, this.height - PANEL_H - BOTTOM_MARGIN)
        val target = maxOf(0, (this.height - PANEL_H) / 2).coerceAtMost(maxY).toFloat()
        animatedPanelY = GameAnimation.INSTANCE.track(this, target).roundToInt()
        super.extractRenderState(ctx, mouseX, mouseY, delta)
    }

    override fun drawNativeOverlay(ctx: GuiGraphicsExtractor) {
        drawIcons(ctx, leftIconPositions, leftListViewport)
        drawIcons(ctx, rightIconPositions, rightListViewport)
    }

    // rect is in Compose's physical-pixel space (density above) -- divide back to MC's
    // logical screen space before drawing the native icon. ctx.item() has no size param
    // (confirmed via javap -c: the 4-arg overload's 3rd int is a render-variance seed, not a
    // size) so shrinking to ICON_DISPLAY_DP is done via the GL pose matrix, same
    // pushMatrix/translate/scale/popMatrix pattern CustomTitleScreen.java uses for scaled
    // text -- this DOES work (confirmed live); the earlier "icons drifted outside the panel"
    // regression was a separate bug (see viewport cull below), not caused by the scale.
    //
    // viewport cull: clipToBounds() on the LazyColumn only clips Compose's OWN raster --
    // this native overlay is a separate draw pass on top of it and ignores that clip
    // entirely. A row scrolling out of view stays in `positions` for a frame or two before
    // ItemRow's onDispose removes it, and during that window its reported rect sits above/
    // below the list's visible band -- drawn unconditionally, that showed as each item's
    // icon flashing on top of the header in turn while scrolling ("mỗi lần scroll xuống thì
    // các item bên trên lần lượt tràn lên whitelist"). Skip anything outside the list's own
    // measured viewport instead of trusting the position map alone.
    private fun drawIcons(ctx: GuiGraphicsExtractor, positions: Map<Item, ComposeRect>, viewport: ComposeRect?) {
        val scale = ICON_DISPLAY_DP / 16f
        for ((item, rect) in positions) {
            if (viewport != null && (rect.top < viewport.top || rect.bottom > viewport.bottom)) continue
            val x = panelX + (rect.left / density).toInt()
            val y = panelY + (rect.top / density).toInt()
            ctx.pose().pushMatrix()
            ctx.pose().translate(x.toFloat(), y.toFloat())
            ctx.pose().scale(scale, scale)
            ctx.item(item.defaultInstance, 0, 0)
            ctx.pose().popMatrix()
        }
    }

    override fun onClose() {
        InventoryCleaner.saveWhitelist()
        GameAnimation.INSTANCE.clear(this)
        super.onClose()
    }
}

private fun itemKey(item: Item): String = BuiltInRegistries.ITEM.getKey(item).toString()

private fun itemLabel(item: Item): String = item.getName(item.defaultInstance).getString()

private fun computeFiltered(query: String): List<Item> {
    val q = query.lowercase().trim()
    return BuiltInRegistries.ITEM
        .filter { it != Items.AIR }
        .filter { !InventoryCleaner.whitelist.contains(itemKey(it)) }
        .filter { q.isEmpty() || itemLabel(it).lowercase().contains(q) || itemKey(it).lowercase().contains(q) }
        .sortedBy { itemLabel(it) }
}

private fun computeWhitelist(): List<Item> {
    return InventoryCleaner.whitelist
        .mapNotNull { net.minecraft.resources.Identifier.tryParse(it) }
        .mapNotNull { BuiltInRegistries.ITEM.getValue(it) }
        .filter { it != Items.AIR }
        .sortedBy { itemLabel(it) }
}

@Composable
private fun WhitelistEditorContent(
    leftIconPositions: MutableMap<Item, ComposeRect>,
    rightIconPositions: MutableMap<Item, ComposeRect>,
    onLeftViewport: (ComposeRect) -> Unit,
    onRightViewport: (ComposeRect) -> Unit,
    searchQuery: MutableState<String>,
) {
    val query = searchQuery.value
    var version by remember { mutableStateOf(0) }
    val filtered = remember(query, version) { computeFiltered(query) }
    val whitelist = remember(version) { computeWhitelist() }

    fun addItem(item: Item) {
        InventoryCleaner.whitelist.add(itemKey(item))
        version++
    }
    fun removeItem(item: Item) {
        InventoryCleaner.whitelist.remove(itemKey(item))
        version++
    }

    val corner = RoundedCornerShape(6.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Drop shadow so the panel reads as floating above the game world behind it,
            // not flush with it -- real depth cue, not just the border/fill layering below.
            .shadow(8.dp, corner)
            .clip(corner)
            .background(Color(0xDD0D0D0D), corner)
            .border(1.dp, accentColor(), corner)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 5.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            BasicText("Whitelist", style = TextStyle(color = Color.White, fontSize = 8.sp))
        }
        Divider()

        // Typed by hand (see WhitelistEditorComposeScreen.charTyped/keyPressed) instead of a
        // real BasicTextField -- that never accepted characters on this offscreen scene even
        // though it focused correctly (cursor blinked, typing silently did nothing). A fake
        // blinking cursor bar is drawn after the text so this still reads as a live text
        // field despite not being a real Compose text-editing session.
        val cursorAlpha by rememberInfiniteTransition(label = "search-cursor").animateFloat(
            initialValue = 1f, targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "search-cursor-alpha",
        )
        Box(modifier = Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 5.dp, vertical = 3.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (query.isEmpty()) {
                    BasicText("Search...", style = TextStyle(color = Color(0xFF888888), fontSize = 8.sp))
                } else {
                    BasicText(query, style = TextStyle(color = Color.White, fontSize = 8.sp))
                }
                BasicText("_", style = TextStyle(color = Color.White.copy(alpha = cursorAlpha), fontSize = 8.sp))
            }
        }
        Divider()

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ItemList(
                title = "ALL ITEMS",
                items = filtered,
                iconPositions = leftIconPositions,
                onClickItem = ::addItem,
                onViewport = onLeftViewport,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(accentColor()))
            ItemList(
                title = "WHITELIST",
                items = whitelist,
                iconPositions = rightIconPositions,
                onClickItem = ::removeItem,
                onViewport = onRightViewport,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(accentColor()))
}

@Composable
private fun ItemList(
    title: String,
    items: List<Item>,
    iconPositions: MutableMap<Item, ComposeRect>,
    onClickItem: (Item) -> Unit,
    onViewport: (ComposeRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Compose Multiplatform's built-in overscroll (rememberOverscrollEffect) renders nothing
    // on desktop -- the stretch/glow visual is Android-only; wiring overscrollEffect= to a
    // LazyColumn on desktop registers the effect but draws no bounce. Custom rubber-band
    // instead: NestedScrollConnection catches the leftover delta LazyColumn couldn't consume
    // at a boundary (onPostScroll's `available`), and a spring Animatable drives a translation
    // that visually overshoots past the edge, then springs back to 0.
    //
    // graphicsLayer, NOT Modifier.offset: offset moves the LazyColumn's actual layout/hit-test
    // bounds, so the moment it bounces once, the mouse position (unchanged, it's real cursor
    // coords) no longer intersects the shifted list -- the next momentum-scroll tick misses it
    // entirely and no further bounce can ever fire (reproduced: "nảy đúng 1 lần rồi thôi").
    // graphicsLayer's translation is draw-only, hit-test bounds stay put.
    val bounceOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) {
                    scope.launch {
                        // clipToBounds() clips to the LazyColumn's own unmoved layout rect --
                        // graphicsLayer only translates what's DRAWN inside that rect, so a
                        // bigger offset reveals more of the empty panel background behind the
                        // list before springing back (real rubber-band pull, not just a
                        // flick). Kept accumulating (not clamped) so scrolling repeatedly
                        // against the edge keeps pulling further, same as a real overscroll.
                        bounceOffset.snapTo(bounceOffset.value + available.y * 2.5f)
                        bounceOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessVeryLow))
                    }
                }
                return Offset.Zero
            }
        }
    }

    Column(modifier = modifier.padding(horizontal = 5.dp)) {
        BasicText(title, style = TextStyle(color = Color(0xFFAAAAAA), fontSize = 6.sp), modifier = Modifier.padding(vertical = 2.dp))
        // Outer frame around the whole scrollable list -- a second, outer outline nested
        // around the individually-outlined item rectangles, so the list reads as its own
        // recessed layer inside the panel instead of floating flush with it.
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .border(0.5.dp, accentColor(0.8f))
                .padding(2.dp)
                .clipToBounds()
                .nestedScroll(nestedScrollConnection)
                .graphicsLayer { translationY = bounceOffset.value }
                .onGloballyPositioned { coords -> onViewport(coords.boundsInRoot()) },
        ) {
            items(items, key = { itemKey(it) }) { item -> ItemRow(item, iconPositions, onClickItem) }
        }
    }
}

@Composable
private fun ItemRow(item: Item, iconPositions: MutableMap<Item, ComposeRect>, onClick: (Item) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    // Fill alpha is deliberately lower than the border's (see accentColor(0.6f) below) --
    // outline reads stronger than the fill behind it, the depth cue the rectangle is meant
    // to give against the panel background. Hover just brightens the same fill, still under
    // the border's alpha.
    val bgColor by animateColorAsState(if (hovered) accentColor(0.2f) else accentColor(0.06f))
    val rowScale by animateFloatAsState(if (pressed) 0.96f else 1f)

    DisposableEffect(item) { onDispose { iconPositions.remove(item) } }

    // Each item sits in its own bordered+filled rectangle. Real spacing between rows comes
    // from the LazyColumn's Arrangement.spacedBy above, not padding here (padding here was
    // too subtle to read as a gap -- see reported "không được split khoảng trống").
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .scale(rowScale)
                .hoverable(interactionSource)
                .background(bgColor)
                .border(0.5.dp, accentColor(0.6f))
                .clickable(interactionSource = interactionSource, indication = null) { onClick(item) }
                .padding(horizontal = 1.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            // Real icon drawn by ComposeScreen.drawNativeOverlay -- this Box just reserves the
            // slot and reports its on-screen rect to the position map every layout pass. Native
            // draw is scaled down to match via ctx.pose() (see drawIcons) -- keep these in sync.
            Box(
                modifier = Modifier
                    .size(ICON_DISPLAY_DP.dp)
                    .onGloballyPositioned { coords -> iconPositions[item] = coords.boundsInRoot() }
            )
            Spacer(modifier = Modifier.width(2.dp))
            // maxLines=1 + ellipsis: this column is narrow (panel shrunk to 173dp) so long
            // item names ("Acacia Boat with Chest") wrapped to 2 lines inside a fixed-height
            // Row, which doesn't clip its own overflow by default -- the wrapped second line
            // spilled outside the row into neighboring rows/the header ("item tràn lên cả
            // Whitelist"). Capping to one line with an ellipsis is the actual fix.
            BasicText(
                itemLabel(item),
                style = TextStyle(color = Color.White, fontSize = 6.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
