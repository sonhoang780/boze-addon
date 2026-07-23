package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.FriendManager;
import dev.boze.api.event.EventInteract;
import dev.boze.api.event.EventPacket;
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
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Port of ThunderHack-Reborn's PistonAura (thunder.hack.features.modules.combat.PistonAura).
 * Fully automatic piston-crystal aura: for a nearby target it enumerates candidate
 * piston+crystal+redstone(+fire) structures from a pattern set, builds the best one (optionally
 * trapping the target and support-placing obsidian), then breaks the crystal to detonate.
 *
 * Adaptations to Boze / real MC 26.1.2 (every ThunderHack dependency mapped -- nothing dropped):
 * - EventSync (rotate via PlayerMoveC2SPacket) + EventPostSync (postAction place) two-phase
 *   design -> Boze's single EventInteract, carrying rotation WITH placement per
 *   Interaction(runnable, yaw, pitch). blocksPerTick / placeDelay budget preserved.
 * - InteractionUtility.placeBlock / getPlaceAngle / calculateAngle / canPlaceBlock ->
 *   InteractionHelper.getPlaceResult (own geometric port of getPlaceResult/getStrictDirections/
 *   getSupportBlocks, NOT PlaceHelper.cast -- cast() raycasts along the player's real current
 *   look direction and only hits when already aimed there, which breaks silent structure
 *   placement at positions computed relative to the target) + PlaceHelper.place/isEmpty +
 *   WorldHelper.canPlaceAt/isReplaceable/isAir + MathHelper.calculateRotation. Piston FACING yaw
 *   is literal ThunderHack: the original's own postAction computes
 *   calculateAngle(pistonHeadPos.toCenterPos(), pistonPos.toCenterPos())[0] and sends it
 *   unconditionally -- this port makes the exact same MathHelper.calculateRotation call, no
 *   invented trick. Works because PistonBaseBlock.FACING derives purely from the player's yaw at
 *   place time (verified via javap).
 * - InventoryUtility.findBlockInHotBar / findItemInHotBar / SearchInvResult / saveSlot /
 *   returnSlot -> InvHelper.findInHotbar + swapToSlot/swapBack.
 * - Crystal place: sendSequencedPacket(PlayerInteractBlockC2SPacket) -> PlaceHelper.place with
 *   a manual BlockHitResult on the obsidian's top face (crystal spawns on top).
 * - getStrictInteract / getLegitInteract / getDefaultInteract raycasts (Vanilla/Strict/Legit
 *   Interact modes) -> getPlaceData keeps the Strict-mode nearest-visible-face algorithm
 *   directly (bp is already-real obsidian/bedrock here, not an empty cell needing neighbor
 *   support, so InteractionHelper's support search doesn't apply to the crystal hit itself).
 * - Managers.FRIEND.isFriend -> FriendManager.isFriend; Managers.ASYNC / getPlayersSorted ->
 *   mc.level entity scan sorted by distance.
 * - Timer -> long timestamps. MathUtility.random -> ThreadLocalRandom. EventEntityRemoved +
 *   PlaySound(FromEntity)S2CPacket explosion detection -> EventPacket.Receive on
 *   ClientboundSoundPacket / ClientboundSoundEntityPacket (SoundEvents.GENERIC_EXPLODE, BLOCKS
 *   source) plus a per-tick lastCrystal liveness check.
 * - Render3DEngine.FILLED_QUEUE + Render2DEngine.injectAlpha -> WorldDrawer.box on
 *   EventWorldRender. isRu()/disable-messages -> plain disable().
 */
public final class PistonAura extends AddonModule {
    public static final PistonAura INSTANCE = new PistonAura();

    public enum Pattern { Small, Cross, Liner, All }
    public enum Stage { Searching, Trap, Piston, Fire, Crystal, RedStone, Break }

    public final SliderOption placeDelay = new SliderOption(this, "Delay/Place", "Tick delay between placement batches.", 1.0, 0.0, 25.0, 1.0);
    public final SliderOption blocksPerTick = new SliderOption(this, "Block/Tick", "Placements per tick.", 3.0, 1.0, 6.0, 1.0);
    public final ModeOption<Pattern> patternsSetting = new ModeOption<>(this, "Pattern", "Which structure patterns to consider.", Pattern.All);
    public final ToggleOption supportPlace = new ToggleOption(this, "SupportPlace", "Place obsidian under floating structure blocks.", false);
    public final ToggleOption bypass = new ToggleOption(this, "FireBypass", "Use fire + crystal (fire-bypass anti-crystal setups).", false);
    public final ToggleOption oldVersion = new ToggleOption(this, "OldVersion", "Require 2 blocks of air above the crystal (old crystal rules).", false);
    public final ToggleOption trap = new ToggleOption(this, "Trap", "Wall the target's head in with obsidian first.", false);
    public final SliderOption targetRange = new SliderOption(this, "Target Range", "Max target distance.", 10.0, 0.0, 12.0, 0.1);
    public final SliderOption placeRange = new SliderOption(this, "PlaceRange", "Max placement distance.", 4.0, 1.0, 7.0, 0.1);
    public final SliderOption wallRange = new SliderOption(this, "WallRange", "Max distance for placements behind walls.", 4.0, 1.0, 7.0, 0.1);
    public final ModeOption<InteractionMode> placeMode = new ModeOption<>(this, "Place Mode", "Anti-cheat bypass mode.", InteractionMode.Grim);
    public final ToggleOption airPlace = new ToggleOption(this, "AirPlace", "Allow placement against air (no adjacent solid neighbor required).", false);
    public final ToggleOption rotate = new ToggleOption(this, "Rotate", "Silently rotate to placements.", true);
    public final ModeOption<SwapType> swapAction = new ModeOption<>(this, "Swap", "How to swap to required items.", SwapType.Silent);
    public final ToggleOption breakCrystal = new ToggleOption(this, "BreakCrystal", "Attack the crystal to detonate it.", true);

