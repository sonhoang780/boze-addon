package com.example.addon.modules.chestscan;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.event.EventWorldRender;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.render.ClientColor;
import dev.boze.api.render.ColorMaker;
import dev.boze.api.render.WorldDrawer;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ChestScan extends AddonModule {
    public static final ChestScan INSTANCE = new ChestScan();

    public final SliderOption scanRadius = new SliderOption(this, "ScanRadius",
        "How far (in blocks) to render tracked chests and consider hopper chains.", 64.0, 8.0, 128.0, 1.0);
    public final ToggleOption hopperChain = new ToggleOption(this, "HopperChain",
        "Smart mode to check chests linked to the bottom chest by hoppers", false);

    private final ChestScanStore store = new ChestScanStore();
    private String lastWorldKey = null;

    public ChestScanStore getStore() {
        return store;
    }

    private static final ClientColor EMPTY_COLOR = ColorMaker.staticColor(0, 200, 0);
    private static final ClientColor PARTIAL_COLOR = ColorMaker.staticColor(230, 200, 0);
    private static final ClientColor FULL_COLOR = ColorMaker.staticColor(220, 0, 0);
    private static final float FILL_OPACITY = 0.47f;
    private static final float OUTLINE_OPACITY = 1.0f;

    private BlockPos lastLookedAtChestPos = null;
    private boolean wasChestMenuOpenLastTick = false;
    private BlockPos openChestPos = null;
    private ChestScanStore.ChestStatus lastSnapshotStatus = null;

    private int chainTicks = 0;
    private Set<BlockPos> lastInferredEmpty = Collections.emptySet();

    // Debounces store.remove() in onWorldRender: right after a relog/teleport, a chunk
    // can report hasChunk()==true while its block/section data is still settling, so
    // getBlockState briefly reads a tracked chest's position as non-chest for one or
    // more frames even though the chest is really there. Only pruning after the
    // mismatch holds for real wall-clock time (not just one frame) stops that transient
    // false negative from permanently deleting the record (user report 2026-07-15:
    // chests inside ScanRadius silently lost on relog/teleport-back; chests outside
    // ScanRadius -- never checked here -- were unaffected, which pointed straight here).
    private final Map<BlockPos, Long> missingSinceMs = new HashMap<>();
    private static final long PRUNE_GRACE_MS = 1500;

    private ChestScan() {
        super("ChestScan", "Highlights opened chests by contents (empty/partial/full), with optional hopper-chain inference.");
        // onTick bails out the instant mc.player/mc.level go null (line below: "if
        // (mc.player == null || mc.level == null) return;"), which happens almost
        // immediately on disconnect -- the SAME tick that would otherwise detect
        // "chest screen just closed" (wasChestMenuOpenLastTick -> false transition)
        // and call finalizeChestState() never gets the chance to run it, so a chest
        // opened right before disconnecting was never persisted at all (user report,
        // 2026-07-16: "thoát ra vào lại server, phần render vừa rồi mất luôn"). This
        // fires BEFORE that teardown (same disconnect hook already proven for
        // HoodResearch's own stale-task bug this session) and finalizes any chest
        // that's still open at that moment using whatever's already in memory.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> {
                if (openChestPos != null && lastSnapshotStatus != null) {
                    finalizeChestState(client, openChestPos, lastSnapshotStatus);
                }
                openChestPos = null;
                lastSnapshotStatus = null;
                wasChestMenuOpenLastTick = false;
            });
    }

    @Override
    public void onEnable() {
        store.loadForWorld();
        lastWorldKey = ChestScanStore.currentWorldKey();
    }

    @Override
    public void onDisable() {
        lastWorldKey = null;
        lastLookedAtChestPos = null;
        wasChestMenuOpenLastTick = false;
        openChestPos = null;
        lastSnapshotStatus = null;
        missingSinceMs.clear();
    }

    private void maybeReloadStoreForWorld() {
        String key = ChestScanStore.currentWorldKey();
        if (!key.equals(lastWorldKey)) {
            store.loadForWorld();
            lastWorldKey = key;
            missingSinceMs.clear();
        }
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        maybeReloadStoreForWorld();

        boolean chestMenuOpenNow = mc.player.containerMenu instanceof ChestMenu;

        if (!chestMenuOpenNow && mc.screen == null) {
            lastLookedAtChestPos = resolveLookedAtChestPos(mc);
        }

        if (chestMenuOpenNow && !wasChestMenuOpenLastTick) {
            openChestPos = lastLookedAtChestPos;
            lastSnapshotStatus = null;
        }

        if (chestMenuOpenNow) {
            ChestMenu menu = (ChestMenu) mc.player.containerMenu;
            lastSnapshotStatus = computeStatus(menu.getContainer());
        }

        if (!chestMenuOpenNow && wasChestMenuOpenLastTick) {
            finalizeChestState(mc, openChestPos, lastSnapshotStatus);
            openChestPos = null;
            lastSnapshotStatus = null;
        }

        wasChestMenuOpenLastTick = chestMenuOpenNow;

        tickChainRecompute(mc);
    }

    private void tickChainRecompute(Minecraft mc) {
        if (!hopperChain.getValue()) {
            lastInferredEmpty = Collections.emptySet();
            return;
        }
        chainTicks++;
        if (chainTicks < 20) return;
        chainTicks = 0;

        BlockPos center = mc.player.blockPosition();
        int radius = scanRadius.getValue().intValue();
        Set<BlockPos> tracked = new HashSet<>(store.positions());

        Map<BlockPos, BlockPos> edges = ChestScanChain.findEdges(mc.level, tracked, center, radius);
        Map<BlockPos, ChestScanStore.ChestStatus> real = new HashMap<>();
        for (BlockPos pos : tracked) {
            real.put(pos, store.get(pos));
        }
        lastInferredEmpty = ChestScanChain.inferEmpty(edges, real);
    }

    @EventHandler
    private void onWorldRender(EventWorldRender event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        double radius = scanRadius.getValue();
        double radiusSq = radius * radius;
        BlockPos center = mc.player.blockPosition();

        WorldDrawer.start();

        for (BlockPos pos : new ArrayList<>(store.positions())) {
            if (center.distSqr(pos) > radiusSq) continue;
            // Chunk not loaded yet (e.g. just reconnected) -> getBlockState falls back to
            // air, which looks identical to "chest was actually broken". Only trust that
            // read (and prune the persisted record) once the chunk has genuinely loaded;
            // otherwise just skip rendering this tick without touching the store.
            if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (!(mc.level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
                long now = System.currentTimeMillis();
                Long since = missingSinceMs.putIfAbsent(pos, now);
                if (since != null && now - since >= PRUNE_GRACE_MS) {
                    store.remove(pos);
                    missingSinceMs.remove(pos);
                }
                continue;
            }
            missingSinceMs.remove(pos);
            WorldDrawer.box(colorFor(store.get(pos)), FILL_OPACITY, OUTLINE_OPACITY, new AABB(pos));
        }

        if (hopperChain.getValue()) {
            for (BlockPos pos : lastInferredEmpty) {
                if (store.get(pos) != null) continue;
                if (center.distSqr(pos) > radiusSq) continue;
                WorldDrawer.box(EMPTY_COLOR, FILL_OPACITY, OUTLINE_OPACITY, new AABB(pos));
            }
        }

        WorldDrawer.draw(event.matrices);
    }

    private ClientColor colorFor(ChestScanStore.ChestStatus status) {
        return switch (status) {
            case EMPTY -> EMPTY_COLOR;
            case PARTIAL -> PARTIAL_COLOR;
            case FULL -> FULL_COLOR;
        };
    }

    private BlockPos resolveLookedAtChestPos(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult bhr) || bhr.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = bhr.getBlockPos();
        return (mc.level.getBlockState(pos).getBlock() instanceof ChestBlock) ? pos : null;
    }

    private ChestScanStore.ChestStatus computeStatus(Container container) {
        int total = container.getContainerSize();
        if (total == 0) return ChestScanStore.ChestStatus.EMPTY;
        int filled = 0;
        for (int i = 0; i < total; i++) {
            if (!container.getItem(i).isEmpty()) filled++;
        }
        if (filled == 0) return ChestScanStore.ChestStatus.EMPTY;
        return (filled == total) ? ChestScanStore.ChestStatus.FULL : ChestScanStore.ChestStatus.PARTIAL;
    }

    private void finalizeChestState(Minecraft mc, BlockPos pos, ChestScanStore.ChestStatus status) {
        if (pos == null || status == null) return;
        store.put(pos, status); // primary persist -- doesn't need mc.level, always runs
        // Double-chest partner lookup needs a live level; the disconnect hook that
        // also calls this method may run with mc.level already null depending on
        // exact teardown ordering -- skip the partner rather than NPE, the primary
        // chest is still saved either way.
        if (mc == null || mc.level == null) return;
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
            store.put(other, status);
        }
    }
}
