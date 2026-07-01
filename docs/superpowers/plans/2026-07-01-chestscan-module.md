# ChestScan Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `ChestScan` module that overlays a colored 3D box on chests/trapped chests the player has opened (green=empty, yellow=partial, red=full), persisted per-world across sessions, with an optional "Hopper Chain" mode that infers upstream chests must be empty when the bottom chest of a hopper chain isn't full.

**Architecture:** Three new files under `com.example.addon.modules.chestscan`: `ChestScanStore` (per-world JSON persistence of `BlockPos -> ChestStatus`, Gson-based, same file-under-gamedir convention as `EvilRekit`), `ChestScanChain` (pure hopper-edge detection + BFS inference over the tracked chest set, no world-write side effects), and `ChestScan` (the module itself — options, `EventTick` tracking of open/close via `mc.hitResult`/`mc.player.containerMenu`, `EventWorldRender` drawing via `WorldDrawer`). No mixins — everything is built on the two existing polling-style events (`EventTick`, `EventWorldRender`) already used elsewhere in this codebase.

**Tech Stack:** Java 25 / Fabric Loom, MC `26.1.2` real Mojang-mapped API, Boze API `3.3+26.1.2` (`WorldDrawer`, `ColorMaker`, `AddonModule`, `SliderOption`, `ToggleOption`), Gson (already a project dependency, see `EvilRekit.java`).

## Global Constraints

- MC `26.1.2`, Boze API `3.3+26.1.2` — no other version's API knowledge applies. Every signature below was verified via `javap -p -classpath <jar> <class>` against `C:\Users\conng\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.1.2\minecraft-merged-deobf-26.1.2.jar` this session — do not substitute pre-26.1 Mojmap/Yarn names.
- No test framework in this repo (no `src/test`). Verification = `.\gradlew compileJava` passing after every task, final task adds a manual in-game check — same convention as the PathFinder and BetterChams-Glow plans.
- Scope is vanilla **Chest** and **Trapped Chest** only (`net.minecraft.world.level.block.ChestBlock` and its subclass `net.minecraft.world.level.block.TrappedChestBlock` — a plain `instanceof ChestBlock` check covers both). Not Shulker Box, Ender Chest, Barrel, Hopper (as a container), Dispenser/Dropper, Furnace.
- No mixins for this module. Tracking is 100% `EventTick`-polling based (no block-update/screen-open event exists in Boze API 3.3 — confirmed by reading the full event package listing).
- Colors and opacity are hardcoded (no `ColorOption`s): green `(0,200,0)`, yellow `(230,200,0)`, red `(220,0,0)`, fill opacity `0.47f` (~120/255), outline opacity `1.0f`.
- Verified MC 26.1.2 API surface used in this plan (via `javap`, this session):
  - `net.minecraft.world.inventory.ChestMenu.getContainer()` → `net.minecraft.world.Container` (the chest's actual container — reading this directly means no player-inventory-offset slot math is ever needed, unlike `EvilRekit`'s manual `handler.slots`).
  - `net.minecraft.world.Container.getContainerSize()` / `.getItem(int)` (returns `ItemStack`, `.isEmpty()` on it).
  - `net.minecraft.world.level.block.ChestBlock.FACING` / `.TYPE` (`EnumProperty<ChestType>`, both `public static final` directly on `ChestBlock`), `.getConnectedBlockPos(BlockPos, BlockState)` (static, resolves the other half of a double chest).
  - `net.minecraft.world.level.block.state.properties.ChestType.SINGLE/LEFT/RIGHT`.
  - `net.minecraft.world.level.block.HopperBlock.FACING` (`EnumProperty<Direction>`, read via `blockState.getValue(HopperBlock.FACING)` — no `HopperBlockEntity` needed at all, hopper detection is pure blockstate).
  - `net.minecraft.core.BlockPos.relative(Direction)`, `.immutable()`, `.getX()/.getY()/.getZ()` (inherited from `Vec3i`), `net.minecraft.core.Vec3i.distSqr(Vec3i)`.
  - `net.minecraft.core.Direction.values()` / `.getOpposite()`.
  - `net.minecraft.world.phys.BlockHitResult.getBlockPos()` / `.getType()` returning `net.minecraft.world.phys.HitResult$Type` (`MISS`/`BLOCK`/`ENTITY`), field `Minecraft.hitResult` (type `HitResult`).
  - `net.minecraft.world.phys.AABB(BlockPos)` constructor — exact 1x1x1 box for a block position, no manual math.
  - `net.minecraft.client.Minecraft.getCurrentServer()` → `ServerData` (public field `.ip`); `.hasSingleplayerServer()` / `.getSingleplayerServer()` → `IntegratedServer extends MinecraftServer`, `.getWorldData().getLevelName()`.
  - `net.minecraft.resources.ResourceKey.identifier()` (NOT `.location()` — that name does not exist on `ResourceKey` in 26.1.2) — used as `mc.level.dimension().identifier().toString()`.
  - `dev.boze.api.render.ColorMaker.staticColor(int r, int g, int b)` → `ClientColor` (unregistered default color, exactly what hardcoded module colors need).
  - `dev.boze.api.render.WorldDrawer.start()` / `.box(ClientColor, float fillOpacity, float outlineOpacity, AABB)` / `.draw(PoseStack)`.
  - `dev.boze.api.event.EventWorldRender` — has public fields `matrices` (`PoseStack`), `camera`, `tickDelta`.
  - `dev.boze.api.event.EventTick.Pre`/`.Post` — no fields, just event markers (matches existing `PathFinder`/`EvilRekit` usage).

