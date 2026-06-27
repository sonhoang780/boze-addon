# WhitelistEditorScreen Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite WhitelistEditorScreen as a small centered floating panel matching MusicHUD FontScreen pattern, and add `others` ToggleOption to InventoryCleaner.

**Architecture:** Two independent file changes. InventoryCleaner gets a new option + updated guard. WhitelistEditorScreen is a full class rewrite — same `extends Screen` base, but rendering shifts from fullscreen overlay to a 520×340 centered panel with GLFW click detection inside `extractRenderState`.

**Tech Stack:** MC 26.1.2, Boze API 3.2.2, LWJGL GLFW, Fabric

## Global Constraints

- MC 26.1.2 snapshot — do not assume 1.21.x API names
- Boze API 3.2.2 — `ToggleOption`, `SliderOption` from `dev.boze.api.option`
- Build verification: `.\gradlew compileJava` must pass after each task
- GLFW class: `org.lwjgl.glfw.GLFW`
- `GuiGraphicsExtractor` — MC 26.1.2 render state extractor, used in `extractRenderState`

---

## Files

| File | Change |
|------|--------|
| `src/main/java/com/example/addon/modules/InventoryCleaner.java` | Add `others` ToggleOption; update AbstractContainerScreen guard |
| `src/main/java/com/example/addon/screens/WhitelistEditorScreen.java` | Full rewrite — small centered panel, GLFW clicks, no fullscreen overlay |

---

### Task 1: Add `others` option to InventoryCleaner

**Files:**
- Modify: `src/main/java/com/example/addon/modules/InventoryCleaner.java`

**Interfaces:**
- Produces: `InventoryCleaner.others` (ToggleOption, default false) — consumed by tick logic guard

- [ ] **Step 1: Add `others` ToggleOption field**

In `InventoryCleaner.java`, after the `editWhitelist` field, add:

```java
public final ToggleOption others = new ToggleOption(this, "Others",
    "Drop from other GUIs like Chest, Shulker, EnderChest...", false);
```

- [ ] **Step 2: Update AbstractContainerScreen guard in `onTickPre`**

Find this block:
```java
// Skip when external container (chest, furnace, etc.) is open
if (mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen)) return;
```

Replace with:
```java
boolean externalGui = mc.screen instanceof AbstractContainerScreen
                   && !(mc.screen instanceof InventoryScreen);
if (externalGui && !others.getValue()) return;
```

- [ ] **Step 3: Build and verify**

```
.\gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```
git add src/main/java/com/example/addon/modules/InventoryCleaner.java
git commit -m "feat: add others option to InventoryCleaner (drop from chest/shulker GUIs)"
```

---

### Task 2: Rewrite WhitelistEditorScreen as centered panel

**Files:**
- Modify (full rewrite): `src/main/java/com/example/addon/screens/WhitelistEditorScreen.java`

**Interfaces:**
- Consumes: `InventoryCleaner.whitelist` (static `Set<String>`), `InventoryCleaner.saveWhitelist()` (static method)
- Produces: `WhitelistEditorScreen` — opened from `InventoryCleaner.onTickPre` via `mc.setScreen(new WhitelistEditorScreen())`

- [ ] **Step 1: Replace the entire file with the new implementation**

```java
package com.example.addon.screens;

