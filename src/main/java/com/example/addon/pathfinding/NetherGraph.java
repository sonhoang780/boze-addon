package com.example.addon.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;

/**
 * D* Lite graph over Nether terrain: node = BlockPos, 26-neighbor 3D grid.
 * Unknown (chunk not loaded) is treated as FREE until a loaded chunk proves
 * otherwise -- correct model for "terrain revealed as you fly, seed unknown"
 * (standard D* Lite assumption for partially-sensed environments).
 */
public final class NetherGraph implements DStarLite.Graph<BlockPos> {
    private static final int[][] OFFSETS = build26Offsets();
    private static final double HAZARD_RADIUS = 2.0;
    private static final double HAZARD_PENALTY = 8.0;

    private final Level level;

    public NetherGraph(Level level) { this.level = level; }

    private static int[][] build26Offsets() {
        List<int[]> list = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    list.add(new int[]{dx, dy, dz});
                }
        return list.toArray(new int[0][]);
    }

    @Override
    public List<BlockPos> neighbors(BlockPos node) {
        List<BlockPos> out = new ArrayList<>(26);
        for (int[] o : OFFSETS) out.add(node.offset(o[0], o[1], o[2]));
        return out;
    }

    @Override
    public double cost(BlockPos a, BlockPos b) {
        if (isKnownBlocked(b)) return Double.POSITIVE_INFINITY;
        return Math.sqrt(a.distSqr(b)) + hazardPenalty(b);
    }

    @Override
    public double heuristic(BlockPos a, BlockPos b) {
        return Math.sqrt(a.distSqr(b));
    }

    /** Blocked once the chunk is loaded AND the block (or the one above
     *  it, for elytra vertical clearance) is solid. Unloaded chunks are free.
     *  Positions outside the level's build-height range are always blocked --
     *  isLoaded is chunk-column based and getBlockState returns free-space air
     *  out of bounds, so without this check the planner could route into the
     *  void or above the world ceiling forever. */
    public boolean isKnownBlocked(BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return true;
        if (isUnknown(pos)) return false;
        BlockState below = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        return below.blocksMotion() || above.blocksMotion();
    }

    public boolean isUnknown(BlockPos pos) {
        return !level.isLoaded(pos);
    }

    private double hazardPenalty(BlockPos pos) {
        int r = (int) Math.ceil(HAZARD_RADIUS);
        for (int dx = -r; dx <= r; dx++)
            for (int dy = -r; dy <= r; dy++)
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = pos.offset(dx, dy, dz);
                    if (p.distSqr(pos) > HAZARD_RADIUS * HAZARD_RADIUS) continue;
                    if (isUnknown(p)) continue;
                    FluidState fluid = level.getFluidState(p);
                    if (!fluid.isEmpty() && fluid.is(FluidTags.LAVA)) return HAZARD_PENALTY;
                    if (level.getBlockState(p).getBlock() == Blocks.FIRE) return HAZARD_PENALTY;
                }
        return 0.0;
    }
}