---

### Task 1: `ChestScanStore` — per-world persistence

**Files:**
- Create: `src/main/java/com/example/addon/modules/chestscan/ChestScanStore.java`

**Interfaces:**
- Produces: `ChestScanStore.ChestStatus` enum (`EMPTY`, `PARTIAL`, `FULL`); instance methods `loadForWorld()`, `put(BlockPos, ChestStatus)`, `get(BlockPos)`, `remove(BlockPos)`, `positions()` (returns `Set<BlockPos>`); static `currentWorldKey()` (returns `String`) — all consumed by Task 3 (`ChestScan`) and Task 2 (`ChestScanChain`, via the `Map<BlockPos, ChestStatus>` it's given, not the store directly).
- Consumes: nothing from other tasks.

- [ ] **Step 1: Create the file**

```java
package com.example.addon.modules.chestscan;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChestScanStore {

    public enum ChestStatus { EMPTY, PARTIAL, FULL }

    private static class Entry {
        int x, y, z;
        String status;
    }

    private final Map<BlockPos, ChestStatus> records = new HashMap<>();
    private File file;

    public static String currentWorldKey() {
        Minecraft mc = Minecraft.getInstance();
        String base;
        if (mc.getCurrentServer() != null) {
            base = "server_" + mc.getCurrentServer().ip;
        } else if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            base = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        } else {
            base = "unknown";
        }
        String dimension = mc.level != null ? mc.level.dimension().identifier().toString() : "no_dimension";
        return sanitize(base) + "__" + sanitize(dimension);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    public void loadForWorld() {
        records.clear();
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/chestscan");
        if (!dir.exists()) dir.mkdirs();
        file = new File(dir, currentWorldKey() + ".json");
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Gson gson = new Gson();
            Type type = new TypeToken<List<Entry>>() {}.getType();
            List<Entry> entries = gson.fromJson(reader, type);
            if (entries != null) {
                for (Entry e : entries) {
                    records.put(new BlockPos(e.x, e.y, e.z), ChestStatus.valueOf(e.status));
                }
            }
        } catch (Exception ignored) {}
    }

    private void save() {
        if (file == null) return;
        try (FileWriter writer = new FileWriter(file)) {
            List<Entry> entries = new ArrayList<>();
            for (Map.Entry<BlockPos, ChestStatus> e : records.entrySet()) {
                Entry entry = new Entry();
                entry.x = e.getKey().getX();
                entry.y = e.getKey().getY();
                entry.z = e.getKey().getZ();
                entry.status = e.getValue().name();
                entries.add(entry);
            }
            new GsonBuilder().create().toJson(entries, writer);
        } catch (Exception ignored) {}
    }

    public void put(BlockPos pos, ChestStatus status) {
        records.put(pos.immutable(), status);
        save();
    }

    public ChestStatus get(BlockPos pos) {
        return records.get(pos);
    }

    public void remove(BlockPos pos) {
        if (records.remove(pos) != null) save();
    }

    public Set<BlockPos> positions() {
        return records.keySet();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/chestscan/ChestScanStore.java
git commit -m "feat: add ChestScanStore per-world persistence"
```

---

### Task 2: `ChestScanChain` — hopper-edge detection + inference

**Files:**
- Create: `src/main/java/com/example/addon/modules/chestscan/ChestScanChain.java`

**Interfaces:**
- Consumes: `ChestScanStore.ChestStatus` (Task 1).
- Produces: `ChestScanChain.findEdges(Level, Set<BlockPos> trackedChests, BlockPos center, int radiusBlocks)` → `Map<BlockPos, BlockPos>` (direct `source chest -> dest chest` edges through one connecting hopper); `ChestScanChain.inferEmpty(Map<BlockPos, BlockPos> edges, Map<BlockPos, ChestScanStore.ChestStatus> realStatuses)` → `Set<BlockPos>` (positions to render as inferred-empty). Both consumed by Task 4 (`ChestScan`'s render/chain-recompute logic).

- [ ] **Step 1: Create the file**

```java
package com.example.addon.modules.chestscan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChestScanChain {

    private ChestScanChain() {}

    /**
     * Direct chest-to-chest edges discovered through exactly one connecting hopper.
     * Case 1: a hopper directly below a tracked chest pulls FROM it (chest is the source).
     * Case 2: a hopper anywhere adjacent to a tracked chest, facing INTO it, feeds FROM
     * whatever chest sits directly above that hopper (chest is the destination).
     */
    public static Map<BlockPos, BlockPos> findEdges(Level level, Set<BlockPos> trackedChests, BlockPos center, int radiusBlocks) {
        Map<BlockPos, BlockPos> edges = new HashMap<>();
        double radiusSq = (double) radiusBlocks * radiusBlocks;

        for (BlockPos chestPos : trackedChests) {
            if (chestPos.distSqr(center) > radiusSq) continue;

            BlockPos belowPos = chestPos.below();
            BlockState belowState = level.getBlockState(belowPos);
            if (belowState.getBlock() instanceof HopperBlock) {
                Direction facing = belowState.getValue(HopperBlock.FACING);
                BlockPos dest = belowPos.relative(facing);
                if (isChest(level.getBlockState(dest))) {
                    edges.put(chestPos, dest);
                }
            }

            for (Direction dir : Direction.values()) {
                BlockPos hopperPos = chestPos.relative(dir);
                BlockState hopperState = level.getBlockState(hopperPos);
                if (!(hopperState.getBlock() instanceof HopperBlock)) continue;
                if (hopperState.getValue(HopperBlock.FACING) != dir.getOpposite()) continue;
                BlockPos sourcePos = hopperPos.above();
                if (isChest(level.getBlockState(sourcePos))) {
                    edges.put(sourcePos, chestPos);
                }
            }
        }
        return edges;
    }

    private static boolean isChest(BlockState state) {
        return state.getBlock() instanceof ChestBlock;
    }

    /**
     * Given source->dest edges and known real (opened) statuses, returns positions that
     * should render as "inferred empty": ancestors (via the edge graph, walked backward)
     * of a sink chest (no outgoing edge) whose real status is known and not FULL, excluding
     * any position that already has a real recorded status (real always wins over inference).
     */
    public static Set<BlockPos> inferEmpty(Map<BlockPos, BlockPos> edges, Map<BlockPos, ChestScanStore.ChestStatus> realStatuses) {
        Set<BlockPos> hasOutgoing = edges.keySet();
        Set<BlockPos> allChests = new HashSet<>();
        allChests.addAll(edges.keySet());
        allChests.addAll(edges.values());

        Set<BlockPos> sinks = new HashSet<>();
        for (BlockPos c : allChests) {
            if (!hasOutgoing.contains(c)) sinks.add(c);
        }

        Map<BlockPos, List<BlockPos>> reverse = new HashMap<>();
        for (Map.Entry<BlockPos, BlockPos> e : edges.entrySet()) {
            reverse.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        Set<BlockPos> inferred = new HashSet<>();
        for (BlockPos sink : sinks) {
            ChestScanStore.ChestStatus sinkStatus = realStatuses.get(sink);
            if (sinkStatus == null || sinkStatus == ChestScanStore.ChestStatus.FULL) continue;

            Deque<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> visited = new HashSet<>();
            queue.add(sink);
            visited.add(sink);
            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                for (BlockPos parent : reverse.getOrDefault(cur, List.of())) {
                    if (!visited.add(parent)) continue;
                    if (!realStatuses.containsKey(parent)) inferred.add(parent);
                    queue.add(parent);
                }
            }
        }
        return inferred;
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/chestscan/ChestScanChain.java
git commit -m "feat: add ChestScanChain hopper-edge detection and inference"
```

---

### Task 3: `ChestScan` module skeleton — options and lifecycle

**Files:**
- Create: `src/main/java/com/example/addon/modules/chestscan/ChestScan.java`

**Interfaces:**
- Consumes: `ChestScanStore` (Task 1, instantiated as a field), `ChestScanChain` (Task 2, called in Task 4).
- Produces: `ChestScan.INSTANCE` (singleton, consumed by Task 5 for registration); fields `scanRadius` (`SliderOption`), `hopperChain` (`ToggleOption`) referenced nowhere outside this class but must exist with these exact names for Task 4's step to extend.

- [ ] **Step 1: Create the file with options and world-reload lifecycle only (no tracking/rendering yet)**

```java
package com.example.addon.modules.chestscan;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;

public class ChestScan extends AddonModule {
    public static final ChestScan INSTANCE = new ChestScan();

    public final SliderOption scanRadius = new SliderOption(this, "Scan Radius",
        "How far (in blocks) to render tracked chests and consider hopper chains.", 64.0, 8.0, 128.0, 1.0);
    public final ToggleOption hopperChain = new ToggleOption(this, "Hopper Chain",
        "Smart mode to check chests linked to the bottom chest by hoppers", false);

    private final ChestScanStore store = new ChestScanStore();
    private String lastWorldKey = null;

    private ChestScan() {
        super("ChestScan", "Highlights opened chests by contents (empty/partial/full), with optional hopper-chain inference.");
    }

    @Override
    public void onEnable() {
        store.loadForWorld();
        lastWorldKey = ChestScanStore.currentWorldKey();
    }

    @Override
    public void onDisable() {
        lastWorldKey = null;
    }

    private void maybeReloadStoreForWorld() {
        String key = ChestScanStore.currentWorldKey();
        if (!key.equals(lastWorldKey)) {
            store.loadForWorld();
            lastWorldKey = key;
        }
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        maybeReloadStoreForWorld();
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/chestscan/ChestScan.java
git commit -m "feat: add ChestScan module skeleton with options"
```

---

### Task 4: Open/close tracking

**Files:**
- Modify: `src/main/java/com/example/addon/modules/chestscan/ChestScan.java`

**Interfaces:**
- Consumes: `ChestScanStore.put(BlockPos, ChestStatus)` (Task 1).
- Produces: fully working chest-open/close tracking, writing to `store` on every close — consumed (indirectly, via `store`) by Task 5's rendering.

- [ ] **Step 1: Replace the file with tracking added**

```java
package com.example.addon.modules.chestscan;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class ChestScan extends AddonModule {
    public static final ChestScan INSTANCE = new ChestScan();

    public final SliderOption scanRadius = new SliderOption(this, "Scan Radius",
        "How far (in blocks) to render tracked chests and consider hopper chains.", 64.0, 8.0, 128.0, 1.0);
    public final ToggleOption hopperChain = new ToggleOption(this, "Hopper Chain",
        "Smart mode to check chests linked to the bottom chest by hoppers", false);

    private final ChestScanStore store = new ChestScanStore();
    private String lastWorldKey = null;

    private BlockPos lastLookedAtChestPos = null;
    private boolean wasChestMenuOpenLastTick = false;
    private BlockPos openChestPos = null;
    private ChestScanStore.ChestStatus lastSnapshotStatus = null;

    private ChestScan() {
        super("ChestScan", "Highlights opened chests by contents (empty/partial/full), with optional hopper-chain inference.");
    }

    @Override
    public void onEnable() {
        store.loadForWorld();
        lastWorldKey = ChestScanStore.currentWorldKey();
    }

    @Override
    public void onDisable() {
        lastWorldKey = null;
        lastLookedAtChestPos = null;
        wasChestMenuOpenLastTick = false;
        openChestPos = null;
        lastSnapshotStatus = null;
    }

    private void maybeReloadStoreForWorld() {
        String key = ChestScanStore.currentWorldKey();
        if (!key.equals(lastWorldKey)) {
            store.loadForWorld();
            lastWorldKey = key;
        }
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        maybeReloadStoreForWorld();

        boolean chestMenuOpenNow = mc.player.containerMenu instanceof ChestMenu;

        if (!chestMenuOpenNow && mc.screen == null) {
            lastLookedAtChestPos = resolveLookedAtChestPos(mc);
        }

        if (chestMenuOpenNow && !wasChestMenuOpenLastTick) {
            openChestPos = lastLookedAtChestPos;
            lastSnapshotStatus = null;
        }

        if (chestMenuOpenNow) {
            ChestMenu menu = (ChestMenu) mc.player.containerMenu;
            lastSnapshotStatus = computeStatus(menu.getContainer());
        }

        if (!chestMenuOpenNow && wasChestMenuOpenLastTick) {
            finalizeChestState(mc, openChestPos, lastSnapshotStatus);
            openChestPos = null;
            lastSnapshotStatus = null;
        }

        wasChestMenuOpenLastTick = chestMenuOpenNow;
    }

    private BlockPos resolveLookedAtChestPos(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = bhr.getBlockPos();
        return (mc.level.getBlockState(pos).getBlock() instanceof ChestBlock) ? pos : null;
    }

    private ChestScanStore.ChestStatus computeStatus(Container container) {
        int total = container.getContainerSize();
        if (total == 0) return ChestScanStore.ChestStatus.EMPTY;
        int filled = 0;
        for (int i = 0; i < total; i++) {
            if (!container.getItem(i).isEmpty()) filled++;
        }
        if (filled == 0) return ChestScanStore.ChestStatus.EMPTY;
        return (filled == total) ? ChestScanStore.ChestStatus.FULL : ChestScanStore.ChestStatus.PARTIAL;
    }

    private void finalizeChestState(Minecraft mc, BlockPos pos, ChestScanStore.ChestStatus status) {
        if (pos == null || status == null) return;
        store.put(pos, status);
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
            store.put(other, status);
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/chestscan/ChestScan.java
git commit -m "feat: add ChestScan open/close tracking"
```

---

### Task 5: Rendering + hopper-chain recompute

**Files:**
- Modify: `src/main/java/com/example/addon/modules/chestscan/ChestScan.java`

**Interfaces:**
- Consumes: `ChestScanChain.findEdges(...)` / `.inferEmpty(...)` (Task 2), `store.positions()`/`.get()`/`.remove()` (Task 1).
- Produces: fully working module — nothing further consumes this internally; Task 6 registers `ChestScan.INSTANCE`.

- [ ] **Step 1: Add rendering and chain-recompute to the file**

Add these imports:

```java
import dev.boze.api.event.EventWorldRender;
import dev.boze.api.render.ClientColor;
import dev.boze.api.render.ColorMaker;
import dev.boze.api.render.WorldDrawer;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
```

Add these fields (alongside the existing tracking fields):

```java
private static final ClientColor EMPTY_COLOR = ColorMaker.staticColor(0, 200, 0);
private static final ClientColor PARTIAL_COLOR = ColorMaker.staticColor(230, 200, 0);
private static final ClientColor FULL_COLOR = ColorMaker.staticColor(220, 0, 0);
private static final float FILL_OPACITY = 0.47f;
private static final float OUTLINE_OPACITY = 1.0f;

private int chainTicks = 0;
private Set<BlockPos> lastInferredEmpty = Collections.emptySet();
```

Add `tickChainRecompute(mc)` as the last line inside the existing `onTick` method (right after `wasChestMenuOpenLastTick = chestMenuOpenNow;`):

```java
        wasChestMenuOpenLastTick = chestMenuOpenNow;

        tickChainRecompute(mc);
    }

    private void tickChainRecompute(Minecraft mc) {
        if (!hopperChain.getValue()) {
            lastInferredEmpty = Collections.emptySet();
            return;
        }
        chainTicks++;
        if (chainTicks < 20) return;
        chainTicks = 0;

        BlockPos center = mc.player.blockPosition();
        int radius = scanRadius.getValue().intValue();
        Set<BlockPos> tracked = new HashSet<>(store.positions());

        Map<BlockPos, BlockPos> edges = ChestScanChain.findEdges(mc.level, tracked, center, radius);
        Map<BlockPos, ChestScanStore.ChestStatus> real = new HashMap<>();
        for (BlockPos pos : tracked) {
            real.put(pos, store.get(pos));
        }
        lastInferredEmpty = ChestScanChain.inferEmpty(edges, real);
    }
```

Add the render handler as a new method on the class:

```java
    @EventHandler
    private void onWorldRender(EventWorldRender event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        double radius = scanRadius.getValue();
        double radiusSq = radius * radius;
        BlockPos center = mc.player.blockPosition();

        WorldDrawer.start();

        for (BlockPos pos : new ArrayList<>(store.positions())) {
            if (center.distSqr(pos) > radiusSq) continue;
            if (!(mc.level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
                store.remove(pos);
                continue;
            }
            WorldDrawer.box(colorFor(store.get(pos)), FILL_OPACITY, OUTLINE_OPACITY, new AABB(pos));
        }

        if (hopperChain.getValue()) {
            for (BlockPos pos : lastInferredEmpty) {
                if (store.get(pos) != null) continue;
                if (center.distSqr(pos) > radiusSq) continue;
                WorldDrawer.box(EMPTY_COLOR, FILL_OPACITY, OUTLINE_OPACITY, new AABB(pos));
            }
        }

        WorldDrawer.draw(event.matrices);
    }

    private ClientColor colorFor(ChestScanStore.ChestStatus status) {
        return switch (status) {
            case EMPTY -> EMPTY_COLOR;
            case PARTIAL -> PARTIAL_COLOR;
            case FULL -> FULL_COLOR;
        };
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/addon/modules/chestscan/ChestScan.java
git commit -m "feat: add ChestScan rendering and hopper-chain recompute"
```

---

### Task 6: Register the module and manually verify in-game

**Files:**
- Modify: `src/main/java/com/example/addon/ExampleAddon.java`

**Interfaces:**
- Consumes: `ChestScan.INSTANCE` (Task 3-5).
- Produces: nothing (terminal task).

- [ ] **Step 1: Add the import**

Add alongside the other module imports (near `import com.example.addon.modules.PathFinder;`):

```java
import com.example.addon.modules.chestscan.ChestScan;
```

- [ ] **Step 2: Register the module**

Add after the existing `modules.add(PathFinder.INSTANCE);` line:

```java
        modules.add(ChestScan.INSTANCE);
```

- [ ] **Step 3: Verify it compiles**

Run: `.\gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/addon/ExampleAddon.java
git commit -m "feat: register ChestScan module"
```

- [ ] **Step 5: Manual in-game verification**

Launch the client (`.\gradlew runClient` or however this project's dev client is normally started), enable `ChestScan`, then check each of the following:

1. Place a single chest, open and close it empty → green box appears on it.
2. Put one item in it, reopen/close → box turns yellow.
3. Fill every slot, reopen/close → box turns red.
4. Place a double chest, open only one half → both halves show the same color.
5. Break a tracked chest → its box disappears next time it's in scan radius (self-heal).
6. Rejoin the world (or restart the client) with `ChestScan` still enabled → previously-colored chests still show their last color (persistence).
7. Build a chest → hopper (facing down into another chest) → chest stack. Open only the bottom chest and leave it non-full. Enable `Hopper Chain` → the un-opened top chest should light up green. Disable `Hopper Chain` → it should stop being drawn.
8. Open that same top chest directly and put items in it → its real (yellow/red) status should now override the inference from step 7, even with `Hopper Chain` still on.
