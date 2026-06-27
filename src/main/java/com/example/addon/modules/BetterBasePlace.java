package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.client.module.BaseModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.Option;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

public class BetterBasePlace extends AddonModule {
    public static final BetterBasePlace INSTANCE = new BetterBasePlace();

    public final SliderOption range = new SliderOption(this, "Range",
        "Scan radius for targets and placement positions (blocks).", 3.0, 1.0, 6.0, 0.5);

    public final SliderOption delay = new SliderOption(this, "Delay",
        "Milliseconds between placement attempts.", 200.0, 50.0, 500.0, 50.0);

    public final ToggleOption airPlace = new ToggleOption(this, "AirPlace",
        "Allow placing against air blocks (needed for most flat-ground placements).", true);

    public final ModeOption<InteractionMode> mode = new ModeOption<>(this, "Mode",
        "Anti-cheat interaction mode.", InteractionMode.NCP);

    private long lastPlaceMs = 0;

    public BetterBasePlace() {
        super("BetterBasePlace", "Auto-places obsidian around nearby targets for crystal PvP base setup.");
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPlaceMs < delay.getValue().longValue()) return;

        AbstractClientPlayer target = findTarget(mc);
        if (target == null) return;

        double minDmg = readACMinDamage();

        BlockPos best = findBestPosition(mc, target, minDmg);
        if (best == null) return;

        // Best position already has obsidian — nothing to do
        if (mc.level.getBlockState(best).is(Blocks.OBSIDIAN)) return;

        int hotbarSlot = InvHelper.findInHotbar(Blocks.OBSIDIAN);
        int invSlot    = (hotbarSlot == -1) ? InvHelper.find(Blocks.OBSIDIAN) : -1;
        if (hotbarSlot == -1 && invSlot == -1) return;

        BlockHitResult hitResult = PlaceHelper.cast(best, airPlace.getValue(), mode.getValue());
        if (hitResult == null) return;

        if (hotbarSlot != -1) {
            InvHelper.swapToSlot(hotbarSlot, SwapType.Silent);
        } else {
            InvHelper.swapToSlot(invSlot, SwapType.Alt);
        }

        PlaceHelper.place(mode.getValue(), hitResult, InteractionHand.MAIN_HAND);
        InvHelper.swapBack();
        lastPlaceMs = now;
    }

    private AbstractClientPlayer findTarget(Minecraft mc) {
        double r = range.getValue();
        AbstractClientPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            double dist = mc.player.distanceTo(p);
            if (dist > r) continue;
            if (dist < bestDist) { bestDist = dist; best = p; }
        }
        return best;
    }

    private BlockPos findBestPosition(Minecraft mc, AbstractClientPlayer target, double minDmg) {
        int r  = (int) Math.ceil(range.getValue());
        int tx = target.getBlockX();
        int ty = target.getBlockY();
        int tz = target.getBlockZ();

        BlockPos bestPos = null;
        double   bestDmg = -1;

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int dy = -1; dy <= 0; dy++) {
                    BlockPos pos = new BlockPos(tx + x, ty + dy, tz + z);

                    var state = mc.level.getBlockState(pos);
                    if (state.is(Blocks.BEDROCK)) continue;
                    if (!mc.level.getFluidState(pos).isEmpty()) continue;
                    if (!mc.level.getFluidState(pos.above()).isEmpty()) continue;
                    // Skip non-obsidian solid blocks (can't place there); allow obsidian (caller checks)
                    if (!state.is(Blocks.OBSIDIAN) && !PlaceHelper.isEmpty(pos)) continue;
                    // Skip if block below pos is already obsidian/bedrock — crystal can go there without us
                    var belowState = mc.level.getBlockState(pos.below());
                    if (belowState.is(Blocks.OBSIDIAN) || belowState.is(Blocks.BEDROCK)) continue;
                    // Skip if crystal spawn position (pos.above) overlaps target hitbox
                    if (crystalSpawnBlocksTarget(pos, target)) continue;

                    double dmg = calcCrystalDamage(pos, target);
                    if (dmg < minDmg) continue;

                    if (dmg > bestDmg) { bestDmg = dmg; bestPos = pos; }
                }
            }
        }
        return bestPos;
    }

    private boolean crystalSpawnBlocksTarget(BlockPos obsidianPos, AbstractClientPlayer target) {
        // Crystal entity spawns in the block directly above obsidian
        BlockPos crystal = obsidianPos.above();
        // Crystal block AABB
        double cx1 = crystal.getX(), cx2 = cx1 + 1.0;
        double cy1 = crystal.getY(), cy2 = cy1 + 1.0;
        double cz1 = crystal.getZ(), cz2 = cz1 + 1.0;
        // Target AABB (player: 0.6 wide, 1.8 tall)
        double tx1 = target.getX() - 0.3, tx2 = target.getX() + 0.3;
        double ty1 = target.getY(),        ty2 = target.getY() + 1.8;
        double tz1 = target.getZ() - 0.3, tz2 = target.getZ() + 0.3;
        return cx1 < tx2 && cx2 > tx1
            && cy1 < ty2 && cy2 > ty1
            && cz1 < tz2 && cz2 > tz1;
    }

    private double calcCrystalDamage(BlockPos obsidianPos, AbstractClientPlayer target) {
        // Crystal spawns at obsidianPos.above(); explosion center = obsidianPos.y + 2.0
        double ex = obsidianPos.getX() + 0.5;
        double ey = obsidianPos.getY() + 2.0;
        double ez = obsidianPos.getZ() + 0.5;

        double tx = target.getX();
        double ty = target.getY() + target.getBbHeight() / 2.0;
        double tz = target.getZ();

        double dist   = Math.sqrt((ex - tx) * (ex - tx) + (ey - ty) * (ey - ty) + (ez - tz) * (ez - tz));
        double impact = Math.max(0.0, 1.0 - dist / 12.0);
        return (impact * impact + impact) / 2.0 * 84.0 + 1.0;
    }

    private double readACMinDamage() {
        try {
            BaseModule ac = ModuleManager.getClientModule("AutoCrystal");
            if (ac == null) return 6.0;
            for (Option<?> opt : ac.getOptions()) {
                if ("MinDamage".equals(opt.name) && opt.getValue() instanceof Number n) {
                    return n.doubleValue();
                }
            }
        } catch (Exception ignored) {}
        return 6.0;
    }
}
