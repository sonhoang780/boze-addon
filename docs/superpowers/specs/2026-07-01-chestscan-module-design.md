# ChestScan Module Design Spec

**Date:** 2026-07-01

---

## Goal

Add a `ChestScan` module that overlays a colored 3D box on chests/trapped chests the player has opened, colored by last-known contents (green=empty, yellow=partial, red=full), persisted across sessions. A "Hopper Chain" mode infers that upstream chests feeding a not-full bottom chest via hoppers must also be empty, without needing to open them.

---

## Constraints established during research

- **No passive content read.** The client only learns a chest's contents when the player opens its GUI (server syncs slots on menu-open). Nothing can be inferred about an unopened chest's contents directly — only via the hopper-chain heuristic below.
- **No screen/menu events in Boze API 3.3.** Event list is exactly: `EventBind`, `EventHudRender`, `EventInput`, `EventInteract`, `EventModuleToggle`, `EventPacket`, `EventPlayerUpdate`, `EventRotate`, `EventShader`, `EventTick`, `EventWorldRender`. No "screen opened/closed" or "block changed/broken" event exists. Tracking must be built entirely on `EventTick` (polling `mc.screen`/`mc.player.containerMenu`/`mc.hitResult`) and `EventWorldRender` (drawing) — **no mixins needed** for this module, avoiding the index-drift risk called out in `CLAUDE.md`.
- A proposal to add a core `EventBlockUpdate` (block-state-change forwarding) was drafted and handed to the user to send to the Boze API dev separately; this module does **not** depend on that landing — it works around the gap via lazy invalidation (see below).
- `dev.boze.api.render.WorldDrawer` (3.3) provides `box(ClientColor color, float fillOpacity, float outlineOpacity, AABB box)` (needs `start()`/`draw(matrices)` wrap) plus `line`/`triangle`/`polygon` if ever needed later — box is sufficient for this module.

---

## Scope

Tracked block types: vanilla **Chest** and **Trapped Chest** only. Explicitly excluded: Shulker Box, Ender Chest (per-player, not world storage), Barrel, Hopper, Dispenser/Dropper, Furnace. Items *inside* a chest (including shulker boxes) just count as occupied slots — no recursion into their contents.

---

## Tracking mechanism (`EventTick`, no mixins)

1. Each tick while `mc.screen == null`, cache `lastLookedAtChestPos` from `mc.hitResult` if it's a `BlockHitResult` on a Chest/Trapped Chest block.
2. **Open detection:** previous tick `mc.screen == null`, this tick `mc.player.containerMenu instanceof ChestMenu` → the interaction that opened it targeted `lastLookedAtChestPos`; record it as the currently-open chest for this session.
3. While open, snapshot `containerMenu`'s container slots (excluding player inventory slots) each tick — cheap, keeps a "last known contents" ready at any time.
4. **Close detection:** `mc.player.containerMenu` reverts to the player's own inventory menu → finalize: compute `status` from the last snapshot:
   - `EMPTY` — zero items across all container slots
   - `FULL` — every container slot occupied
   - `PARTIAL` — anything in between
   Write `{pos → status}` to the persisted store, unconditionally overwriting whatever was there. This alone satisfies "color updates when contents change" — no separate content-fingerprint tracking needed.
5. **Double chest:** if the blockstate's `ChestType` is `LEFT`/`RIGHT` (not `SINGLE`), resolve the paired half's `BlockPos` via the facing+type relationship and write the same status to **both** positions — a double chest is one shared 54-slot inventory, so both halves must render the same color.
6. **Self-heal on break:** no block-update event exists, so instead of listening for removal, every time a persisted record is about to be used (rendering pass or chain recompute), check `mc.level.getBlockState(pos)` is still a Chest/Trapped Chest. If not, skip it for that pass and delete it from the store (and rewrite the file). This is lazy invalidation — the box disappears the next time that position is scanned (bounded by scan cadence/radius), not instantly, but requires no extra event plumbing.

