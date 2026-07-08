package com.example.addon.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Glues DStarLite + NetherGraph together for PathFinder to drive: owns the
 * live goal/search state, rescans upcoming path nodes each tick for chunks
 * that finished loading, and triggers incremental replanning (not a full
 * rebuild) when they reveal a new obstacle.
 */
public final class NetherPathfinder {
    private static final int RESCAN_AHEAD = 12; // upcoming path nodes re-checked each tick

    private final NetherGraph graph;
    private final DStarLite<BlockPos> search;
    private BlockPos goal;
    private BlockPos currentPos;
    private boolean active = false;

    public NetherPathfinder(Level level) {
        this.graph = new NetherGraph(level);
        this.search = new DStarLite<>(graph);
    }

    public void setGoal(BlockPos start, BlockPos goal) {
        this.goal = goal;
        this.currentPos = start;
        search.initialize(start, goal);
        search.computeShortestPath();
        active = true;
    }

    public void clear() {
        active = false;
        goal = null;
    }

    public boolean isActive() { return active; }
    public BlockPos getGoal() { return goal; }

    /** Call once per tick with the player's current block position. Returns the
     *  next waypoint to fly toward, or null if unreachable (caller stops). */
    public BlockPos tick(BlockPos playerPos) {
        if (!active) return null;
        if (!playerPos.equals(currentPos)) {
            search.updateStart(playerPos);
            currentPos = playerPos;
        }
        rescanAhead(playerPos);
        search.computeShortestPath();
        BlockPos next = search.nextStep(playerPos);
        if (next == null) active = false;
        return next;
    }

    private void rescanAhead(BlockPos from) {
        BlockPos node = from;
        for (int i = 0; i < RESCAN_AHEAD && node != null; i++) {
            for (BlockPos neighbor : graph.neighbors(node)) {
                if (!graph.isUnknown(neighbor)) {
                    search.updateEdgeCost(node, neighbor);
                }
            }
            node = search.nextStep(node);
        }
    }
}
