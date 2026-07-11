package com.example.addon.screens;

import com.example.addon.mixin.GuiGraphicsExtractorAccessor;
import com.example.addon.modules.WebBrowser;
import com.example.addon.modules.webbrowser.mcef.MCEFBrowser;
import com.example.addon.modules.webbrowser.WebBrowserManager;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.client.module.BaseModule;
import dev.boze.api.option.Option;
import dev.boze.api.option.ToggleOption;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Full-window interactive overlay for WebBrowserManager's tabs -- tab strip + URL/nav
 * chrome at a fixed-height header, active tab's page fills the rest below it. Opened via
 * WebBrowser's openKey bind (see WebBrowser's class doc for why keybind-only, not
 * click-to-focus).
 *
 * A prior version of this screen had a URL bar + Back/Forward/Reload/Home nav chrome too,
 * removed after repeated bugs (specifics not recorded). This version's fix for the
 * suspected bug class (chrome clicks leaking through to the page underneath): every
 * mouse/key event handler below calls super.<event>(...) FIRST -- real MC widgets (the URL
 * EditBox) get first refusal via normal Screen dispatch -- and any event whose Y coordinate
 * falls inside the HEADER_H chrome strip is swallowed unconditionally (manual tab-strip/
 * nav-button hit-testing happens there, but the strip ALWAYS consumes the event even on a
 * miss) before anything is ever forwarded to the page. The page only ever sees events whose
 * Y is strictly below HEADER_H.
 *
 * See docs/superpowers/specs/2026-07-10-web-browser-design.md and
 * docs/superpowers/specs/2026-07-10-webbrowser-multitab-design.md.
 */
public class WebBrowserScreen extends Screen {

    private static final int TAB_ROW_H = 28;
    private static final int NAV_ROW_H = 28;
    private static final int HEADER_H = TAB_ROW_H + NAV_ROW_H;
    private static final int TAB_W = 120;
    private static final int TAB_GAP = 2;
    private static final int TAB_CLOSE_W = 16;
    private static final int NEW_TAB_W = 24;
    private static final int NAV_BTN_W = 28;
    private static final float CORNER_RADIUS = 10f;

    private EditBox urlBox;
    private int lastPageW = -1, lastPageH = -1;
    private int lastResizedTab = -1;
    private Boolean hudRenderPrevState;

    public WebBrowserScreen() {
        super(Component.literal("Web Browser"));
    }

    @Override
    protected void init() {
        super.init();
        int navY = TAB_ROW_H + 4;
        int urlX = 4 + NAV_BTN_W * 4 + 4;
        urlBox = new EditBox(this.font, urlX, navY, Math.max(40, this.width - urlX - 4), 20, Component.empty());
        urlBox.setMaxLength(2048);
        urlBox.setValue(currentTabUrlForDisplay());
        this.addRenderableWidget(urlBox);

        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null) browser.setFocus(true);
        resizeActiveTabToPageArea();