---

## Hopper Chain inference (`Hopper Chain` toggle)

Only runs when the `Hopper Chain` `BooleanOption` is enabled. Recomputed periodically (every ~20 ticks), restricted to `Scan Radius` around the player — cheap since it only walks positions near already-tracked chests, not the whole loaded world.

**Edge detection:** for every tracked chest position, check its 6 neighbor blocks for a Hopper block entity. For each hopper found:
- **source** = the block directly above the hopper (hoppers always pull from directly above only), if that block is a chest.
- **destination** = the block the hopper's `FACING` points into, if that block is a chest.
- This yields a directed edge `source chest → dest chest` through that one hopper. Covers both vertical stacks (chest on hopper on chest...) and hoppers facing sideways into a neighboring chest.

**Graph walk:** build the edge set from all hoppers found in radius. Find **sink** chests (no outgoing edge — nothing feeds out of them via hopper). For each sink with a real (opened) status that is **not `FULL`**: BFS backward along edges, and for every ancestor chest with **no real opened record**, mark it green ("inferred empty") for this render pass only.

**Not persisted.** Inference is recomputed live every cycle — hoppers keep moving items over time, so a sink's fullness can change without the addon knowing. If the player ever opens an "inferred" chest directly, the real recorded status takes over from then on and overrides inference permanently for that position.

**Not traced:** multi-hopper chains (hopper → hopper → chest) — only direct chest→hopper→chest single hops are detected. Not required by the original description; can be extended later if needed.

---

## Rendering (`EventWorldRender`)

Each frame: `WorldDrawer.start()` → iterate persisted records within `Scan Radius` of the player (dropping stale/broken ones per the self-heal rule above) → for each, draw `WorldDrawer.box(color, fillOpacity=0.47, outlineOpacity=1.0, AABB-of-block)`:
- green = `EMPTY` (real or inferred, rendered identically — no visual distinction between the two)
- yellow = `PARTIAL`
- red = `FULL`

Then `WorldDrawer.draw(matrices)`. Colors and opacity are hardcoded (no `ColorOption`s) per explicit preference — keeps the module simple.

---

## Module: `ChestScan.java` (`com.example.addon.modules`)

**Options:**
- Master enable/disable (standard `AddonModule` toggle).
- `Scan Radius` — `SliderOption`, default 64 blocks (range ~8–128). Governs which persisted records are rendered and which chests/hoppers are considered during chain recompute.
- `Hopper Chain` — `BooleanOption`, description: *"Smart mode to check chests linked to the bottom chest by hoppers"*.

---

## Persistence

One JSON file per world/server under `boze/chestscan/` (matches the existing `FabricLoader.getInstance().getGameDir()`-based convention used by `EvilRekit`/`EbookReader`). Key = server address (`ip:port`) for multiplayer, or the singleplayer level folder name for SP/LAN, combined with the dimension id (coordinates collide across dimensions) — e.g. `boze/chestscan/<worldkey>__<dimension>.json`.

Format: flat JSON array of `{x, y, z, status}`. No fingerprint field — every close event unconditionally overwrites the record with freshly computed status (see tracking step 4), so change-detection falls out naturally without extra bookkeeping.

Loaded on module enable / world join. Saved write-through on every finalized chest-close (infrequent event, no need to batch) and whenever a stale record is pruned during a render/chain pass.

---

## Testing plan

- Unit-testable pieces: status classification (slot-count → EMPTY/PARTIAL/FULL), double-chest pos-pairing, hopper edge detection (source/destination resolution from a mocked/synthetic block-state grid), sink/BFS chain-walk logic. These are pure functions over simple inputs (no world/render dependency) — can be tested without a running client.
- Manual in-world verification required for: open/close tick-detection timing, persistence across a world rejoin, box rendering color/position/double-chest alignment, self-heal-on-break timing, and the hopper-chain toggle actually changing which chests light up green.
