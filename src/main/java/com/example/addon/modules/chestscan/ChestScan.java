package com.example.addon.modules.chestscan;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;

public class ChestScan extends AddonModule {
    public static final ChestScan INSTANCE = new ChestScan();

    public final SliderOption scanRadius = new SliderOption(this, "Scan Radius",
        "How far (in blocks) to render tracked chests and consider hopper chains.", 64.0, 8.0, 128.0, 1.0);
    public final ToggleOption hopperChain = new ToggleOption(this, "Hopper Chain",
        "Smart mode to check chests linked to the bottom chest by hoppers", false);

    private final ChestScanStore store = new ChestScanStore();
    private String lastWorldKey = null;

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
    }
}
