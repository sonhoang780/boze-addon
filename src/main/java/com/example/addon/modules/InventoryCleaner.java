package com.example.addon.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class InventoryCleaner extends AddonModule {
    public static final InventoryCleaner INSTANCE = new InventoryCleaner();

    public enum CleanMode { WhiteList, BlackList, All }

    public final ModeOption<CleanMode> mode = new ModeOption<>(this, "Mode",
        "WhiteList = drop items NOT in list. BlackList = drop items IN list. All = drop everything.",
        CleanMode.WhiteList);

    public final ToggleOption ignoreHotbar = new ToggleOption(this, "IgnoreHotbar",
        "Skip hotbar slots (0-8) when cleaning.", true);

    public final ToggleOption throwWorse = new ToggleOption(this, "ThrowWorse",
        "Drop lower-tier duplicate tools of same type (e.g. diamond+iron pickaxe → drop iron). Compares base tier, not remaining HP.", true);

    public final ToggleOption editWhitelist = new ToggleOption(this, "EditWhitelist",
        "Open the whitelist editor screen.", false);

    public final ToggleOption others = new ToggleOption(this, "Others",
        "Drop from other GUIs like Chest, Shulker, EnderChest...", false);

    public final SliderOption delay = new SliderOption(this, "Delay",
        "Tick delay between drop passes.", 1.0, 0.0, 20.0, 1.0);

    public final SliderOption actionsPerTick = new SliderOption(this, "ActionsPerTick",
        "Max items to drop per pass.", 5.0, 1.0, 20.0, 1.0);

    // ── Whitelist (static so WhitelistEditorScreen can read/write directly) ──
    public static Set<String> whitelist = new HashSet<>();

    private int ticks = 0;

    private static final Path WHITELIST_FILE =
        FabricLoader.getInstance().getConfigDir().resolve("inventory_cleaner_whitelist.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public InventoryCleaner() {
        super("InventoryCleaner", "Auto-drop unwanted items from inventory each tick.");
    }

    @Override
    public void onEnable() {
        loadWhitelist();
    }

    @Override
    public void onDisable() {
        saveWhitelist();
    }

    public static void saveWhitelist() {
        try {
            Files.writeString(WHITELIST_FILE, GSON.toJson(whitelist));
        } catch (Exception ignored) {}
    }

    public static void loadWhitelist() {
        try {
            if (Files.exists(WHITELIST_FILE)) {
                Type type = new TypeToken<Set<String>>() {}.getType();
                Set<String> loaded = GSON.fromJson(Files.readString(WHITELIST_FILE), type);
                if (loaded != null) whitelist = loaded;
            }
        } catch (Exception ignored) {}
    }

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();

        if (editWhitelist.getValue()) {
            editWhitelist.setValue(false);
            mc.execute(() -> mc.setScreen(new com.example.addon.screens.WhitelistEditorScreen()));
            return;
        }

        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.player.isCreative()) return;

        // Skip when external container is open, unless others is enabled
        boolean externalGui = mc.screen instanceof AbstractContainerScreen
                           && !(mc.screen instanceof InventoryScreen);
        if (externalGui && !others.getValue()) return;

        if (ticks < delay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        int containerId = mc.player.containerMenu.containerId;
        int actions = 0;
        int maxActions = actionsPerTick.getValue().intValue();

        // Scan inventory slots: invSlot 0-35 (0-8 = hotbar, 9-35 = main)
        for (int invSlot = 0; invSlot < 36 && actions < maxActions; invSlot++) {
            if (ignoreHotbar.getValue() && invSlot <= 8) continue;

            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (stack.isEmpty()) continue;

            if (shouldDrop(stack)) {
                int handlerSlot = invToHandlerSlot(invSlot);
                // button=1 with THROW drops the entire stack
                mc.gameMode.handleContainerInput(containerId, handlerSlot, 1, ContainerInput.THROW, mc.player);
                actions++;
            }
        }

        // ThrowWorse pass (counts against actionsPerTick budget)
        if (throwWorse.getValue() && actions < maxActions) {
            runThrowWorsePass(mc, containerId, maxActions - actions);
        }

        // Others: also drop from open container slots (chest / shulker / enderchest)
        if (externalGui && others.getValue() && actions < maxActions) {
            int totalSlots = mc.player.containerMenu.slots.size();
            int containerSlotCount = totalSlots - 36; // last 36 are always player inv + hotbar
            for (int slot = 0; slot < containerSlotCount && actions < maxActions; slot++) {
                ItemStack stack = mc.player.containerMenu.slots.get(slot).getItem();
                if (stack.isEmpty()) continue;
                if (shouldDrop(stack)) {
                    mc.gameMode.handleContainerInput(containerId, slot, 1, ContainerInput.THROW, mc.player);
                    actions++;
                }
            }
        }
    }

    private boolean shouldDrop(ItemStack stack) {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(stack.getItem()).toString();
        return switch (mode.getValue()) {
            case All       -> true;
            case WhiteList -> !whitelist.contains(key);
            case BlackList ->  whitelist.contains(key);
        };
    }

    private void runThrowWorsePass(Minecraft mc, int containerId, int budget) {
        String[] suffixes = { "_pickaxe", "_axe", "_shovel", "_hoe", "_sword" };
        int remaining = budget;

        for (String suffix : suffixes) {
            if (remaining <= 0) break;
            java.util.List<int[]> group = new java.util.ArrayList<>(); // [invSlot, maxDamage]
            for (int invSlot = 0; invSlot < 36; invSlot++) {
                if (ignoreHotbar.getValue() && invSlot <= 8) continue;
                ItemStack stack = mc.player.getInventory().getItem(invSlot);
                if (stack.isEmpty()) continue;
                String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).toString();
                if (key.endsWith(suffix)) {
                    group.add(new int[]{ invSlot, stack.getMaxDamage() });
                }
            }
            if (group.size() < 2) continue;

            int maxDur = group.stream().mapToInt(e -> e[1]).max().orElse(0);
            for (int[] entry : group) {
                if (remaining <= 0) break;
                if (entry[1] < maxDur) {
                    int handlerSlot = invToHandlerSlot(entry[0]);
                    mc.gameMode.handleContainerInput(containerId, handlerSlot, 1, ContainerInput.THROW, mc.player);
                    remaining--;
                }
            }
        }
    }

    // invSlot 0-8  → handlerSlot 36-44 (hotbar at bottom of InventoryMenu)
    // invSlot 9-35 → handlerSlot 9-35  (main inventory)
    private static int invToHandlerSlot(int invSlot) {
        return (invSlot <= 8) ? 36 + invSlot : invSlot;
    }
}
