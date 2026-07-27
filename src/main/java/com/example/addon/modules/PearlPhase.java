package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInteract;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.ToggleOption;
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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Throws an ender pearl to clip/phase into the block at the feet. Toggling the module on runs
 * the sequence once, then it auto-disables.
 * <p>
 * Everything runs from EventInteract, not a tick handler. BreakHelper's javadoc states breaks
 * are only anticheat-compliant "when run from an interaction" (the Interaction system is what
 * applies the rotation), so calling breakBlock() from EventTick silently did nothing.
 * <p>
 * The throw target is a real 3D point instead of a bare yaw snap: when standing within
 * CORNER_SNAP_THRESHOLD of a 4-block lattice corner, the point is nudged toward the diagonal
 * neighbor (CORNER_SNAP_OFFSET past the corner) so a phase into a corner no longer needs manual
 * lining-up mid-fight; otherwise it stays under the player's own feet. MathHelper.calculateRotation
 * derives yaw/pitch from eye-to-target geometry (same helper BedAura/hazard-placement below use),
 * so pitch always comes out correct for the current eye height instead of a hand-picked constant.
 * <p>
 * Crawl: vanilla blocks a phase throw while in the crawling pose (low-ceiling gap); Folia lifts
 * that restriction if the pearl is aimed at the bottom edge of the block instead of just under
 * its top surface, per user confirmation -- so this option retargets the Y coordinate there
 * instead of changing anything else.
 * <p>
 * NCP additionally places a web (fallback flint&amp;steel/fire) under the feet first -- NCP's
 * clip check wants a block there.
 */
public class PearlPhase extends AddonModule {
    public static final PearlPhase INSTANCE = new PearlPhase();

    // How close (in blocks, each axis) to a 4-block lattice corner counts as "standing at a corner".
    private static final double CORNER_SNAP_THRESHOLD = 0.3;
    // How far past that corner, into the diagonal neighbor, the throw target gets nudged.
    private static final double CORNER_SNAP_OFFSET = 0.4;
    // Aims just under the floor's top surface rather than exactly on it.
    private static final double FLOOR_EPSILON = 0.1;

    public final ModeOption<InteractionMode> anticheat = new ModeOption<>(this, "AntiCheat",
        "Grim: throw pearl directly. NCP: silently place a web/fire under the feet first.", InteractionMode.Grim);

    public final ModeOption<SwapType> swap = new ModeOption<>(this, "Swap",
        "Silent/Alt/Normal -- how to swap to the pearl before throwing it.", SwapType.Silent);

    public final ToggleOption crawl = new ToggleOption(this, "Crawl",
        "Aim at the bottom edge of the block underfoot instead of just under its top surface -- "
        + "needed to phase while in the crawling pose (Folia only, vanilla blocks it either way).", false);

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

        Vec3 target = computeThrowTarget(mc);
        float[] rot = MathHelper.calculateRotation(mc.player.getEyePosition(), target);

        final int useSlot = slot;
        thrown = true;
        event.addInteraction(new Interaction(() -> {
            InvHelper.swapToSlot(useSlot, swap.getValue());
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            InvHelper.swapBack();
        }, rot[0], rot[1]));
    }

    /**
     * Horizontally: the player's own feet, nudged toward the diagonal neighbor if standing near
     * a lattice corner. Vertically: just under the floor's top surface, or its bottom edge with
     * Crawl on.
     */
    private Vec3 computeThrowTarget(Minecraft mc) {
        double x = mc.player.getX();
        double z = mc.player.getZ();

        double nearestX = Math.round(x);
        double nearestZ = Math.round(z);
        double dx = nearestX - x;
        double dz = nearestZ - z;

        if (Math.abs(dx) <= CORNER_SNAP_THRESHOLD && Math.abs(dz) <= CORNER_SNAP_THRESHOLD) {
            x += Mth.clamp(dx, -CORNER_SNAP_OFFSET, CORNER_SNAP_OFFSET);
            z += Mth.clamp(dz, -CORNER_SNAP_OFFSET, CORNER_SNAP_OFFSET);
        }

        BlockPos below = mc.player.blockPosition().below();
        double y = crawl.getValue() ? below.getY() : below.getY() + 1.0 - FLOOR_EPSILON;

        return new Vec3(x, y, z);
    }
}
