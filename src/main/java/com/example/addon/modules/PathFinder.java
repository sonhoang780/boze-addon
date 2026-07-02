package com.example.addon.modules;

import dev.babbaj.pathfinder.NetherPathfinder;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.event.EventWorldRender;
import dev.boze.api.option.SliderOption;
import dev.boze.api.render.ClientColor;
import dev.boze.api.render.ColorMaker;
import dev.boze.api.render.WorldDrawer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

public class PathFinder extends AddonModule {

    public static final PathFinder INSTANCE = new PathFinder();

    public final SliderOption maxHeight = new SliderOption(this, "Max Height",
        "Nether ceiling height assumption passed to the pathfinder.", 128.0, 1.0, 255.0, 1.0);
    public final SliderOption flySpeed = new SliderOption(this, "Fly Speed",
        "Horizontal flight speed.", 1.0, 0.1, 3.0, 0.05);
    public final SliderOption vertSpeed = new SliderOption(this, "Vertical Speed",
        "Vertical flight speed.", 0.6, 0.1, 2.0, 0.05);

    private static final ClientColor PATH_COLOR = ColorMaker.staticColor(0, 220, 220);
    private static final float PATH_OPACITY = 0.9f;
    private static final double PATH_THICKNESS = 0.08;

    private static final int REPATH_INTERVAL_TICKS = 40;   // re-plan periodically so newly-fed terrain corrects the route
    private static final int STUCK_TICKS_THRESHOLD = 20;   // ~1s of sustained collision while flying = dead end
    private static final int BACKOFF_TICKS = 8;            // ticks spent backing away before re-requesting a path
    private static final int FEED_RADIUS_CHUNKS = 8;       // real chunk-feed radius; wall ring sits one chunk past this

    private long context = 0;
    public BlockPos goal = null;
    public Long seed = null;
    public boolean flying = false;

    private final java.util.Set<Long> fedChunks = new java.util.HashSet<>();
    // Synthetic solid ring cells (Mode B / no-seed only) -- chebyshev distance
    // radius+1 from the player, one chunk thick, keeps the native search from ever
    // reaching genuinely-unknown territory. See buildWallRing().
    private final java.util.Set<Long> wallChunks = new java.util.HashSet<>();
    public volatile long[] currentPath = null;
    public volatile int pathCursor = 0;
    private volatile boolean pathfindInProgress = false;
    private final java.util.concurrent.atomic.AtomicInteger inFlightNativeCalls = new java.util.concurrent.atomic.AtomicInteger(0);
    private int cullTicks = 0;
    private int repathTicks = 0;
    private int stuckTicks = 0;
    private int backoffTicks = 0;

    private PathFinder() {
        super("PathFinder", "Nether-ceiling elytra pathfinder (babbaj/nether-pathfinder algorithm). Nether only.");
    }

    // The vendored native library documents "no synchronization is done within the jni
    // code" -- the caller must externally serialize calls that mutate/read the shared
    // native context (insertChunkData/cullFarChunks from the tick thread vs. pathFind on
    // its own background thread). Without this, concurrent access corrupts the native
    // PageAllocator's internal bookkeeping (Allocator.h) and calls std::terminate(),
    // killing the whole process with no JVM-visible exception and no hs_err dump.
    private final Object nativeLock = new Object();

