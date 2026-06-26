# InventoryCleaner — Design Spec
Date: 2026-06-27  
Project: Boze addon (MC 26.1.2, Boze API 3.2.2)

---

## Overview

Auto-drop unwanted items from inventory each tick. Supports whitelist/blacklist/all modes, optional hotbar protection, and auto-drop of lower-tier duplicate tools. Ships with an in-game editor screen for managing the whitelist.

---

## Files

```
src/main/java/com/example/addon/
  modules/InventoryCleaner.java
  screens/WhitelistEditorScreen.java
```

---

## InventoryCleaner.java

### Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mode` | `ModeOption<CleanMode>` | `WhiteList` | WhiteList / BlackList / All |
| `ignoreHotbar` | `ToggleOption` | `true` | When ON, skip slots 0–8 |
| `throwWorse` | `ToggleOption` | `true` | Drop lower-tier duplicate tools |
| `editWhitelist` | `ToggleOption` | `false` | Opens editor screen, auto-resets to false |

### Static State

```java
static Set<String> whitelist = new HashSet<>();
```

Key = item registry name, e.g. `"minecraft:diamond_pickaxe"`.

### Persistence

- Load on `onEnable()`: `JsonTools.loadObject(path, Set.class)` → populate `whitelist`.
- Save on `onDisable()` and when editor screen closes: `JsonTools.saveObject(whitelist, path)`.
- Path: `config/inventory_cleaner_whitelist.json`.

### Tick Logic (EventTick.Pre)

1. If `editWhitelist` is true → open `WhitelistEditorScreen`, reset toggle to false, return.
2. Determine slot range:
   - `ignoreHotbar=true`: slots 9–44 (main inventory only).
   - `ignoreHotbar=false`: slots 0–44 (hotbar + inventory).
   - Always skip armor slots (36–39 client-side, but armor slots in container are 5–8 — use `player.getInventory()` indexing carefully).
3. For each slot, resolve `ItemStack`. If empty, skip.
4. Apply `CleanMode` check:
   - `All` → drop (safety exceptions below).
   - `WhiteList` → drop if item NOT in whitelist.
   - `BlackList` → drop if item IS in whitelist.
5. Safety: never drop items in armor slots or offhand (slot 40 / `EquipmentSlot.OFFHAND`).
6. Drop via: `mc.player.containerMenu.handleContainerInput(syncId, slotId, 0, ContainerInput.THROW, mc.player)`.
   - `syncId = mc.player.containerMenu.containerId`.
   - `slotId` = slot index in `InventoryMenu` coordinates: hotbar = slots 36–44 in menu (map from `player.getInventory()` index carefully). Use `mc.player.containerMenu.slots` list to find correct index by iterating and matching `slot.container == player.getInventory() && slot.getSlotIndex() == invIdx`.
7. If `throwWorse=true`, run **ThrowWorse pass** after normal pass.

### ThrowWorse Pass

Group slots by tool type:
- Detect via `instanceof`: `PickaxeItem`, `AxeItem`, `ShovelItem`, `HoeItem`, `SwordItem`.
- For each group, find max tier level via `((TieredItem) item).getTier().getLevel()`.
- Drop all slots in the group whose tier level < max.
- Tier levels: Wood=0, Stone=1, Iron=2, Diamond=3, Netherite=4.
- Only compare within same `instanceof` type (no pickaxe vs. sword comparison).

---

## WhitelistEditorScreen.java

Extends `Screen` (vanilla).

### Layout

```
┌─────────────────────────────────────────────────────────┐
│  Search: [_________________________________]            │
├──────────────────────┬──────────────────────────────────┤
│  ALL ITEMS           │  WHITELIST                       │
│  [icon] Acacia Boat  │  [icon] Diamond Pickaxe          │
│  [icon] Acacia Log   │  [icon] Iron Ore                 │
│  ...  (scrollable)   │  ...  (scrollable)               │
└──────────────────────┴──────────────────────────────────┘
```

### Data

- `allItems`: `BuiltInRegistries.ITEM.stream()` filtered by `searchQuery`. Sorted alphabetically by display name.
- `whitelistItems`: items from `InventoryCleaner.whitelist` as `Item` list.
- `scrollLeft`, `scrollRight`: int offsets (rows from top).
- `searchQuery`: string, updated on keystroke.

### Rendering

- Item icon: `context.renderItem(new ItemStack(item), x, y)` — 16×16.
- Item name: `context.drawString(font, item.getDescription(), x+20, y+4, 0xFFFFFF, false)`.
- Row height: 20px. Column width: ~(width/2 - 10)px each.
- Visible rows per column: `(height - headerHeight) / 20`.
- Search bar: `EditBox` widget, positioned top-center.

### Interaction

- `mouseClicked` on left column row → `InventoryCleaner.whitelist.add(registryName)`, rebuild `whitelistItems`.
- `mouseClicked` on right column row → `InventoryCleaner.whitelist.remove(registryName)`, rebuild `whitelistItems`.
- `mouseScrolled` → scroll respective column (detect by x position).
- Search `EditBox` onChange → reset `scrollLeft = 0`, refilter `allItems`.

### Lifecycle

- `init()`: create `EditBox`, populate `allItems` from registry.
- `onClose()`: `InventoryCleaner.saveWhitelist()`.

---

## Data Flow

```
EventTick.Pre
  → editWhitelist? → open WhitelistEditorScreen
  → scan inventory slots
  → CleanMode filter → drop via handleContainerInput
  → ThrowWorse pass → drop lower-tier tools

WhitelistEditorScreen
  → reads/writes InventoryCleaner.whitelist (static)
  → onClose → saveWhitelist (JsonTools)

InventoryCleaner.onEnable → loadWhitelist
InventoryCleaner.onDisable → saveWhitelist
```

---

## Error Handling / Safety

- Empty slot: skip silently.
- `containerMenu` not open (chest/crafting screen open): skip entire tick — check `mc.player.containerMenu instanceof InventoryMenu`.
- Armor slots: always excluded from drop candidates.
- Offhand (slot 40): always excluded.
- `ThrowWorse` never drops last item of a tool type (even if worse) — only drops when ≥2 items in the group.

---

## Registration

In `ExampleAddon.java`:
```java
import com.example.addon.modules.InventoryCleaner;
// ...
modules.add(InventoryCleaner.INSTANCE);
```

No mixin needed.
