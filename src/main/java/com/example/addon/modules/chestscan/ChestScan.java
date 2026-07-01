package com.example.addon.modules.chestscan;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class ChestScan extends AddonModule {
    public static final ChestScan INSTANCE = new ChestScan();

    public final SliderOption scanRadius = new SliderOption(this, "Scan Radius",
        "How far (in blocks) to render tracked chests and consider hopper chains.", 64.0, 8.0, 128.0, 1.0);
    public final ToggleOption hopperChain = new ToggleOption(this, "Hopper Chain",
        "Smart mode to check chests linked to the bottom chest by hoppers", false);

    private final ChestScanStore store = new ChestScanStore();
    private String lastWorldKey = null;

    private BlockPos lastLookedAtChestPos = null;
    private boolean wasChestMenuOpenLastTick = false;
    private BlockPos openChestPos = null;
    private ChestScanStore.ChestStatus lastSnapshotStatus = null;

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
