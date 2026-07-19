package com.example.addon.modules.pistoncrystal;

import com.example.addon.modules.bedaura.DamageUtils;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.FriendManager;
import dev.boze.api.event.EventInteract;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.MathHelper;
import dev.boze.api.utility.WorldHelper;
import dev.boze.api.utility.interaction.BreakHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PistonCrystal — ported from Kallean's {@code PistonCrystalModule} (LeavesHack/Kallean client,
 * package {@code net.kallean.client.impl.module.combat}). Places a piston + redstone near an
 * existing crystal spot; when the piston extends it pushes into the crystal's placement path,
 * then the crystal itself is placed and detonated against the target.
 *
 * <p>Adapted to this addon's real APIs instead of Kallean's Yarn/Fabric + framework-specific
 * ones (verified against the actual 26.1.2 jar and boze-api 3.3 sources, not guessed):
 * <ul>
 *   <li>{@code net.minecraft.block.PistonBlock} does not exist in this Mojmap build — the real
 *       class is {@link PistonBaseBlock} in package {@code net.minecraft.world.level.block.piston}
 *       (verified via javap on minecraft-merged-1c9175fa40-26.1.2.jar).</li>
 *   <li>Yarn's {@code RedstoneBlock} has no Mojmap counterpart -- the solid "Block of Redstone"
 *       is just a plain {@link Block}, checked via {@code == Blocks.REDSTONE_BLOCK} (confirmed:
 *       no such class exists in the jar's block package listing).</li>
 *   <li>{@code BlockUtilPlus}/{@code Managers}/{@code Config} (Kallean's own framework) replaced
 *       with this addon's {@link WorldHelper}/{@link PlaceHelper}/{@link InvHelper} (boze-api 3.3)
 *       and {@link ToggleOption}/{@link SliderOption}/{@link ModeOption} settings, same convention
 *       {@code BedAura}/{@code AntiPiston} already use in this codebase.</li>
 *   <li>{@code ExplosionUtil.getDamageTo} replaced with {@code BedAura}'s {@link DamageUtils}
 *       (javap-verified real armor-mitigation formula), extended here with an explosionRadius
 *       param (crystal = 6.0F, vanilla EndCrystal.onRemove's explosion power) plus
 *       ignoreTerrain/assumeBestArmor flags for the BlockDestruction/AssumeBestArmor toggles.</li>
 *   <li>{@code SnapRotation}'s core contract (silent rotate for one action, hold until snapBack)
 *       is ported behaviorally, not literally: Kallean's version mutates outbound movement-packet
 *       fields directly, but {@code ServerboundMovePlayerPacket}'s fields are {@code protected
 *       final} in this MC version (verified via javap — no setters, immutable), so field mutation
 *       is not possible here. Reimplemented instead with the same save/set/act/restore pattern
 *       already proven working in this codebase by {@code AntiPiston.attackCrystal} (temporarily
 *       set {@code mc.player}'s yaw/pitch, send a {@code ServerboundMovePlayerPacket.Rot} sync,
 *       run the action, restore) — same externally-observable "silent rotation" result.</li>
 *   <li>The YawDeceive piston-facing override IS real and necessary, not a Kallean-specific
 *       trick: verified via javap on {@code PistonBaseBlock.getStateForPlacement} — FACING is
 *       set from {@code context.getNearestLookingDirection().getOpposite()}, i.e. purely from
 *       the player's rotation at placement time, never from which face was clicked. Table kept
 *       identical to Kallean's (same look-direction -> yaw mapping, cross-checked against
 *       {@code Direction.fromYRot}'s real bytecode: yaw 0=SOUTH, 90=WEST, 180=NORTH, -90=EAST).</li>
 * </ul>
 *
 * <p>{@code BlockDestruction}/{@code AssumeBestArmor}/{@code SelfExtrapolate} are honored via
 * {@link DamageUtils}'s new params rather than Kallean's separate {@code ExplosionTrace}/
 * protection-enchant modeling — same documented simplification {@code DamageUtils} already had
 * (no protection-enchant reduction, client-side armor/toughness only).
 */
public class PistonCrystal extends AddonModule {
    public static final PistonCrystal INSTANCE = new PistonCrystal();

    public enum Sort { CloseAngle, LowestDistance, LowestHealth }

    // ── Damage ───────────────────────────────────────────────────────────────
    public final SliderOption minDamage = new SliderOption(this, "MinDamage",
            "Minimum damage to attempt a sequence.", 6.0, 1.0, 20.0, 0.5);
    public final SliderOption maxLocalDamage = new SliderOption(this, "MaxLocalDamage",
            "Max self-damage allowed.", 12.0, 1.0, 20.0, 0.5);

    // ── Delay ────────────────────────────────────────────────────────────────
    public final SliderOption delay = new SliderOption(this, "Delay",
            "Delay between place actions (ticks). 0 = every tick.", 0.0, 0.0, 10.0, 1.0);

    // ── Sort / Target ────────────────────────────────────────────────────────
    public final ModeOption<Sort> sort = new ModeOption<>(this, "Sort", "How to pick target.", Sort.CloseAngle);
    public final SliderOption targetRange = new SliderOption(this, "TargetRange",
            "Max target range.", 6.0, 1.0, 20.0, 0.5);
    public final ToggleOption inAirTarget = new ToggleOption(this, "InAirTarget",
            "Target players in the air, not just grounded ones.", true);
    public final ToggleOption ignoreFriends = new ToggleOption(this, "IgnoreFriends",
            "Never target friends.", true);

    // ── Break / Place range ─────────────────────────────────────────────────
    public final ToggleOption breakCrystal = new ToggleOption(this, "BreakCrystal",
            "Attack the crystal right after placing it.", true);
    public final SliderOption placeRange = new SliderOption(this, "PlaceRange",
            "Range to place piston/redstone/crystal.", 4.5, 0.1, 6.0, 0.1);
    public final SliderOption breakRange = new SliderOption(this, "BreakRange",
            "Range to attack crystals.", 4.5, 0.1, 6.0, 0.1);

    // ── Rotate ───────────────────────────────────────────────────────────────
    public final ToggleOption rotate = new ToggleOption(this, "Rotate",
            "Silently rotate to placed blocks / attacked crystals.", true);
    public final ModeOption<InteractionMode> placeMode = new ModeOption<>(this, "PlaceMode",
            "Bypass mode used for placement.", InteractionMode.Grim);
    public final ToggleOption grimYawDeceive = new ToggleOption(this, "GrimYawDeceive",
            "Forces the server-perceived look direction so the piston's FACING resolves to the "
                    + "intended push direction, independent of which real face got clicked -- "
                    + "PistonBaseBlock derives FACING purely from the player's rotation at "
                    + "placement time (verified via javap), never from the clicked face.", true);
    public final ToggleOption snapBack = new ToggleOption(this, "SnapBack",
            "Send an extra look packet to restore the real rotation immediately after each action.", false);

    // ── Swap ─────────────────────────────────────────────────────────────────
    public final ModeOption<SwapType> swapAction = new ModeOption<>(this, "SwapAction",
            "How to swap to required items.", SwapType.Silent);

    // ── Misc ─────────────────────────────────────────────────────────────────
    public final ToggleOption airPlace = new ToggleOption(this, "AirPlace",
            "Allow placing piston/redstone when not on ground.", false);
    public final ToggleOption safety = new ToggleOption(this, "Safety",
            "Skip a sequence if the crystal would kill self.", true);
    public final ToggleOption blockDestruction = new ToggleOption(this, "BlockDestruction",
            "Assume intervening blocks get destroyed by the blast -- don't count them as damage blockers.", false);
    public final ToggleOption selfExtrapolate = new ToggleOption(this, "SelfExtrapolate",
            "Extrapolate self position (using ExtrapolationTicks too) for the self-damage estimate.", false);
    public final SliderOption extrapolationTicks = new SliderOption(this, "ExtrapolationTicks",
            "Ticks to extrapolate the enemy's (and, if SelfExtrapolate, self's) position ahead.", 0.0, 0.0, 10.0, 1.0);
    public final ToggleOption assumeBestArmor = new ToggleOption(this, "AssumeBestArmor",
            "Assume the target is wearing full netherite armor for the damage estimate.", false);

    // ── State ────────────────────────────────────────────────────────────────
    private BlockPos pistonPos;
    private BlockPos redstonePos;
    private BlockPos crystalPos;
    private BlockPos lastPiston;
    private Direction face;
    private boolean usingTorch;
    private Player target;
    private long lastActionMs = 0;
    private long breakTimerMs = 0;
    private int preSwapSlot = -1;
    // 2026-07-19 fix ("đặt thừa crystal khi delay<=2", still reproduced after the first
    // resetState()-based attempt): resetState() right after queueing a crystal placement
    // didn't help because the NEXT cycle just re-searches from scratch and finds the exact
    // same spot again -- the world hasn't actually changed yet (the crystal is an ENTITY, its
    // spawn packet hasn't round-tripped back from the server, canPlaceCrystal still reads the
    // spot as empty). A short delay setting means the next cycle runs before that round-trip
    // completes, so a NEW crystal gets queued at the same spot every cycle until the real
    // entity finally shows up client-side -- confirmed on video, dozens stacked. Real fix:
    // explicitly WAIT for confirmation (or a safety timeout) after queueing a crystal, instead
    // of trusting client state as ground truth the instant after firing.
    private BlockPos pendingCrystalPos = null;
    private long pendingCrystalSinceMs = 0;
    private static final long PENDING_CRYSTAL_TIMEOUT_MS = 750;

    public PistonCrystal() {
        super("PistonCrystal", "Places piston + redstone to push a crystal into targets.");
    }

    @Override
    public void onEnable() {
        resetState();
        breakTimerMs = 0;
        pendingCrystalPos = null;
    }

    @Override
    public void onDisable() {
        resetState();
        pendingCrystalPos = null;
    }

    // ── Main loop ────────────────────────────────────────────────────────────

    @EventHandler
    private void onInteract(EventInteract event) {
        if (event.getMode() != placeMode.getValue()) return;
        try {
            runCycle();
        } catch (Exception e) {
            dev.boze.api.utility.ChatHelper.sendMsg("PistonCrystal", "onInteract exception: " + e);
        }
    }

    private void runCycle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.player.isUsingItem()) return;

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastActionMs < delay.getValue() * 50L) return;

        // A crystal placement is still awaiting confirmation (see pendingCrystalPos's field
        // doc) -- do nothing until the entity actually shows up (or the safety timeout
        // expires), instead of re-searching/re-placing on stale client state every cycle.
        if (pendingCrystalPos != null) {
            if (canPlaceCrystal(mc, pendingCrystalPos) && nowMs - pendingCrystalSinceMs < PENDING_CRYSTAL_TIMEOUT_MS) {
                return;
            }
            pendingCrystalPos = null;
        }

        int crystalSlot = InvHelper.find(Items.END_CRYSTAL);
        int pistonSlot = InvHelper.find(Blocks.PISTON, Blocks.STICKY_PISTON);
        int redstoneSlot = findRedstoneSlot();

        if (crystalSlot == -1 || pistonSlot == -1 || redstoneSlot == -1) {
            resetState();
            return;
        }

        target = pickTarget(mc);
        if (target == null) return;

        // Validate existing positions -- terrain can change under a stale search result.
        if (crystalPos != null && redstonePos != null && pistonPos != null) {
            if (!canPlaceCrystal(mc, crystalPos) || !canPlace(mc, redstonePos) || !canPlace(mc, pistonPos)) {
                resetState();
            }
        }

        // Search if nothing found yet -- fall through so a spot found this tick places immediately.
        if (pistonPos == null && crystalPos == null && redstonePos == null) {
            doPistonCrystal(mc, target);
        }

        // Mine the old piston once it's extended (matches Kallean's LeavesHack-style tidy-up).
        if (lastPiston != null && face != null) {
            BlockState headState = mc.level.getBlockState(lastPiston.relative(face.getOpposite()));
            BlockState baseState = mc.level.getBlockState(lastPiston);
            if (headState.getBlock() instanceof PistonHeadBlock && baseState.getBlock() instanceof PistonBaseBlock) {
                BreakHelper.breakBlock(lastPiston);
                resetState();
                return;
            }
        }

        // Place piston.
        if (pistonPos != null && canPlace(mc, pistonPos)) {
            BlockHitResult hit = findPlaceHit(mc, pistonPos, null);
            if (hit != null) {
                placeBlockAt(mc, pistonSlot, hit, face);
                lastPiston = pistonPos;
            }
        }

        // Place redstone.
        if (redstonePos != null && canPlace(mc, redstonePos)) {
            if (usingTorch) {
                BlockHitResult hit = findPlaceHit(mc, redstonePos, d -> d != Direction.UP
                        && !(mc.level.getBlockState(redstonePos.relative(d)).getBlock() instanceof PistonBaseBlock));
                if (hit != null) placeBlockAt(mc, redstoneSlot, hit, null);
            } else {
                BlockHitResult hit = findPlaceHit(mc, redstonePos, null);
                if (hit != null) placeBlockAt(mc, redstoneSlot, hit, null);
            }
        }

        // Place crystal.
        if (crystalPos != null && canPlaceCrystal(mc, crystalPos)) {
            BlockPos base = crystalPos.below();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false);
            placeBlockAt(mc, crystalSlot, hit, null);
            lastActionMs = nowMs;
            breakTimerMs = nowMs;
            // See pendingCrystalPos's field doc -- wait for confirmation before this exact
            // spot can be searched/placed at again.
            pendingCrystalPos = crystalPos;
            pendingCrystalSinceMs = nowMs;
            resetState();
            return;
        }

        // Break crystal after delay.
        if (breakCrystal.getValue() && nowMs - breakTimerMs >= delay.getValue() * 50L) {
            for (int yOff = -1; yOff <= 2; yOff++) {
                BlockPos check = target.blockPosition().above(yOff);
                if (findCrystalAt(mc, check) != null) {
                    attackCrystalNear(mc, check);
                    lastActionMs = nowMs;
                    breakTimerMs = nowMs;
                    resetState();
                    break;
                }
            }
        }
    }

    // ── Search (Kallean's LeavesHack-derived logic) ─────────────────────────

    private void doPistonCrystal(Minecraft mc, Player tgt) {
        int ticks = extrapolationTicks.getValue().intValue();
        Vec3 vel = tgt.getDeltaMovement();
        double ex = tgt.getX() + vel.x * ticks;
        double ey = tgt.getY() + vel.y * ticks;
        double ez = tgt.getZ() + vel.z * ticks;
        BlockPos base = BlockPos.containing(ex, ey, ez);

        for (int h : new int[]{0, 1, 2}) {
            BlockPos standPos = base.above(h);
            Vec3 vec = new Vec3(standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5);

            float dmg = DamageUtils.estimateHpLoss(vec, tgt, ticks, DamageUtils.CRYSTAL_EXPLOSION_RADIUS,
                    blockDestruction.getValue(), assumeBestArmor.getValue());
            // 2026-07-19 fix ("bật BlockDestruction thì PistonCrystal hoàn toàn tịt"): applying
            // ignoreTerrain to the SELF estimate too (Kallean's own doPistonCrystal does this,
            // same flag both calls) meant getExposure's obsidian/bedrock-tier occlusion (see
            // DamageUtils' blastBlocked -- normally the ONLY thing that already counts as a
            // blocker) got skipped for self as well, spiking selfDmg since the search always
            // stands close to its own piston-crystal setup. That obsidian is the literal
            // support block this whole setup requires -- it doesn't get "destroyed" by the
            // crystal in any scenario this search considers, so BlockDestruction should only
            // make the TARGET more damageable (thin/breakable terrain between crystal and
            // target), never discount self's real, always-present obsidian cover. With that
            // discount removed, selfDmg almost always exceeded maxLocalDamage -- every
            // (height, direction) combination failed the check below, so trySearch() was never
            // even attempted (the observed "no response" symptom).
            float selfDmg = DamageUtils.estimateHpLoss(vec, mc.player,
                    selfExtrapolate.getValue() ? ticks : 0, DamageUtils.CRYSTAL_EXPLOSION_RADIUS,
                    false, assumeBestArmor.getValue());

            if (dmg <= minDamage.getValue() || selfDmg > maxLocalDamage.getValue()) continue;
            if (safety.getValue() && selfDmg >= mc.player.getHealth() + mc.player.getAbsorptionAmount()) continue;

            // Selftrap with an exposed foot gap: try pushing through the gap first.
            Direction footGapDir = h == 0 ? detectFootGapDirection(mc, tgt) : null;
            if (trySearch(mc, base, h, footGapDir)) return;
        }
    }

    /**
     * Target boxed in (head level fully solid on all 4 sides) but with exactly one open
     * horizontal neighbor at foot level -- the only way to reach them is through that gap.
     */
    private Direction detectFootGapDirection(Minecraft mc, Player tgt) {
        BlockPos foot = tgt.blockPosition();
        BlockPos head = foot.above();
        Direction openDir = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (mc.level.getBlockState(head.relative(d)).canBeReplaced()) return null;
            if (mc.level.getBlockState(foot.relative(d)).canBeReplaced()) {
                if (openDir != null) return null; // more than one gap -- not this pattern
                openDir = d;
            }
        }
        return openDir;
    }

    /** Direction order only follows camera when we can't force rotation (see GrimYawDeceive). */
    private boolean trySearch(Minecraft mc, BlockPos base, int height, Direction priorityDir) {
        List<Direction> dirs = new ArrayList<>();
        if (priorityDir != null) dirs.add(priorityDir);
        boolean canForceFacing = rotate.getValue() && grimYawDeceive.getValue();
        if (canForceFacing) {
            for (Direction d : Direction.Plane.HORIZONTAL) if (d != priorityDir) dirs.add(d);
        } else {
            Direction facing = Direction.fromYRot(mc.player.getYRot());
            if (facing != priorityDir) dirs.add(facing);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (d != facing && d != priorityDir) dirs.add(d);
            }
        }

        for (Direction dir : dirs) {
            BlockPos crystalStand = base.above(height);

            BlockPos temp1 = crystalStand.relative(dir);
            if (!canPlaceCrystal(mc, temp1)) continue;
            if (!inRange(mc, temp1, placeRange.getValue())) continue;

            int perpX = dir.getStepZ();
            int perpZ = dir.getStepX();

            BlockPos[] pistonCandidates = {
                temp1.relative(dir, 2),
                temp1.relative(dir, 2).above(),
                temp1.relative(dir, 2).offset(perpX, 0, perpZ),
                temp1.relative(dir, 2).offset(-perpX, 0, -perpZ),
                temp1.relative(dir, 2).offset(perpX, 1, perpZ),
                temp1.relative(dir, 2).offset(-perpX, 1, -perpZ),
                temp1.relative(dir, 3),
                temp1.relative(dir, 3).above(),
                temp1.relative(dir, 3).offset(perpX, 0, perpZ),
                temp1.relative(dir, 3).offset(-perpX, 0, -perpZ),
                temp1.relative(dir, 3).offset(perpX, 1, perpZ),
                temp1.relative(dir, 3).offset(-perpX, 1, -perpZ),
            };

            for (BlockPos pistonCandidate : pistonCandidates) {
                boolean alreadyPiston = mc.level.getBlockState(pistonCandidate).getBlock() instanceof PistonBaseBlock;
                if (!alreadyPiston && !canPlace(mc, pistonCandidate)) continue;
                if (!inRange(mc, pistonCandidate, placeRange.getValue())) continue;

                // Block the arm would extend into must be free.
                BlockPos extCheck = pistonCandidate.relative(dir.getOpposite());
                BlockState extState = mc.level.getBlockState(extCheck);
                if (!WorldHelper.isAir(extCheck) && !extState.canBeReplaced()
                        && !(extState.getBlock() instanceof PistonBaseBlock)) continue;

                BlockPos tempRedstone = null;
                for (Direction dir3 : Direction.values()) {
                    if (dir3 == dir.getOpposite()) continue;
                    BlockPos rPos = pistonCandidate.relative(dir3);
                    if (rPos.equals(temp1)) continue;

                    Block rBlock = mc.level.getBlockState(rPos).getBlock();
                    if (rBlock == Blocks.REDSTONE_BLOCK || rBlock instanceof net.minecraft.world.level.block.RedstoneTorchBlock) {
                        tempRedstone = rPos;
                        break;
                    }
                    if (!canPlace(mc, rPos)) continue;
                    if (!inRange(mc, rPos, placeRange.getValue())) continue;
                    tempRedstone = rPos;
                    break;
                }

                if (tempRedstone == null) continue;

                face = dir;
                crystalPos = temp1;
                pistonPos = pistonCandidate;
                redstonePos = tempRedstone;
                return true;
            }
        }
        return false;
    }

    // ── Placement / rotation ─────────────────────────────────────────────────

    /**
     * Finds a real, clickable neighbor face of {@code pos} (mirrors AntiPiston's
     * getValidHitResult: prefer a raycast-visible face, fall back to the first solid one).
     * {@code filter} additionally restricts which {@link Direction} may be used (torch placement
     * skips UP and any face already touching a piston, same as Kallean's placeTorch).
     */
    private BlockHitResult findPlaceHit(Minecraft mc, BlockPos pos, java.util.function.Predicate<Direction> filter) {
        Vec3 eyePos = mc.player.getEyePosition();
        BlockHitResult fallback = null;
        for (Direction dir : Direction.values()) {
            if (filter != null && !filter.test(dir)) continue;
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = mc.level.getBlockState(neighbor);
            if (neighborState.canBeReplaced() || !neighborState.getFluidState().isEmpty()) continue;
            Direction clickFace = dir.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(neighbor).add(
                    clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, clickFace, neighbor, false);
            var raycast = mc.level.clip(new net.minecraft.world.level.ClipContext(
                    eyePos, hitVec, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            if (raycast.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return hit;
            if (fallback == null) fallback = hit;
        }
        return fallback;
    }

    /**
     * Places {@code slot}'s item at {@code hit}, silently rotating first (unless Rotate is off).
     * 2026-07-19 rewrite: was routed through Boze's {@link Interaction}/{@link EventInteract}
     * system (BedAura's own pattern, which works fine for bed placement) -- reverted after
     * still reproducing "chỉ trúng khi quay sang trái" even after fixing trySearch's
     * camera-priority bug. Checked against TWO independent real reference clients (Mint's
     * PistonCrystalFeature.sendLook, LeavesHack's PistonCrystal.place -> Rotation.snapAt):
     * neither goes through any "interaction" abstraction for rotation delivery -- both send an
     * explicit {@code PlayerMoveC2SPacket.Full}-equivalent (WITH position, not rotation-only)
     * directly and immediately, right before the place packet, then restore local yaw/pitch
     * after. Mirrored here with the real Mojmap equivalent,
     * {@code ServerboundMovePlayerPacket.PosRot} -- bypasses whatever gap exists in Boze's
     * Interaction handling for a YawDeceive rotation that doesn't correlate with the real
     * clicked face (BedAura's dir.toYRot() usually roughly does; the piston's doesn't).
     * {@code pistonFacing} is non-null only for the piston itself: when set (and GrimYawDeceive
     * is on) the rotation sent is the YawDeceive override, not the real look-toward-hit angle
     * -- see the class doc for why PistonBaseBlock needs this.
     */
    private void placeBlockAt(Minecraft mc, int slot, BlockHitResult hit, Direction pistonFacing) {
        if (slot == -1) return;
        if (!rotate.getValue()) {
            InvHelper.swapToSlot(slot, swapAction.getValue());
            PlaceHelper.place(placeMode.getValue(), hit, InteractionHand.MAIN_HAND);
            InvHelper.swapBack();
            return;
        }

        float yaw, pitch;
        if (pistonFacing != null && grimYawDeceive.getValue()) {
            float[] angles = yawDeceiveAngles(pistonFacing);
            yaw = angles[0]; pitch = angles[1];
        } else {
            float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), hit.getLocation());
            yaw = rot[0]; pitch = rot[1];
        }

        float savedYaw = mc.player.getYRot();
        float savedPitch = mc.player.getXRot();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), yaw, pitch,
                mc.player.onGround(), mc.player.horizontalCollision));
        }
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        InvHelper.swapToSlot(slot, swapAction.getValue());
        PlaceHelper.place(placeMode.getValue(), hit, InteractionHand.MAIN_HAND);
        InvHelper.swapBack();

        mc.player.setYRot(savedYaw);
        mc.player.setXRot(savedPitch);
        mc.player.setYHeadRot(yaw);
        mc.player.setYBodyRot(yaw);
        if (snapBack.getValue() && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.PosRot(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), savedYaw, savedPitch,
                mc.player.onGround(), mc.player.horizontalCollision));
        }
    }

    /**
     * Look-direction -> yaw table for the piston YawDeceive override (see class doc). Identical
     * to Kallean's own table, cross-checked against {@code Direction.fromYRot}'s real bytecode
     * (BY_2D_DATA order: yaw 0=SOUTH, 90=WEST, 180=NORTH, -90=EAST) -- {@code dir} here is the
     * search direction (the module's {@code face} field), which IS the look direction that makes
     * {@code getNearestLookingDirection().getOpposite()} resolve to {@code dir.getOpposite()},
     * matching this search's own extCheck assumption (arm extends toward dir.getOpposite()).
     */
    private static float[] yawDeceiveAngles(Direction dir) {
        float yaw = switch (dir) {
            case EAST -> -90f;
            case WEST -> 90f;
            case NORTH -> 180f;
            default -> 0f; // SOUTH
        };
        return new float[]{yaw, 5f};
    }

    /**
     * Behavioral port of Kallean's SnapRotation for a single discrete action (see class doc for
     * why the literal packet-mutation approach doesn't apply here). Sends an explicit Rot sync
     * packet, temporarily sets the client's own yaw/pitch so {@code action} (and whatever
     * packets it sends) carry the target rotation, then restores immediately -- all within this
     * one synchronous call, same tick, before the frame renders, so (like AntiPiston's
     * attackCrystal) no camera snap is visible and no MixinEntity override is needed.
     */
    private void rotateAndRun(Minecraft mc, float yaw, float pitch, Runnable action) {
        float savedYaw = mc.player.getYRot();
        float savedPitch = mc.player.getXRot();

        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    yaw, pitch, mc.player.onGround(), mc.player.horizontalCollision));
        }
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);

        action.run();

        mc.player.setYRot(savedYaw);
        mc.player.setXRot(savedPitch);
        mc.player.setYHeadRot(yaw);
        mc.player.setYBodyRot(yaw);

        if (snapBack.getValue() && mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    savedYaw, savedPitch, mc.player.onGround(), mc.player.horizontalCollision));
        }
    }

    // ── Crystal attack ───────────────────────────────────────────────────────

    private EndCrystal findCrystalAt(Minecraft mc, BlockPos pos) {
        AABB box = new AABB(pos);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof EndCrystal c && c.isAlive() && c.getBoundingBox().intersects(box)) return c;
        }
        return null;
    }

    private void attackCrystalNear(Minecraft mc, BlockPos pos) {
        AABB box = new AABB(pos).inflate(1.0);
        double brSq = breakRange.getValue() * breakRange.getValue();
        EndCrystal crystal = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EndCrystal c) || !c.isAlive() || !c.getBoundingBox().intersects(box)) continue;
            double d = mc.player.distanceToSqr(c);
            if (d > brSq || d >= bestDist) continue;
            bestDist = d;
            crystal = c;
        }
        if (crystal == null) return;
        final EndCrystal crystalTarget = crystal;

        Vec3 aim = MathHelper.getBestAimPoint(crystalTarget.getBoundingBox());
        Runnable attack = () -> {
            mc.gameMode.attack(mc.player, crystalTarget);
            mc.player.swing(InteractionHand.MAIN_HAND);
        };
        if (rotate.getValue()) {
            float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), aim);
            rotateAndRun(mc, rot[0], rot[1], attack);
        } else {
            attack.run();
        }
    }

    // ── Placement validation ─────────────────────────────────────────────────

    private boolean canPlace(Minecraft mc, BlockPos pos) {
        if (!WorldHelper.isInWorldBounds(pos) || !WorldHelper.isRegionLoaded(pos)) return false;
        if (!WorldHelper.isReplaceable(pos)) return false;
        // No floor and AirPlace is off -- same gate BedAura's own search uses.
        if (!airPlace.getValue() && WorldHelper.isReplaceable(pos.below())) return false;
        if (!WorldHelper.canPlaceAt(pos)) return false;
        return PlaceHelper.isEmpty(pos);
    }

    /** Crystal needs a real obsidian/bedrock base -- AirPlace never applies to it (matches Kallean). */
    private boolean canPlaceCrystal(Minecraft mc, BlockPos pos) {
        if (!WorldHelper.isAir(pos)) return false;
        BlockPos basePos = pos.below();
        Block base = WorldHelper.getBlock(basePos);
        if (base != Blocks.OBSIDIAN && base != Blocks.BEDROCK) return false;
        if (!WorldHelper.isAir(pos.above())) return false;
        return PlaceHelper.isEmpty(pos) && PlaceHelper.isEmpty(pos.above());
    }

    private boolean inRange(Minecraft mc, BlockPos pos, double r) {
        return mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= r * r;
    }

    // ── Target ────────────────────────────────────────────────────────────────

    private Player pickTarget(Minecraft mc) {
        double r = targetRange.getValue();
        List<Player> found = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Player p) || p == mc.player) continue;
            if (!p.isAlive() || p.getHealth() <= 0) continue;
            if (mc.player.distanceToSqr(p) > r * r) continue;
            if (ignoreFriends.getValue() && FriendManager.isFriend(p.getName().getString())) continue;
            if (!inAirTarget.getValue() && !p.onGround()) continue;
            found.add(p);
        }
        if (found.isEmpty()) return null;
        found.sort(getComparator(mc));
        return found.get(0);
    }

    private Comparator<Player> getComparator(Minecraft mc) {
        return switch (sort.getValue()) {
            case LowestHealth -> Comparator.comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount());
            case LowestDistance, CloseAngle -> Comparator.comparingDouble(mc.player::distanceToSqr);
        };
    }

    // ── Find items ────────────────────────────────────────────────────────────

    private int findRedstoneSlot() {
        int slot = InvHelper.find(Items.REDSTONE_BLOCK);
        if (slot != -1) { usingTorch = false; return slot; }
        slot = InvHelper.find(Items.REDSTONE_TORCH);
        if (slot != -1) { usingTorch = true; return slot; }
        return -1;
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private void resetState() {
        pistonPos = null;
        redstonePos = null;
        crystalPos = null;
        lastPiston = null;
        face = null;
        usingTorch = false;
    }
}
