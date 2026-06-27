package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Anti-Mace: intercepts enemy mace dives by placing blocks in their predicted path.
 *
 * Strategy (fast, no delay):
 *   1. Detect a diving mace user (airborne, above player, velocity.y < threshold, horizontal dist < range).
 *   2. Simulate their trajectory with Minecraft physics (gravity 0.04, air-drag 0.91/0.98).
 *   3. Build queue (in order of priority):
 *        a. Y+2 and Y+3 directly above player — guaranteed immediate intercept.
 *        b. Predicted trajectory blocks from player upward — early intercept.
 *        c. 2×2 cap centered on impact X/Z at Y+3 — lateral coverage.
 *   4. Place blocksPerTick blocks per tick, no delay.
 */
public class AntiMace extends AddonModule {
    public static final AntiMace INSTANCE = new AntiMace();

    public enum SwapMode { Normal, Alt, Silent }

    public final ModeOption<SwapMode> swapMode = new ModeOption<>(this, "Swap Mode",
            "", SwapMode.Alt);
    // How many blocks to place per game tick (higher = faster, more packet spam)
    public final SliderOption blocksPerTick = new SliderOption(this, "Blocks/Tick",
            "Blocks placed per tick (higher = faster).", 3.0, 1.0, 6.0, 1.0);
    public final SliderOption detectRange = new SliderOption(this, "DetectRange",
            "Horizontal range to detect diving enemy.", 24.0, 5.0, 40.0, 0.5);
    public final SliderOption predictTicks = new SliderOption(this, "PredictTicks",
            "Physics simulation steps for trajectory prediction.", 12.0, 2.0, 30.0, 1.0);
    public final ToggleOption autoTrigger = new ToggleOption(this, "AutoTrigger",
            "Auto trigger when enemy with Mace is detected above.", true);

    private final List<BlockPos> buildQueue = new ArrayList<>();
    private int  buildIndex    = 0;
    private boolean building   = false;
    private long lastBuildEndMs = 0; // cooldown: don't re-trigger immediately after finishing

    public AntiMace() {
        super("AntiMace", "Places blocks in the mace enemy's dive path.");
    }

    @Override public void onEnable()  { reset(); if (!autoTrigger.getValue()) initBuild(Minecraft.getInstance(), null); }
    @Override public void onDisable() { reset(); }

    private void reset() { buildQueue.clear(); buildIndex = 0; building = false; lastBuildEndMs = 0; }

    // ── Build queue construction ──────────────────────────────────────────────

