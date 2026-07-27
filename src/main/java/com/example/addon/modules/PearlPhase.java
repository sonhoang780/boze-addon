package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInteract;
import dev.boze.api.option.ModeOption;
import dev.boze.api.utility.MathHelper;
import dev.boze.api.utility.interaction.BreakHelper;
import dev.boze.api.utility.interaction.Interaction;
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

/**
 * Throws an ender pearl to clip/phase into the block at the feet. Toggling the module on runs
 * the sequence once, then it auto-disables.
 * <p>
 * Everything runs from EventInteract, not a tick handler -- that is the fix for both
 * long-standing failures. BreakHelper's javadoc states breaks are only anticheat-compliant
 * "when run from an interaction" (the Interaction system is what applies the rotation), so
 * calling breakBlock() straight from EventTick silently did nothing, which is why scaffolding
 * never broke. And the throw now carries a forced rotation instead of riding whatever the
 * player happened to be looking at, so holding pitch 88-90 by hand is no longer needed.
 * <p>
 * NCP additionally places a web (fallback flint&amp;steel/fire) under the feet first -- NCP's
 * clip check wants a block there.
 */
public class PearlPhase extends AddonModule {
    public static final PearlPhase INSTANCE = new PearlPhase();

    public enum Aim { Down, Corner }

    // Straight down. Vanilla clamps pitch to [-90, 90]; user-confirmed that manually holding
    // 88-90 is what actually lands the pearl in the block underfoot.
    private static final float PITCH_DOWN = 90.0f;
    // A corner throw has to angle into the wedge between two blocks rather than drop straight
    // down, so it stays just off vertical.
    // ponytail: both constants are tuned-by-hand values, not derived -- re-measure in-game if
    // phase reliability drifts.
    private static final float PITCH_CORNER = 88.0f;

    public final ModeOption<InteractionMode> anticheat = new ModeOption<>(this, "AntiCheat",
        "Grim: throw pearl directly. NCP: silently place a web/fire under the feet first.", InteractionMode.Grim);

    public final ModeOption<SwapType> swap = new ModeOption<>(this, "Swap",
        "Silent/Alt/Normal -- how to swap to the pearl before throwing it.", SwapType.Silent);

    public final ModeOption<Aim> aim = new ModeOption<>(this, "Aim",
        "Down: throw straight down into the block underfoot. Corner: snap yaw to the nearest "
        + "block corner so phasing into a corner needs no manual lining-up mid-fight.", Aim.Down);

    private boolean thrown = false;
    private boolean hazardPlaced = false;

    private PearlPhase() {
        super("PearlPhase", "Throw an ender pearl to phase/clip into the block under your feet. Runs once then auto-disables.");
    }

    @Override
    public void onEnable() {
        thrown = false;
        hazardPlaced = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) setState(false);
    }

    @EventHandler
    private void onInteract(EventInteract event) {
        if (event.getMode() != anticheat.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { setState(false); return; }

        // Disabling one fire later (rather than inside the throw's own Runnable) keeps
        // setState -- which unsubscribes this module from the event bus -- out of the bus's
        // own dispatch/execution pass.
        if (thrown) { setState(false); return; }

        if (submitScaffoldingBreak(event, mc)) return;
        if (anticheat.getValue() == InteractionMode.NCP && submitHazardPlace(event, mc)) return;
        submitThrow(event, mc);
    }

    /**
     * Scaffolding is climbable, so the player stands INSIDE it as often as on top of it --
     * checking only the block below (the old behavior) missed the standing-inside case
     * entirely. Returns true once a break is queued, so the caller retries next fire until
     * the block is actually gone.
     */
    private boolean submitScaffoldingBreak(EventInteract event, Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        for (BlockPos pos : new BlockPos[] { feet, feet.below() }) {
            if (!mc.level.getBlockState(pos).is(Blocks.SCAFFOLDING)) continue;
            // throughWalls=true: the block you are standing in or on routinely fails the
            // line-of-sight check the default overload requires.
            Interaction breakIt = BreakHelper.interaction(pos, true, mc.player.blockInteractionRange(), true);
            if (breakIt == null) continue;
            event.addInteraction(breakIt);
            return true;
        }
        return false;
    }

    /** NCP only. Runs at most once per enable so a place that never registers can't stall the throw. */
    private boolean submitHazardPlace(EventInteract event, Minecraft mc) {
        if (hazardPlaced) return false;

        BlockPos pos = mc.player.blockPosition();
        if (!mc.level.getBlockState(pos).canBeReplaced()) return false;

        boolean web = true;
        int slot = InvHelper.findInHotbar(Items.COBWEB);
        if (slot == -1) { web = false; slot = InvHelper.findInHotbar(Items.FLINT_AND_STEEL); }
        if (slot == -1) return false;

        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos.below(), false);
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), hit.getLocation());
        final boolean useWeb = web;
        final int useSlot = slot;

        hazardPlaced = true;
        event.addInteraction(new Interaction(() -> {
            InvHelper.swapToSlot(useSlot, SwapType.Silent);
            if (useWeb) {
                PlaceHelper.place(anticheat.getValue(), hit, InteractionHand.MAIN_HAND);
            } else {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
            }
            InvHelper.swapBack();
        }, rot[0], rot[1]));
        return true;
    }

    private void submitThrow(EventInteract event, Minecraft mc) {
        int slot = InvHelper.findInHotbar(Items.ENDER_PEARL);
        if (slot == -1) slot = InvHelper.find(Items.ENDER_PEARL);
        if (slot == -1) { setState(false); return; }

        boolean corner = aim.getValue() == Aim.Corner;
        float yaw = corner ? snapToNearestCorner(mc.player.getYRot()) : mc.player.getYRot();
        float pitch = corner ? PITCH_CORNER : PITCH_DOWN;

        final int useSlot = slot;
        thrown = true;
        event.addInteraction(new Interaction(() -> {
            InvHelper.swapToSlot(useSlot, swap.getValue());
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvHelper.swapBack();
        }, yaw, pitch));
    }

    /** Nearest of the four block-corner diagonals (-45/45/135/225 ...). */
    private static float snapToNearestCorner(float yaw) {
        return Math.round((yaw - 45f) / 90f) * 90f + 45f;
    }
}
