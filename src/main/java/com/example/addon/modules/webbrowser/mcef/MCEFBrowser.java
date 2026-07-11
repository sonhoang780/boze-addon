package com.example.addon.modules.webbrowser.mcef;

import com.cinemamod.mcef.MCEFClient;
import com.cinemamod.mcef.MCEFDragContext;
import com.cinemamod.mcef.MCEFPlatform;
import com.cinemamod.mcef.listeners.MCEFCursorChangeListener;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.ByteBuffer;

/**
 * Ported from com.cinemamod.mcef.MCEFBrowser -- see CefUtil's class doc for why.
 * MCEFClient/MCEFDragContext/MCEFPlatform/MCEFCursorChangeListener are reused directly
 * from the original jar (com.cinemamod.mcef.*) -- confirmed zero Minecraft-class
 * references in those, so the Yarn/Mojmap mismatch never applies to them.
 */
public class MCEFBrowser extends CefBrowserOsr {
    private final MCEFRenderer renderer;
    private final MCEFDragContext dragContext = new MCEFDragContext();
    private MCEFCursorChangeListener cursorChangeListener;
    private boolean browserControls = true;
    private int lastWidth = 0;
    private int lastHeight = 0;
    private int btnMask = 0;
    protected ByteBuffer popupGraphics;
    protected Rectangle popupSize;
    protected boolean showPopup = false;
    protected boolean popupDrawn = false;

    public MCEFBrowser(MCEFClient client, String url, boolean transparent) {
        super(client.getHandle(), url, transparent, null);
        this.renderer = new MCEFRenderer(transparent);
        this.cursorChangeListener = cefCursorID -> setCursor(CefCursorType.fromId(cefCursorID));
        Minecraft.getInstance().execute(renderer::initialize);
    }

