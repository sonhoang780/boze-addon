package com.example.addon.modules;

import com.example.addon.modules.webbrowser.mcef.MCEF;
import com.example.addon.modules.webbrowser.mcef.MCEFBrowser;
import com.example.addon.modules.webbrowser.WebBrowserManager;
import com.example.addon.mixin.GuiGraphicsExtractorAccessor;
import com.example.addon.screens.WebBrowserPipRenderer;
import com.example.addon.screens.WebBrowserPipState;
import com.example.addon.screens.WebBrowserShadowPipState;
import com.example.addon.screens.WebBrowserScreen;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.BindOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.event.EventBind;
import meteordevelopment.orbit.EventHandler;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * HUD tile showing WebBrowserManager's currently-active tab, following this addon's own
 * draggable-image-HUD pattern (GifHUD: HudElementRegistry + HudPositions + width/height
 * SliderOptions + GLFW-poll drag), NOT Boze's AddonHudModule (text-line only).
 *
 * Drawing MCEF's texture does NOT go through context.blit(Identifier, ...) like GifHUD's
 * frames: MCEFRenderer.getTextureLocation() returns net.minecraft.resources.Identifier
 * typed against MCEF's OWN (Yarn-intermediary, 1.21.10) compiled classpath, which fails
 * to resolve at compile time against this project's differently-mapped Minecraft classes
 * ("class file for net.minecraft.class_2960 not found") -- MCEF's Fabric jar was pulled
 * in via plain implementation/include (bundled like ViaFabricPlus), not Loom's mod-remap
 * pipeline. getTextureID() (a raw int, zero Minecraft-type dependency) DOES resolve, and
 * this addon already has an established zero-copy path from a raw GL texture ID into the
 * GUI's deferred Picture-in-Picture render queue -- see AbstractSkiaPipRenderer's class
 * doc for why raw GL calls during HUD extraction are unsafe here in the first place
 * (GuiGraphicsExtractor is a deferred "extract now, submit later" pipeline, unlike
 * LevelRenderEvents' 3D "drawing phase" hooks CustomSky/AuraStep use raw GL from).
 *
 * Opening the browser is keybind-only (openKey) -- clicking the tile to focus it was
 * removed: the tile's mouse position only means anything when the cursor is already free,
 * which it normally isn't during gameplay (camera-captured), making click-to-focus
 * practically undiscoverable. A keybind works regardless of cursor state.
 *
 * See docs/superpowers/specs/2026-07-10-web-browser-design.md and
 * docs/superpowers/specs/2026-07-10-webbrowser-multitab-design.md.
 */
public class WebBrowser extends AddonModule {

    public static final WebBrowser INSTANCE = new WebBrowser();
    public boolean active = false;

    private static final float CORNER_RADIUS = 8f;

    private double posX = com.example.addon.util.HudPositions.getX("WebBrowser", 50.0);
    private double posY = com.example.addon.util.HudPositions.getY("WebBrowser", 50.0);
    public final SliderOption width = new SliderOption(this, "Width", "", 320.0, 100.0, 1000.0, 1.0);
    public final SliderOption height = new SliderOption(this, "Height", "", 180.0, 60.0, 600.0, 1.0);
    public final BindOption openKey = new BindOption(this, "Open Key", "Key that opens the interactive browser screen.", GLFW.GLFW_KEY_B, false);
    public final ToggleOption showHud = new ToggleOption(this, "ShowHud",
        "Shows the floating browser tile HUD. Independent of the browser process itself -- disable to hide the tile while still being able to open the full-screen browser via Open Key (the CEF message loop keeps pumping either way, so tabs opened in the full-screen browser keep working).", true);

    private boolean isDraggingHUD = false;
    private double dragOffsetX = 0, dragOffsetY = 0;
    private boolean wasMouseDownEditor = false;
    private int lastTexW = -1, lastTexH = -1;
    // Which MCEFBrowser instance lastTexW/lastTexH actually apply to -- with multi-tab,
    // switching/opening tabs swaps in a DIFFERENT MCEFBrowser (its own fresh
    // DEFAULT_WIDTH/HEIGHT-sized process) while the tile's logical W/H slider is
    // unchanged, so a dimension-only dedupe check wrongly skipped resize() on the new
    // browser (looked "already resized" because the numbers matched the PREVIOUS tab's).
    private MCEFBrowser lastResizedBrowser = null;
    private long loadingSinceMs = -1;
    private boolean slowLoadWarned = false;

    private WebBrowser() {
        super("WebBrowser", "Embedded Chromium browser HUD tile (MCEF).");
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("example-addon", "webbrowser"), (context, tracker) -> {
            if (this.active) render(context);
        });
    }

    @Override
    public void onEnable() {
        this.active = true;
        WebBrowserManager.init();
    }

    @Override
    public void onDisable() {
        this.active = false;
        WebBrowserManager.close();
    }

    @EventHandler
    private void onBind(EventBind event) {
        if (!active) return;
        if (openKey.getBind() < 0) return; // unbound
        if (event.action != GLFW.GLFW_PRESS) return;
        if (event.isButton != openKey.isButton() || event.bind != openKey.getBind()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // don't steal input from an already-open GUI/inventory
        // Cancel so nothing ELSE bound to the same key (e.g. a Boze chat/console overlay,
        // if openKey's default happens to collide with it) also reacts to this same press --
        // that's what showed Minecraft's chat drawn on top of the freshly-opened
        // WebBrowserScreen (two different systems both responding to one keypress,
        // Boze's chat overlay isn't routed through mc.screen so our own mc.screen==null
        // guard above can't see or block it).
        event.setCancelled(true);
        mc.execute(() -> mc.setScreen(new WebBrowserScreen()));
    }

    private void render(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        // Message loop MUST be pumped every frame the module is enabled, tile visible or
        // not (WebBrowserScreen's tabs need it pumped too while the tile itself is hidden
        // behind that screen) -- MUST be the NATIVE N_DoMessageLoopWork(). The public
        // wrapper doMessageLoopWork(long) is a no-op stub in this JCEF build (verified via
        // javap -c: its entire bytecode body is a bare `return`), so calling it every frame
        // did nothing and CEF never painted a single frame, no matter how long you waited.
        // Soar's own WebBrowserMod calls the native method directly for the same reason.
        if (MCEF.isInitialized()) {
            MCEF.getApp().getHandle().N_DoMessageLoopWork();
        }

        // WebBrowserScreen already shows the active tab full-size when open -- skip the
        // tile's own draw so it doesn't queue a second WebBrowserShadowPipState/
        // WebBrowserPipState the same frame (two elements dispatching through the same
        // PictureInPictureRenderer instance in one frame stomp each other's shared
        // offscreen texture -- see AbstractSkiaPipRenderer's class doc).
        if (mc.screen instanceof WebBrowserScreen) return;

        // ShowHud only gates the tile's own drawing below -- the message loop pump above
        // already ran unconditionally, so tabs opened in the full-screen WebBrowserScreen
        // keep working even with the floating tile hidden.
        if (!showHud.getValue()) return;

        // REVERTED 2026-07-10: forcing browser.repaint() (N_Invalidate) every frame here
        // was meant to raise the effective paint rate past CEF's internal 30fps OSR cap.
        // Confirmed via CEF's own issue tracker afterward that Invalidate() does NOT
        // bypass windowless_frame_rate -- it only marks the view dirty, actual paint
        // dispatch is still gated by CEF's internal compositor timer. Forcing it every MC
        // frame (which can be well above 30fps) instead introduced new stutter/tearing
        // ("like no vsync") with no fps benefit. Removed; CEF paints on its own schedule.

        double w = width.getValue();
        double h = height.getValue();

        if (HUDEditor.INSTANCE.active) {
            handleDrag(mc, w, h);
        }

        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        context.pose().pushMatrix();
        context.pose().translate((float) posX, (float) posY);

        if (WebBrowserManager.isUnsupported()) {
            context.text(mc.font, "Browser unavailable", 0, 0, 0xFFFF5555, true);
        } else if (browser == null || !browser.isTextureReady()) {
            context.text(mc.font, "Loading browser...", 0, 0, 0xFFFFFF00, true);
            // First paint from a fresh Chromium instance (page fetch + render) can
            // legitimately take several seconds -- not a hang by itself. Ping chat
            // once if it's still not ready after 15s so a genuine stall (vs. normal
            // first-load latency) is distinguishable without digging through logs.
            if (browser != null) {
                if (loadingSinceMs < 0) loadingSinceMs = System.currentTimeMillis();
                else if (!slowLoadWarned && System.currentTimeMillis() - loadingSinceMs > 15000) {
                    slowLoadWarned = true;
                    dev.boze.api.utility.ChatHelper.sendMsg("WebBrowser",
                        "§eStill no first paint 15s after browser creation -- likely a real stall, not normal load time.");
                }
            }
        } else {
            loadingSinceMs = -1;
            slowLoadWarned = false;
            // Resize at PHYSICAL pixel resolution (tile size * GUI scale), not logical
            // GUI-scaled size -- CEF renders/reflows the page at whatever pixel dims it's
            // given, so resizing to the small logical tile size (e.g. 320x180) made the
            // page reflow/render at that tiny resolution and just get upscaled blurry into
            // the tile's actual on-screen physical size, reading as "shrunk". Matching
            // physical pixels keeps the page rendered sharp at the tile's real display size.
            double guiScale = mc.getWindow().getGuiScale();
            int texW = (int) Math.round(w * guiScale), texH = (int) Math.round(h * guiScale);
            if (browser != lastResizedBrowser || texW != lastTexW || texH != lastTexH) {
                browser.resize(texW, texH);
                lastTexW = texW;
                lastTexH = texH;
                lastResizedBrowser = browser;
            }
        }

        context.pose().popMatrix();

        if (browser != null && browser.isTextureReady()) {
            int x0 = (int) posX, y0 = (int) posY;
            int x1 = (int) (posX + w), y1 = (int) (posY + h);
            // Shadow queued first (behind), page content queued second (in front) --
            // same layer ordering GifHUD's drawSkiaShadow/paintSkiaShadow uses.
            drawShadow(context, posX, posY, w, h);
            ((GuiGraphicsExtractorAccessor) context).getGuiRenderState().addPicturesInPictureState(
                new WebBrowserPipState(canvas -> paintBrowser(canvas, browser, posX, posY, w, h), x0, y0, x1, y1));
        }
    }

    /** Drop shadow behind the tile, mirroring GifHUD's drawSkiaShadow/paintSkiaShadow but rounded to match the tile's own corner radius. */
    private void drawShadow(GuiGraphicsExtractor context, double x, double y, double w, double h) {
        float blurRadius = 12f;
        float margin = blurRadius * 3f;
        int x0 = (int) Math.floor(x - margin), y0 = (int) Math.floor(y - margin);
        int x1 = (int) Math.ceil(x + w + margin), y1 = (int) Math.ceil(y + h + margin);
        ((GuiGraphicsExtractorAccessor) context).getGuiRenderState()
            .addPicturesInPictureState(new WebBrowserShadowPipState(
                canvas -> paintShadow(canvas, x, y, w, h, blurRadius), x0, y0, x1, y1));
    }

    private void paintShadow(Canvas canvas, double x, double y, double w, double h, float blurRadius) {
        try (Paint paint = new Paint();
             ImageFilter shadowFilter = ImageFilter.makeDropShadowOnly(0, 0, blurRadius, blurRadius, 0xB0000000)) {
            paint.setImageFilter(shadowFilter);
            paint.setAntiAlias(true);
            canvas.drawRRect(RRect.makeXYWH((float) x, (float) y, (float) w, (float) h, CORNER_RADIUS), paint);
        }
    }

    // Absolute GUI-logical coordinates, same convention GifHUD's paintSkiaShadow uses
    // (see AbstractSkiaPipRenderer.renderToTexture -- the canvas passed to the painter
    // is NOT pre-shifted to the element's own (0,0) despite that class's doc comment;
    // matched against GifHUD's proven-working call site, not re-derived from the doc).
    private void paintBrowser(Canvas canvas, MCEFBrowser browser, double x, double y, double w, double h) {
        WebBrowserPipRenderer renderer = WebBrowserPipRenderer.ACTIVE;
        if (renderer == null) return;
        int glId = browser.getRenderer().getTextureID();
        if (glId <= 0) return;
        Image img = renderer.borrowTexture(browser, glId,
            browser.getRenderer().getTextureWidth(), browser.getRenderer().getTextureHeight());
        if (img == null) return;
        canvas.save();
        try (Paint paint = new Paint()) {
            canvas.clipRRect(RRect.makeXYWH((float) x, (float) y, (float) w, (float) h, CORNER_RADIUS), ClipMode.INTERSECT, true);
            canvas.drawImageRect(img, Rect.makeXYWH((float) x, (float) y, (float) w, (float) h), paint);
        } finally {
            canvas.restore();
        }
    }

    private void handleDrag(Minecraft mc, double w, double h) {
        double scale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / scale;
        double my = mc.mouseHandler.ypos() / scale;
        boolean mouseDown = GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (mouseDown && !wasMouseDownEditor) {
            if (mx >= posX && mx <= posX + w && my >= posY && my <= posY + h) {
                if (HUDEditor.draggingHUD.isEmpty() || HUDEditor.draggingHUD.equals("WebBrowser")) {
                    isDraggingHUD = true;
                    HUDEditor.draggingHUD = "WebBrowser";
                    dragOffsetX = mx - posX;
                    dragOffsetY = my - posY;
                }
            }
        } else if (!mouseDown) {
            if (isDraggingHUD) {
                HUDEditor.draggingHUD = "";
                com.example.addon.util.HudPositions.save("WebBrowser", posX, posY);
            }
            isDraggingHUD = false;
        }

        if (isDraggingHUD && mouseDown) {
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();
            posX = Math.max(0, Math.min(mx - dragOffsetX, screenW - w));
            posY = Math.max(0, Math.min(my - dragOffsetY, screenH - h));
        }
        wasMouseDownEditor = mouseDown;
    }

    public double getX() { return posX; }
    public double getY() { return posY; }
    public double getTileWidth() { return width.getValue(); }
    public double getTileHeight() { return height.getValue(); }
}
