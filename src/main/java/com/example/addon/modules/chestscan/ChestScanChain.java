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
     * Case 1: a hopper directly below a tracked chest pulls FROM it (chest is the source).
     * Case 2: a hopper anywhere adjacent to a tracked chest, facing INTO it, feeds FROM
     * whatever chest sits directly above that hopper (chest is the destination).
     */
    public static Map<BlockPos, BlockPos> findEdges(Level level, Set<BlockPos> trackedChests, BlockPos center, int radiusBlocks) {
        Map<BlockPos, BlockPos> edges = new HashMap<>();
        double radiusSq = (double) radiusBlocks * radiusBlocks;

        for (BlockPos chestPos : trackedChests) {
            if (chestPos.distSqr(center) > radiusSq) continue;

            BlockPos belowPos = chestPos.below();
            BlockState belowState = level.getBlockState(belowPos);
            if (belowState.getBlock() instanceof HopperBlock) {
                Direction facing = belowState.getValue(HopperBlock.FACING);
                BlockPos dest = belowPos.relative(facing);
                if (isChest(level.getBlockState(dest))) {
                    edges.put(chestPos, dest);
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
     * of a sink chest (no outgoing edge) whose real status is known and not FULL, excluding
     * any position that already has a real recorded status (real always wins over inference).
     */
    public static Set<BlockPos> inferEmpty(Map<BlockPos, BlockPos> edges, Map<BlockPos, ChestScanStore.ChestStatus> realStatuses) {
        Set<BlockPos> hasOutgoing = edges.keySet();
        Set<BlockPos> allChests = new HashSet<>();
        allChests.addAll(edges.keySet());
        allChests.addAll(edges.values());

        Set<BlockPos> sinks = new HashSet<>();
        for (BlockPos c : allChests) {
            if (!hasOutgoing.contains(c)) sinks.add(c);
        }

        Map<BlockPos, List<BlockPos>> reverse = new HashMap<>();
        for (Map.Entry<BlockPos, BlockPos> e : edges.entrySet()) {
            reverse.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        Set<BlockPos> inferred = new HashSet<>();
        for (BlockPos sink : sinks) {
            ChestScanStore.ChestStatus sinkStatus = realStatuses.get(sink);
            if (sinkStatus == null || sinkStatus == ChestScanStore.ChestStatus.FULL) continue;

            Deque<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> visited = new HashSet<>();
            queue.add(sink);
            visited.add(sink);
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