    private void initBuild(Minecraft mc, AbstractClientPlayer threat) {
        if (building || mc.player == null) return;
        // 1-second cooldown between builds so the module doesn't spam-rebuild every tick
        // after the previous build completes while the threat is still present.
        if (System.currentTimeMillis() - lastBuildEndMs < 1000) return;
        buildQueue.clear();
        buildIndex = 0;

        BlockPos feet  = mc.player.blockPosition();
        int      baseY = feet.getY();

        // --- Priority 1: directly above player (Y+2, Y+3) — fastest guaranteed intercept ---
        addIfReplaceable(mc, feet.above(2));
        addIfReplaceable(mc, feet.above(3));

        // --- Priority 2: predicted trajectory from player.Y up to enemy ---
        if (threat != null) {
            Vec3 pos = new Vec3(threat.getX(), threat.getY(), threat.getZ());
            Vec3 vel = threat.getDeltaMovement();
            int   n   = predictTicks.getValue().intValue();

            List<BlockPos> path = new ArrayList<>();
            for (int t = 1; t <= n; t++) {
                // Minecraft air physics: gravity 0.04, vertical drag 0.98, horizontal drag 0.91
                vel = new Vec3(vel.x * 0.91, (vel.y - 0.04) * 0.98, vel.z * 0.91);
                pos = pos.add(vel);

                int by = (int) Math.floor(pos.y);
                if (by < baseY + 1) break;    // reached player level, stop
                if (by > baseY + 20) continue; // too high to be useful

                path.add(new BlockPos((int) Math.floor(pos.x), by, (int) Math.floor(pos.z)));
            }
            // Add from lowest Y first so the blocks closest to player are placed first.
            path.sort((a, b) -> a.getY() - b.getY());
            for (BlockPos p : path) addIfReplaceable(mc, p);

            // --- Priority 3: 2×2 cap at Y+3 centered on predicted impact X/Z ---
            // Find the trajectory tick where Y crosses player.Y+3
            Vec3 impactPos = projectToY(threat, baseY + 3);
            if (impactPos != null) {
                int ix = (int) Math.floor(impactPos.x);
                int iz = (int) Math.floor(impactPos.z);
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        addIfReplaceable(mc, new BlockPos(ix + dx, baseY + 3, iz + dz));
                        addIfReplaceable(mc, new BlockPos(ix + dx, baseY + 4, iz + dz));
                    }
                }
            }
        }

        // --- Fallback: column Y+4 to Y+6 directly above player ---
        for (int dy = 4; dy <= 6; dy++) addIfReplaceable(mc, feet.above(dy));

        if (!buildQueue.isEmpty()) {
            building = true;
        }
    }

    /** Returns the predicted X/Z position of the entity when it crosses targetY, or null. */
    private Vec3 projectToY(AbstractClientPlayer e, int targetY) {
        Vec3 pos = new Vec3(e.getX(), e.getY(), e.getZ());
        Vec3 vel = e.getDeltaMovement();
        for (int t = 1; t <= 40; t++) {
            vel = new Vec3(vel.x * 0.91, (vel.y - 0.04) * 0.98, vel.z * 0.91);
            pos = pos.add(vel);
            if (pos.y <= targetY) return pos;
        }
        return null;
    }

    private void addIfReplaceable(Minecraft mc, BlockPos p) {
        if (mc.level.getBlockState(p).canBeReplaced() && !buildQueue.contains(p)) {
            buildQueue.add(p);
        }
    }

    // ── Tick handler ─────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Auto trigger: find closest diving mace threat
        if (autoTrigger.getValue() && !building) {
            AbstractClientPlayer threat = findThreat(mc);
            if (threat != null) initBuild(mc, threat);
        }

        if (!building) return;

        int placed = 0;
        int max    = blocksPerTick.getValue().intValue();

        while (buildIndex < buildQueue.size() && placed < max) {
            BlockPos target = buildQueue.get(buildIndex++);

            // Skip if already occupied
            if (!mc.level.getBlockState(target).canBeReplaced()) continue;

            int slot = swapMode.getValue() == SwapMode.Alt
                    ? InvHelper.find(Items.OBSIDIAN)
                    : InvHelper.findInHotbar(Items.OBSIDIAN);
            if (slot == -1) {
                ChatHelper.sendMsg("AntiMace", "§cNo obsidian!");
                building = false;
                return;
            }

            BlockHitResult hit = getHitResult(mc, target);
            if (hit == null) continue; // no solid neighbour yet, skip this position

            InvHelper.swapToSlot(slot, getSwapType());
            PlaceHelper.place(InteractionMode.NCP, hit, InteractionHand.MAIN_HAND);
            InvHelper.swapBack();
            placed++;
        }

        if (buildIndex >= buildQueue.size()) {
            building = false;
            lastBuildEndMs = System.currentTimeMillis();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AbstractClientPlayer findThreat(Minecraft mc) {
        AbstractClientPlayer best    = null;
        double                    bestDist = Double.MAX_VALUE;

        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            if (p.onGround()) continue;
            if (p.getY() <= mc.player.getY() + 2) continue;  // not above us
            if (p.getDeltaMovement().y >= -0.05) continue;         // not diving down
            double hDist = hDist(mc.player, p);
            if (hDist > detectRange.getValue()) continue;
            if (p.getMainHandItem().getItem() != Items.MACE && p.getOffhandItem().getItem() != Items.MACE) continue;
            if (hDist < bestDist) { bestDist = hDist; best = p; }
        }
        return best;
    }

    private static double hDist(net.minecraft.world.entity.Entity a, net.minecraft.world.entity.Entity b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private SwapType getSwapType() {
        return switch (swapMode.getValue()) {
            case Normal -> SwapType.Normal;
            case Silent -> SwapType.Silent;
            default     -> SwapType.Alt;
        };
    }

    /**
     * Returns a BlockHitResult that clicks on a solid neighbour of {@code target}
     * so PlaceHelper can place a block into the target position.
     * Returns null if all six neighbours are also replaceable (can't place yet).
     */
    private BlockHitResult getHitResult(Minecraft mc, BlockPos target) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = target.relative(dir);
            if (!mc.level.getBlockState(neighbour).canBeReplaced()) {
                Direction face = dir.getOpposite();
                Vec3 hitVec = Vec3.atCenterOf(neighbour).add(
                        face.getStepX() * 0.5,
                        face.getStepY() * 0.5,
                        face.getStepZ() * 0.5);
                return new BlockHitResult(hitVec, face, neighbour, false);
            }
        }
        return null;
    }
}
