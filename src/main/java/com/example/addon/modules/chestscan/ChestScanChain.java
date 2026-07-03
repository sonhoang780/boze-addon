package com.example.addon.modules.chestscan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChestScanChain {

    private ChestScanChain() {}

    /**
     * Direct chest-to-chest edges discovered through exactly one connecting hopper.
     * Case 1: a hopper directly below a chest pulls FROM it (chest is the source).
     * Case 2: a hopper anywhere adjacent to a chest, facing INTO it, feeds FROM
     * whatever chest sits directly above that hopper (chest is the destination).
     *
     * Explores outward from trackedChests (the chests the player has actually opened)
     * by following hopper links in both directions, so the whole chain gets discovered
     * even though only one chest in it was ever opened — not just the single edge
     * touching a tracked chest directly.
     */
    public static Map<BlockPos, BlockPos> findEdges(Level level, Set<BlockPos> trackedChests, BlockPos center, int radiusBlocks) {
        Map<BlockPos, BlockPos> edges = new HashMap<>();
        double radiusSq = (double) radiusBlocks * radiusBlocks;

        Deque<BlockPos> frontier = new ArrayDeque<>(trackedChests);
        Set<BlockPos> visited = new HashSet<>(trackedChests);

        while (!frontier.isEmpty()) {
            BlockPos chestPos = frontier.poll();
            if (chestPos.distSqr(center) > radiusSq) continue;

            BlockPos belowPos = chestPos.below();
            BlockState belowState = level.getBlockState(belowPos);
            if (belowState.getBlock() instanceof HopperBlock) {
                Direction facing = belowState.getValue(HopperBlock.FACING);
                BlockPos dest = belowPos.relative(facing);
                if (isChest(level.getBlockState(dest))) {
                    edges.put(chestPos, dest);
                    if (visited.add(dest)) frontier.add(dest);
                }
            }

            for (Direction dir : Direction.values()) {
                BlockPos hopperPos = chestPos.relative(dir);
                BlockState hopperState = level.getBlockState(hopperPos);
                if (!(hopperState.getBlock() instanceof HopperBlock)) continue;
                if (hopperState.getValue(HopperBlock.FACING) != dir.getOpposite()) continue;
                BlockPos sourcePos = hopperPos.above();
                if (isChest(level.getBlockState(sourcePos))) {
                    edges.put(sourcePos, chestPos);
                    if (visited.add(sourcePos)) frontier.add(sourcePos);
                }
            }
        }
        return edges;
    }

    private static boolean isChest(BlockState state) {
        return state.getBlock() instanceof ChestBlock;
    }

    /**
     * Given source->dest edges and known real (opened) statuses, returns positions that
     * should render as "inferred empty": ancestors (via the edge graph, walked backward)
     * of ANY chest whose real status is known and not FULL — not just chain sinks. Opening
     * a chest in the middle of a stack (which still has its own outgoing edge further down)
     * must still propagate "empty" upward to its ancestors. Excludes any position that
     * already has a real recorded status (real always wins over inference).
     */
    public static Set<BlockPos> inferEmpty(Map<BlockPos, BlockPos> edges, Map<BlockPos, ChestScanStore.ChestStatus> realStatuses) {
        Map<BlockPos, List<BlockPos>> reverse = new HashMap<>();
        for (Map.Entry<BlockPos, BlockPos> e : edges.entrySet()) {
            reverse.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        Set<BlockPos> inferred = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        for (Map.Entry<BlockPos, ChestScanStore.ChestStatus> e : realStatuses.entrySet()) {
            if (e.getValue() == ChestScanStore.ChestStatus.FULL) continue;
            BlockPos start = e.getKey();
            if (!visited.add(start)) continue;

            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos cur = queue.poll();
                for (BlockPos parent : reverse.getOrDefault(cur, List.of())) {
                    if (!visited.add(parent)) continue;
                    if (!realStatuses.containsKey(parent)) inferred.add(parent);
                    queue.add(parent);
                }
            }
        }
        return inferred;
    }
}
