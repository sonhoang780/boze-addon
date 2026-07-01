# PathFinder Module Design Spec

**Date:** 2026-07-01

---

## Goal

Add a `PathFinder` module that flies the player through the Nether ceiling toward a goal, using the same algorithm as [babbaj/nether-pathfinder](https://github.com/babbaj/nether-pathfinder) (native C++ A*, JNI bindings), driven by a direct-velocity elytra flight controller (same technique as `MaceAura.elytraFly` — no fireworks).

---

## Feasibility Spike (already done this session)

Confirmed by actually building the native library on this machine:
- Cloned `babbaj/nether-pathfinder`, initialized submodules (`zlib-ng` needed re-cloning over https — its own submodule URL is SSH-only and unreachable here).
- Installed `cmake`, `zig` (0.16.0 — repo's own script comments assume 0.9.1/0.11.0, but `zig cc`/`zig c++` still cross-compiled clean; `cmake_minimum_required` 3.16 and clang≥13 requirement both satisfied), `ninja`, and `llvm-mingw` (for `windres`, needed by `zlib-ng`'s Windows `.rc` resource — `zig rc` exists but speaks MSVC-style flags, incompatible with the GNU-style `windres` invocation CMake emits).
- Windows `.sh` compiler wrapper scripts (`zigcc.sh` etc.) don't execute natively on Windows (no shebang support) — replaced with `.bat` equivalents calling `zig cc`/`zig c++`/`zig ar`/`zig ranlib`.
- Result: **`libnether_pathfinder.dll` built successfully** (1.3 MB). Verified via `llvm-objdump -p` that its PE export table contains all 17 expected `Java_dev_babbaj_pathfinder_NetherPathfinder_*` JNI symbols plus `JNI_OnLoad`/`JNI_OnUnload`.
- Read the actual Java binding source (`java/src/main/java/dev/babbaj/pathfinder/{NetherPathfinder,PathSegment,Octree}.java`) rather than guessing the API.

This means: **use the real native library**, not a Java reimplementation. No terrain-ahead-generation problem to solve ourselves — the native side already owns that (optionally, via `CACHE_MISS_GENERATE` + world seed).

---

## Vendored Native API (from `dev.babbaj.pathfinder.NetherPathfinder`)

```java
public static native long newContext(long seed, String baritoneCacheDirCanBeNull, int dimension, int maxHeight, boolean allocator);
public static native void freeContext(long pointer);
public static native void insertChunkData(long context, int chunkX, int chunkZ, boolean[] data); // index = y<<8 | z<<4 | x
public static native void cullFarChunks(long context, int chunkX, int chunkZ, int maxDistanceBlocks);
public static native PathSegment pathFind(long context, int x1, int y1, int z1, int x2, int y2, int z2,
    boolean atLeastX4, boolean refine, int failTimeoutInMillis, boolean defaultAirElseGenerate, double fakeChunkCost);
public static native boolean cancel(long context);
// CACHE_MISS_GENERATE=0, CACHE_MISS_AIR=1, CACHE_MISS_SOLID=2
// DIMENSION_OVERWORLD=0, DIMENSION_NETHER=1, DIMENSION_END=2
```

`PathSegment{ boolean finished; long[] packed; }` — `pathFind` is incremental: each call runs A* for up to `failTimeoutInMillis` and returns whatever segment it found so far (`finished=false` means "call again, it's still working / path is far"). This matches the repo's own description: *"pathfinding long distance is accomplished by running A* for no 500ms and splicing together many invocations."*

**Not vendoring `Octree.java`** — it uses `sun.misc.Unsafe` for raw pointer access into the native chunk octree, needed only if we want direct bit-level block read/write into the pathfinder's own memory. We only ever *feed* chunk data via the plain `boolean[]` overload of `insertChunkData`, so it's unnecessary and avoids the `Unsafe`/module-access risk entirely.

---

## Cache-miss strategy

- User can optionally supply a world seed via the `goal` command's trailing arg.
- **Seed given:** `newContext(seed, null, DIMENSION_NETHER, maxHeight, true)`, pathfinding uses `CACHE_MISS_GENERATE` — the native side's ported 1.12.2-style nether generator fills in terrain we haven't fed yet, giving a path through completely unexplored area.
- **No seed:** `CACHE_MISS_SOLID` — anything not fed via `insertChunkData` is treated as solid rock, so the path never route through unseen space. As real chunks load client-side and get fed in, the module recomputes/extends the path. Works on any server regardless of seed knowledge. This is the default and the safe fallback.

---

## Module: `PathFinder.java` (`com.example.addon.modules`)

**Options:**
- `ToggleOption` "PathFinder" — master enable. On enable: create context (`newContext`), start feeding chunks. On disable: `freeContext`, clear state.
- `SliderOption` "Max Height" — passed to `newContext`'s `maxHeight` (nether ceiling assumption, default ~128).
- `SliderOption` "Fly Speed" / reuse existing pattern from `MaceAura.flySpeed`/`vertSpeed` for the flight controller.

**State:** `long context`, current goal `BlockPos`(or null), `Long seed` (nullable), current `long[] path` + cursor index, background executor for `pathFind()` calls (must not run on the render/tick thread — up to `failTimeoutInMillis` per call).

**Tick loop (`EventTick.Pre`, mirroring `MaceAura`'s pattern):**
1. If module disabled or no goal set → no-op.
2. Feed newly-loaded/changed chunks near the player (nether Y range only) into the context via `insertChunkData`, using the `y<<8|z<<4|x` index order documented in the binding. Track which chunks are already fed to avoid redundant re-inserts every tick.
3. If flight engaged (`elytra` command toggled on) and current path segment is stale/consumed/missing → dispatch an async `pathFind()` call (player pos → goal), decode the returned `packed` long[] into waypoints, replacing the current path.
4. If flying: steer toward the next waypoint (see Flight Controller below); advance the cursor when within a small distance threshold; if `finished` was false on the last segment and cursor reaches its end, request the next segment (continues the incremental splice described above).
5. `cullFarChunks` periodically to bound memory as the player moves on.

**Flight Controller (new method in `PathFinder.java`, NOT a call into `MaceAura`):**
Same direct-velocity technique as `MaceAura.steer()` — every tick, while `mc.player.isFallFlying()`, compute a unit vector toward the current waypoint (full 3D, unlike `MaceAura`'s dip-target which holds `vy=0`) and `mc.player.setDeltaMovement(vx, vy, vz)` scaled by the Fly Speed option. No firework use anywhere — matches the "không dùng fireworks" requirement exactly, since this technique already doesn't need them (this is why `MaceAura.elytraFly` was named as the reference).

---

## Commands (Boze `AddonCommand`/dispatcher, same pattern as `KitCommand`)

Boze's own client-command prefix applies (not choosable per-addon; not Baritone's separate `#` — Baritone is a compile-only dependency here, not guaranteed to even be running).

**`goal`** — two overloads, Y optional (matches Baritone's own `goal` grammar):
- `goal <x> <z> [seed]`
- `goal <x> <y> <z> [seed]`

Each numeric arg node's `.suggests()` offers the player's current floored coordinate as a one-click suggestion (`(int) Math.floor(mc.player.getX())` etc.), matching the existing `.suggests()` pattern already used in `KitCommand`/`ItemDropCommand` for name arguments. The `seed` arg (if typed) suggests the integrated server's actual world seed when singleplayer (`mc.hasSingleplayerServer()`), so the common case ("I know my own seed") is a single tab-press.

Executing `goal` sets the target and seed (or clears seed if the 2-arg/no-seed form is used); it does **not** start flying by itself.

**`elytra`** — no args, toggles the flight-follow engagement on/off. Requires PathFinder module enabled and a goal already set; otherwise responds with a chat message explaining what's missing rather than silently doing nothing.

---

## Files

**New:**
- `src/main/resources/natives/nether_pathfinder-x86_64.dll` (built this session, vendored binary)
- `src/main/java/dev/babbaj/pathfinder/NetherPathfinder.java` (vendored, package/class name preserved to match compiled JNI symbols — trimmed to drop the multi-platform `natives.zip.xz` loader in favor of loading our single vendored DLL directly)
- `src/main/java/dev/babbaj/pathfinder/PathSegment.java` (vendored, unchanged)
- `src/main/java/com/example/addon/modules/PathFinder.java`
- `src/main/java/com/example/addon/commands/GoalCommand.java`
- `src/main/java/com/example/addon/commands/ElytraCommand.java`

**Modified:**
- `src/main/java/com/example/addon/ExampleAddon.java` — register module + 2 commands (same pattern as existing entries)

---

## Out of scope

- Overworld/End pathfinding (nether-ceiling only, per earlier decision).
- Baritone command-prefix integration (not reachable; using Boze's own dispatcher instead).
- `Octree.java`/`Unsafe`-based direct block editing (not needed for feeding chunk data).
- Any UI beyond the two chat commands (no waypoint-list screen, no path visualization overlay — could be a later addition, not this pass).
