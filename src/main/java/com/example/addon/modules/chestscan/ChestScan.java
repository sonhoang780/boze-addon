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

    public final SliderOption scanRadius = new SliderOption(this, "Scan Radius",
        "How far (in blocks) to render tracked chests and consider hopper chains.", 64.0, 8.0, 128.0, 1.0);
    public final ToggleOption hopperChain = new ToggleOption(this, "Hopper Chain",
        "Smart mode to check chests linked to the bottom chest by hoppers", false);

    private final ChestScanStore store = new ChestScanStore();
    private String lastWorldKey = null;

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

    private ChestScan() {
        super("ChestScan", "Highlights opened chests by contents (empty/partial/full), with optional hopper-chain inference.");
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
    }

    private void maybeReloadStoreForWorld() {
        String key = ChestScanStore.currentWorldKey();
        if (!key.equals(lastWorldKey)) {
            store.loadForWorld();
            lastWorldKey = key;
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
            if (!(mc.level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
                store.remove(pos);
                continue;
            }
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
        store.put(pos, status);
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
            store.put(other, status);
        }
    }
}
