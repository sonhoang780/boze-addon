# MaceAura v2 — Pure Flight Hover + Single-Tick Strike

**Date:** 2026-07-02
**Status:** Approved design, pending implementation
**Target:** 6b6t (lenient anticheat; custom mace plugin), MC 26.1.2, Boze API 3.2.2

## Problem

MaceAura v1 (two-tick fold → strike) is dead on current 6b6t:

1. The FOLD step (`StatusOnly(onGround=true)` mid-air) now triggers a rubber-band:
   the server rejects subsequent elytra movement and the player falls straight down.
2. The post-strike `START_FALL_FLYING` resync trips 6b6t's mace check: attacking
   while gliding (or having just sent START_FALL_FLYING) gets the mace confiscated
   (thrown out of hand).
3. Approach steering dove diagonally into targets, missing hits entirely — no base
   damage, no smash.

Reference behavior (video, IEatHex on 6b6t): hovers stationary 6–10 blocks above a
grounded victim, no fireworks, no wind burst, lands a smash hit every ~1–1.5s.
Victim health drops steadily; totem pops. 6b6t's plugin deals full "painful" smash
damage whenever the attacker is more than ~6 blocks above the target.

## Key insight

If the player **never glides server-side, there is nothing to fold**:

- `canSmashAttack` (MaceItem, verified 26.1.2) = `fallDistance > 1.5 && !isFallFlying()`.
  The second condition is permanently true when we never send START_FALL_FLYING.
- The mace-confiscation check can't trigger — we are never gliding at swing time.
- 6b6t permits plain velocity-flight hover (confirmed by user experience), so glide
  is not needed to hold altitude.

`fallDistance` is accumulated **synchronously during movement-packet handling**
(`handleMovePlayer → doCheckFallDamage → checkFallDamage`: `if (dy < 0 && !onGround)
fallDistance -= dy`). A single spoofed `PosRot` that drops Y by N blocks credits N
fall distance before the attack packet in the same batch is processed. The hover
itself contributes nothing and doesn't need to.

The strike-tick Y drop does three jobs with one packet:
reach (closes vertical distance to ≤ attack range), fallDistance (> 1.5 gate), and
damage scaling (smash damage grows with fallDistance; 6b6t may cap it at the real
height difference, hence the 6.5–10 block hover band).

## Architecture

Three parts replace the v1 state machine (IDLE/APPROACH/FOLD/STRIKE deleted):

### 1. FlightHover (replaces ElytraFly)

Velocity-based flight, **no `isFallFlying()` requirement**:

- Every tick: `mc.player.setDeltaMovement(vx, vy, vz)`.
- Manual mode (no target): WASD steering by yaw, Space/Shift for vertical, same as
  v1's `steer()` minus the fall-flying gate.
- Auto mode (target acquired): hold `player.y = target.y + hoverHeight` (P-style
  correction, clamped to ±vertSpeed per tick) and steer horizontally toward the
  target until within `range`.

### 2. Strike (single tick)

Preconditions: target selected, `dy = player.y − target.y` within
`[minHeight, vertRange]`, horizontal distance ≤ `range` (after approach), attack
cooldown elapsed.

Sequence, all in one `EventTick.Post`:

1. Send `ServerboundMovePlayerPacket.PosRot(target.x, target.y + strikeGap,
   target.z, yaw, pitch, onGround=false, horizontalCollision=false)`.
   - `strikeGap` ≈ 2.0: puts the server-side position well inside the entity
     interaction range; fallDistance credited = `dy − strikeGap`.
   - Y is exempt from the server "moved wrongly" check; X/Z delta is ≤ approach
     range and within 6b6t tolerance (this is the v1 XZSpoof, which worked).
2. Silent swap to mace: `InvHelper.swapToSlot(maceSlot, SwapType.Silent)`.
3. Attack via **manually crafted packet**: `new ServerboundAttackPacket(target.getId())`
   (26.1.2 record, public constructor, javap-verified). This bypasses
   `MultiPlayerGameMode.attack` and therefore `ensureHasSentCarriedItem`, which
   would otherwise re-sync the real hotbar slot and land the hit with the wrong
   item (the v1 SwapType.Normal workaround is no longer needed).
4. `mc.player.swing(InteractionHand.MAIN_HAND)`.
5. `InvHelper.swapBack()`.
6. Send **nothing else**. No START_FALL_FLYING, no StatusOnly. The next vanilla
   movement packet (real position) walks the server position back up.

Passive ground path kept from v1: if standing with real `fallDistance ≥ 1.5`,
target in reach — attack directly (same silent-swap + manual packet path).

### 3. Options

| Option | Keep/Change | Notes |
|---|---|---|
| `range` | keep | horizontal attack range |
| `approachRange` | keep | acquisition radius |
| `vertRange` | keep | max Y-delta |
| `minHeight` | keep, default 6.5 | 6b6t "painful" threshold is ~6 |
| `hoverHeight` | **new**, default 7.0, range 6.5–10 | auto-hover altitude above target |
| `strikeGap` | **new**, default 2.0, range 1.5–3.5 | server-Y above target at strike |
| `attackDelay` | keep | ms between cycles |
| `autoTarget` | keep | nearest player |
| `silentSwap` | keep | if off, requires mace in hand |
| `attributeSwap` | keep | optional sword pre-hit |
| `flySpeed` / `vertSpeed` | keep | flight steering |
| `smashThreshold` | **delete** | subsumed by strikeGap/hoverHeight |
| `foldDelay` | **delete** | no fold phase exists |
| `xzSpoof` | **delete (always on)** | strike packet always centers on target |
| `elytraFly` | **delete** | flight is core, not optional |

## Error handling

- Target removed/dead mid-approach → reset to idle.
- Rubber-band detection: if the server sends a position correction
  (`ClientboundPlayerPositionPacket`) within the strike tick window, back off —
  skip the next 10 ticks before re-engaging, to avoid flag spam.
- Mace missing from hotbar → module idles (no throw/no crash).
- Failed strike (target still alive, no cooldown consumed server-side) → normal
  cooldown applies anyway; no same-tick retry.

## Testing

1. `.\gradlew build` — signature check (ServerboundAttackPacket, SwapType.Silent).
2. In-game on 6b6t:
   - Hover holds altitude, no rubber-band, no fall.
   - Strike lands: target takes damage (base damage present).
   - Smash confirmed: crit-style smash particles + heavy damage vs ≥6-block band.
   - Mace never confiscated across ≥20 consecutive cycles.
   - Post-strike return packet causes no setback.

## Explicitly out of scope

- Wing-cosmetic glide between cycles (v1-style START_FALL_FLYING) — rejected;
  v2 is pure approach A. Revisit only if 6b6t starts flagging wingless hover.
- Wind burst / firework recovery — reference player doesn't use them.
- Vertical blink beyond `vertRange` — victim band is 6–10 blocks, no need.