    private static final ClientColor CYAN = ColorMaker.staticColor(0, 255, 255);
    private static final ClientColor PINK = ColorMaker.staticColor(255, 105, 180);
    private static final ClientColor GREEN = ColorMaker.staticColor(0, 255, 0);
    private static final ClientColor RED = ColorMaker.staticColor(255, 0, 0);
    private static final ClientColor YELLOW = ColorMaker.staticColor(255, 255, 0);

    private Player target;
    private BlockPos targetPos, pistonPos, crystalPos, redStonePos, firePos, pistonHeadPos;
    private boolean builtTrap, isFire;
    private long attackTimerMs = 0;
    private int delay = 0;
    private Stage stage = Stage.Searching;
    private EndCrystal lastCrystal;
    // Rotating-interaction serialization: Boze pipelines rotation packet and action across
    // ticks (Grim: rotation must precede the use-packet). Submitting a rotating interaction
    // every tick let interaction N+1's rotation go out BEFORE interaction N's action fired --
    // the server resolved N's use-packet under N+1's rotation (piston FACING corrupted,
    // hit/rotation mismatch swallowed by Grim). With Rotate off only the piston ever rotates,
    // which is why that path was stable. Gate: no new rotating submit until the previous
    // rotating action has actually RUN (flag cleared inside the Runnable), 500ms timeout in
    // case the queued interaction gets dropped.
    private boolean rotPending;
    private long rotPendingSince;

    public PistonAura() {
        super("PistonAura", "Fully automatic piston-crystal aura.");
    }

    private void disable(String reason) {
        dev.boze.api.utility.ChatHelper.sendMsg("PistonAura", reason);
        setState(false);
    }

    public void reset() {
        builtTrap = false;
        target = null;
        stage = Stage.Searching;
        attackTimerMs = 0;
        pistonPos = null;
        targetPos = null;
        firePos = null;
        pistonHeadPos = null;
        crystalPos = null;
        redStonePos = null;
        lastCrystal = null;
        isFire = false;
        delay = 0;
        rotPending = false;
    }

    @Override
    public void onEnable() { reset(); }

    @Override
    public void onDisable() { reset(); }

    // ── Main loop (EventInteract, mode-gated) ────────────────────────────────

