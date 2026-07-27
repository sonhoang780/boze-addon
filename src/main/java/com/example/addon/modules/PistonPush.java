package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.FriendManager;
import dev.boze.api.event.EventInteract;
import dev.boze.api.event.EventWorldRender;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.render.ClientColor;
import dev.boze.api.render.ColorMaker;
import dev.boze.api.render.WorldDrawer;
import dev.boze.api.utility.MathHelper;
import dev.boze.api.utility.WorldHelper;
import dev.boze.api.utility.interaction.Interaction;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import com.example.addon.util.InteractionHelper;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Port of ThunderHack-Reborn's PistonPush (thunder.hack.features.modules.combat.PistonPush).
 * Finds a player standing in a hole, places a piston one block above them facing down into the
 * hole, then powers it with a redstone charge so the extending head shoves the player up/out.
 *
 * Adaptations to Boze / real MC 26.1.2 (every ThunderHack dep accounted for, nothing dropped):
 * - EventSync (rotate-via-PlayerMoveC2SPacket) + EventPostSync (place) two-phase design ->
 *   Boze's single EventInteract, which carries the rotation WITH the placement in one
 *   Interaction(runnable, yaw, pitch). The forced yaw itself is literal ThunderHack, not an
 *   invented trick: the original's own placeRunnable computes
 *   calculateAngle(target.getEyePos(), pistonPos.toCenterPos())[0] and sends it unconditionally
 *   (outside the Rotate toggle) -- this port does the exact same MathHelper.calculateRotation
 *   call. It works because PistonBaseBlock.FACING derives purely from the player's yaw at
 *   place time (verified via javap), so aiming target->piston makes the head extend
 *   piston->target.
 * - InteractionUtility.placeBlock / getPlaceAngle / canPlaceBlock -> PlaceHelper.cast/place +
 *   WorldHelper.canPlaceAt/isReplaceable. InventoryUtility.findBlockInHotBar / SearchInvResult
 *   -> InvHelper.find + InvHelper.swapToSlot/swapBack.
 * - HoleUtility.isHole -> WorldHelper.isHole. Managers.FRIEND.isFriend -> FriendManager.isFriend.
 *   Managers.ASYNC.getAsyncPlayers -> mc.level entity scan.
 * - Render3DEngine.FILLED_QUEUE/OUTLINE_QUEUE + Render2DEngine.injectAlpha -> WorldDrawer.box on
 *   an EventWorldRender hook, with a 500ms fade exactly like the original renderPoses map.
 * - blocksPerTick/delayPerPlace preserved as the original's per-tick placement budget.
 */
public final class PistonPush extends AddonModule {
    public static final PistonPush INSTANCE = new PistonPush();

    public enum ChargeType { Block, Torch, All }
    public enum PistonType { Sticky, Normal, All }

    public final SliderOption range = new SliderOption(this, "Target Range", "Max target distance.", 5.0, 1.5, 7.0, 0.1);
    public final SliderOption blocksPerTick = new SliderOption(this, "Blocks/Tick", "Placements per tick.", 2.0, 1.0, 2.0, 1.0);
    public final SliderOption delayPerPlace = new SliderOption(this, "Delay/Place", "Tick delay between placement batches.", 0.0, 0.0, 5.0, 1.0);
    public final ModeOption<InteractionMode> placeMode = new ModeOption<>(this, "Place Mode", "Anti-cheat bypass mode.", InteractionMode.Grim);
    public final ToggleOption rotate = new ToggleOption(this, "Rotate", "Silently rotate to placements.", false);
    public final ModeOption<ChargeType> chargeType = new ModeOption<>(this, "Charge Type", "Which redstone charge to place.", ChargeType.All);
    public final ModeOption<PistonType> pistonType = new ModeOption<>(this, "Piston Type", "Which piston to place.", PistonType.All);
    public final ModeOption<SwapType> autoSwap = new ModeOption<>(this, "Swap", "How to swap to required items.", SwapType.Silent);
    public final ToggleOption swing = new ToggleOption(this, "Swing", "Swing the hand on place.", true);
    public final ToggleOption render = new ToggleOption(this, "Render", "Draw a fading box at each placement.", true);
    public final ClientColor renderColor = ColorMaker.staticColor(255, 0, 0);

