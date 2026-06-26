# InventoryCleaner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-drop unwanted inventory items each tick with whitelist/blacklist/all modes and an in-game editor screen.

**Architecture:** `InventoryCleaner.java` owns all drop logic and a static `Set<String> whitelist` persisted via plain Gson to `config/inventory_cleaner_whitelist.json`. `WhitelistEditorScreen.java` is a vanilla `Screen` that reads/writes the static set and saves on close. `ExampleAddon.java` registers the module.

**Tech Stack:** Boze API 3.2.2, MC 26.1.2 (Mojmap), Gson (already on classpath via Fabric/Boze), FabricLoader for config dir.

## Global Constraints

- Boze API: `3.2.2+1.21.11` jar only — NO guessing from older versions
- MC 26.1.2 — `TieredItem` does NOT exist; use `stack.getMaxDamage()` for tool tier comparison
- Drop call: `mc.gameMode.handleContainerInput(containerId, handlerSlot, 0, ContainerInput.THROW, player)` — exact signature from `InventorySorter.java`
- Slot mapping (from `InventorySorter.getHandlerSlot`): invSlot 0–8 (hotbar) → handlerSlot 36–44; invSlot 9–35 (main inv) → handlerSlot 9–35
- Skip when external container open: same guard as `InventorySorter` — skip if `mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen)`
- Package: `com.example.addon.modules` / `com.example.addon.screens`
- No mixin needed

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/com/example/addon/modules/InventoryCleaner.java` | Module logic, options, tick drop, ThrowWorse, whitelist I/O |
| Create | `src/main/java/com/example/addon/screens/WhitelistEditorScreen.java` | 3-column HUD editor, search, icon render |
| Modify | `src/main/java/com/example/addon/ExampleAddon.java` | Import + register module |

---

### Task 1: InventoryCleaner core module (no ThrowWorse, no screen)

**Files:**
- Create: `src/main/java/com/example/addon/modules/InventoryCleaner.java`

**Interfaces:**
- Produces: `InventoryCleaner.INSTANCE`, `InventoryCleaner.whitelist` (static `Set<String>`), `InventoryCleaner.saveWhitelist()`, `InventoryCleaner.loadWhitelist()` — consumed by Task 2 and Task 3.

- [ ] **Step 1: Write InventoryCleaner.java**

```java
package com.example.addon.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class InventoryCleaner extends AddonModule {
    public static final InventoryCleaner INSTANCE = new InventoryCleaner();

    public enum CleanMode { WhiteList, BlackList, All }

    public final ModeOption<CleanMode> mode = new ModeOption<>(this, "Mode",
        "WhiteList = drop items NOT in list. BlackList = drop items IN list. All = drop everything.",
        CleanMode.WhiteList);

    public final ToggleOption ignoreHotbar = new ToggleOption(this, "IgnoreHotbar",
        "Skip hotbar slots (0-8) when cleaning.", true);

    public final ToggleOption throwWorse = new ToggleOption(this, "ThrowWorse",
        "Drop lower-durability duplicate tools (same type, e.g. two pickaxes).", true);

    public final ToggleOption editWhitelist = new ToggleOption(this, "EditWhitelist",
        "Open whitelist editor. Auto-resets after opening.", false);

    // ── Whitelist (static so WhitelistEditorScreen can read/write directly) ──
    public static Set<String> whitelist = new HashSet<>();

    private static final Path WHITELIST_FILE =
        FabricLoader.getInstance().getConfigDir().resolve("inventory_cleaner_whitelist.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public InventoryCleaner() {
        super("InventoryCleaner", "Auto-drop unwanted items from inventory each tick.");
    }

    @Override
    public void onEnable() {
        loadWhitelist();
    }

    @Override
    public void onDisable() {
        saveWhitelist();
    }

    public static void saveWhitelist() {
        try {
            Files.writeString(WHITELIST_FILE, GSON.toJson(whitelist));
        } catch (Exception ignored) {}
    }

    public static void loadWhitelist() {
        try {
            if (Files.exists(WHITELIST_FILE)) {
                Type type = new TypeToken<Set<String>>() {}.getType();
                Set<String> loaded = GSON.fromJson(Files.readString(WHITELIST_FILE), type);
                if (loaded != null) whitelist = loaded;
            }
        } catch (Exception ignored) {}
    }

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;

        // Open whitelist editor if toggle was flipped
        if (editWhitelist.getValue()) {
            editWhitelist.setValue(false);
            mc.setScreen(new com.example.addon.screens.WhitelistEditorScreen());
            return;
        }

        // Skip when external container (chest, furnace, etc.) is open
        if (mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen)) return;

        int containerId = mc.player.containerMenu.containerId;

        // Scan inventory slots: invSlot 0-35 (0-8 = hotbar, 9-35 = main)
        for (int invSlot = 0; invSlot < 36; invSlot++) {
            if (ignoreHotbar.getValue() && invSlot <= 8) continue;

            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;

            // Never drop armor (handled separately by equipment slots, not inv index 0-35)
            // slot 100-103 = armor in AbstractContainerMenu but player.getInventory() 0-35
            // is safe — armor is in EquipmentSlot, not in these indices for drop.

            if (shouldDrop(stack)) {
                int handlerSlot = invToHandlerSlot(invSlot);
                mc.gameMode.handleContainerInput(containerId, handlerSlot, 0, ContainerInput.THROW, mc.player);
            }
        }

        // ThrowWorse pass
        if (throwWorse.getValue()) {
            runThrowWorsePass(mc, containerId);
        }
    }

    private boolean shouldDrop(ItemStack stack) {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(stack.getItem()).toString();
        return switch (mode.getValue()) {
            case All       -> true;
            case WhiteList -> !whitelist.contains(key);
            case BlackList ->  whitelist.contains(key);
        };
    }

    private void runThrowWorsePass(Minecraft mc, int containerId) {
        // Group slots by tool type suffix (_pickaxe, _axe, _shovel, _hoe, _sword)
        // For each group, find max durability and drop all lower-durability slots.
        // Only drop when group has >= 2 items (never drop last of a type).
        String[] suffixes = { "_pickaxe", "_axe", "_shovel", "_hoe", "_sword" };

        for (String suffix : suffixes) {
            // Find all slots with this tool type
            java.util.List<int[]> group = new java.util.ArrayList<>(); // [invSlot, maxDamage]
            for (int invSlot = 0; invSlot < 36; invSlot++) {
                if (ignoreHotbar.getValue() && invSlot <= 8) continue;
                ItemStack stack = mc.player.getInventory().getItem(invSlot);
                if (stack.isEmpty()) continue;
                String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
                if (key.endsWith(suffix)) {
                    group.add(new int[]{ invSlot, stack.getMaxDamage() });
                }
            }
            if (group.size() < 2) continue;

            int maxDur = group.stream().mapToInt(e -> e[1]).max().orElse(0);
            for (int[] entry : group) {
                if (entry[1] < maxDur) {
                    int handlerSlot = invToHandlerSlot(entry[0]);
                    mc.gameMode.handleContainerInput(containerId, handlerSlot, 0, ContainerInput.THROW, mc.player);
                }
            }
        }
    }

    // invSlot 0-8  → handlerSlot 36-44 (hotbar at bottom of InventoryMenu)
    // invSlot 9-35 → handlerSlot 9-35  (main inventory)
    private static int invToHandlerSlot(int invSlot) {
        return (invSlot <= 8) ? 36 + invSlot : invSlot;
    }
}
```

- [ ] **Step 2: Build and verify compile**

```
.\gradlew compileJava
```

Expected: `BUILD SUCCESSFUL` with no errors. Fix any import/API errors before proceeding.

- [ ] **Step 3: Register module in ExampleAddon.java**

In `src/main/java/com/example/addon/ExampleAddon.java`, add after the last `modules.add(...)` line before `extensions.add(...)`:

```java
import com.example.addon.modules.InventoryCleaner;
// (add to import block at top of file)
```

And in `initialize()`:
```java
modules.add(InventoryCleaner.INSTANCE);
```

- [ ] **Step 4: Build again to confirm registration compiles**

```
.\gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/example/addon/modules/InventoryCleaner.java
git add src/main/java/com/example/addon/ExampleAddon.java
git commit -m "feat: add InventoryCleaner module (core drop logic, no screen yet)"
```

---

### Task 2: WhitelistEditorScreen

**Files:**
- Create: `src/main/java/com/example/addon/screens/WhitelistEditorScreen.java`

**Interfaces:**
- Consumes: `InventoryCleaner.whitelist` (static `Set<String>`), `InventoryCleaner.saveWhitelist()` (static)
- Produces: `WhitelistEditorScreen` class — referenced in `InventoryCleaner.onTickPre` as `new com.example.addon.screens.WhitelistEditorScreen()`

- [ ] **Step 1: Create screens package directory**

```
mkdir src\main\java\com\example\addon\screens
```

- [ ] **Step 2: Write WhitelistEditorScreen.java**

```java
package com.example.addon.screens;

