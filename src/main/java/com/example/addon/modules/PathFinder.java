package com.example.addon.modules;

import dev.babbaj.pathfinder.NetherPathfinder;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;

public class PathFinder extends AddonModule {

    public static final PathFinder INSTANCE = new PathFinder();

    public final SliderOption maxHeight = new SliderOption(this, "Max Height",
        "Nether ceiling height assumption passed to the pathfinder.", 128.0, 1.0, 255.0, 1.0);
    public final SliderOption flySpeed = new SliderOption(this, "Fly Speed",
        "Horizontal flight speed.", 1.0, 0.1, 3.0, 0.05);
    public final SliderOption vertSpeed = new SliderOption(this, "Vertical Speed",
        "Vertical flight speed.", 0.6, 0.1, 2.0, 0.05);

    private long context = 0;
    public BlockPos goal = null;
    public Long seed = null;
    public boolean flying = false;

    private final java.util.Set<Long> fedChunks = new java.util.HashSet<>();
    public volatile long[] currentPath = null;
    public volatile int pathCursor = 0;
    private volatile boolean pathfindInProgress = false;
    private int cullTicks = 0;

    private PathFinder() {
        super("PathFinder", "Nether-ceiling elytra pathfinder (babbaj/nether-pathfinder algorithm).");
    }

    @Override
    public void onEnable() {
        if (!NetherPathfinder.isThisSystemSupported()) {
            System.err.println("[PathFinder] Native library failed to load; module will do nothing.");
            return;
        }
        context = NetherPathfinder.newContext(seed != null ? seed : 0L, null,
            NetherPathfinder.DIMENSION_NETHER, maxHeight.getValue().intValue(), true);
    }

    @Override
    public void onDisable() {
        if (context != 0) {
            NetherPathfinder.freeContext(context);
            context = 0;
        }
        goal = null;
        flying = false;
    }

    public long getContext() {
        return context;
    }

    public boolean isReady() {
        return getState() && context != 0 && NetherPathfinder.isThisSystemSupported();
    }

    private void feedNearbyChunks(net.minecraft.client.Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        final int NETHER_HEIGHT = 256;
        final int radius = 8; // chunks
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
                if (fedChunks.contains(key)) continue;
                if (!mc.level.hasChunk(cx, cz)) continue;

                net.minecraft.world.level.chunk.LevelChunk chunk = mc.level.getChunkSource()
                    .getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (chunk == null) continue;

                boolean[] data = new boolean[16 * 16 * NETHER_HEIGHT];
                net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (int y = 0; y < NETHER_HEIGHT; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            pos.set((cx << 4) + x, y, (cz << 4) + z);
                            net.minecraft.world.level.block.state.BlockState state = chunk.getBlockState(pos);
                            boolean blocked = state.blocksMotion() || !state.getFluidState().isEmpty();
                            data[(y << 8) | (z << 4) | x] = blocked;
                        }
                    }
                }
                NetherPathfinder.insertChunkData(context, cx, cz, data);
                fedChunks.add(key);
            }
        }

        cullTicks++;
        if (cullTicks >= 100) {
            cullTicks = 0;
            NetherPathfinder.cullFarChunks(context, pcx, pcz, radius * 16 + 32);
        }
    }

    public void requestPath() {
        if (goal == null || context == 0 || pathfindInProgress) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        final BlockPos start = mc.player.blockPosition();
        final BlockPos target = goal;
        final boolean generate = (seed != null);

        pathfindInProgress = true;
        new Thread(() -> {
            try {
                dev.babbaj.pathfinder.PathSegment segment = NetherPathfinder.pathFind(
                    context,
                    start.getX(), start.getY(), start.getZ(),
                    target.getX(), target.getY(), target.getZ(),
                    false, true, 500, generate, 10.0
                );
                if (segment != null) {
                    currentPath = segment.packed;
                    pathCursor = 0;
                }
            } finally {
                pathfindInProgress = false;
            }
        }, "PathFinder-pathfind").start();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (!isReady()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        feedNearbyChunks(mc);

        if (flying && goal != null) {
            if (currentPath == null || pathCursor >= currentPath.length) {
                requestPath();
            }
        }
    }
}