    private Player target;
    private BlockPos pistonPos;
    private BlockPos chargePos;
    private boolean firstPlace;
    private int delay;
    private final Map<BlockPos, Long> renderPoses = new ConcurrentHashMap<>();

    public PistonPush() {
        super("PistonPush", "Places a piston above a holed target and powers it to shove them out.");
    }

    @Override
    public void onEnable() {
        target = null; pistonPos = null; chargePos = null; delay = 0; firstPlace = true;
    }

    @Override
    public void onDisable() {
        renderPoses.clear();
    }

    @EventHandler
    private void onInteract(EventInteract event) {
        if (event.getMode() != placeMode.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!isTargetValid(mc, target)) {
            // Target becoming invalid mid-combo (e.g. the piston just pushed them out of the
            // hole -- isTargetValid's own isHole() check flips false on success) must not leave
            // pistonPos/chargePos pointing at the OLD target's now-useless blocks: a later target
            // would otherwise inherit that stale state, skip findPlacePoses, waste a charge
            // placement attempt on the abandoned position, and only THEN search fresh -- placing
            // a second, redundant piston. Upstream ThunderHack has this same latent bug (its
            // findTarget() doesn't clear pistonPos/chargePos either); it rarely showed there
            // because charge placement rarely succeeded at all, so combos rarely completed.
            pistonPos = null; chargePos = null; firstPlace = true;
            findTarget(mc);
            return;
        }
        if (pistonPos == null || chargePos == null) { findPlacePoses(mc); return; }
        if (delay < delayPerPlace.getValue().intValue()) { delay++; return; }

        int budget = blocksPerTick.getValue().intValue();
        int placed = 0;
        while (placed < budget) {
            boolean submitted = firstPlace ? placePiston(event, mc) : placeCharge(event, mc);
            if (!submitted) break;
            placed++;
        }
        delay = 0;
    }

