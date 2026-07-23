package com.example.addon.util;

import dev.boze.api.utility.WorldHelper;
import dev.boze.api.utility.interaction.InteractionMode;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.PlaceHelper;
import dev.boze.api.utility.interaction.SwapType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geometric (camera-independent) block-hit builder for placing a new block into an empty
 * target cell against one of its solid neighbors. Ported from ThunderHack-Reborn's
 * InteractionUtility.getPlaceResult/getStrictDirections/getSupportBlocks.
 *
 * Boze's PlaceHelper.cast raycasts using the player's REAL current look direction, so it only
 * returns a hit when the player already happens to be aimed at the target -- wrong tool for
 * silent structure placement (piston/redstone/obsidian placed at positions computed relative
 * to a target, not wherever the camera currently points). This builds the BlockHitResult
 * purely from block-position geometry (which face is exposed toward the player's eyes, and
 * which neighbor is solid); callers compute and send the rotation separately afterward
 * (MathHelper.calculateRotation), matching the original's Rotate.None + external angle send.
 */
public final class InteractionHelper {
    private static final Map<BlockPos, Long> awaiting = new ConcurrentHashMap<>();
    private static final long AWAIT_TTL_MS = 1000;

    private InteractionHelper() {}

    /** Call right after sending a placement so a still-unconfirmed neighbor counts as solid support. */
    public static void markAwaiting(BlockPos pos) {
        awaiting.put(pos.immutable(), System.currentTimeMillis());
    }

    /** Undo a speculative {@link #markAwaiting} (e.g. a search branch that was not committed). */
    public static void clearAwaiting(BlockPos pos) {
        awaiting.remove(pos.immutable());
    }

    /** True if {@code pos} has a still-valid pending placement (submitted, not yet confirmed). */
    public static boolean isAwaiting(BlockPos pos) {
        Long t = awaiting.get(pos);
        if (t == null) return false;
        if (System.currentTimeMillis() - t < AWAIT_TTL_MS) return true;
        awaiting.remove(pos);
        return false;
    }

    /**
     * allowAwaiting=true: a still-pending placement counts as solid -- ONLY valid for search-time
     * feasibility (canPlace), never for building a hit that goes to the server: the server does
     * not know the awaiting block yet, a use-packet against it reads as use-on-air and is
     * silently swallowed (redstone "not placeable in mid-air", PistonPush's multi-second stalls
     * = 1s awaiting-TTL reject cycles).
     */
    private static boolean isSolid(BlockPos pos, boolean allowAwaiting) {
        if (allowAwaiting && isAwaiting(pos)) return true;
        return !WorldHelper.isReplaceable(pos);
    }

    private record Support(BlockPos pos, Direction facing) {}

    private static List<Support> getSupportBlocks(BlockPos bp, boolean allowAwaiting) {
        List<Support> list = new ArrayList<>();
        if (isSolid(bp.below(), allowAwaiting)) list.add(new Support(bp.below(), Direction.UP));
        if (isSolid(bp.above(), allowAwaiting)) list.add(new Support(bp.above(), Direction.DOWN));
        if (isSolid(bp.west(), allowAwaiting)) list.add(new Support(bp.west(), Direction.EAST));
        if (isSolid(bp.east(), allowAwaiting)) list.add(new Support(bp.east(), Direction.WEST));
        if (isSolid(bp.north(), allowAwaiting)) list.add(new Support(bp.north(), Direction.SOUTH));
        if (isSolid(bp.south(), allowAwaiting)) list.add(new Support(bp.south(), Direction.NORTH));
        return list;
    }

    /** Faces of {@code bp} geometrically exposed toward the eyes (position-based, not look-direction). */
    private static List<Direction> getStrictDirections(BlockPos bp, Vec3 eyes, boolean allowAwaiting) {
        List<Direction> sides = new ArrayList<>();
        Vec3 c = Vec3.atCenterOf(bp);

        double west = eyes.x - (c.x + 0.5), east = eyes.x - (c.x - 0.5);
        double north = eyes.z - (c.z + 0.5), south = eyes.z - (c.z - 0.5);
        double up = eyes.y - (c.y + 0.5), down = eyes.y - (c.y - 0.5);

        if (west > 0 && isSolid(bp.west(), allowAwaiting)) sides.add(Direction.EAST);
        if (west < 0 && isSolid(bp.east(), allowAwaiting)) sides.add(Direction.WEST);
        if (east < 0 && isSolid(bp.east(), allowAwaiting)) sides.add(Direction.WEST);
        if (east > 0 && isSolid(bp.west(), allowAwaiting)) sides.add(Direction.EAST);

        if (north > 0 && isSolid(bp.north(), allowAwaiting)) sides.add(Direction.SOUTH);
        if (north < 0 && isSolid(bp.south(), allowAwaiting)) sides.add(Direction.NORTH);
        if (south < 0 && isSolid(bp.south(), allowAwaiting)) sides.add(Direction.NORTH);
        if (south > 0 && isSolid(bp.north(), allowAwaiting)) sides.add(Direction.SOUTH);

        if (up > 0 && isSolid(bp.below(), allowAwaiting)) sides.add(Direction.UP);
        if (up < 0 && isSolid(bp.above(), allowAwaiting)) sides.add(Direction.DOWN);
        if (down < 0 && isSolid(bp.above(), allowAwaiting)) sides.add(Direction.DOWN);
        if (down > 0 && isSolid(bp.below(), allowAwaiting)) sides.add(Direction.UP);

        return sides;
    }