    public MCEFRenderer getRenderer() { return renderer; }
    public Identifier getTextureLocation() { return renderer != null ? renderer.getTextureLocation() : null; }
    public boolean isTextureReady() { return renderer != null && renderer.isTextureReady(); }
    public MCEFCursorChangeListener getCursorChangeListener() { return cursorChangeListener; }
    public void setCursorChangeListener(MCEFCursorChangeListener cursorChangeListener) { this.cursorChangeListener = cursorChangeListener; }
    public boolean usingBrowserControls() { return browserControls; }
    public MCEFBrowser useBrowserControls(boolean browserControls) { this.browserControls = browserControls; return this; }
    public MCEFDragContext getDragContext() { return dragContext; }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        super.onPopupShow(browser, show);
        showPopup = show;
        if (!show) popupDrawn = false;
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        super.onPopupSize(browser, size);
        popupSize = size;
        popupGraphics = ByteBuffer.allocateDirect(size.width * size.height * 4);
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (dirtyRects.length == 0) return;
        if (!popup) {
            // Always full-frame replace, even when dirtyRects only covers part of the
            // page -- the per-dirtyRect glTexSubImage2D path (removed) assumed `buffer`
            // is always the FULL width*height frame at a fixed stride and only the
            // requested sub-region needs re-uploading. That assumption is where real-page
            // artifacts kept showing up (Facebook feed gaps, Instagram comment-row
            // ghosting, a modal's stale background bleeding through) -- all consistent
            // with dirty-rect bookkeeping going wrong under heavy/rapid reflow, not with
            // anything CEF-version- or GPU-compositing-related (both were tested and
            // ruled out). Full replace can't have an offset/stride bug because there's no
            // offset math left: at ~30fps windowless cap and typical tile/screen
            // resolutions the extra upload bandwidth is trivial.
            lastWidth = width;
            lastHeight = height;
            renderer.onPaint(buffer, width, height);
            if ((popupDrawn || showPopup) && popupSize != null) {
                if (!showPopup) {
                    GlStateManager._pixelStore(3316, popupSize.width);
                    GlStateManager._pixelStore(3315, popupSize.height);
                    renderer.onPaint(buffer, popupSize.x, popupSize.y, popupSize.width, popupSize.height);
                    popupGraphics = null;
                    popupSize = null;
                } else if (popupDrawn) {
                    GlStateManager._pixelStore(3314, popupSize.width);
                    GlStateManager._pixelStore(3316, 0);
                    GlStateManager._pixelStore(3315, 0);
                    renderer.onPaint(popupGraphics, popupSize.x, popupSize.y, popupSize.width, popupSize.height);
                }
            }
        } else {
            if (renderer.getTextureID() == 0) return;
            GlStateManager._bindTexture(renderer.getTextureID());
            int start = buffer.capacity();
            int end = 0;
            for (Rectangle dirtyRect : dirtyRects) {
                GlStateManager._pixelStore(3314, popupSize.width);
                GlStateManager._pixelStore(3316, dirtyRect.x);
                GlStateManager._pixelStore(3315, dirtyRect.y);
                renderer.onPaint(buffer, popupSize.x + dirtyRect.x, popupSize.y + dirtyRect.y, dirtyRect.width, dirtyRect.height);
                int rectStart = (dirtyRect.x + dirtyRect.y * popupSize.width) << 2;
                if (rectStart < start) start = rectStart;
                int rectEnd = (dirtyRect.x + dirtyRect.width + (dirtyRect.y + popupSize.height) * dirtyRect.width) << 2;
                if (rectEnd > end) end = rectEnd;
            }
            if (start < 0) start = 0;
            if (end > buffer.capacity()) end = buffer.capacity();
            if (end > start && popupGraphics != null) {
                long addrFrom = MemoryUtil.memAddress(buffer);
                long addrTo = MemoryUtil.memAddress(popupGraphics);
                MemoryUtil.memCopy(addrFrom + start, addrTo + start, end - start);
            }
            popupDrawn = true;
        }
    }

    public void resize(int width, int height) {
        this.browser_rect_.setBounds(0, 0, width, height);
        this.wasResized(width, height);
    }

    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        if (browserControls) {
            if (modifiers == 2) {
                if (keyCode == 82) { reload(); return; }
                if (keyCode == 61) { if (getZoomLevel() < 9.0) setZoomLevel(getZoomLevel() + 1.0); return; }
                if (keyCode == 45) { if (getZoomLevel() > -9.0) setZoomLevel(getZoomLevel() - 1.0); return; }
                if (keyCode == 48) { setZoomLevel(0.0); return; }
            } else if (modifiers == 4) {
                if (keyCode == 263 && canGoBack()) { goBack(); return; }
                if (keyCode == 262 && canGoForward()) { goForward(); return; }
            }
        }
        CefKeyEvent e = new CefKeyEvent(1, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        if (browserControls) {
            if (modifiers == 2) {
                if (keyCode == 82 || keyCode == 61 || keyCode == 45 || keyCode == 48) return;
            } else if (modifiers == 4) {
                if (keyCode == 263 && canGoBack()) return;
                if (keyCode == 262 && canGoForward()) return;
            }
        }
        CefKeyEvent e = new CefKeyEvent(0, keyCode, (char) keyCode, modifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyTyped(char c, int modifiers) {
        if (browserControls) {
            if (modifiers == 2) {
                if (c == 'R' || c == '=' || c == '-' || c == '0') return;
            } else if (modifiers == 4) {
                if (c == 'ć' && canGoBack()) return;
                if (c == 'Ć' && canGoForward()) return;
            }
        }
        sendKeyEvent(new CefKeyEvent(2, c, c, modifiers));
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        CefMouseEvent e = new CefMouseEvent(503, mouseX, mouseY, 0, 0, dragContext.getVirtualModifiers(btnMask));
        sendMouseEvent(e);
        if (dragContext.isDragging()) {
            dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
        }
    }

    public void sendMousePress(int mouseX, int mouseY, int button) {
        if (button == 1) button = 2;
        else if (button == 2) button = 1;
        if (button == 0) btnMask |= 0x10;
        else if (button == 1) btnMask |= 0x20;
        else if (button == 2) btnMask |= 0x40;
        sendMouseEvent(new CefMouseEvent(1, mouseX, mouseY, 1, button, btnMask));
    }

    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        if (button == 1) button = 2;
        else if (button == 2) button = 1;
        if (button == 0 && (btnMask & 0x10) != 0) btnMask ^= 0x10;
        else if (button == 1 && (btnMask & 0x20) != 0) btnMask ^= 0x20;
        else if (button == 2 && (btnMask & 0x40) != 0) btnMask ^= 0x40;
        sendMouseEvent(new CefMouseEvent(0, mouseX, mouseY, 1, button, btnMask));
        if (dragContext.isDragging() && button == 0) {
            finishDragging(mouseX, mouseY);
        }
    }

    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        if (browserControls && (modifiers & 2) != 0) {
            if (amount > 0.0) { if (getZoomLevel() < 9.0) setZoomLevel(getZoomLevel() + 1.0); }
            else if (getZoomLevel() > -9.0) setZoomLevel(getZoomLevel() - 1.0);
            return;
        }
        if (!MCEFPlatform.getPlatform().isMacOS()) {
            amount = amount < 0.0 ? Math.floor(amount) : Math.ceil(amount);
            amount *= 3.0;
        }
        sendMouseWheelEvent(new CefMouseWheelEvent(0, mouseX, mouseY, amount, modifiers));
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        dragContext.startDragging(dragData, mask);
        dragTargetDragEnter(dragContext.getDragData(), new Point(x, y), btnMask, dragContext.getMask());
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation)) {
            onCursorChange(this, dragContext.getVirtualCursor(dragContext.getActualCursor()));
        }
        super.updateDragCursor(browser, operation);
    }

    public void startDragging(CefDragData dragData, int mask, int x, int y) {
        startDragging(this, dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        dragTargetDrop(new Point(x, y), btnMask);
        dragTargetDragLeave();
        dragContext.stopDragging();
        onCursorChange(this, dragContext.getActualCursor());
    }

    public void cancelDrag() {
        dragTargetDragLeave();
        dragContext.stopDragging();
        onCursorChange(this, dragContext.getActualCursor());
    }

    public void close() {
        renderer.cleanup();
        cursorChangeListener.onCursorChange(0);
        super.close(true);
    }

    @Override
    protected void finalize() throws Throwable {
        Minecraft.getInstance().execute(renderer::cleanup);
        super.finalize();
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        cursorType = dragContext.getVirtualCursor(cursorType);
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    public void setCursor(CefCursorType cursorType) {
        long windowHandle = Minecraft.getInstance().getWindow().handle();
        if (cursorType == CefCursorType.NONE) {
            GLFW.glfwSetInputMode(windowHandle, 208897, 212994);
        } else {
            GLFW.glfwSetInputMode(windowHandle, 208897, 212993);
            GLFW.glfwSetCursor(windowHandle, MCEF.getGLFWCursorHandle(cursorType));
        }
    }
}