    /** Places the piston facing target->piston (so its head extends into the target). */
    private boolean placePiston(EventInteract event, Minecraft mc) {
        if (pistonPos == null) return false;
        int slot = getPistonSlot();
        if (slot == -1) return false;
        if (mc.level.getBlockState(pistonPos).getBlock() instanceof PistonBaseBlock) { firstPlace = false; return false; }

        // Geometric hit, not PlaceHelper.cast: cast() raycasts along the player's REAL current
        // look direction and only hits when already aimed there. Piston/redstone/obsidian are
        // placed at positions computed relative to the target, not wherever the camera happens
        // to point, so this needs ThunderHack's actual technique (InteractionUtility.getPlaceResult
        // Strict branch): a purely position-based support-face pick, rotation sent separately.
        BlockHitResult hit = InteractionHelper.getPlaceResult(mc, pistonPos, false, false);
        if (hit == null) return false;

        // Forced yaw pointing from target toward the piston -> FACING resolves to piston->target
        // (getNearestLookingDirection().getOpposite()), so the extending head shoves the target.
        // ALWAYS forced (independent of the Rotate toggle) -- matches the original, whose
        // placeRunnable sends LookAndOnGround(facingYaw) unconditionally; a piston with the wrong
        // facing is useless, so this yaw is not optional the way a "look at the face" rotate is.
        float yaw = MathHelper.calculateRotation(target.getEyePosition(), Vec3.atCenterOf(pistonPos))[0];
        BlockPos placedPistonPos = pistonPos; // field can be null'd (reset/disable) before this deferred Runnable fires
        Runnable place = () -> {
            // placeSwapped: handles swapToSlot failure + carried-item resync (see InteractionHelper).
            if (InteractionHelper.placeSwapped(placeMode.getValue(), hit, slot, autoSwap.getValue())) {
                if (swing.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
                InteractionHelper.markAwaiting(placedPistonPos);
                renderPoses.put(placedPistonPos, System.currentTimeMillis());
            }
        };
        event.addInteraction(new Interaction(place, yaw, 0f));
        // Flip synchronously at submit, NOT inside the deferred Runnable (fires end-of-tick):
        // deferred, the same tick's budget loop re-entered placePiston with firstPlace still true
        // and resubmitted the same piston -- the wasted-piston spam. Sync flip also lets the
        // charge go out in the SAME tick's interaction batch, right after the piston.
        firstPlace = false;
        return true;
    }

    private boolean placeCharge(EventInteract event, Minecraft mc) {
        if (chargePos == null) return false;
        int slot = getChargeSlot();
        if (slot == -1) return false;
        Block cur = mc.level.getBlockState(chargePos).getBlock();
        if (cur == Blocks.REDSTONE_BLOCK || cur instanceof RedstoneTorchBlock) { reset(); return false; }

        BlockHitResult hit = InteractionHelper.getPlaceResult(mc, chargePos, false, false);
        if (hit == null) return false;

        BlockPos placedChargePos = chargePos; // field can be null'd (reset/disable) before this deferred Runnable fires
        Runnable place = () -> {
            if (InteractionHelper.placeSwapped(placeMode.getValue(), hit, slot, autoSwap.getValue())) {
                if (swing.getValue()) mc.player.swing(InteractionHand.MAIN_HAND);
                InteractionHelper.markAwaiting(placedChargePos);
                renderPoses.put(placedChargePos, System.currentTimeMillis());
            }
        };
        if (rotate.getValue()) {
            float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), Vec3.atCenterOf(chargePos));
            event.addInteraction(new Interaction(place, rot[0], rot[1]));
        } else {
            event.addInteraction(new Interaction(place));
        }
        // Sync, same reason as placePiston: deferred state left the budget loop resubmitting the
        // charge, and a combo that completed mid-tick kept stale pistonPos/chargePos around.
        firstPlace = true;
        reset();
        return true;
    }

    private void reset() {
        pistonPos = null;
        chargePos = null;
    }

    private int getPistonSlot() {
        int sticky = InvHelper.findInHotbar(Blocks.STICKY_PISTON);
        int normal = InvHelper.findInHotbar(Blocks.PISTON);
        return switch (pistonType.getValue()) {
            case Normal -> normal;
            case Sticky -> sticky;
            case All -> normal != -1 ? normal : sticky;
        };
    }

    private int getChargeSlot() {
        int torch = InvHelper.findInHotbar(Blocks.REDSTONE_TORCH);
        int block = InvHelper.findInHotbar(Blocks.REDSTONE_BLOCK);
        return switch (chargeType.getValue()) {
            case Block -> block;
            case Torch -> torch;
            case All -> torch != -1 ? torch : block;
        };
    }

    private void findPlacePoses(Minecraft mc) {
        BlockPos targetBP = target.blockPosition();
        BlockPos[] surroundPoses = {
                targetBP.offset(1, 1, 0), targetBP.offset(-1, 1, 0),
                targetBP.offset(0, 1, 1), targetBP.offset(0, 1, -1)
        };
        for (BlockPos pos : surroundPoses) {
            // A piston already at this wall (previous attempt still extending, or an orphan from
            // an interrupted combo): reuse it instead of skipping to another wall and burning a
            // second piston. placePiston's PistonBaseBlock check flips straight to the charge stage.
            boolean existingPiston = mc.level.getBlockState(pos).getBlock() instanceof PistonBaseBlock;
            if (!existingPiston && !canPlace(mc, pos)) continue;
            BlockPos[] chargePoses = {
                    pos.offset(0, 1, 0), pos.offset(0, -1, 0),
                    pos.offset(1, 0, 0), pos.offset(-1, 0, 0),
                    pos.offset(0, 0, 1), pos.offset(0, 0, -1)
            };
            if (existingPiston) {
                // Charge already sitting next to it -> combo is mid-fire; don't stack another.
                for (BlockPos chPos : chargePoses) {
                    Block cb = mc.level.getBlockState(chPos).getBlock();
                    if (cb == Blocks.REDSTONE_BLOCK || cb instanceof RedstoneTorchBlock) return;
                }
            }
            // A 1x1 hole on a FLAT plane: the piston sits on top of a 1-high wall, so every
            // charge cell around it floats over open ground -- its ONLY possible support is the
            // piston itself, which does not exist yet at search time. Manual play works because a
            // human places the piston first, then leans the redstone against it. Mirror that:
            // validate charge cells with the piston speculatively marked as awaiting-solid, so
            // "charge supported only by the future piston" counts. Committed pairs keep the mark
            // (the piston is placed next tick anyway); rejected walls clear it immediately.
            InteractionHelper.markAwaiting(pos);
            BlockPos foundCharge = null;
            for (BlockPos chPos : chargePoses) {
                if (chPos.equals(targetBP)) continue;
                if (!WorldHelper.isReplaceable(chPos)) continue;
                if (chargeType.getValue() == ChargeType.Torch && chPos.equals(pos.above())) continue;
                if (canPlace(mc, chPos)) { foundCharge = chPos; break; }
            }
            // Both must come from the SAME candidate pair -- with a symmetric 1x1 hole (all 4
            // walls valid at once), the old code kept overwriting pistonPos on every valid `pos`
            // while chargePos (a field, never reset per outer iteration) stayed from an earlier
            // wall -- piston and charge ended up on different, non-adjacent walls, so the charge
            // never actually touched the piston and it never fired.
            if (foundCharge != null) { pistonPos = pos; chargePos = foundCharge; return; }
            InteractionHelper.clearAwaiting(pos);
        }
    }

    /**
     * MUST be the same feasibility check the real placement uses (InteractionHelper.getPlaceResult
     * via placePiston/placeCharge), matching upstream's canPlaceBlock == getPlaceResult(...) != null.
     * A separate WorldHelper-only check here (position/eye-independent) let this validate a
     * candidate as "placeable" regardless of stance, while the real Strict-mode placement then
     * rejected it from most positions -- search-time and place-time validity must be the same
     * function or the two silently disagree.
     */
    private boolean canPlace(Minecraft mc, BlockPos pos) {
        if (!WorldHelper.isInWorldBounds(pos) || !WorldHelper.isRegionLoaded(pos)) return false;
        return InteractionHelper.canPlace(mc, pos, false, false);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isTargetValid(Minecraft mc, Player player) {
        if (player == null) return false;
        return player != mc.player
                && !FriendManager.isFriend(player.getName().getString())
                && player.distanceTo(mc.player) <= range.getValue()
                && player.isAlive()
                && player.getHealth() + player.getAbsorptionAmount() > 0
                && isPushableHole(player);
    }

    /**
     * Boze's WorldHelper.isHole demands a pristine 1x1: exactly-empty centre cell + all four
     * horizontal walls solid. That rejects two perfectly pushable targets:
     *   Bug 1 -- any stray block in the pocket (mushroom, button, an obsidian someone placed):
     *            isHole treats the disturbed centre as "not a clean hole" and bails.
     *   Bug 2 -- a 1x2-WIDE pocket (target boxed but with one side opening into the twin cell):
     *            isHole wants all four sides walled, so a 3-walled cell never qualifies.
     * PistonPush only needs the target trapped enough that a side-piston shove ejects them, so
     * gate on the real requirement instead: a solid floor + at least 3 of 4 horizontal walls.
     * Stray blocks only ever ADD to the wall count (never subtract), so they no longer disqualify.
     */
    private boolean isPushableHole(Player player) {
        BlockPos feet = player.blockPosition();
        if (WorldHelper.isReplaceable(feet.below())) return false; // no floor -> not a hole
        int walls = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!WorldHelper.isReplaceable(feet.relative(d))) walls++;
        }
        return walls >= 3;
    }

    private void findTarget(Minecraft mc) {
        target = null;
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Player p)) continue;
            if (!isTargetValid(mc, p)) continue;
            double d = mc.player.distanceToSqr(p);
            if (d < bestDist) { bestDist = d; best = p; }
        }
        target = best;
    }

    @EventHandler
    private void onWorldRender(EventWorldRender event) {
        if (!render.getValue() || renderPoses.isEmpty()) return;
        long now = System.currentTimeMillis();
        WorldDrawer.start();
        renderPoses.entrySet().removeIf(entry -> {
            long age = now - entry.getValue();
            if (age > 500) return true;
            float fade = 1f - age / 500f;
            AABB box = new AABB(entry.getKey());
            WorldDrawer.box(renderColor, 0.2f * fade, 0.8f * fade,
                    box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            return false;
        });
        WorldDrawer.draw(event.matrices);
    }
}