import com.example.addon.modules.InventoryCleaner;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class WhitelistEditorScreen extends Screen {

    private static final int ROW_H      = 22;
    private static final int PADDING    = 6;
    private static final int HEADER_H   = 36;  // space for search bar
    private static final int ICON_SIZE  = 16;
    private static final int COL_GAP    = 4;

    private EditBox searchBox;
    private int scrollLeft  = 0;
    private int scrollRight = 0;

    // Filtered list of all items in registry
    private List<Item> filteredItems = new ArrayList<>();
    // Current whitelist as ordered list for display
    private List<Item> whitelistItems = new ArrayList<>();

    public WhitelistEditorScreen() {
        super(Component.literal("Whitelist Editor"));
    }

    @Override
    protected void init() {
        super.init();
        int boxW = Math.min(200, this.width / 2 - PADDING * 2);
        int boxX = (this.width - boxW) / 2;
        searchBox = new EditBox(this.font, boxX, PADDING, boxW, 18, Component.literal(""));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search items..."));
        searchBox.setResponder(text -> {
            scrollLeft = 0;
            rebuildFiltered(text);
        });
        this.addRenderableWidget(searchBox);
        rebuildFiltered("");
        rebuildWhitelistItems();
    }

    private void rebuildFiltered(String query) {
        String q = query.toLowerCase().trim();
        filteredItems = BuiltInRegistries.ITEM.stream()
            .filter(item -> item != net.minecraft.world.item.Items.AIR)
            .filter(item -> {
                if (q.isEmpty()) return true;
                String name = item.getDescription().getString().toLowerCase();
                String key  = BuiltInRegistries.ITEM.getKey(item).toString().toLowerCase();
                return name.contains(q) || key.contains(q);
            })
            .sorted(Comparator.comparing(item -> item.getDescription().getString()))
            .collect(java.util.stream.Collectors.toList());
    }

    private void rebuildWhitelistItems() {
        whitelistItems = InventoryCleaner.whitelist.stream()
            .map(key -> BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(key)))
            .filter(item -> item != null && item != net.minecraft.world.item.Items.AIR)
            .sorted(Comparator.comparing(item -> item.getDescription().getString()))
            .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);

        int colW    = (this.width - PADDING * 3 - COL_GAP) / 2;
        int leftX   = PADDING;
        int rightX  = PADDING + colW + COL_GAP;
        int contentY = HEADER_H;
        int visibleRows = (this.height - contentY - PADDING) / ROW_H;

        // Column headers
        ctx.drawString(this.font, Component.literal("ALL ITEMS"), leftX, contentY - 12, 0xAAAAAA, false);
        ctx.drawString(this.font, Component.literal("WHITELIST"), rightX, contentY - 12, 0xAAAAAA, false);

        // Left column
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollLeft;
            if (idx >= filteredItems.size()) break;
            Item item = filteredItems.get(idx);
            int y = contentY + i * ROW_H;

            boolean hovered = mouseX >= leftX && mouseX < leftX + colW && mouseY >= y && mouseY < y + ROW_H;
            if (hovered) ctx.fill(leftX, y, leftX + colW, y + ROW_H, 0x33FFFFFF);

            ctx.renderItem(new ItemStack(item), leftX + 2, y + (ROW_H - ICON_SIZE) / 2);
            ctx.drawString(this.font, item.getDescription(),
                leftX + 2 + ICON_SIZE + 3, y + (ROW_H - 8) / 2, 0xFFFFFF, false);
        }

        // Right column
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollRight;
            if (idx >= whitelistItems.size()) break;
            Item item = whitelistItems.get(idx);
            int y = contentY + i * ROW_H;

            boolean hovered = mouseX >= rightX && mouseX < rightX + colW && mouseY >= y && mouseY < y + ROW_H;
            if (hovered) ctx.fill(rightX, y, rightX + colW, y + ROW_H, 0x33FFFFFF);

            ctx.renderItem(new ItemStack(item), rightX + 2, y + (ROW_H - ICON_SIZE) / 2);
            ctx.drawString(this.font, item.getDescription(),
                rightX + 2 + ICON_SIZE + 3, y + (ROW_H - 8) / 2, 0xFFFFFF, false);
        }

        // Scroll indicators
        if (scrollLeft > 0)
            ctx.drawString(this.font, Component.literal("▲"), leftX + colW - 10, contentY, 0xAAAAAA, false);
        if (scrollLeft + visibleRows < filteredItems.size())
            ctx.drawString(this.font, Component.literal("▼"), leftX + colW - 10, this.height - PADDING - 10, 0xAAAAAA, false);
        if (scrollRight > 0)
            ctx.drawString(this.font, Component.literal("▲"), rightX + colW - 10, contentY, 0xAAAAAA, false);
        if (scrollRight + visibleRows < whitelistItems.size())
            ctx.drawString(this.font, Component.literal("▼"), rightX + colW - 10, this.height - PADDING - 10, 0xAAAAAA, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        int colW     = (this.width - PADDING * 3 - COL_GAP) / 2;
        int leftX    = PADDING;
        int rightX   = PADDING + colW + COL_GAP;
        int contentY = HEADER_H;
        int visibleRows = (this.height - contentY - PADDING) / ROW_H;

        int rowIdx = ((int) mouseY - contentY) / ROW_H;
        if (rowIdx < 0 || rowIdx >= visibleRows) return false;

        // Click on left column → add to whitelist
        if (mouseX >= leftX && mouseX < leftX + colW) {
            int idx = rowIdx + scrollLeft;
            if (idx < filteredItems.size()) {
                Item item = filteredItems.get(idx);
                String key = BuiltInRegistries.ITEM.getKey(item).toString();
                InventoryCleaner.whitelist.add(key);
                rebuildWhitelistItems();
            }
            return true;
        }

        // Click on right column → remove from whitelist
        if (mouseX >= rightX && mouseX < rightX + colW) {
            int idx = rowIdx + scrollRight;
            if (idx < whitelistItems.size()) {
                Item item = whitelistItems.get(idx);
                String key = BuiltInRegistries.ITEM.getKey(item).toString();
                InventoryCleaner.whitelist.remove(key);
                rebuildWhitelistItems();
                if (scrollRight > 0 && scrollRight >= whitelistItems.size()) scrollRight--;
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;

        int colW    = (this.width - PADDING * 3 - COL_GAP) / 2;
        int leftX   = PADDING;
        int rightX  = PADDING + colW + COL_GAP;
        int contentY = HEADER_H;
        int visibleRows = (this.height - contentY - PADDING) / ROW_H;
        int delta = (int) -Math.signum(verticalAmount);

        if (mouseX >= leftX && mouseX < leftX + colW) {
            scrollLeft = Math.max(0, Math.min(scrollLeft + delta, Math.max(0, filteredItems.size() - visibleRows)));
        } else if (mouseX >= rightX && mouseX < rightX + colW) {
            scrollRight = Math.max(0, Math.min(scrollRight + delta, Math.max(0, whitelistItems.size() - visibleRows)));
        }
        return true;
    }

    @Override
    public void onClose() {
        InventoryCleaner.saveWhitelist();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
```

- [ ] **Step 3: Build and verify compile**

```
.\gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. Common issues to fix:
- `ResourceLocation.parse` vs `ResourceLocation.of` — check MC 26.1.2 API; if `parse` doesn't exist use `new ResourceLocation(key)` or `ResourceLocation.tryParse(key)`
- `BuiltInRegistries.ITEM.get(ResourceLocation)` returns `Optional<Item>` in some versions — wrap accordingly
- `renderBackground` signature — may need `(GuiGraphics ctx)` without mouseX/mouseY in some versions

- [ ] **Step 4: Full build**

```
.\gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/example/addon/screens/WhitelistEditorScreen.java
git commit -m "feat: add WhitelistEditorScreen with search + icon rendering"
```

---

### Task 3: Wire screen into InventoryCleaner + final build

**Files:**
- Verify: `src/main/java/com/example/addon/modules/InventoryCleaner.java` — `new WhitelistEditorScreen()` import resolves

- [ ] **Step 1: Verify import resolves**

`InventoryCleaner.java` already references `com.example.addon.screens.WhitelistEditorScreen`. After Task 2 creates that class, the reference should compile. If `.\gradlew compileJava` from Task 1 Step 2 was done BEFORE Task 2, it may have failed — that's expected. Now that both files exist, do a clean build:

```
.\gradlew build
```

Expected: `BUILD SUCCESSFUL` with zero errors.

- [ ] **Step 2: Smoke test in-game**

Manual test checklist:
1. Launch client, enable **InventoryCleaner** module.
2. Set Mode = `All`, put dirt in inventory → dirt should auto-drop each tick.
3. Set Mode = `WhiteList`, whitelist is empty → all items drop. Add `minecraft:dirt` via EditWhitelist screen → dirt no longer drops.
4. Toggle `EditWhitelist` → editor screen opens, closes cleanly, whitelist persists after re-enabling module.
5. Put two pickaxes of different tiers, enable `ThrowWorse` → lower-tier pickaxe drops.
6. Disable module → re-enable → whitelist loads from file correctly.

- [ ] **Step 3: Final commit**

```
git add -A
git commit -m "feat: InventoryCleaner complete — drop logic, ThrowWorse, whitelist editor screen"
```

---

## Notes

- `ResourceLocation.parse(key)` — MC 26.1.2 / 1.21.11 Mojmap. If compile fails, try `ResourceLocation.tryParse(key)` or `new ResourceLocation(namespace, path)` split by `:`.
- `BuiltInRegistries.ITEM.get(rl)` — returns `Optional<Holder<Item>>` in 1.21+; use `.map(Holder::value).orElse(Items.AIR)` if needed.
- Gold tools have durability 32 (lower than Wood at 59) — ThrowWorse will drop gold tools if a wood tool of same type is present. This is expected behavior given the durability-based comparison.
- `isPauseScreen() = false` keeps the game running while editor is open (important so inventory is live).