    @Override
    public void onEnable() {
        if (!isInNether()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                    "[PathFinder] Nether only — disabling (current dimension is not the nether)."));
            }
            setState(false);
            return;
        }
        if (!NetherPathfinder.isThisSystemSupported()) {
            System.err.println("[PathFinder] Native library failed to load; module will do nothing.");
            return;
        }
        context = createContext();
        repathTicks = 0;
        stuckTicks = 0;
        backoffTicks = 0;
    }

    private boolean isInNether() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == Level.NETHER;
    }

    private boolean seedKnown() {
        return seed != null;
    }

    private long createContext() {
        return NetherPathfinder.newContext(seed != null ? seed : 0L, null,
            NetherPathfinder.DIMENSION_NETHER, maxHeight.getValue().intValue(), true);
    }

    // Waits (with a safety timeout) for in-flight native pathFind calls to drain before
    // it's safe to free/replace the native context. Must be called from the tick thread
    // only, and only after new requestPath() calls have been prevented (getState()==false,
    // or the caller is about to replace context itself).
    private void awaitNoInFlightNativeCalls() {
        long deadline = System.currentTimeMillis() + 2000;
        while (inFlightNativeCalls.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // Called from GoalCommand when the user runs `goal ... seed <v>`. Recreates the native
    // context with the new seed if the module is already enabled (since onEnable() already
    // baked the old seed into the existing context), otherwise just stores the seed for
    // onEnable() to use later.
    public void updateSeed(Long newSeed) {
        if (java.util.Objects.equals(seed, newSeed)) {
            return;
        }
        seed = newSeed;
        if (context == 0) {
            return;
        }
        awaitNoInFlightNativeCalls();
        NetherPathfinder.freeContext(context);
        context = createContext();
        fedChunks.clear();
        wallChunks.clear();
        currentPath = null;
        pathCursor = 0;
    }

    @Override
    public void onDisable() {
        if (context != 0) {
            awaitNoInFlightNativeCalls();
            NetherPathfinder.freeContext(context);
            context = 0;
        }
        fedChunks.clear();
        wallChunks.clear();
        goal = null;
        flying = false;
        currentPath = null;
        pathCursor = 0;
        repathTicks = 0;
        stuckTicks = 0;
        backoffTicks = 0;
    }

    public long getContext() {
        return context;
    }

    public boolean isReady() {
        return getState() && context != 0 && NetherPathfinder.isThisSystemSupported();
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private void feedNearbyChunks(net.minecraft.client.Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        final int NETHER_HEIGHT = 256;
        final int radius = FEED_RADIUS_CHUNKS;
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = chunkKey(cx, cz);
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
                try {
                    synchronized (nativeLock) {
                        NetherPathfinder.insertChunkData(context, cx, cz, data);
                    }
                } catch (Throwable t) {
                    System.err.println("[PathFinder] insertChunkData failed cx=" + cx + " cz=" + cz + ": " + t);
                    t.printStackTrace();
                    continue;
                }
                fedChunks.add(key);
                // This chunk may have been a synthetic solid wall cell in a previous
                // cycle (player has since flown close enough to load it for real).
                // insertChunkData above already overwrote its native-side data with the
                // real terrain; drop it from wallChunks so it's not treated as
                // already-walled if it falls on the ring again, and log once so the
                // overwrite-on-reinsert assumption (undocumented in the vendored binding)
                // gets eyeballed against actual pathing behavior in-game.
                if (wallChunks.remove(key)) {
                    System.out.println("[PathFinder] chunk (" + cx + "," + cz
                        + ") transitioned wall -> real data");
                }
            }
        }

        if (!seedKnown()) {
            buildWallRing(mc, pcx, pcz, radius);
        }

        cullTicks++;
        if (cullTicks >= 100) {
            cullTicks = 0;
            try {
                synchronized (nativeLock) {
                    NetherPathfinder.cullFarChunks(context, pcx, pcz, radius * 16 + 32);
                }
            } catch (Throwable t) {
                System.err.println("[PathFinder] cullFarChunks failed: " + t);
                t.printStackTrace();
            }
        }
    }

    /**
     * Mode B (no seed): inserts an all-solid ring of chunks at chebyshev distance
     * exactly radius+1 from the player's chunk -- one chunk thick, immediately outside
     * the real-fed bubble built by feedNearbyChunks' main loop above. The native A* can
     * never search past a solid cell, so this bounds every pathFind call to terrain the
     * client has actually loaded, regardless of the (moot, but kept honest) `air`
     * cache-miss default passed by requestPath(). As the player flies and the real
     * radius advances, new ring cells get walled and previously-ringed cells that are
     * now inside the real radius get overwritten with real data by the loop above (see
     * the wallChunks.remove(key) log) -- the frontier organically pushes outward.
     */
    private void buildWallRing(net.minecraft.client.Minecraft mc, int pcx, int pcz, int radius) {
        final int ring = radius + 1;
        final int NETHER_HEIGHT = 256;
        boolean[] solid = new boolean[16 * 16 * NETHER_HEIGHT];
        java.util.Arrays.fill(solid, true);

        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue; // ring only, not a filled disk
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = chunkKey(cx, cz);
                if (fedChunks.contains(key) || wallChunks.contains(key)) continue;

                try {
                    synchronized (nativeLock) {
                        NetherPathfinder.insertChunkData(context, cx, cz, solid);
                    }
                } catch (Throwable t) {
                    System.err.println("[PathFinder] wall insertChunkData failed cx=" + cx + " cz=" + cz + ": " + t);
                    continue;
                }
                wallChunks.add(key);
            }
        }
    }

    private void steer(net.minecraft.client.Minecraft mc) {
        if (!flying || currentPath == null || !mc.player.isFallFlying()) return;
        if (pathCursor >= currentPath.length) return;

        BlockPos waypoint = BlockPos.of(currentPath[pathCursor]);
        double dx = (waypoint.getX() + 0.5) - mc.player.getX();
        double dy = (waypoint.getY() + 0.5) - mc.player.getY();
        double dz = (waypoint.getZ() + 0.5) - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 2.0) {
            pathCursor++;
            return;
        }

        double speed = flySpeed.getValue();
        double vSpeed = vertSpeed.getValue();
        double vx = (dx / dist) * speed;
        double vy = Math.max(-vSpeed, Math.min(vSpeed, (dy / dist) * speed));
        double vz = (dz / dist) * speed;

        mc.player.setDeltaMovement(vx, vy, vz);
    }

    /**
     * Backs the player away from whatever it just rammed into: reverse the last steering
     * direction for BACKOFF_TICKS so the native re-path (which will see the same fed
     * terrain) doesn't immediately recompute the exact route that just failed.
     */
    private void backOff(net.minecraft.client.Minecraft mc) {
        var vel = mc.player.getDeltaMovement();
        double speed = flySpeed.getValue();
        double len = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double vx = len > 0.01 ? -(vel.x / len) * speed * 0.5 : 0;
        double vz = len > 0.01 ? -(vel.z / len) * speed * 0.5 : 0;
        mc.player.setDeltaMovement(vx, vertSpeed.getValue() * 0.5, vz);
    }

    private static BlockPos clampY(BlockPos pos, int maxY) {
        int y = Math.max(0, Math.min(maxY, pos.getY()));
        return y == pos.getY() ? pos : new BlockPos(pos.getX(), y, pos.getZ());
    }

    /**
     * No-seed mode only: the real fed radius (and the solid wall ring one chunk past it,
     * see buildWallRing) bounds every pathFind call's reachable search space to
     * FEED_RADIUS_CHUNKS*16 blocks around the player. A goal farther than that is
     * unreachable within a single call -- return it unchanged (goal already in range), or
     * a point along the start->goal vector clamped to just inside the wall (one chunk of
     * margin, so the frontier point itself sits in real-fed, not solid, terrain).
     */
    private static BlockPos frontierTarget(BlockPos start, BlockPos goal) {
        double dx = goal.getX() - start.getX();
        double dz = goal.getZ() - start.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        double maxDist = (FEED_RADIUS_CHUNKS - 1) * 16.0;
        if (horizDist <= maxDist) return goal;

        double scale = maxDist / horizDist;
        int fx = start.getX() + (int) Math.round(dx * scale);
        int fz = start.getZ() + (int) Math.round(dz * scale);
        return new BlockPos(fx, goal.getY(), fz);
    }

    public void requestPath() {
        if (goal == null || context == 0 || pathfindInProgress) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;

        // Native isInBounds() requires y in [0, maxHeight) -- y == maxHeight (or above/below)
        // makes findAir() hit its "shouldn't be possible" fallback, which calls exit(1) and
        // kills the whole process with no exception, no hs_err. Clamp defensively since the
        // native side does no bounds-checking of its own.
        final int ceiling = maxHeight.getValue().intValue() - 1;
        final BlockPos start = clampY(mc.player.blockPosition(), ceiling);
        final long ctx = context;
        final boolean seedKnownForThisCall = seedKnown();

        // In no-seed mode the search space is walled off just past the real-fed radius
        // (see buildWallRing) -- a goal farther than that is architecturally unreachable
        // in a single pathFind call, and the native side falls back to some arbitrary
        // direction when the true target can't be reached (observed: consistently +Z
        // regardless of where the real goal was). Route toward a frontier waypoint on the
        // wall boundary, in the goal's direction, instead of the real goal until the
        // player is close enough for the goal to actually sit inside the known bubble.
        // Seed-known mode has no wall (generation covers the whole distance), so it
        // always targets the real goal directly.
        final BlockPos target = clampY(
            seedKnownForThisCall ? goal : frontierTarget(start, goal), ceiling);

        pathfindInProgress = true;
        new Thread(() -> {
            inFlightNativeCalls.incrementAndGet();
            try {
                dev.babbaj.pathfinder.PathSegment segment;
                synchronized (nativeLock) {
                    segment = NetherPathfinder.pathFind(
                        ctx,
                        start.getX(), start.getY(), start.getZ(),
                        target.getX(), target.getY(), target.getZ(),
                        // seedKnownForThisCall: false = GENERATE using the real seed baked
                        // into this context by createContext() -- the native side
                        // reproduces actual server terrain for any unfed chunk, enabling
                        // long-range avoidance/backtrack/turns ahead of loaded chunks.
                        // !seedKnownForThisCall: true = default cache-miss chunks to AIR.
                        // Moot for correctness once the wall ring (feedNearbyChunks) is in
                        // place -- the wall makes true cache-misses unreachable by the
                        // search -- but must NOT be `false` here: with no real seed the
                        // context was created with a fake seed 0L, and `false` would
                        // generate WRONG terrain the bot would wrongly avoid/trust.
                        false, true, 500, !seedKnownForThisCall, 10.0
                    );
                }
                if (segment != null) {
                    currentPath = segment.packed;
                    pathCursor = 0;
                }
            } catch (Throwable t) {
                System.err.println("[PathFinder] pathFind failed: " + t);
                t.printStackTrace();
            } finally {
                inFlightNativeCalls.decrementAndGet();
                pathfindInProgress = false;
            }
        }, "PathFinder-pathfind").start();
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (!isReady()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (!isInNether()) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                "[PathFinder] Left the nether — disabling."));
            setState(false);
            return;
        }

        feedNearbyChunks(mc);

        if (!flying || goal == null) return;

        // Dead-end / stuck recovery: sustained collision while trying to fly the path
        // means the route we're following rams a wall the planner didn't know about
        // (frontier not yet fed) or a corridor that dead-ends. Back off, then force a
        // fresh path instead of waiting on the periodic timer or the exhausted-path check.
        if (backoffTicks > 0) {
            backOff(mc);
            backoffTicks--;
            if (backoffTicks == 0) {
                currentPath = null;
                pathCursor = 0;
                repathTicks = 0;
                requestPath();
            }
            return;
        }

        boolean colliding = mc.player.horizontalCollision || mc.player.verticalCollision;
        if (colliding && currentPath != null) {
            stuckTicks++;
            if (stuckTicks >= STUCK_TICKS_THRESHOLD) {
                stuckTicks = 0;
                backoffTicks = BACKOFF_TICKS;
                return;
            }
        } else {
            stuckTicks = 0;
        }

        if (currentPath == null || pathCursor >= currentPath.length) {
            repathTicks = 0;
            requestPath();
        } else {
            // Periodic re-plan: newly fed terrain (player has moved, feedNearbyChunks
            // loaded more real chunks) can open a better/necessary route before the
            // current path is exhausted or before it's rammed into anything.
            repathTicks++;
            if (repathTicks >= REPATH_INTERVAL_TICKS) {
                repathTicks = 0;
                requestPath();
            }
        }

        steer(mc);
    }

    @EventHandler
    private void onWorldRender(EventWorldRender event) {
        long[] path = currentPath;
        if (path == null || path.length < 2) return;

        WorldDrawer.start();
        int from = Math.max(0, pathCursor);
        for (int i = from; i < path.length - 1; i++) {
            BlockPos a = BlockPos.of(path[i]);
            BlockPos b = BlockPos.of(path[i + 1]);
            double x1 = Math.min(a.getX(), b.getX()) + 0.5 - PATH_THICKNESS;
            double y1 = Math.min(a.getY(), b.getY()) + 0.5 - PATH_THICKNESS;
            double z1 = Math.min(a.getZ(), b.getZ()) + 0.5 - PATH_THICKNESS;
            double x2 = Math.max(a.getX(), b.getX()) + 0.5 + PATH_THICKNESS;
            double y2 = Math.max(a.getY(), b.getY()) + 0.5 + PATH_THICKNESS;
            double z2 = Math.max(a.getZ(), b.getZ()) + 0.5 + PATH_THICKNESS;
            WorldDrawer.box(PATH_COLOR, PATH_OPACITY, PATH_OPACITY, x1, y1, z1, x2, y2, z2);
        }
        WorldDrawer.draw(event.matrices);
    }
}
