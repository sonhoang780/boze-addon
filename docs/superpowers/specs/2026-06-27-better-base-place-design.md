# BetterBasePlace — Design Spec

Date: 2026-06-27
Project: Boze addon (MC 26.1.2, Boze API 3.2.2)

---

## Overview

Auto-places obsidian around the nearest enemy target to set up a crystal PvP base. Each tick (ms-gated) scans positions around the target, filters for valid placements that would yield sufficient crystal damage, and places obsidian via Silent or Alt swap depending on obsidian location.

---

## File

```
src/main/java/com/example/addon/modules/BetterBasePlace.java
```

No mixins. No helper classes. Single module file.

---

## Options

| Option | Type | Default | Range / Values | Description |
|--------|------|---------|----------------|-------------|
| `range` | SliderOption | 3.0 | 1.0–6.0 step 0.5 | Scan radius for targets and placement positions |
| `delay` | SliderOption | 200.0 | 50.0–500.0 step 50 | ms between placement attempts |
| `airPlace` | ToggleOption | false | — | Allow placing against air (PlaceHelper airPlace param) |
| `mode` | ModeOption\<InteractionMode\> | NCP | NCP / Grim | Anti-cheat interaction mode |

No own MinDamage slider — reads from Boze's AutoCrystal module at runtime (see Section: MinDamage).

---

## Tick Logic (EventTick.Pre)

### Gate

```java
long now = System.currentTimeMillis();
if (now - lastPlaceMs < delay.getValue().longValue()) return;
```

### Step 1 — Player check

```java
Minecraft mc = Minecraft.getInstance();
if (mc.player == null || mc.level == null) return;
```

### Step 2 — Target scan

Iterate `mc.level.players()`. Filter:
- Not spectator: `!player.isSpectator()`
- Not self: `player != mc.player`
- Within range: `player.distanceTo(mc.player) <= range.getValue()`

Pick nearest (lowest distance). If none → return.

### Step 3 — Read MinDamage from AutoCrystal

```java
double minDmg = 6.0; // fallback
try {
    ClientModule ac = ModuleManager.getClientModule("AutoCrystal");
    if (ac != null) {
        for (Option<?> opt : ac.getOptions()) {
            if ("MinDamage".equals(opt.getName()) && opt.getValue() instanceof Number) {
                minDmg = ((Number) opt.getValue()).doubleValue();
                break;
            }
        }
    }
} catch (Exception ignored) {}
```

### Step 4 — Position scan

For x in `[-ceil(range), +ceil(range)]`, z in `[-ceil(range), +ceil(range)]`, y in `{target.blockY()-1, target.blockY()}`:

Build `BlockPos candidate = new BlockPos(target.blockX() + x, targetY, target.blockZ() + z)`.

**Reject if:**
1. `blockState.is(Blocks.OBSIDIAN) || blockState.is(Blocks.BEDROCK)` — already blast-resistant, skip
2. `!mc.level.getFluidState(candidate).isEmpty()` — water/lava at placement pos
3. `!mc.level.getFluidState(candidate.above()).isEmpty()` — water/lava at crystal spawn pos
4. `!PlaceHelper.isEmpty(candidate)` — occupied by non-replaceable block or entity

**Damage check:**

Crystal spawns at `candidate.above()`. Explosion center:
```java
double ex = candidate.getX() + 0.5;
double ey = candidate.getY() + 2.0;   // crystal entity at pos.above(), center = pos.above().y + 1
double ez = candidate.getZ() + 0.5;
```

Target center:
```java
double tx = target.getX();
double ty = target.getY() + target.getBbHeight() / 2.0;
double tz = target.getZ();
```

Raw damage (power 6, no armor reduction):
```java
double dist   = Math.sqrt((ex-tx)*(ex-tx) + (ey-ty)*(ey-ty) + (ez-tz)*(ez-tz));
double impact = Math.max(0.0, 1.0 - dist / 12.0);
double rawDmg = (impact * impact + impact) / 2.0 * 84.0 + 1.0;
```

Reject if `rawDmg < minDmg`.

Collect all valid candidates. Pick the one with highest `rawDmg`.

### Step 5 — Place

```java
// Prefer hotbar (Silent), fall back to inventory (Alt)
int hotbarSlot = InvHelper.findInHotbar(Blocks.OBSIDIAN);
int invSlot    = (hotbarSlot == -1) ? InvHelper.find(Blocks.OBSIDIAN) : -1;

if (hotbarSlot == -1 && invSlot == -1) return; // no obsidian

BlockHitResult hitResult = PlaceHelper.cast(bestPos, airPlace.getValue(), mode.getValue());
if (hitResult == null) return;

if (hotbarSlot != -1) {
    InvHelper.swapToSlot(hotbarSlot, SwapType.Silent);
} else {
    InvHelper.swapToSlot(invSlot, SwapType.Alt);
}

PlaceHelper.place(mode.getValue(), hitResult, InteractionHand.MAIN_HAND);
InvHelper.swapBack();
lastPlaceMs = now;
```

---

## MinDamage

Reads from Boze's internal AutoCrystal module option at runtime via `ModuleManager.getClientModule("AutoCrystal")`.

- If AutoCrystal module not found → fallback 6.0
- If option named `"MinDamage"` not found in option list → fallback 6.0
- Option value cast via `Number.doubleValue()` — safe for Double and Float
- AutoCrystal does NOT need to be enabled, only present in module list

---

## AirPlace

When `airPlace = true`, `PlaceHelper.cast(pos, true, mode)` is called — this allows casting against air blocks. Use when target is airborne and surrounding blocks haven't been placed yet.

When `airPlace = false` (default), `PlaceHelper.cast(pos, false, mode)` requires a solid neighbor face.

---

## Safety

- Obsidian/bedrock positions are skipped (already blast-resistant — no need to place again)
- Water/lava at placement pos OR crystal spawn pos both cause skip (fluid blocks crystal placement)
- `PlaceHelper.isEmpty()` check prevents double-placing into existing blocks or entities
- `InvHelper.swapBack()` always called after swap to restore held item

---

## Registration

In `ExampleAddon.java`:
```java
import com.example.addon.modules.BetterBasePlace;
// ...
modules.add(BetterBasePlace.INSTANCE);
```

---

## Non-Goals

- No self-damage check (not calculating damage to self)
- No multi-target (only nearest target)
- No visual renderer
- No rotation spoofing beyond what PlaceHelper/InteractionMode provides
- No crystal placement (this module only places obsidian base)