    @EventHandler
    private void onInteract(EventInteract event) {
        if (event.getMode() != placeMode.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (delay < placeDelay.getValue().intValue()) delay++;

        // Serialize rotating interactions: wait until the previous one's action has fired.
        if (rotPending) {
            if (System.currentTimeMillis() - rotPendingSince < 500) return;
            rotPending = false; // timed out -- the queued interaction was dropped
        }

        if (stage == Stage.Break) {
            if (isFire) { reset(); return; }
            // Boze has no EventEntityRemoved (verified: dev.boze.api.event has no entity-lifecycle
            // event at all) -- the sound-packet explosion detection below can silently fail to
            // match (wrong distance/entity-id on a given server), leaving stage stuck at Break
            // forever with no crystal left to attack. Fallback: if the tracked crystal is no
            // longer in the world at all, reset unconditionally. Additive to the sound detection,
            // which stays as the fast/legit-looking path; this is just the guaranteed backstop.
            if (lastCrystal != null && !isEntityPresent(mc, lastCrystal)) { reset(); return; }
            if (breakCrystal.getValue()) breakCrystal(event, mc);
            return;
        }

        if (delay < placeDelay.getValue().intValue()) return;

        int budget = blocksPerTick.getValue().intValue();
        int placed = 0;
        while (placed < budget) {
            boolean submitted = handlePistonAura(event, mc);
            if (!submitted) break;
            delay = 0;
            placed++;
            // Only ONE rotation packet can go out per tick. Batching several rotating
            // interactions into one event ran the later placements under the wrong rotation
            // (piston FACING corrupts, Grim flags the rest). BedAura -- the proven-stable
            // placer in this addon -- submits exactly one rotated action per event for the
            // same reason. Non-rotating submissions may still fill the rest of the budget.
            if (event.hasRotatingInteractions()) break;
        }
    }

    /** One structure step. Returns true if an interaction was submitted (so the loop counts it). */
    private boolean handlePistonAura(EventInteract event, Minecraft mc) {
        if (InvHelper.findInHotbar(Blocks.OBSIDIAN) == -1 && (trap.getValue() || supportPlace.getValue())) {
            disable("No obsidian!"); return false;
        }
        if (InvHelper.findInHotbar(Blocks.REDSTONE_BLOCK) == -1 && InvHelper.findInHotbar(Blocks.REDSTONE_TORCH) == -1) {
            disable("No redstone!"); return false;
        }
        if (InvHelper.findInHotbar(Items.END_CRYSTAL) == -1 && mc.player.getOffhandItem().getItem() != Items.END_CRYSTAL) {
            disable("No crystals!"); return false;
        }
        if (InvHelper.findInHotbar(Items.PISTON) == -1 && InvHelper.findInHotbar(Items.STICKY_PISTON) == -1) {
            disable("No pistons!"); return false;
        }

        return switch (stage) {
            case Searching -> { findPos(mc); stage = Stage.Trap; yield false; }
            case Trap -> buildTrap(event, mc);
            case Piston -> placePiston(event, mc);
            case Fire -> placeFire(event, mc);
            case Crystal -> placeCrystal(event, mc);
            case RedStone -> placeRedStone(event, mc);
            case Break -> { if (isFire) stage = Stage.Searching; yield false; }
        };
    }

    // ── Stage placements ─────────────────────────────────────────────────────

    private boolean buildTrap(EventInteract event, Minecraft mc) {
        if (!trap.getValue()) { stage = Stage.Piston; return false; }
        if (targetPos == null || pistonPos == null || crystalPos == null) { stage = Stage.Searching; return false; }
        if (WorldHelper.getBlock(targetPos.offset(0, 2, 0)) == Blocks.OBSIDIAN) {
            stage = Stage.Piston; return false;
        }
        if (builtTrap) { stage = Stage.Piston; return false; }

        BlockPos offset = new BlockPos(crystalPos.getX() - targetPos.getX(), 0, crystalPos.getZ() - targetPos.getZ());
        BlockPos trapBase = targetPos.offset(offset.getX() * -1, 0, offset.getZ() * -1);
        BlockPos headCap = targetPos.offset(0, 2, 0);
        // AirPlace: the head cap needs no support chain -- ONE block straight above the target's
        // head is the whole trap. The 3-block side-wall ladder exists only to give the cap a
        // support chain when air-placing isn't available.
        // Bottom-up otherwise: base leans on ground/existing terrain, wall-top leans on base,
        // head cap leans on wall-top. Top-down order left the head cap support-less
        // forever with AirPlace off (its only neighbor is the wall block placed after it).
        List<BlockPos> trapPos = airPlace.getValue()
                ? List.of(headCap)
                : List.of(trapBase.offset(0, 1, 0), trapBase.offset(0, 2, 0), headCap);

        int obiSlot = InvHelper.findInHotbar(Blocks.OBSIDIAN);
        for (BlockPos bp : trapPos) {
            if (!WorldHelper.isReplaceable(bp) || InteractionHelper.isAwaiting(bp)) continue;
            if (supportBelow(event, mc, bp)) return true;
            if (placeBlock(event, mc, bp, obiSlot, null)) {
                if (bp.equals(headCap)) { builtTrap = true; stage = Stage.Piston; }
                return true;
            }
        }
        return false;
    }

    private boolean placePiston(EventInteract event, Minecraft mc) {
        if (pistonPos == null || pistonHeadPos == null) { stage = Stage.Searching; return false; }
        if (WorldHelper.getBlockState(pistonPos).getBlock() instanceof PistonBaseBlock) {
            stage = isFire ? Stage.Fire : Stage.Crystal; return false;
        }
        if (supportBelow(event, mc, pistonPos)) return true;

        int slot = InvHelper.findInHotbar(Blocks.PISTON);
        if (slot == -1) slot = InvHelper.findInHotbar(Blocks.STICKY_PISTON);
        if (slot == -1) { disable("No pistons!"); return false; }

        // Forced yaw pointing head->piston so FACING resolves piston->head (head extends toward
        // pistonHeadPos, into the target's cell).
        float yaw = MathHelper.calculateRotation(Vec3.atCenterOf(pistonHeadPos), Vec3.atCenterOf(pistonPos))[0];
        if (!placeBlock(event, mc, pistonPos, slot, yaw)) return false;
        stage = isFire ? Stage.Fire : Stage.Crystal;
        return true;
    }

    private boolean placeFire(EventInteract event, Minecraft mc) {
        if (firePos == null) { stage = Stage.Searching; return false; }
        if (WorldHelper.getBlockState(firePos).getBlock() instanceof BaseFireBlock) { stage = Stage.Crystal; return false; }
        if (supportBelow(event, mc, firePos)) return true;

        int slot = InvHelper.findInHotbar(Items.FLINT_AND_STEEL);
        if (slot == -1) { stage = Stage.Crystal; return false; }
        if (!placeBlock(event, mc, firePos, slot, null)) return false;
        stage = Stage.Crystal;
        return true;
    }

    private boolean placeCrystal(EventInteract event, Minecraft mc) {
        if (crystalPos == null) { stage = Stage.Searching; return false; }
        if (WorldHelper.isReplaceable(crystalPos) && supportPlace.getValue()) {
            int obiSlot = InvHelper.findInHotbar(Blocks.OBSIDIAN);
            if (placeBlock(event, mc, crystalPos, obiSlot, null, true)) return true;
        }

        BlockHitResult result = getPlaceData(mc, crystalPos);
        if (result == null) return false;

        boolean offHand = mc.player.getOffhandItem().getItem() == Items.END_CRYSTAL;
        int crystalSlot = offHand ? -1 : InvHelper.findInHotbar(Items.END_CRYSTAL);
        final BlockHitResult hit = result;
        final InteractionHand hand = offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;

        Runnable place = () -> {
            if (offHand) {
                PlaceHelper.place(placeMode.getValue(), hit, hand);
                mc.player.swing(hand);
            } else if (crystalSlot != -1
                    && InteractionHelper.placeSwapped(placeMode.getValue(), hit, crystalSlot, swapAction.getValue())) {
                mc.player.swing(hand);
            }
        };
        // Flip stage synchronously (same as every other stage), not inside the deferred
        // Runnable: the Runnable only runs when its queued Interaction actually fires, which can
        // lag several ticks. Deferring it left `stage` at Crystal across that gap, so the SAME
        // tick's budget loop (and every tick after, until the Runnable caught up) kept re-entering
        // placeCrystal and submitting duplicate crystal placements at the same spot -- RedStone
        // never got a turn.
        stage = Stage.RedStone;
        addRotating(event, place, hit.getLocation());
        return true;
    }

    private boolean placeRedStone(EventInteract event, Minecraft mc) {
        if (redStonePos == null) { stage = Stage.Searching; return false; }
        Block rb = WorldHelper.getBlock(redStonePos);
        if (rb == Blocks.REDSTONE_BLOCK || rb instanceof RedstoneTorchBlock) { stage = Stage.Break; return false; }
        if (supportBelow(event, mc, redStonePos)) return true;

        int slot = InvHelper.findInHotbar(Blocks.REDSTONE_BLOCK);
        if (slot == -1) slot = InvHelper.findInHotbar(Blocks.REDSTONE_TORCH);
        if (slot == -1) { disable("No redstone!"); return false; }
        if (!placeBlock(event, mc, redStonePos, slot, null)) return false;
        stage = Stage.Break;
        return true;
    }

    /** SupportPlace: if the block below {@code pos} is replaceable, place obsidian there first. */
    private boolean supportBelow(EventInteract event, Minecraft mc, BlockPos pos) {
        BlockPos below = pos.below();
        if (!supportPlace.getValue() || !WorldHelper.isReplaceable(below) || InteractionHelper.isAwaiting(below)) return false;
        int obiSlot = InvHelper.findInHotbar(Blocks.OBSIDIAN);
        return obiSlot != -1 && placeBlock(event, mc, below, obiSlot, null, true);
    }

    // ── Generic block placement via EventInteract ────────────────────────────

    /** Places {@code slot}'s block at {@code pos}. {@code forcedYaw} non-null forces that yaw
     *  (piston facing), else rotates toward the cast hit if Rotate is on. */
    private boolean placeBlock(EventInteract event, Minecraft mc, BlockPos pos, int slot, Float forcedYaw) {
        return placeBlock(event, mc, pos, slot, forcedYaw, airPlace.getValue());
    }

    /**
     * forceAirPlace lets SupportPlace's own obsidian-under-a-floating-cell placements (placeCrystal's
     * support branch, supportBelow) always bypass the neighbor requirement regardless of the
     * AirPlace toggle -- SupportPlace's whole point is placing into open air under the structure,
     * so it can't depend on a SEPARATE toggle also being on to actually work.
     */
    private boolean placeBlock(EventInteract event, Minecraft mc, BlockPos pos, int slot, Float forcedYaw, boolean forceAirPlace) {
        if (slot == -1) return false;
        // Geometric hit, not PlaceHelper.cast: cast() raycasts along the player's REAL current
        // look direction and only hits when already aimed there. Structure blocks are placed at
        // positions computed relative to the target, not wherever the camera happens to point,
        // so this needs ThunderHack's actual technique (InteractionUtility.getPlaceResult Strict
        // branch): a purely position-based support-face pick, rotation sent separately.
        BlockHitResult hit = InteractionHelper.getPlaceResult(mc, pos, forceAirPlace, false);
        if (hit == null) return false;
        final BlockHitResult h = hit;
        final BlockPos placedPos = pos;
        Runnable place = () -> {
            // placeSwapped, not bare swap/place/swapBack: swapToSlot CAN fail, and ignoring it
            // desyncs the server's selected slot -- the use-packet then places whatever the server
            // still thinks is in hand (the "piston appears at the redstone position" bug).
            if (InteractionHelper.placeSwapped(placeMode.getValue(), h, slot, swapAction.getValue())) {
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
        };
        // Mark awaiting SYNCHRONOUSLY, at submit time -- not inside the deferred Runnable (which
        // runs at end of tick). Otherwise the same tick's budget loop and the immediately-following
        // ticks re-run getPlaceResult/isReplaceable on this exact cell, see it still empty, and
        // re-submit it forever (support chain never registers the previous cell -> trap stalls on
        // the first block, obsidian swap spam with no progress). TTL self-heals if the place fails.
        InteractionHelper.markAwaiting(placedPos);
        if (forcedYaw != null) {
            // Piston facing yaw is ALWAYS forced, independent of the Rotate toggle -- the
            // original's postAction sends LookAndOnGround(facingYaw, 0) unconditionally; a
            // wrongly-facing piston is useless. pitch 0 so FACING stays horizontal.
            submit(event, place, forcedYaw, 0f);
        } else {
            addRotating(event, place, hit.getLocation());
        }
        return true;
    }

    /**
     * Rotation is ALWAYS sent for structural placement (piston/trap/crystal/redstone/fire), same
     * as placePiston's forced yaw already was -- the `rotate` toggle no longer gates this. Our
     * hit-finding (getPlaceResult) is trig-based, not a real Level.clip() raycast (unlike the old
     * PistonCrystal module it replaced), so a hit can be geometrically "visible" on paper without
     * matching the player's actual look direction; Grim independently verifies rotation against
     * the interacted block, and without a matching rotation packet it rejects the placement --
     * that's the entire "Rotate off = everything breaks except piston" bug. `rotate` stays as a
     * declared option for whatever future non-structural use needs it; it no longer affects
     * whether these placements rotate.
     */
    private void addRotating(EventInteract event, Runnable place, Vec3 lookAt) {
        float[] rot = MathHelper.calculateRotation(Minecraft.getInstance().player.getEyePosition(), lookAt);
        submit(event, place, rot[0], rot[1]);
    }

    /** Single submit path; rotating interactions arm the rotPending serialization gate. */
    private void submit(EventInteract event, Runnable action, Float yaw, Float pitch) {
        if (yaw == null) { event.addInteraction(new Interaction(action)); return; }
        Runnable wrapped = () -> { rotPending = false; action.run(); };
        event.addInteraction(new Interaction(wrapped, yaw, pitch));
        rotPending = true;
        rotPendingSince = System.currentTimeMillis();
    }

    // ── Crystal hit-vector (Interact modes) ──────────────────────────────────

    /**
     * Crystal hit: {@code bp} is the ALREADY-REAL obsidian/bedrock support (not an empty target
     * cell), so this is InteractionHelper's neighbor-support search does not apply here -- pick
     * the nearest face of bp itself that's within eye-los, exactly like the original's Strict
     * mode (getStrictInteract). Not PlaceHelper.cast: same real-camera-raycast problem as the
     * piston/redstone path (cast() only hits when already looking that way).
     */
    private BlockHitResult getPlaceData(Minecraft mc, BlockPos bp) {
        Block base = WorldHelper.getBlock(bp);
        Block freeSpace = WorldHelper.getBlock(bp.above());
        Block legacyFreeSpace = WorldHelper.getBlock(bp.above().above());

        if (base != Blocks.OBSIDIAN && base != Blocks.BEDROCK) return null;
        if (!(freeSpace == Blocks.AIR && (!oldVersion.getValue() || legacyFreeSpace == Blocks.AIR))) return null;
        if (checkEntities(mc, bp)) return null;

        float bestDistance = Float.MAX_VALUE;
        Direction bestDirection = null;
        Vec3 bestVector = null;
        Vec3 eyes = mc.player.getEyePosition();

        if (eyes.y > bp.above().getY()) {
            bestDirection = Direction.UP;
            bestVector = new Vec3(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);
        } else if (eyes.y < bp.getY()) {
            bestDirection = Direction.DOWN;
            bestVector = new Vec3(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
        } else {
            for (Direction dir : Direction.values()) {
                Vec3 v = new Vec3(bp.getX() + 0.5 + dir.getStepX() * 0.5, bp.getY() + 0.5 + dir.getStepY() * 0.5, bp.getZ() + 0.5 + dir.getStepZ() * 0.5);
                float distance = (float) eyes.distanceToSqr(v);
                if (bestDistance > distance) { bestDirection = dir; bestVector = v; bestDistance = distance; }
            }
        }
        if (bestVector == null) return null;
        if (eyes.distanceToSqr(bestVector) > placeRange.getValue() * placeRange.getValue()) return null;

        BlockHitResult wallCheck = WorldHelper.raycast(eyes, bestVector);
        if (wallCheck != null && wallCheck.getType() == HitResult.Type.BLOCK
                && !wallCheck.getBlockPos().equals(bp)
                && eyes.distanceToSqr(bestVector) > wallRange.getValue() * wallRange.getValue()) {
            return null;
        }
        return new BlockHitResult(bestVector, bestDirection, bp, false);
    }

    private boolean checkEntities(Minecraft mc, BlockPos base) {
        // Yarn Box.expand(0,1,0) grows the box 1 on BOTH +/-Y (Mojmap inflate), NOT the
        // one-directional expandTowards -- getting this wrong shrank the entity-clearance
        // check to the wrong region.
        AABB box = new AABB(base.above()).inflate(0, 1, 0);
        for (Entity ent : mc.level.entitiesForRendering()) {
            if (ent == null) continue;
            if (ent.getBoundingBox().intersects(box)) {
                if (ent instanceof net.minecraft.world.entity.ExperienceOrb) continue;
                if (ent instanceof EndCrystal) continue;
                return true;
            }
        }
        return false;
    }

    // ── Crystal break / detonate ─────────────────────────────────────────────

    /**
     * Silent aim, not mc.player.setYRot/setXRot: those mutate the LOCAL player's real rotation
     * fields, which the render pipeline reads for the camera -> the screen visibly snaps to the
     * crystal. BedAura never does that; it only ever hands yaw/pitch to
     * event.addInteraction(new Interaction(action, yaw, pitch)), which sends the rotation as a
     * packet alongside the action without moving the real camera. Same fix here.
     */
    private boolean isEntityPresent(Minecraft mc, Entity e) {
        for (Entity ent : mc.level.entitiesForRendering()) {
            if (ent == e) return true;
        }
        return false;
    }

    private void breakCrystal(EventInteract event, Minecraft mc) {
        if (target == null) return;
        for (Entity ent : mc.level.entitiesForRendering()) {
            if (!(ent instanceof EndCrystal c) || target.distanceToSqr(ent) > 16 || ent.tickCount < 2) continue;
            lastCrystal = c;
            if (System.currentTimeMillis() - attackTimerMs < 200) continue;

            // Original aims at the crystal's entity position (feet-center), not a bounding-box
            // best-point -- match it exactly.
            float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), c.position());
            float yaw = rot[0] + ThreadLocalRandom.current().nextFloat() * 6f - 3f;
            float pitch = rot[1];
            Runnable attack = () -> {
                mc.gameMode.attack(mc.player, c);
                mc.player.swing(InteractionHand.MAIN_HAND);
            };
            submit(event, attack, yaw, pitch);
            attackTimerMs = System.currentTimeMillis();
        }
    }

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        if (event.packet instanceof ClientboundSoundPacket p
                && p.getSource() == SoundSource.BLOCKS && p.getSound().value() == SoundEvents.GENERIC_EXPLODE.value()) {
            if (lastCrystal == null || !lastCrystal.isAlive()) return;
            double soundRange = lastCrystal.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
            if (soundRange > 121) return;
            reset();
        } else if (event.packet instanceof ClientboundSoundEntityPacket p
                && p.getSource() == SoundSource.BLOCKS && p.getSound().value() == SoundEvents.GENERIC_EXPLODE.value()) {
            if (lastCrystal == null || !lastCrystal.isAlive()) return;
            if (p.getId() != lastCrystal.getId()) return;
            reset();
        }
    }

