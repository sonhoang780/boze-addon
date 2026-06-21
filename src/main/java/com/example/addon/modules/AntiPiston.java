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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

public class AntiPiston extends AddonModule {
    public static final AntiPiston INSTANCE = new AntiPiston();
    public boolean active = false;

    public final ModeOption<InteractionMode> placeMode = new ModeOption<>(this, "Place Mode",
            "Bypass mode for placing blocks.", InteractionMode.Grim);
    public final SliderOption placeRange = new SliderOption(this, "Place Range",
            "Max range to place obsidian.", 4.5, 1.0, 6.0, 0.1);
    public final SliderOption crystalRange = new SliderOption(this, "Crystal Range",
            "Max range to attack crystals.", 4.5, 1.0, 6.0, 0.1);
    // Default 0: attack every tick. Raise only if server kicks for packet spam.
    public final SliderOption attackDelay = new SliderOption(this, "Attack Delay",
            "Min ms between crystal attacks (0 = every tick).", 0.0, 0.0, 500.0, 1.0);
    public final ToggleOption antiWeakness = new ToggleOption(this, "Anti Weakness",
            "Swap to sword when under Weakness effect.", true);
    // Surround: fill 4 adjacent foot blocks so crystals can't be placed/pushed there.
    public final ToggleOption surround = new ToggleOption(this, "Surround",
            "Fill adjacent foot positions with obsidian each tick.", false);

    private volatile Vec3d rotateTarget = null;
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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        if (event.packet instanceof EntitySpawnS2CPacket spawnPkt) {
            if (spawnPkt.getEntityType() != EntityType.END_CRYSTAL) return;
            Vec3d crystalPos = new Vec3d(spawnPkt.getX(), spawnPkt.getY(), spawnPkt.getZ());
            if (mc.player.squaredDistanceTo(crystalPos) > sq(crystalRange.getValue())) return;

            rotateTarget = crystalPos;
            // Entity is added to world before this event fires — try immediate attack.
            Entity e = mc.world.getEntityById(spawnPkt.getEntityId());
            if (e instanceof EndCrystalEntity crystal) {
                attackCrystal(mc, crystal);
                lastAttackTime = System.currentTimeMillis();
            } else {
                // Not in world yet — queue for attack at start of next tick.
                synchronized (pendingCrystalIds) { pendingCrystalIds.add(spawnPkt.getEntityId()); }
            }
            return;
        }

        // Absolute position sync — sent when an entity is teleported or piston-pushed.
        // Intercept so we can attack a crystal the moment it moves into danger range,
        // before the next game tick where isDangerous checks would catch it.
        if (event.packet instanceof EntityPositionSyncS2CPacket syncPkt) {
            Entity e = mc.world.getEntityById(syncPkt.id());
            if (!(e instanceof EndCrystalEntity crystal)) return;
            Vec3d newPos = syncPkt.values().position();
            if (mc.player.squaredDistanceTo(newPos) <= sq(crystalRange.getValue())) {
                rotateTarget = newPos;
                attackCrystal(mc, crystal);
                lastAttackTime = System.currentTimeMillis();
            }
            return;
        }

        if (event.packet instanceof BlockUpdateS2CPacket pkt) {
            checkPistonPacket(mc, pkt.getPos(), pkt.getState());
            return;
        }