    /**
     * BlockHitResult for placing a block at {@code bp}, camera-independent. Face pick order:
     * (1) strict -- nearest support face geometrically exposed toward the eyes;
     * (2) any REAL support face, even if not eye-visible. Pure strict makes some legit builds
     *     physically impossible (placing on TOP of a head-height block from level ground: you
     *     cannot see a top face from below -- a human jumps for that click; the module can't);
     * (3) airPlace: fabricated hit on bp's own top face (no neighbor needed).
     * Hit-building NEVER uses awaiting blocks as support (see isSolid). Callers that need
     * search-time feasibility use canPlace, which does count awaiting.
     */
    public static BlockHitResult getPlaceResult(Minecraft mc, BlockPos bp, boolean airPlace, boolean ignoreEntities) {
        return getPlaceResult(mc, bp, airPlace, ignoreEntities, false);
    }

    private static BlockHitResult getPlaceResult(Minecraft mc, BlockPos bp, boolean airPlace, boolean ignoreEntities, boolean allowAwaiting) {
        if (!ignoreEntities) {
            for (Entity ent : mc.level.getEntities((Entity) null, new AABB(bp))) {
                if (!(ent instanceof ItemEntity) && !(ent instanceof ExperienceOrb)) return null;
            }
        }
        if (!WorldHelper.isReplaceable(bp)) return null;

        List<Support> supports = getSupportBlocks(bp, allowAwaiting);
        List<Direction> visible = getStrictDirections(bp, mc.player.getEyePosition(), allowAwaiting);
        for (Support support : supports) {
            if (!visible.contains(support.facing())) continue;
            return hitOn(support);
        }
        // Non-visible fallback (ThunderHack's default/vanilla interact, not Strict): any solid
        // support face. Without this, chained builds stall exactly where a human would jump.
        if (!supports.isEmpty()) return hitOn(supports.get(0));
        if (airPlace) {
            Vec3 hitVec = new Vec3(bp.getX() + 0.5, bp.getY() + 1.0, bp.getZ() + 0.5);
            return new BlockHitResult(hitVec, Direction.UP, bp, false);
        }
        return null;
    }

    private static BlockHitResult hitOn(Support support) {
        Vec3 hitVec = new Vec3(
                support.pos().getX() + 0.5 + support.facing().getStepX() * 0.5,
                support.pos().getY() + 0.5 + support.facing().getStepY() * 0.5,
                support.pos().getZ() + 0.5 + support.facing().getStepZ() * 0.5);
        return new BlockHitResult(hitVec, support.facing(), support.pos(), false);
    }

    /** Search-time feasibility: counts awaiting (pending) blocks as solid support. */
    public static boolean canPlace(Minecraft mc, BlockPos bp, boolean airPlace, boolean ignoreEntities) {
        return getPlaceResult(mc, bp, airPlace, ignoreEntities, true) != null;
    }

    /**
     * Swap-to-slot + place + swap-back with the failure handling BedAura/Mint proved necessary
     * (see BedAura.executePlaceBed): swapToSlot CAN fail, and ignoring that leaves the server
     * believing a different hotbar slot is selected -- the use-packet then places whatever the
     * SERVER thinks is in hand (e.g. a piston materializing at the redstone's hit). On swap
     * failure: abort unless the right item is already held, and resync the carried-item slot
     * before placing. swapBack only when the swap actually happened, in a finally.
     */
    public static boolean placeSwapped(InteractionMode mode, BlockHitResult hit, int slot, SwapType swap) {
        Minecraft mc = Minecraft.getInstance();
        ItemStack expected = mc.player.getInventory().getItem(slot);
        boolean swapped = InvHelper.swapToSlot(slot, swap);
        if (!swapped) {
            if (expected.isEmpty() || !ItemStack.isSameItem(mc.player.getMainHandItem(), expected)) return false;
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().getSelectedSlot()));
            }
        }
        try {
            return PlaceHelper.place(mode, hit, InteractionHand.MAIN_HAND);
        } finally {
            if (swapped) InvHelper.swapBack();
        }
    }
}
