package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventRotate;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.MathHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayList;
import java.util.List;

public class AntiPiston extends AddonModule {
    public static final AntiPiston INSTANCE = new AntiPiston();
    public boolean active = false;

    public final ModeOption<InteractionMode> placeMode = new ModeOption<>(this, "Place Mode",
            "Bypass mode for placing blocks.", InteractionMode.Grim);
    public final SliderOption placeRange = new SliderOption(this, "PlaceRange",
            "Max range to place obsidian.", 4.5, 1.0, 6.0, 0.1);
    public final SliderOption crystalRange = new SliderOption(this, "CrystalRange",
            "Max range to attack crystals.", 4.5, 1.0, 6.0, 0.1);
    // Default 0: attack every tick. Raise only if server kicks for packet spam.
    public final SliderOption attackDelay = new SliderOption(this, "AttackDelay",
            "Min ms between crystal attacks (0 = every tick).", 0.0, 0.0, 500.0, 1.0);
    public final ToggleOption antiWeakness = new ToggleOption(this, "AntiWeakness",
            "Swap to sword when under Weakness effect.", true);
    // Surround: fill 4 adjacent foot blocks so crystals can't be placed/pushed there.
    public final ToggleOption surround = new ToggleOption(this, "Surround",
            "Fill adjacent foot positions with obsidian each tick.", false);

    private volatile Vec3 rotateTarget = null;
    private long lastAttackTime = 0;
    // Crystal entity IDs seen in spawn packets but not yet in world — attack next tick.
    private final List<Integer> pendingCrystalIds = new ArrayList<>();

    public AntiPiston() {
        super("AntiPiston", "Counters piston-crystal combos.");
    }

    @Override public void onEnable()  { active = true;  pendingCrystalIds.clear(); }
    @Override public void onDisable() { active = false; rotateTarget = null; pendingCrystalIds.clear(); }

    // ── Packet-level detection (runs on main thread, during packet dispatch) ───

    @EventHandler
    private void onPacketReceive(EventPacket.Receive event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (event.packet instanceof ClientboundAddEntityPacket spawnPkt) {
            if (spawnPkt.getType() != EntityType.END_CRYSTAL) return;
            Vec3 crystalPos = new Vec3(spawnPkt.getX(), spawnPkt.getY(), spawnPkt.getZ());
            if (mc.player.distanceToSqr(crystalPos) > sq(crystalRange.getValue())) return;

            rotateTarget = crystalPos;
            // Entity is added to world before this event fires — try immediate attack.
            Entity e = mc.level.getEntity(spawnPkt.getId());
            if (e instanceof EndCrystal crystal) {
                attackCrystal(mc, crystal);
                lastAttackTime = System.currentTimeMillis();
            } else {
                // Not in world yet — queue for attack at start of next tick.
                synchronized (pendingCrystalIds) { pendingCrystalIds.add(spawnPkt.getId()); }
            }
            return;
        }

        // Absolute position sync — sent when an entity is teleported or piston-pushed.
        // Intercept so we can attack a crystal the moment it moves into danger range,
        // before the next game tick where isDangerous checks would catch it.
        if (event.packet instanceof ClientboundEntityPositionSyncPacket syncPkt) {
            Entity e = mc.level.getEntity(syncPkt.id());
            if (!(e instanceof EndCrystal crystal)) return;
            Vec3 newPos = syncPkt.values().position();
            if (mc.player.distanceToSqr(newPos) <= sq(crystalRange.getValue())) {
                rotateTarget = newPos;
                attackCrystal(mc, crystal);
                lastAttackTime = System.currentTimeMillis();
            }
            return;
        }

        if (event.packet instanceof ClientboundBlockUpdatePacket pkt) {
            checkPistonPacket(mc, pkt.getPos(), pkt.getBlockState());
            return;
        }

        if (event.packet instanceof ClientboundSectionBlocksUpdatePacket pkt) {
            pkt.runUpdates((pos, state) -> checkPistonPacket(mc, pos, state));
        }
    }

