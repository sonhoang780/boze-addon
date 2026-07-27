package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.utility.interaction.BreakHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Throws an ender pearl to clip/phase into the block at the feet.
 * Grim: throw directly (real look direction decides straight-down vs corner landing).
 * NCP: silently place a web (fallback flint&amp;steel/fire) under the feet first --
 * NCP's clip check wants a block there. Both modes break scaffolding under the feet
 * first if present.
 * <p>
 * ponytail: corner/2-block-wedge targeting and the retreat offset are a best-effort
 * geometry guess (no forced rotation -- real player look decides the pearl's landing
 * spot). Tune CornerThreshold / re-derive the offsets in-game; can't verify pearl-clip
 * physics without a live server.
 */
public class PearlPhase extends AddonModule {
    public static final PearlPhase INSTANCE = new PearlPhase();

    // Degrees from a 45deg diagonal that still counts as "aiming at a corner" (wedge between 2
    // blocks). Nobody adjusted this in practice -- hardcoded instead of a user-facing slider.
    private static final double CORNER_THRESHOLD = 15.0;

    public final ModeOption<InteractionMode> anticheat = new ModeOption<>(this, "AntiCheat",
        "Grim: throw pearl directly. NCP: silently place a web/fire under the feet first.", InteractionMode.Grim);

    public final ModeOption<SwapType> swap = new ModeOption<>(this, "Swap",
        "Silent/Alt/Normal -- how to swap to the pearl before throwing it.", SwapType.Silent);

    private PearlPhase() {
        super("PearlPhase", "Throw an ender pearl to phase/clip into the block under your feet. Runs once then auto-disables.");
    }

    private enum Step { BREAK, PLACE }

    private List<BlockPos> targets = List.of();
    private int targetIdx = 0;
    private Step step = Step.BREAK;

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { setState(false); return; }

        targets = computeTargets(mc);
        targetIdx = 0;
        step = Step.BREAK;
    }

    @Override
    public void onDisable() {
        targets = List.of();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { setState(false); return; }

        tickRun(mc);
    }

    private void tickRun(Minecraft mc) {
        if (targetIdx >= targets.size()) {
            throwPearl(mc);
            setState(false);
            return;
        }

        BlockPos pos = targets.get(targetIdx);

        if (step == Step.BREAK) {
            BlockPos below = pos.below();
            if (mc.level.getBlockState(below).is(Blocks.SCAFFOLDING)) {
                // throughWalls=true: breakBlock's default overload requires line-of-sight on the
                // block's visible face (verified via BreakHelper's javadoc), which the block right
                // under your own feet often fails looking straight down at any angle -- bypass it
                // instead of forcing a rotation.
                BreakHelper.breakBlock(below, mc.player.blockInteractionRange(), true);
                return; // retry next tick until it's actually gone
            }
            if (anticheat.getValue() == InteractionMode.NCP) {
                step = Step.PLACE;
            } else {
                targetIdx++;
            }
            return;
        }

        // step == PLACE (NCP only)
        placeHazard(mc, pos);
        targetIdx++;
        step = Step.BREAK;
    }

    /** Feet position (or the block behind, if retreating), plus a diagonal neighbor when aim is near a corner. */
    private List<BlockPos> computeTargets(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();

        boolean retreating = mc.options.keyDown.isDown() && !mc.options.keyUp.isDown();
        if (retreating) {
            feet = feet.relative(mc.player.getDirection().getOpposite());
        }

        List<BlockPos> list = new ArrayList<>();
        list.add(feet);

        float yaw = mc.player.getYRot();
        float mod45 = ((yaw % 90) + 90) % 90;
        if (Math.abs(mod45 - 45f) < CORNER_THRESHOLD) {
            double rad = Math.toRadians(yaw);
            int ox = -Math.sin(rad) >= 0 ? 1 : -1;
            int oz = Math.cos(rad) >= 0 ? 1 : -1;
            list.add(feet.offset(ox, 0, oz));
        }
        return list;
    }

    private void placeHazard(Minecraft mc, BlockPos pos) {
        if (!mc.level.getBlockState(pos).canBeReplaced()) return;

        boolean web = true;
        int slot = InvHelper.findInHotbar(Items.COBWEB);
        if (slot == -1) { web = false; slot = InvHelper.findInHotbar(Items.FLINT_AND_STEEL); }
        if (slot == -1) return;

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos.below(), false);
        InvHelper.swapToSlot(slot, SwapType.Silent);
        if (web) {
            PlaceHelper.place(anticheat.getValue(), hit, InteractionHand.MAIN_HAND);
        } else {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        }
        InvHelper.swapBack();
    }

    private void throwPearl(Minecraft mc) {
        int slot = InvHelper.findInHotbar(Items.ENDER_PEARL);
        if (slot == -1) slot = InvHelper.find(Items.ENDER_PEARL);
        if (slot == -1) return;

        InvHelper.swapToSlot(slot, swap.getValue());
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        InvHelper.swapBack();
    }
}