        if (event.packet instanceof ChunkDeltaUpdateS2CPacket pkt) {
            pkt.visitUpdates((pos, state) -> checkPistonPacket(mc, pos, state));
        }
    }

    /**
     * Called on every piston block-update packet.
     * Immediately attacks ALL crystals in range (any crystal near player + piston = threat)
     * and tries to block the push path with obsidian.
     */
    private void checkPistonPacket(MinecraftClient mc, BlockPos pos, BlockState state) {
        if (!(state.isOf(Blocks.PISTON) || state.isOf(Blocks.STICKY_PISTON))) return;
        if (mc.player.getBlockPos().getSquaredDistance(pos) > sq(placeRange.getValue())) return;

        long now = System.currentTimeMillis();
        // A piston appeared near us — attack every crystal in range immediately.
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity crystal)) continue;
            if (mc.player.distanceTo(crystal) > crystalRange.getValue()) continue;
            attackCrystal(mc, crystal);
            rotateTarget = new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ());
            lastAttackTime = now;
        }

        // Try to fill the push target so the piston has nothing to extend into.
        Direction facing = state.get(Properties.FACING);
        BlockPos pushTarget = pos.offset(facing);
        if (mc.world.getBlockState(pushTarget).isReplaceable()
                && mc.player.getBlockPos().getSquaredDistance(pushTarget) <= sq(placeRange.getValue())) {
            int obsSlot = InvHelper.find(Items.OBSIDIAN);
            if (obsSlot != -1) placeBlockSilently(mc, pushTarget, obsSlot);
        }
    }

    // ── Tick-level logic ──────────────────────────────────────────────────────

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();

        // Flush crystals queued from spawn packets that weren't in world yet.
        synchronized (pendingCrystalIds) {
            if (!pendingCrystalIds.isEmpty()) {
                for (int id : pendingCrystalIds) {
                    Entity e = mc.world.getEntityById(id);
                    if (e instanceof EndCrystalEntity crystal) {
                        attackCrystal(mc, crystal);
                        lastAttackTime = now;
                        rotateTarget = new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ());
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
        EndCrystalEntity normalTarget = null;
        double bestNormalDist = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            double dist = mc.player.distanceTo(crystal);
            if (dist > crystalRange.getValue()) continue;

            // A crystal inside a solid block = has been piston-pushed through a wall/ceiling.
            // Also treat any crystal within 3 blocks as critical.
            boolean insideBlock = !mc.world.isAir(crystal.getBlockPos());
            boolean critical     = dist <= 3.0 || insideBlock;

            if (critical) {
                // Attack every critical crystal this tick — don't limit to one.
                rotateTarget = new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ());
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

    private void doSurround(MinecraftClient mc) {
        int obsSlot = InvHelper.find(Items.OBSIDIAN);
        if (obsSlot == -1) return;
        BlockPos feet = mc.player.getBlockPos();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            // Lower tier (feet level) + upper tier (feet+1 = head level) — 2-block tall wall.
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = feet.offset(dir).up(dy);
                if (mc.world.getBlockState(pos).isReplaceable()) {
                    placeBlockSilently(mc, pos, obsSlot);
                }
            }
        }
    }

    private void doPistonScan(MinecraftClient mc) {
        BlockPos playerPos = mc.player.getBlockPos();
        int pRange = (int) Math.ceil(placeRange.getValue());
        outer:
        for (int x = -pRange; x <= pRange; x++) {
            for (int y = -pRange; y <= pRange; y++) {
                for (int z = -pRange; z <= pRange; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    BlockState state = mc.world.getBlockState(pos);
                    if (state.getBlock() != Blocks.PISTON && state.getBlock() != Blocks.STICKY_PISTON) continue;
                    Direction facing = state.get(Properties.FACING);
                    BlockPos pushTarget = pos.offset(facing);
                    if (mc.player.getBlockPos().getSquaredDistance(pushTarget) > sq(placeRange.getValue())) continue;
                    if (!mc.world.getBlockState(pushTarget).isReplaceable()) continue;
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
        Vec3d target = rotateTarget;
        if (target == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePos(), target);
        mc.player.setHeadYaw(rot[0]);
        mc.player.setBodyYaw(rot[0]);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Attacks a crystal with silent rotation — ElytraFix style:
     *  1. Send PlayerMoveC2SPacket.LookAndOnGround so the server registers the correct
     *     look direction for hit validation before the attack arrives.
     *  2. Temporarily override mc.player yaw/pitch (for any packet that reads them),
     *     attack, then IMMEDIATELY restore — no camera snap.
     *  3. Set headYaw/bodyYaw for the F5 visual head turn.
     */
    private void attackCrystal(MinecraftClient mc, EndCrystalEntity crystal) {
        Vec3d aim = MathHelper.getBestAimPoint(crystal.getBoundingBox());
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePos(), aim);
        float crystalYaw   = rot[0];
        float crystalPitch = rot[1];

        // Sync rotation with server before the attack packet arrives.
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                crystalYaw, crystalPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        }

        // Temporarily set client rotation so attack packet carries correct angles.
        float savedYaw   = mc.player.getYaw();
        float savedPitch = mc.player.getPitch();
        mc.player.setYaw(crystalYaw);
        mc.player.setPitch(crystalPitch);

        boolean hasWeakness = mc.player.hasStatusEffect(StatusEffects.WEAKNESS);
        boolean needSword   = antiWeakness.getValue() && hasWeakness;
        int     swordSlot   = needSword ? findSwordInHotbar(mc) : -1;
        if (needSword && swordSlot != -1) {
            InvHelper.swapToSlot(swordSlot, SwapType.Silent);
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvHelper.swapBack();
        } else {
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        // Restore camera — no visible snap.
        mc.player.setYaw(savedYaw);
        mc.player.setPitch(savedPitch);

        // Head turns toward crystal in F5 without camera moving.
        mc.player.setHeadYaw(crystalYaw);
        mc.player.setBodyYaw(crystalYaw);
        rotateTarget = aim;
    }

    private void placeBlockSilently(MinecraftClient mc, BlockPos pos, int slot) {
        BlockHitResult hit = getValidHitResult(mc, pos);
        if (hit == null) return;
        InvHelper.swapToSlot(slot, SwapType.Silent);
        PlaceHelper.place(placeMode.getValue(), hit, Hand.MAIN_HAND);
        InvHelper.swapBack();
    }

    private BlockHitResult getValidHitResult(MinecraftClient mc, BlockPos targetPos) {
        Vec3d eyePos = mc.player.getEyePos();
        BlockHitResult fallback = null;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = targetPos.offset(dir);
            BlockState neighborState = mc.world.getBlockState(neighbor);
            if (neighborState.isReplaceable() || !neighborState.getFluidState().isEmpty()) continue;
            Direction clickFace = dir.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(neighbor).add(
                    clickFace.getOffsetX() * 0.5,
                    clickFace.getOffsetY() * 0.5,
                    clickFace.getOffsetZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, clickFace, neighbor, false);
            var raycast = mc.world.raycast(new RaycastContext(
                    eyePos, hitVec,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    mc.player));
            if (raycast.getType() == HitResult.Type.MISS) return hit;
            if (fallback == null) fallback = hit;
        }
        return fallback;
    }

    private int findSwordInHotbar(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.NETHERITE_SWORD || item == Items.DIAMOND_SWORD
                    || item == Items.IRON_SWORD || item == Items.STONE_SWORD) return i;
        }
        return -1;
    }

    private static double sq(double v) { return v * v; }
}