import com.example.addon.modules.InventoryCleaner;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class WhitelistEditorScreen extends Screen {

    private static final int PANEL_W  = 520;
    private static final int PANEL_H  = 340;
    private static final int PADDING  = 8;
    // Y offset from winY where item rows start (title + separator + search + separator + header)
    private static final int CONTENT_Y_OFFSET = 68;
    private static final int ROW_H    = 20;
    private static final int ICON_SIZE = 16;

    private EditBox searchBox;
    private int scrollLeft  = 0;
    private int scrollRight = 0;
    private boolean wasMouseDown = false;

    private List<Item> filteredItems  = new ArrayList<>();
    private List<Item> whitelistItems = new ArrayList<>();

    public WhitelistEditorScreen() {
        super(Component.literal("Whitelist Editor"));
    }

    private int winX() { return (this.width  - PANEL_W) / 2; }
    private int winY() { return (this.height - PANEL_H) / 2; }

    @Override
    protected void init() {
        super.init();
        int wx = winX(), wy = winY();
        searchBox = new EditBox(this.font, wx + PADDING, wy + 28, PANEL_W - PADDING * 2 - 2, 16, Component.empty());
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search items..."));
        searchBox.setResponder(text -> { scrollLeft = 0; rebuildFiltered(text); });
        this.addRenderableWidget(searchBox);
        rebuildFiltered("");
        rebuildWhitelistItems();
    }

    private void rebuildFiltered(String query) {
        String q = query.toLowerCase().trim();
        filteredItems = BuiltInRegistries.ITEM.stream()
            .filter(item -> item != Items.AIR)
            .filter(item -> !InventoryCleaner.whitelist.contains(
                BuiltInRegistries.ITEM.getKey(item).toString()))
            .filter(item -> {
                if (q.isEmpty()) return true;
                String name = item.getName(item.getDefaultInstance()).getString().toLowerCase();
                String key  = BuiltInRegistries.ITEM.getKey(item).toString().toLowerCase();
                return name.contains(q) || key.contains(q);
            })
            .sorted(Comparator.comparing(item -> item.getName(item.getDefaultInstance()).getString()))
            .collect(Collectors.toList());
    }

    private void rebuildWhitelistItems() {
        whitelistItems = InventoryCleaner.whitelist.stream()
            .map(key -> {
                Identifier id = Identifier.tryParse(key);
                return id != null ? BuiltInRegistries.ITEM.getValue(id) : null;
            })
            .filter(item -> item != null && item != Items.AIR)
            .sorted(Comparator.comparing(item -> item.getName(item.getDefaultInstance()).getString()))
            .collect(Collectors.toList());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int wx = winX(), wy = winY();
        int colW   = (PANEL_W - PADDING * 3) / 2;
        int leftX  = wx + PADDING;
        int rightX = wx + PADDING * 2 + colW;
        int divX   = wx + PADDING + colW + PADDING / 2;
        int contentY = wy + CONTENT_Y_OFFSET;
        int visibleRows = (PANEL_H - CONTENT_Y_OFFSET - PADDING - 14) / ROW_H;

        // ── Panel background ──────────────────────────────────────────────────
        ctx.fill(wx, wy, wx + PANEL_W, wy + PANEL_H, 0xDD0D0D0D);

        // ── Outer border ──────────────────────────────────────────────────────
        ctx.fill(wx,            wy,            wx + PANEL_W, wy + 1,              0x3CFFFFFF);
        ctx.fill(wx,            wy + PANEL_H,  wx + PANEL_W, wy + PANEL_H + 1,   0x3CFFFFFF);
        ctx.fill(wx,            wy,            wx + 1,       wy + PANEL_H,        0x3CFFFFFF);
        ctx.fill(wx + PANEL_W,  wy,            wx + PANEL_W + 1, wy + PANEL_H,   0x3CFFFFFF);

        // ── Horizontal separators (below title, below search) ─────────────────
        ctx.fill(wx, wy + 22, wx + PANEL_W, wy + 23, 0x3CFFFFFF);
        ctx.fill(wx, wy + 50, wx + PANEL_W, wy + 51, 0x3CFFFFFF);

        // ── Vertical column divider ───────────────────────────────────────────
        ctx.fill(divX, wy + 51, divX + 1, wy + PANEL_H - 10, 0x3CFFFFFF);

        // ── Title ─────────────────────────────────────────────────────────────
        ctx.text(this.font, "Whitelist Editor", wx + PADDING, wy + 7, 0xFFFFFF, true);

        // ── Close button ──────────────────────────────────────────────────────
        int closeX = wx + PANEL_W - 50;
        int closeY = wy + 4;
        boolean hoverClose = mouseX >= closeX && mouseX <= closeX + 42
                          && mouseY >= closeY && mouseY <= closeY + 14;
        ctx.fill(closeX, closeY, closeX + 42, closeY + 14, hoverClose ? 0xFF444444 : 0xFF222222);
        fillOutline(ctx, closeX, closeY, 42, 14, 0x3CFFFFFF);
        ctx.text(this.font, "Close",
                 closeX + (42 - this.font.width("Close")) / 2, closeY + 3, 0xFFFFFF, true);

        // ── Column headers ────────────────────────────────────────────────────
        ctx.text(this.font, "ALL ITEMS", leftX,  wy + 54, 0xAAAAAA, false);
        ctx.text(this.font, "WHITELIST", rightX, wy + 54, 0xAAAAAA, false);

        // ── Left column (all items, excluding whitelist) ───────────────────────
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollLeft;
            if (idx >= filteredItems.size()) break;
            Item item = filteredItems.get(idx);
            int rowY = contentY + i * ROW_H;
            boolean hovered = mouseX >= leftX && mouseX < leftX + colW
                           && mouseY >= rowY  && mouseY < rowY + ROW_H;
            if (hovered) ctx.fill(leftX, rowY, leftX + colW, rowY + ROW_H, 0x33FFFFFF);
            ctx.item(item.getDefaultInstance(), leftX + 2, rowY + 2);
            ctx.text(this.font, item.getName(item.getDefaultInstance()),
                     leftX + 2 + ICON_SIZE + 3, rowY + (ROW_H - 8) / 2, 0xFFFFFF, false);
        }

        // ── Right column (whitelist) ──────────────────────────────────────────
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollRight;
            if (idx >= whitelistItems.size()) break;
            Item item = whitelistItems.get(idx);
            int rowY = contentY + i * ROW_H;
            boolean hovered = mouseX >= rightX && mouseX < rightX + colW
                           && mouseY >= rowY   && mouseY < rowY + ROW_H;
            if (hovered) ctx.fill(rightX, rowY, rightX + colW, rowY + ROW_H, 0x33FFFFFF);
            ctx.item(item.getDefaultInstance(), rightX + 2, rowY + 2);
            ctx.text(this.font, item.getName(item.getDefaultInstance()),
                     rightX + 2 + ICON_SIZE + 3, rowY + (ROW_H - 8) / 2, 0xFFFFFF, false);
        }

        // ── Scroll indicators ─────────────────────────────────────────────────
        if (scrollLeft > 0)
            ctx.text(this.font, "^", leftX + colW - 10, contentY, 0xAAAAAA, false);
        if (scrollLeft + visibleRows < filteredItems.size())
            ctx.text(this.font, "v", leftX + colW - 10, wy + PANEL_H - 12, 0xAAAAAA, false);
        if (scrollRight > 0)
            ctx.text(this.font, "^", rightX + colW - 10, contentY, 0xAAAAAA, false);
        if (scrollRight + visibleRows < whitelistItems.size())
            ctx.text(this.font, "v", rightX + colW - 10, wy + PANEL_H - 12, 0xAAAAAA, false);

        // ── GLFW click detection ──────────────────────────────────────────────
        boolean mouseDown = GLFW.glfwGetMouseButton(
            minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean justPressed = mouseDown && !wasMouseDown;
        wasMouseDown = mouseDown;

        if (justPressed) {
            int rowIdx = (mouseY - contentY) / ROW_H;
            boolean inRowArea = mouseY >= contentY && rowIdx >= 0 && rowIdx < visibleRows;

            if (hoverClose) {
                this.onClose();
            } else if (inRowArea && mouseX >= leftX && mouseX < leftX + colW) {
                // Add to whitelist
                int idx = rowIdx + scrollLeft;
                if (idx < filteredItems.size()) {
                    String key = BuiltInRegistries.ITEM.getKey(filteredItems.get(idx)).toString();
                    InventoryCleaner.whitelist.add(key);
                    rebuildWhitelistItems();
                    rebuildFiltered(searchBox.getValue());
                    if (scrollLeft > 0 && scrollLeft >= filteredItems.size()) scrollLeft--;
                }
            } else if (inRowArea && mouseX >= rightX && mouseX < rightX + colW) {
                // Remove from whitelist
                int idx = rowIdx + scrollRight;
                if (idx < whitelistItems.size()) {
                    String key = BuiltInRegistries.ITEM.getKey(whitelistItems.get(idx)).toString();
                    InventoryCleaner.whitelist.remove(key);
                    rebuildWhitelistItems();
                    rebuildFiltered(searchBox.getValue());
                    if (scrollRight > 0 && scrollRight >= whitelistItems.size()) scrollRight--;
                }
            }
        }

        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    /** 1px border rectangle. */
    private void fillOutline(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,         y,         x + w,     y + 1,     color);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     color);
        ctx.fill(x,         y,         x + 1,     y + h,     color);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     color);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        int wx = winX(), wy = winY();
        int colW     = (PANEL_W - PADDING * 3) / 2;
        int leftX    = wx + PADDING;
        int rightX   = wx + PADDING * 2 + colW;
        int contentY = wy + CONTENT_Y_OFFSET;
        int visibleRows = (PANEL_H - CONTENT_Y_OFFSET - PADDING - 14) / ROW_H;
        int d = (int) -Math.signum(verticalAmount);

        if (mouseX >= leftX && mouseX < leftX + colW && mouseY >= contentY) {
            scrollLeft  = Math.max(0, Math.min(scrollLeft  + d,
                          Math.max(0, filteredItems.size()  - visibleRows)));
        } else if (mouseX >= rightX && mouseX < rightX + colW && mouseY >= contentY) {
            scrollRight = Math.max(0, Math.min(scrollRight + d,
                          Math.max(0, whitelistItems.size() - visibleRows)));
        }
        return true;
    }

    @Override
    public void onClose() {
        InventoryCleaner.saveWhitelist();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
```

- [ ] **Step 2: Build and verify**

```
.\gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```
git add src/main/java/com/example/addon/screens/WhitelistEditorScreen.java
git commit -m "feat: redesign WhitelistEditorScreen as centered panel (FontScreen pattern)"
```