        ToggleOption hudRender = findHudRenderOption();
        if (hudRender != null) {
            hudRenderPrevState = hudRender.getValue();
            hudRender.setValue(false);
        }
    }

    @Override
    public void onClose() {
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null) {
            browser.setFocus(false);
            // Resize back to whatever the HUD tile currently is so it keeps rendering
            // correctly once this screen closes (tile size may have changed via
            // HUDEditor while this screen was open).
            double scale = Minecraft.getInstance().getWindow().getGuiScale();
            browser.resize((int) Math.round(WebBrowser.INSTANCE.getTileWidth() * scale),
                (int) Math.round(WebBrowser.INSTANCE.getTileHeight() * scale));
        }
        if (hudRenderPrevState != null) {
            ToggleOption hudRender = findHudRenderOption();
            if (hudRender != null) hudRender.setValue(hudRenderPrevState);
            hudRenderPrevState = null;
        }
        super.onClose();
    }

    /** Boze's built-in "HUD" module has a "Render" toggle controlling whether its own HUD
     * elements draw at all -- suppressed while WebBrowserScreen is open so Boze's HUD
     * doesn't paint over the browser, restored to whatever it was before on close. */
    private ToggleOption findHudRenderOption() {
        BaseModule hud = ModuleManager.getClientModule("HUD");
        if (hud == null) return null;
        for (Option<?> option : hud.getOptions()) {
            if (option instanceof ToggleOption toggleOption && toggleOption.name.equalsIgnoreCase("Render")) {
                return toggleOption;
            }
        }
        return null;
    }

    private String currentTabUrlForDisplay() {
        int active = WebBrowserManager.getActiveTabIndex();
        return active < 0 ? "" : liveTabUrl(active);
    }

    /** WebBrowserManager.getTabTitle() is a static snapshot from when the tab was opened
     * (see BrowserTab's field doc) -- not updated when the page navigates elsewhere. Use
     * the browser's own live getURL() whenever one is available (it reflects wherever the
     * tab actually is right now, e.g. after following a link to youtube.com), falling back
     * to the static title only before the browser/first navigation exists yet. */
    private String liveTabUrl(int index) {
        MCEFBrowser browser = WebBrowserManager.getTabBrowser(index);
        if (browser != null) {
            String live = browser.getURL();
            if (live != null && !live.isEmpty()) return live;
        }
        return WebBrowserManager.getTabTitle(index);
    }

    /** Real-browser URL-bar heuristic: anything that isn't URL-shaped (has a space, or has
     * no dot at all -- "tích phân" is neither a domain nor has a scheme) becomes a Google
     * search instead of literally prefixing "https://" onto non-URL text (which just
     * produced a broken address like "https://tích phân"). */
    private String resolveUrlBarInput(String input) {
        String trimmed = input.trim();
        if (trimmed.contains("://")) return trimmed;
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            return "https://www.google.com/search?q="
                + java.net.URLEncoder.encode(trimmed, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "https://" + trimmed;
    }

    private int pageAreaWidth() { return this.width; }
    private int pageAreaHeight() { return Math.max(1, this.height - HEADER_H); }

    private void resizeActiveTabToPageArea() {
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        int active = WebBrowserManager.getActiveTabIndex();
        if (browser == null) return;
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        int physW = (int) Math.round(pageAreaWidth() * scale);
        int physH = (int) Math.round(pageAreaHeight() * scale);
        if (active == lastResizedTab && physW == lastPageW && physH == lastPageH) return;
        browser.resize(physW, physH);
        lastPageW = physW;
        lastPageH = physH;
        lastResizedTab = active;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Must pump here too, not just rely on WebBrowser's HUD tile doing it -- the tile
        // may not fire every frame while this Screen is open (HUD suppression during an
        // open GUI varies by render path), and this Screen's own render is the one thing
        // GUARANTEED to run every frame while it's the thing actually being interacted
        // with. CEF drops/delays input processing (scroll, clicks) without a frequent
        // pump, which reads as page-interaction lag even while video (driven more by
        // CEF's own compositor frame timer, less pump-dependent) stays smooth.
        if (com.example.addon.modules.webbrowser.mcef.MCEF.isInitialized()) {
            com.example.addon.modules.webbrowser.mcef.MCEF.getApp().getHandle().N_DoMessageLoopWork();
        }
        resizeActiveTabToPageArea();

        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        // REVERTED 2026-07-10: forced browser.repaint() (N_Invalidate) every frame here --
        // same revert as WebBrowser's HUD tile, see that class's doc for why (Invalidate()
        // doesn't bypass CEF's internal 30fps OSR cap, confirmed via CEF's issue tracker;
        // forcing it every MC frame just introduced new stutter with no fps gain).
        int px0 = 0, py0 = HEADER_H, px1 = this.width, py1 = this.height;

        // Shadow behind everything (screen + chrome), rounded to match the panel.
        drawShadow(ctx, 0, 0, this.width, this.height);

        if (browser != null && browser.isTextureReady()) {
            ((GuiGraphicsExtractorAccessor) ctx).getGuiRenderState().addPicturesInPictureState(
                new WebBrowserPipState(canvas -> paintPage(canvas, browser, px0, py0, px1 - px0, py1 - py0),
                    px0, py0, px1, py1));
        } else {
            ctx.fill(px0, py0, px1, py1, 0xDD0D0D0D);
            ctx.text(this.font, "Loading browser...", px0 + 8, py0 + 8, 0xFFFFFF00, true);
        }

        // Chrome bar backgrounds -- ONE Skija paint call for the whole strip (tabs + nav
        // buttons together), never one call per button: two elements dispatching through
        // the same PictureInPictureRenderer instance in the same frame stomp each other's
        // shared offscreen texture (see AbstractSkiaPipRenderer's class doc).
        int tabCount = WebBrowserManager.getTabCount();
        int active = WebBrowserManager.getActiveTabIndex();
        ((GuiGraphicsExtractorAccessor) ctx).getGuiRenderState().addPicturesInPictureState(
            new WebBrowserChromePipState(canvas -> paintChrome(canvas, tabCount, active), 0, 0, this.width, HEADER_H));

        // Text labels + icon glyphs drawn with MC's own font renderer (GuiGraphicsExtractor),
        // layered over the Skia chrome panels -- avoids needing Skija's Font/TextLine APIs
        // for what's just plain HUD text, same as every other panel+text HUD in this addon.
        for (int i = 0; i < tabCount; i++) {
            int tx = tabX(i);
            String title = liveTabUrl(i);
            String shown = this.font.plainSubstrByWidth(title, TAB_W - TAB_CLOSE_W - 8);
            ctx.text(this.font, shown, tx + 4, 9, 0xFFFFFFFF, false);
            ctx.text(this.font, "x", tx + TAB_W - TAB_CLOSE_W + 4, 9, 0xFFAAAAAA, false);
        }
        int newTabX = tabX(tabCount);
        ctx.text(this.font, "+", newTabX + (NEW_TAB_W - this.font.width("+")) / 2, 9, 0xFFFFFFFF, false);

        MCEFBrowser activeBrowser = browser;
        boolean canBack = activeBrowser != null && activeBrowser.canGoBack();
        boolean canFwd = activeBrowser != null && activeBrowser.canGoForward();
        ctx.text(this.font, "<", navBtnX(0) + (NAV_BTN_W - this.font.width("<")) / 2, TAB_ROW_H + 10, canBack ? 0xFFFFFFFF : 0xFF666666, false);
        ctx.text(this.font, ">", navBtnX(1) + (NAV_BTN_W - this.font.width(">")) / 2, TAB_ROW_H + 10, canFwd ? 0xFFFFFFFF : 0xFF666666, false);
        // Reload/Home are drawn as vector icons in paintChrome (below), not text glyphs --
        // "R"/"H" letters looked like placeholders, not real browser icons.

        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    private void drawShadow(GuiGraphicsExtractor ctx, double x, double y, double w, double h) {
        float blurRadius = 16f;
        float margin = blurRadius * 3f;
        int x0 = (int) Math.floor(x - margin), y0 = (int) Math.floor(y - margin);
        int x1 = (int) Math.ceil(x + w + margin), y1 = (int) Math.ceil(y + h + margin);
        ((GuiGraphicsExtractorAccessor) ctx).getGuiRenderState()
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

    private void paintPage(Canvas canvas, MCEFBrowser browser, double x, double y, double w, double h) {
        WebBrowserPipRenderer renderer = WebBrowserPipRenderer.ACTIVE;
        if (renderer == null) return;
        int glId = browser.getRenderer().getTextureID();
        if (glId <= 0) return;
        Image img = renderer.borrowTexture(browser, glId,
            browser.getRenderer().getTextureWidth(), browser.getRenderer().getTextureHeight(),
            !browser.getRenderer().isTransparent());
        if (img == null) return;
        try (Paint paint = new Paint()) {
            canvas.drawImageRect(img, Rect.makeXYWH((float) x, (float) y, (float) w, (float) h), paint);
        }
    }

    private void paintChrome(Canvas canvas, int tabCount, int active) {
        WebBrowserChromePipRenderer renderer = WebBrowserChromePipRenderer.ACTIVE;
        if (renderer == null) return;
        try (Paint tint = new Paint(); Paint activeTint = new Paint(); Paint rim = new Paint()) {
            tint.setColor(0x40FFFFFF);
            activeTint.setColor(0x70FFFFFF);
            rim.setColor(0x30FFFFFF);
            rim.setStroke(true);
            rim.setStrokeWidth(1f);

            canvas.drawRect(Rect.makeXYWH(0, 0, this.width, HEADER_H), tint);

            for (int i = 0; i < tabCount; i++) {
                RRect tab = RRect.makeXYWH(tabX(i), 3, TAB_W, TAB_ROW_H - 6, 6f);
                canvas.drawRRect(tab, i == active ? activeTint : tint);
                canvas.drawRRect(tab, rim);
            }
            RRect newTab = RRect.makeXYWH(tabX(tabCount), 3, NEW_TAB_W, TAB_ROW_H - 6, 6f);
            canvas.drawRRect(newTab, tint);
            canvas.drawRRect(newTab, rim);

            for (int i = 0; i < 4; i++) {
                RRect btn = RRect.makeXYWH(navBtnX(i), TAB_ROW_H + 3, NAV_BTN_W - 4, NAV_ROW_H - 6, 6f);
                canvas.drawRRect(btn, tint);
                canvas.drawRRect(btn, rim);
            }

            float reloadCx = navBtnX(2) + (NAV_BTN_W - 4) / 2f;
            float reloadCy = TAB_ROW_H + NAV_ROW_H / 2f;
            drawReloadIcon(canvas, reloadCx, reloadCy, 6f);

            float homeCx = navBtnX(3) + (NAV_BTN_W - 4) / 2f;
            float homeCy = TAB_ROW_H + NAV_ROW_H / 2f;
            drawHomeIcon(canvas, homeCx, homeCy, 13f);
        }
    }

    /** Reload icon: a ~280deg circular arc + a small arrowhead, like every real browser's reload glyph. Skija 0.143's Path is immutable -- build via PathBuilder, drawPath the detached snapshot. */
    private void drawReloadIcon(Canvas canvas, float cx, float cy, float r) {
        try (Paint stroke = new Paint()) {
            stroke.setColor(0xFFFFFFFF);
            stroke.setStroke(true);
            stroke.setStrokeWidth(1.6f);
            stroke.setAntiAlias(true);
            Rect oval = Rect.makeLTRB(cx - r, cy - r, cx + r, cy + r);
            try (io.github.humbleui.skija.PathBuilder arcBuilder = new io.github.humbleui.skija.PathBuilder()) {
                arcBuilder.addArc(oval, -50, 280);
                try (io.github.humbleui.skija.Path arc = arcBuilder.detach()) {
                    canvas.drawPath(arc, stroke);
                }
            }
            double angRad = Math.toRadians(-50);
            float ax = cx + r * (float) Math.cos(angRad);
            float ay = cy + r * (float) Math.sin(angRad);
            try (Paint fill = new Paint();
                 io.github.humbleui.skija.PathBuilder headBuilder = new io.github.humbleui.skija.PathBuilder()) {
                fill.setColor(0xFFFFFFFF);
                fill.setAntiAlias(true);
                headBuilder.moveTo(ax - 4.5f, ay - 3f)
                    .lineTo(ax + 3.5f, ay - 0.5f)
                    .lineTo(ax - 1.5f, ay + 4.5f)
                    .closePath();
                try (io.github.humbleui.skija.Path head = headBuilder.detach()) {
                    canvas.drawPath(head, fill);
                }
            }
        }
    }

    /** Home icon: plain house silhouette (roof triangle + wall rectangle), like every real browser's home glyph. */
    private void drawHomeIcon(Canvas canvas, float cx, float cy, float size) {
        try (Paint fill = new Paint();
             io.github.humbleui.skija.PathBuilder houseBuilder = new io.github.humbleui.skija.PathBuilder()) {
            fill.setColor(0xFFFFFFFF);
            fill.setAntiAlias(true);
            float half = size / 2f;
            float wallHalf = half * 0.72f;
            houseBuilder.moveTo(cx, cy - half)
                .lineTo(cx + half, cy - half * 0.15f)
                .lineTo(cx + wallHalf, cy - half * 0.15f)
                .lineTo(cx + wallHalf, cy + half)
                .lineTo(cx - wallHalf, cy + half)
                .lineTo(cx - wallHalf, cy - half * 0.15f)
                .lineTo(cx - half, cy - half * 0.15f)
                .closePath();
            try (io.github.humbleui.skija.Path house = houseBuilder.detach()) {
                canvas.drawPath(house, fill);
            }
        }
    }

    private int tabX(int index) { return 4 + index * (TAB_W + TAB_GAP); }
    private int navBtnX(int index) { return 4 + index * NAV_BTN_W; }

    private boolean inPageBounds(double mouseX, double mouseY) {
        return mouseY >= HEADER_H && mouseX >= 0 && mouseX < this.width && mouseY < this.height;
    }

    private int toPageX(double mouseX) { return (int) Math.round(mouseX * (Minecraft.getInstance().getWindow().getGuiScale())); }
    private int toPageY(double mouseY) { return (int) Math.round((mouseY - HEADER_H) * (Minecraft.getInstance().getWindow().getGuiScale())); }

    /** Chrome-zone click handling -- always consumes (return value ignored by caller, the zone check itself is what swallows the event). Hit-test miss inside the strip still does nothing to the page. */
    private void handleChromeClick(double mouseX, double mouseY) {
        int tabCount = WebBrowserManager.getTabCount();
        if (mouseY < TAB_ROW_H) {
            for (int i = 0; i < tabCount; i++) {
                int tx = tabX(i);
                if (mouseX < tx || mouseX >= tx + TAB_W) continue;
                if (mouseX >= tx + TAB_W - TAB_CLOSE_W) {
                    WebBrowserManager.closeTab(i);
                    if (WebBrowserManager.getTabCount() == 0) { this.onClose(); return; }
                } else {
                    WebBrowserManager.switchTab(i);
                    urlBox.setValue(currentTabUrlForDisplay());
                }
                return;
            }
            int newTabX = tabX(tabCount);
            if (mouseX >= newTabX && mouseX < newTabX + NEW_TAB_W) {
                try {
                    WebBrowserManager.openTab(WebBrowserManager.getDefaultUrl());
                    urlBox.setValue(currentTabUrlForDisplay());
                } catch (Throwable t) {
                    dev.boze.api.utility.ChatHelper.sendMsg("WebBrowser", "§cOpen tab failed: " + t);
                }
            }
            return;
        }

        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser == null) return;
        if (mouseX >= navBtnX(0) && mouseX < navBtnX(0) + NAV_BTN_W) {
            if (browser.canGoBack()) browser.goBack();
        } else if (mouseX >= navBtnX(1) && mouseX < navBtnX(1) + NAV_BTN_W) {
            if (browser.canGoForward()) browser.goForward();
        } else if (mouseX >= navBtnX(2) && mouseX < navBtnX(2) + NAV_BTN_W) {
            browser.reload();
        } else if (mouseX >= navBtnX(3) && mouseX < navBtnX(3) + NAV_BTN_W) {
            browser.loadURL(WebBrowserManager.getDefaultUrl());
        }
    }

    private boolean inUrlBoxBounds(double mouseX, double mouseY) {
        if (urlBox == null) return false;
        return mouseX >= urlBox.getX() && mouseX < urlBox.getX() + urlBox.getWidth()
            && mouseY >= urlBox.getY() && mouseY < urlBox.getY() + urlBox.getHeight();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();

        // Explicitly focus the URL bar on click instead of relying on Screen's default
        // click-to-focus -- this fork rewired input to event objects
        // (MouseButtonEvent/KeyEvent/CharacterEvent instead of vanilla's raw
        // double/int params), and super.mouseClicked was NOT reliably auto-focusing the
        // EditBox here: clicks inside its bounds fell through into the generic
        // "mouseY < HEADER_H -> swallow as chrome" branch (handleChromeClick has no case
        // for the URL bar's x-range at all), so the box was never focusable by click.
        if (inUrlBoxBounds(mouseX, mouseY)) {
            super.mouseClicked(event, doubleClick); // let EditBox place the cursor/selection
            urlBox.setFocused(true);
            return true;
        }

        // Any click NOT on the URL bar must drop its focus -- EditBox otherwise stays
        // focused until something else explicitly takes it, so without this, typing after
        // clicking into the page (e.g. a search box) or a chrome button kept landing in the
        // URL bar instead (super.charTyped/keyPressed intercepted it first).
        if (urlBox != null) urlBox.setFocused(false);

        if (super.mouseClicked(event, doubleClick)) return true;
        if (mouseY < HEADER_H) {
            handleChromeClick(mouseX, mouseY);
            return true;
        }
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null) browser.sendMousePress(toPageX(mouseX), toPageY(mouseY), event.button());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (super.mouseReleased(event)) return true;
        double mouseX = event.x(), mouseY = event.y();
        if (mouseY < HEADER_H) return true;
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null) browser.sendMouseRelease(toPageX(mouseX), toPageY(mouseY), event.button());
        return true;
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(mouseX, mouseY);
        if (mouseY < HEADER_H) return;
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null && inPageBounds(mouseX, mouseY)) {
            browser.sendMouseMove(toPageX(mouseX), toPageY(mouseY));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        if (mouseY < HEADER_H) return true;
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null && inPageBounds(mouseX, mouseY)) {
            browser.sendMouseWheel(toPageX(mouseX), toPageY(mouseY), verticalAmount, 0);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if (urlBox != null && urlBox.isFocused() && event.key() == GLFW.GLFW_KEY_ENTER) {
            MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
            if (browser != null) browser.loadURL(resolveUrlBarInput(urlBox.getValue()));
            return true;
        }
        if (super.keyPressed(event)) return true;
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null) browser.sendKeyPress(event.key(), event.scancode(), event.modifiers());
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (super.keyReleased(event)) return true;
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null) browser.sendKeyRelease(event.key(), event.scancode(), event.modifiers());
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (super.charTyped(event)) return true;
        // MCEFBrowser.sendKeyTyped takes a single char, CharacterEvent a full codepoint --
        // narrowing loses astral-plane (surrogate-pair) codepoints, an accepted gap for
        // page text input (matches this addon's other text entry points, none of which
        // handle astral codepoints either).
        MCEFBrowser browser = WebBrowserManager.getActiveBrowser();
        if (browser != null) browser.sendKeyTyped((char) event.codepoint(), 0);
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