    // ── Search / structure enumeration ───────────────────────────────────────

    private void findPos(Minecraft mc) {
        List<Structure> list = new ArrayList<>();
        for (Player tgt : Objects.requireNonNull(getPlayersSorted(mc, targetRange.getValue().floatValue()))) {
            for (int i = 0; i <= 2; i++) {
                Pattern pat = patternsSetting.getValue();
                if (pat == Pattern.Small || pat == Pattern.All) {
                    list.add(new Structure(mc, tgt, new BlockPos(1, i, 0), new BlockPos(1, i, -1), new BlockPos(0, i, -1),
                            new BlockPos[]{new BlockPos(1, i, -2)}, new BlockPos[]{new BlockPos(0, 0, 1), new BlockPos(1, i, 1), new BlockPos(0, 1 + i, 1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(1, i, 0), new BlockPos(1, i, 1), new BlockPos(0, i, 1),
                            new BlockPos[]{new BlockPos(1, i, 2)}, new BlockPos[]{new BlockPos(0, i, -1), new BlockPos(1, i, -1), new BlockPos(0, 1 + i, -1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, 1), new BlockPos(1, i, 1), new BlockPos(1, i, 0),
                            new BlockPos[]{new BlockPos(2, i, 1)}, new BlockPos[]{new BlockPos(-1, i, 1), new BlockPos(-1, i, 0), new BlockPos(-1, 1 + i, 0)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, 1), new BlockPos(-1, i, 1), new BlockPos(-1, i, 0),
                            new BlockPos[]{new BlockPos(-2, i, 1)}, new BlockPos[]{new BlockPos(1, i, 1), new BlockPos(1, i, 0), new BlockPos(1, 1 + i, 0)}));
                    list.add(new Structure(mc, tgt, new BlockPos(-1, i, 0), new BlockPos(-1, i, 1), new BlockPos(0, i, 1),
                            new BlockPos[]{new BlockPos(-1, i, 2)}, new BlockPos[]{new BlockPos(-1, i, -1), new BlockPos(0, i, -1), new BlockPos(0, 1 + i, -1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(-1, i, 0), new BlockPos(-1, i, -1), new BlockPos(0, i, -1),
                            new BlockPos[]{new BlockPos(-1, i, -2)}, new BlockPos[]{new BlockPos(-1, i, 1), new BlockPos(0, i, 1), new BlockPos(0, 1 + i, 1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, -1), new BlockPos(-1, i, -1), new BlockPos(-1, i, 0),
                            new BlockPos[]{new BlockPos(-2, i, -1)}, new BlockPos[]{new BlockPos(1, i, 0), new BlockPos(1, i, -1), new BlockPos(1, i + 1, 0)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, -1), new BlockPos(1, i, -1), new BlockPos(1, i, 0),
                            new BlockPos[]{new BlockPos(2, i, -1)}, new BlockPos[]{new BlockPos(-1, i, 0), new BlockPos(-1, i, -1), new BlockPos(-1, 1 + i, 0)}));
                }
                if (pat == Pattern.Cross || pat == Pattern.All) {
                    list.add(new Structure(mc, tgt, new BlockPos(1, i, 0), new BlockPos(2, i, -1), new BlockPos(1, i, -1),
                            new BlockPos[]{new BlockPos(3, i, -1), new BlockPos(2, i, -2)}, new BlockPos[]{new BlockPos(0, i, -1), new BlockPos(1, i, 1), new BlockPos(0, i, 1), new BlockPos(0, 1 + i, 1), new BlockPos(0, 1 + i, -1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(1, i, 0), new BlockPos(2, i, 1), new BlockPos(1, i, 1),
                            new BlockPos[]{new BlockPos(3, i, 1), new BlockPos(2, i, 2)}, new BlockPos[]{new BlockPos(0, i, 1), new BlockPos(1, i, -1), new BlockPos(0, i, -1), new BlockPos(0, 1 + i, -1), new BlockPos(0, 1 + i, 1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, 1), new BlockPos(1, i, 2), new BlockPos(1, i, 1),
                            new BlockPos[]{new BlockPos(1, i, 3), new BlockPos(2, i, 2)}, new BlockPos[]{new BlockPos(1, i, 0), new BlockPos(-1, i, 0), new BlockPos(-1, i, 1), new BlockPos(-1, 1 + i, 0), new BlockPos(1, 1 + i, 0)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, 1), new BlockPos(-1, i, 2), new BlockPos(-1, i, 1),
                            new BlockPos[]{new BlockPos(-1, i, 3), new BlockPos(-2, i, 2)}, new BlockPos[]{new BlockPos(-1, i, 0), new BlockPos(1, i, 1), new BlockPos(1, i, 0), new BlockPos(-1, 1 + i, 0), new BlockPos(1, 1 + i, 0)}));
                    list.add(new Structure(mc, tgt, new BlockPos(-1, i, 0), new BlockPos(-2, i, 1), new BlockPos(-1, i, 1),
                            new BlockPos[]{new BlockPos(-3, i, 1), new BlockPos(-2, i, 2)}, new BlockPos[]{new BlockPos(0, i, 1), new BlockPos(-1, i, -1), new BlockPos(0, i, -1), new BlockPos(0, 1 + i, 1), new BlockPos(0, 1 + i, -1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(-1, i, 0), new BlockPos(-2, i, -1), new BlockPos(-1, i, -1),
                            new BlockPos[]{new BlockPos(-3, i, -1), new BlockPos(-2, i, -2)}, new BlockPos[]{new BlockPos(0, i, -1), new BlockPos(0, i, -1), new BlockPos(-1, i, 1), new BlockPos(0, 1 + i, 1), new BlockPos(0, 1 + i, -1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, -1), new BlockPos(-1, i, -2), new BlockPos(-1, i, -1),
                            new BlockPos[]{new BlockPos(-1, i, -3), new BlockPos(-2, i, -2)}, new BlockPos[]{new BlockPos(-1, i, 0), new BlockPos(1, i, 0), new BlockPos(1, i, -1), new BlockPos(-1, 1 + i, 0), new BlockPos(1, 1 + i, 0)}));
                }
                if (pat == Pattern.Liner || pat == Pattern.All) {
                    list.add(new Structure(mc, tgt, new BlockPos(1, i, 0), new BlockPos(2, i, 0), new BlockPos(1, i, 0),
                            new BlockPos[]{new BlockPos(3, i, 0)}, new BlockPos[]{new BlockPos(1, i, 1), new BlockPos(1, i, -1), new BlockPos(0, i, 1), new BlockPos(0, i, -1), new BlockPos(0, 1 + i, 1), new BlockPos(0, 1 + i, -1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(-1, i, 0), new BlockPos(-2, i, 0), new BlockPos(-1, i, 0),
                            new BlockPos[]{new BlockPos(-3, i, 0)}, new BlockPos[]{new BlockPos(-1, i, -1), new BlockPos(-1, i, 1), new BlockPos(0, i, 1), new BlockPos(0, i, -1), new BlockPos(0, 1 + i, 1), new BlockPos(0, 1 + i, -1)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, 1), new BlockPos(0, i, 2), new BlockPos(0, i, 1),
                            new BlockPos[]{new BlockPos(0, i, 3)}, new BlockPos[]{new BlockPos(-1, i, 1), new BlockPos(1, i, 1), new BlockPos(-1, i, 0), new BlockPos(1, i, 0), new BlockPos(1, 1 + i, 0), new BlockPos(-1, 1 + i, 0)}));
                    list.add(new Structure(mc, tgt, new BlockPos(0, i, -1), new BlockPos(0, i, -2), new BlockPos(0, i, -1),
                            new BlockPos[]{new BlockPos(0, i, -3)}, new BlockPos[]{new BlockPos(-1, i, -1), new BlockPos(1, i, -1), new BlockPos(-1, i, 0), new BlockPos(1, i, 0), new BlockPos(1, 1 + i, 0), new BlockPos(-1, 1 + i, 0)}));
                }
            }
            this.target = tgt;
        }

        if (bypass.getValue() && InvHelper.findInHotbar(Items.FLINT_AND_STEEL) != -1) {
            List<Structure> fireStructs = list.stream().filter(Structure::isFirePa).sorted(Comparator.comparingDouble(Structure::getMaxRange)).toList();
            if (fireStructs.isEmpty()) {
                isFire = false;
                assignBest(list.stream().filter(Structure::isNormalPa).sorted(Comparator.comparingDouble(Structure::getMaxRange)).toList(), false);
            } else {
                isFire = true;
                assignBest(fireStructs, true);
            }
        } else {
            isFire = false;
            assignBest(list.stream().filter(Structure::isNormalPa).sorted(Comparator.comparingDouble(Structure::getMaxRange)).toList(), false);
        }
    }

    private void assignBest(List<Structure> best, boolean withFire) {
        if (best.isEmpty()) { disable("No target or free space!"); return; }
        Structure s = best.get(0);
        pistonPos = s.pistonPos;
        crystalPos = s.crystalPos;
        redStonePos = s.redStonePos;
        pistonHeadPos = s.pistonHeadPos;
        targetPos = s.targetPos;
        target = s.target;
        firePos = withFire ? s.firePos : null;
    }

    private List<Player> getPlayersSorted(Minecraft mc, float range) {
        List<Player> playerList = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Player p) || p == mc.player) continue;
            if (FriendManager.isFriend(p.getName().getString())) continue;
            if (mc.player.distanceToSqr(p) > range * range) continue;
            playerList.add(p);
        }
        playerList.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
        return playerList;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onWorldRender(EventWorldRender event) {
        if (pistonPos == null || crystalPos == null || redStonePos == null || pistonHeadPos == null) return;
        WorldDrawer.start();
        drawBox(CYAN, pistonHeadPos);
        drawBox(PINK, crystalPos);
        drawBox(GREEN, pistonPos);
        drawBox(RED, redStonePos);
        if (firePos != null) drawBox(YELLOW, firePos);
        WorldDrawer.draw(event.matrices);
    }

    private void drawBox(ClientColor color, BlockPos pos) {
        AABB b = new AABB(pos);
        WorldDrawer.box(color, 0.4f, 0.9f, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
    }

    // ── Structure candidate ──────────────────────────────────────────────────

    private final class Structure {
        private final BlockPos pistonPos, targetPos;
        private BlockPos pistonHeadPos, crystalPos, redStonePos, firePos;
        private final Player target;

        Structure(Minecraft mc, Player target, BlockPos crystal, BlockPos piston, BlockPos pistonHead, BlockPos[] redstone, BlockPos[] fire) {
            this.target = target;
            this.targetPos = target.blockPosition();
            BlockPos pp = targetPos.offset(piston.getX(), piston.getY() + 1, piston.getZ());
            this.pistonPos = canPlace(pp) ? pp : null;
            BlockPos cp = targetPos.offset(crystal.getX(), crystal.getY(), crystal.getZ());
            this.crystalPos = getPlaceData(mc, cp) != null ? cp : null;
            BlockPos hp = targetPos.offset(pistonHead.getX(), pistonHead.getY() + 1, pistonHead.getZ());
            this.pistonHeadPos = WorldHelper.isAir(hp) ? hp : null;

            if (this.pistonHeadPos != null && !mc.level.getEntitiesOfClass(Player.class, new AABB(this.pistonHeadPos)).isEmpty()) {
                this.pistonHeadPos = null;
            }
            if (this.crystalPos != null && !mc.level.getEntitiesOfClass(Entity.class, new AABB(this.crystalPos)).isEmpty()) {
                this.crystalPos = null;
            }

            this.redStonePos = null;
            List<BlockPos> tempRed = Arrays.stream(redstone).map(b -> targetPos.offset(b.getX(), b.getY() + 1, b.getZ())).toList();
            // ThunderHack temporarily sets the piston block so the redstone-support check sees it;
            // client-side setBlock is safe (world reverts on next chunk update) but we avoid mutating
            // the world here -- canPlace already validates each redstone cell independently.
            for (BlockPos pos : tempRed) {
                if (canPlace(pos)) { this.redStonePos = pos; break; }
            }

            this.firePos = null;
            List<BlockPos> tempFire = Arrays.stream(fire).map(b -> targetPos.offset(b.getX(), b.getY() + 1, b.getZ())).toList();
            for (BlockPos pos : tempFire) {
                if (canPlace(pos)) { this.firePos = pos; break; }
            }
        }

        boolean isNormalPa() {
            return pistonPos != null && crystalPos != null && targetPos != null && redStonePos != null && pistonHeadPos != null;
        }

        boolean isFirePa() {
            return isNormalPa() && firePos != null;
        }

        private boolean canPlace(BlockPos pos) {
            if (pos == null) return false;
            // MUST be the exact same feasibility check the real placement uses
            // (InteractionHelper.getPlaceResult), matching upstream's canPlaceBlock ==
            // getPlaceResult(...) != null. A previous version used a separate WorldHelper-only
            // check here (position/eye-independent) -- that let Structure validate a candidate
            // as "placeable" regardless of where the player was standing, while the real
            // placement (Strict-mode, position-dependent support/visibility) then rejected it
            // from most stances. Search-time and place-time validity must be the same function.
            if (!WorldHelper.isInWorldBounds(pos) || !WorldHelper.isRegionLoaded(pos)) return false;
            return InteractionHelper.canPlace(Minecraft.getInstance(), pos, airPlace.getValue(), false);
        }

        double getMaxRange() {
            if (pistonPos == null || crystalPos == null || redStonePos == null) return 999;
            Vec3 eye = Minecraft.getInstance().player.getEyePosition();
            double piston = eye.distanceToSqr(Vec3.atCenterOf(pistonPos));
            double crystal = eye.distanceToSqr(Vec3.atCenterOf(crystalPos));
            double redStone = eye.distanceToSqr(Vec3.atCenterOf(redStonePos));
            BlockPos fp = firePos != null ? firePos : pistonPos;
            double fire = eye.distanceToSqr(Vec3.atCenterOf(fp));
            return Math.max(Math.max(fire, crystal), Math.max(redStone, piston));
        }
    }
}