    /**
     * Called on every piston block-update packet.
     * Immediately attacks ALL crystals in range (any crystal near player + piston = threat)
     * and tries to block the push path with obsidian.
     */
    private void checkPistonPacket(Minecraft mc, BlockPos pos, BlockState state) {
        if (!(state.getBlock() == Blocks.PISTON || state.getBlock() == Blocks.STICKY_PISTON)) return;
        if (mc.player.blockPosition().distSqr(pos) > sq(placeRange.getValue())) return;

        long now = System.currentTimeMillis();
        // A piston appeared near us — attack every crystal in range immediately.
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EndCrystal crystal)) continue;
            if (mc.player.distanceTo(crystal) > crystalRange.getValue()) continue;
            attackCrystal(mc, crystal);
            rotateTarget = new Vec3(crystal.getX(), crystal.getY(), crystal.getZ());
            lastAttackTime = now;
        }

        // Try to fill the push target so the piston has nothing to extend into.
        Direction facing = state.getValue(BlockStateProperties.FACING);
        BlockPos pushTarget = pos.relative(facing);
        if (mc.level.getBlockState(pushTarget).canBeReplaced()
                && mc.player.blockPosition().distSqr(pushTarget) <= sq(placeRange.getValue())) {
            int obsSlot = InvHelper.find(Items.OBSIDIAN);
            if (obsSlot != -1) placeBlockSilently(mc, pushTarget, obsSlot);
        }
    }

    // ── Tick-level logic ──────────────────────────────────────────────────────

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        long now = System.currentTimeMillis();

        // Flush crystals queued from spawn packets that weren't in world yet.
        synchronized (pendingCrystalIds) {
            if (!pendingCrystalIds.isEmpty()) {
                for (int id : pendingCrystalIds) {
                    Entity e = mc.level.getEntity(id);
                    if (e instanceof EndCrystal crystal) {
                        attackCrystal(mc, crystal);
                        lastAttackTime = now;
                        rotateTarget = new Vec3(crystal.getX(), crystal.getY(), crystal.getZ());
                    }
                }
                pendingCrystalIds.clear();
            }
        }

        // Scan all crystals in range.
        // Critical (dist ≤ 3 OR inside a solid block after piston push): attack ALL of them
        //   immediately, no delay — covers the "crystal pushed through ceiling obsidian" case.
        // Normal (farther, in air): attack only the nearest, respecting attackDelay.
        boolean canAttackNormal = now - lastAttackTime >= attackDelay.getValue();
        EndCrystal normalTarget = null;
        double bestNormalDist = Double.MAX_VALUE;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal)) continue;
            double dist = mc.player.distanceTo(crystal);
            if (dist > crystalRange.getValue()) continue;

            // A crystal inside a solid block = has been piston-pushed through a wall/ceiling.
            // Also treat any crystal within 3 blocks as critical.
            boolean insideBlock = !mc.level.isEmptyBlock(crystal.blockPosition());
            boolean critical     = dist <= 3.0 || insideBlock;

            if (critical) {
                // Attack every critical crystal this tick — don't limit to one.
                rotateTarget = new Vec3(crystal.getX(), crystal.getY(), crystal.getZ());
                attackCrystal(mc, crystal);
                lastAttackTime = now;
            } else if (dist < bestNormalDist) {
                bestNormalDist = dist;
                normalTarget = crystal;
            }
        }

        if (normalTarget != null && canAttackNormal) {
            rotateTarget = MathHelper.getBestAimPoint(normalTarget.getBoundingBox());
            attackCrystal(mc, normalTarget);
            lastAttackTime = now;
        }

        // Surround: fill 4 adjacent foot positions each tick.
        if (surround.getValue()) doSurround(mc);

        // Tick-based piston scan: fill push targets with obsidian.
        doPistonScan(mc);
    }

    private void doSurround(Minecraft mc) {
        int obsSlot = InvHelper.find(Items.OBSIDIAN);
        if (obsSlot == -1) return;
        BlockPos feet = mc.player.blockPosition();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            // Lower tier (feet level) + upper tier (feet+1 = head level) — 2-block tall wall.
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = feet.relative(dir).above(dy);
                if (mc.level.getBlockState(pos).canBeReplaced()) {
                    placeBlockSilently(mc, pos, obsSlot);
                }
            }
        }
    }

    private void doPistonScan(Minecraft mc) {
        BlockPos playerPos = mc.player.blockPosition();
        int pRange = (int) Math.ceil(placeRange.getValue());
        outer:
        for (int x = -pRange; x <= pRange; x++) {
            for (int y = -pRange; y <= pRange; y++) {
                for (int z = -pRange; z <= pRange; z++) {
                    BlockPos pos = playerPos.offset(x, y, z);
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.getBlock() != Blocks.PISTON && state.getBlock() != Blocks.STICKY_PISTON) continue;
                    Direction facing = state.getValue(BlockStateProperties.FACING);
                    BlockPos pushTarget = pos.relative(facing);
                    if (mc.player.blockPosition().distSqr(pushTarget) > sq(placeRange.getValue())) continue;
                    if (!mc.level.getBlockState(pushTarget).canBeReplaced()) continue;
                    int obsSlot = InvHelper.find(Items.OBSIDIAN);
                    if (obsSlot == -1) continue;
                    placeBlockSilently(mc, pushTarget, obsSlot);
                    break outer;
                }
            }
        }
    }

    // ── Rotation ──────────────────────────────────────────────────────────────

    // Visual-only: sets head/body yaw so the model points at the last attacked crystal
    // in F5 (third-person) without moving the camera. No event.rotate() call — the
    // server gets its rotation update from the explicit packet sent in attackCrystal().
    @EventHandler
    private void onRotate(EventRotate event) {
        Vec3 target = rotateTarget;
        if (target == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), target);
        mc.player.setYHeadRot(rot[0]);
        mc.player.setYBodyRot(rot[0]);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Attacks a crystal with silent rotation — ElytraFix style:
     *  1. Send ServerboundMovePlayerPacket.Rot so the server registers the correct
     *     look direction for hit validation before the attack arrives.
     *  2. Temporarily override mc.player yaw/pitch (for any packet that reads them),
     *     attack, then IMMEDIATELY restore — no camera snap.
     *  3. Set headYaw/bodyYaw for the F5 visual head turn.
     */
    private void attackCrystal(Minecraft mc, EndCrystal crystal) {
        Vec3 aim = MathHelper.getBestAimPoint(crystal.getBoundingBox());
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), aim);
        float crystalYaw   = rot[0];
        float crystalPitch = rot[1];

        // Sync rotation with server before the attack packet arrives.
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                crystalYaw, crystalPitch, mc.player.onGround(), mc.player.horizontalCollision));
        }

        // Temporarily set client rotation so attack packet carries correct angles.
        float savedYaw   = mc.player.getYRot();
        float savedPitch = mc.player.getXRot();
        mc.player.setYRot(crystalYaw);
        mc.player.setXRot(crystalPitch);

        boolean hasWeakness = mc.player.hasEffect(MobEffects.WEAKNESS);
        boolean needSword   = antiWeakness.getValue() && hasWeakness;
        int     swordSlot   = needSword ? findSwordInHotbar(mc) : -1;
        if (needSword && swordSlot != -1) {
            InvHelper.swapToSlot(swordSlot, SwapType.Silent);
            mc.gameMode.attack(mc.player, crystal);
            mc.player.swing(InteractionHand.MAIN_HAND);
            InvHelper.swapBack();
        } else {
            mc.gameMode.attack(mc.player, crystal);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        // Restore camera — no visible snap.
        mc.player.setYRot(savedYaw);
        mc.player.setXRot(savedPitch);

        // Head turns toward crystal in F5 without camera moving.
        mc.player.setYHeadRot(crystalYaw);
        mc.player.setYBodyRot(crystalYaw);
        rotateTarget = aim;
    }

    private void placeBlockSilently(Minecraft mc, BlockPos pos, int slot) {
        BlockHitResult hit = getValidHitResult(mc, pos);
        if (hit == null) return;
        InvHelper.swapToSlot(slot, SwapType.Silent);
        PlaceHelper.place(placeMode.getValue(), hit, InteractionHand.MAIN_HAND);
        InvHelper.swapBack();
    }

    private BlockHitResult getValidHitResult(Minecraft mc, BlockPos targetPos) {
        Vec3 eyePos = mc.player.getEyePosition();
        BlockHitResult fallback = null;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = targetPos.relative(dir);
            BlockState neighborState = mc.level.getBlockState(neighbor);
            if (neighborState.canBeReplaced() || !neighborState.getFluidState().isEmpty()) continue;
            Direction clickFace = dir.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(neighbor).add(
                    clickFace.getStepX() * 0.5,
                    clickFace.getStepY() * 0.5,
                    clickFace.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, clickFace, neighbor, false);
            var raycast = mc.level.clip(new ClipContext(
                    eyePos, hitVec,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    mc.player));
            if (raycast.getType() == HitResult.Type.MISS) return hit;
            if (fallback == null) fallback = hit;
        }
        return fallback;
    }

    private int findSwordInHotbar(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getItem(i).getItem();
            if (item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD
                    || item == Items.IRON_SWORD || item == Items.STONE_SWORD) return i;
        }
        return -1;
    }

    private static double sq(double v) { return v * v; }
}
