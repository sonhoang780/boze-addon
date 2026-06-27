# WhitelistEditorScreen — Redesign Spec

Date: 2026-06-27
Project: Boze addon (MC 26.1.2, Boze API 3.2.2)

---

## Problem

Current `WhitelistEditorScreen` fills the entire screen with a dark overlay (`ctx.fill(0, 0, width, height, 0xCC111111)`), shows items as tiny icons with no names on the far left, and has no visible column separation. The target look is MusicHUD's `FontScreen`: a small centered floating panel, game world + HUD visible behind it, clear borders.

---

## Design

### Pattern

Follows `MusicHUD.FontScreen` exactly:
- `extends Screen` — no change to class hierarchy
- `hideGui` is **not** modified — HUD (health/hotbar) stays visible
- Game world renders behind the panel
- Mouse clicks detected via GLFW `glfwGetMouseButton` polled inside `extractRenderState`, using a `wasMouseDown` edge-trigger boolean — this bypasses the `mouseClicked` override issues documented in FontScreen
- `mouseScrolled` override kept (works fine in 26.1.2 — FontScreen only bypassed `mouseClicked`)

### Panel Dimensions

```
Panel width:  520px
Panel height: 340px
Position:     centered  →  winX = (width - 520) / 2,  winY = (height - 340) / 2
```

### Layout

```
winX                                              winX+520
 │                                                     │
 ┌─────────────────────────────────────────────────────┐  winY
 │  Whitelist Editor                        [Close]    │
 │ ─────────────────────────────────────────────────── │  winY+22
 │  Search: [EditBox 490px wide]                       │  winY+28
 │ ─────────────────────────────────────────────────── │  winY+50
 │  ALL ITEMS           │  WHITELIST                   │  winY+54 (headers)
 │  [icon] Item Name    │  [icon] Item Name            │
 │  [icon] Item Name    │  [icon] Item Name            │
 │  ...                 │  ...                         │
 │                    [v]                            [v]│  winY+330
 └─────────────────────────────────────────────────────┘  winY+340
```

Constants:
```
PANEL_W      = 520
PANEL_H      = 340
PADDING      = 8
HEADER_H     = 54       // top of content rows
ROW_H        = 20
ICON_SIZE    = 16
COL_DIVIDER  = winX + PANEL_W / 2
```

### Rendering (extractRenderState)

1. `context.fill(winX, winY, winX+PANEL_W, winY+PANEL_H, 0xDD0D0D0D)` — panel background
2. Border: 4× `context.fill(...)` 1px lines on all sides, color `0x3CFFFFFF`
3. Horizontal separator at `winY+22` and `winY+50`, color `0x3CFFFFFF`
4. Vertical column divider: `context.fill(COL_DIVIDER, HEADER_H, COL_DIVIDER+1, winY+PANEL_H-10, 0x3CFFFFFF)`
5. Title text `"Whitelist Editor"` at `winX+PADDING, winY+7`
6. `"[Close]"` button highlight on hover, drawn top-right inside panel
7. Column headers `"ALL ITEMS"` / `"WHITELIST"` at row `winY+54`
8. Each item row: `context.item(stack, iconX, rowY)` + `context.text(font, name, iconX+ICON_SIZE+3, rowY+4, 0xFFFFFF, false)`
9. Scroll indicators `"^"` / `"v"` at top/bottom of each column when content overflows
10. Hover highlight: `context.fill(rowX, rowY, rowX+colW, rowY+ROW_H, 0x33FFFFFF)`

### EditBox placement

```java
searchBox = new EditBox(font, winX + PADDING, winY + 28, PANEL_W - PADDING*2 - 2, 16, Component.empty());
```

Created in `init()`. `winX`/`winY` are computed fresh each `init()` call from `this.width`/`this.height`.

### Click detection (GLFW pattern)

```java
// Inside extractRenderState:
boolean mouseDown = GLFW.glfwGetMouseButton(mc.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
boolean justPressed = mouseDown && !wasMouseDown;
wasMouseDown = mouseDown;

if (justPressed) {
    // hit-test Close button → onClose()
    // hit-test left column row → add to whitelist, rebuildFiltered, rebuildWhitelist
    // hit-test right column row → remove from whitelist, rebuildFiltered, rebuildWhitelist
}
```

### Item filtering

`rebuildFiltered(query)` excludes items already in whitelist — so adding an item to whitelist removes it from the left column immediately (same behaviour already implemented, keep it).

---

## InventoryCleaner — `others` Option

### New option

```java
public final ToggleOption others = new ToggleOption(this, "Others",
    "Drop from other GUIs like Chest, Shulker, EnderChest...", false);
```

### Tick logic change

Current guard (skips when container GUI open):
```java
if (mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen)) return;
```

New guard:
```java
boolean externalGui = mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen);
if (externalGui && !others.getValue()) return;
```

When `others = true`, the cleaner runs even while chest/shulker/ender chest is open. Drop logic still targets **player inventory slots only** (slots 0–35), not the container's own slots.

---

## Files Changed

| File | Change |
|------|--------|
| `screens/WhitelistEditorScreen.java` | Full rewrite — small centered panel, GLFW clicks |
| `modules/InventoryCleaner.java` | Add `others` ToggleOption, update guard logic |

---

## Non-Goals

- No animation or transition on open/close
- No drag-and-drop
- No pagination beyond scroll indicators
- No multi-select
