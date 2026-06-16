package com.example.addon.modules.betterrekit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.utility.ChatHelper;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EvilRekit extends AddonModule {
    public static final EvilRekit INSTANCE = new EvilRekit();

    // TÍCH HỢP CLASS KITITEM VÀO TRONG NÀY ĐỂ KHÔNG BAO GIỜ LỖI CANNOT FIND SYMBOL
    public static class KitItem {
        public String id;
        public String name;
        public int maxCount;
        public KitItem(String id, String name, int maxCount) {
            this.id = id; this.name = name; this.maxCount = maxCount;
        }
    }

    public final SliderOption delay = new SliderOption(this, "Delay", "", 1.0, 0.0, 10.0, 1.0);
    public final SliderOption frequency = new SliderOption(this, "Frequency", "", 1.0, 1.0, 3.0, 1.0);

    private final File folder = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/evilrekit");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    public Map<Integer, KitItem> activeKit = new HashMap<>();
    public String activeKitName = "";
    public boolean active = false;
    private int ticks = 0;

    public EvilRekit() {
        super("EvilRekit", "");
        if (!folder.exists()) folder.mkdirs();
    }

    @Override
    public void onEnable() { this.active = true; }
    
    @Override
    public void onDisable() { this.active = false; }

    // HÀM IN TIN NHẮN TÍCH HỢP
    public void info(String msg) {
        ChatHelper.sendMsg("EvilRekit", "§a" + msg);
    }

    public void error(String msg) {
        ChatHelper.sendMsg("EvilRekit", "§c" + msg);
    }

    public List<String> getKitNames() {
        List<String> names = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".json")) {
                    names.add(f.getName().replace(".json", ""));
                }
            }
        }
        return names;
    }
    @EventHandler
    private void onTick(EventTick.Post event) {
        if (!this.active || activeKit.isEmpty()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || !(mc.currentScreen instanceof HandledScreen<?> screen)) return;
        if (ticks < delay.getValue()) {
            ticks++;
            return;
        }
        ticks = 0;

        ScreenHandler handler = screen.getScreenHandler();
        int containerSize = handler.slots.size() - 36; 
        if (containerSize != 27 && containerSize != 54) return;
        int actionsDone = 0;

        for (int i = 0; i < 36; i++) {
            if (actionsDone >= frequency.getValue()) break;

            KitItem kitItem = activeKit.get(i);
            int screenSlot = mapToScreenSlot(i, containerSize);
            ItemStack currentStack = handler.getSlot(screenSlot).getStack();

            if (kitItem == null) continue;

            Item targetItem = Registries.ITEM.get(Identifier.of(kitItem.id));
            boolean isWrongItem = !currentStack.isEmpty() && !currentStack.isOf(targetItem);
            boolean needsRefill = currentStack.isEmpty() || (currentStack.isOf(targetItem) && currentStack.getCount() < kitItem.maxCount);

            if (isWrongItem || needsRefill) {
                int containerSlot = findContainerSlot(handler, containerSize, targetItem, kitItem.name);

                if (containerSlot != -1) {
                    swapOrRefill(mc, handler.syncId, containerSlot, screenSlot);
                    actionsDone++;
                } else if (isWrongItem) {
                    int emptySlot = findEmptyContainerSlot(handler, containerSize);
                    if (emptySlot != -1) {
                        moveItem(mc, handler.syncId, screenSlot, emptySlot);
                        actionsDone++;
                    }
                }
            }
        }
    }

    private void swapOrRefill(MinecraftClient mc, int syncId, int containerSlot, int playerSlot) {
        mc.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, playerSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, containerSlot, 0, SlotActionType.PICKUP, mc.player);
    }

    private void moveItem(MinecraftClient mc, int syncId, int fromSlot, int toSlot) {
        mc.interactionManager.clickSlot(syncId, fromSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(syncId, toSlot, 0, SlotActionType.PICKUP, mc.player);
    }

    private int mapToScreenSlot(int playerSlot, int containerSize) {
        if (playerSlot >= 0 && playerSlot <= 8) return containerSize + 27 + playerSlot; 
        if (playerSlot >= 9 && playerSlot <= 35) return containerSize + (playerSlot - 9); 
        return -1;
    }

    private int findContainerSlot(ScreenHandler handler, int containerSize, Item targetItem, String targetName) {
        int fallbackSlot = -1;
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.isOf(targetItem) && !isShulkerBox(stack)) {
                if (targetName != null && stack.getName().getString().equals(targetName)) {
                    return i;
                }
                fallbackSlot = i;
            }
        }
        return fallbackSlot; 
    }

    private int findEmptyContainerSlot(ScreenHandler handler, int containerSize) {
        for (int i = 0; i < containerSize; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock;
    }

    public void saveKit(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        Map<Integer, KitItem> kitData = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && !isShulkerBox(stack)) {
                String id = Registries.ITEM.getId(stack.getItem()).toString();
                // FIX CHUẨN 1.21: Xóa hasCustomName, gọi trực tiếp getString()
                String customName = stack.getName().getString(); 
                int maxCount = stack.getMaxCount(); 
                kitData.put(i, new KitItem(id, customName, maxCount));
            }
        }

        try {
            File file = new File(folder, name + ".json");
            FileWriter writer = new FileWriter(file);
            gson.toJson(kitData, writer);
            writer.close();
            
            activeKit = kitData;
            activeKitName = name;
            try {
                java.io.File lastKitFile = new java.io.File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
                java.nio.file.Files.writeString(lastKitFile.toPath(), name);
            } catch (Exception ignored) {}
            info("Kit saved and activated: " + name);
        } catch (Exception e) {
            error("Error occurred while saving kit!");
            e.printStackTrace();
        }
    }

    public void loadKit(String name) {
        File file = new File(folder, name + ".json");
        if (!file.exists()) {
            error("Kit not found: " + name);
            return;
        }

        try {
            FileReader reader = new FileReader(file);
            Type type = new TypeToken<Map<Integer, KitItem>>() {}.getType();
            activeKit = gson.fromJson(reader, type);
            activeKitName = name;
            try {
                java.io.File lastKitFile = new java.io.File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
                java.nio.file.Files.writeString(lastKitFile.toPath(), name);
            } catch (Exception ignored) {}
            reader.close();
            info("Kit loaded: " + name);
        } catch (Exception e) {
            error("Error occurred while reading kit!");
            e.printStackTrace();
        }
    }

    public void listKits() {
        File[] files = folder.listFiles();
        if (files == null || files.length == 0) {
            info("You don't have any kits.");
            return;
        }
        info("Available kits:");
        for (File f : files) {
            if (f.getName().endsWith(".json")) {
                info("- " + f.getName().replace(".json", ""));
            }
        }
    }

    public void deleteKit(String name) {
        File file = new File(folder, name + ".json");
        if (file.exists() && file.delete()) {
            if (name.equals(activeKitName)) {
                activeKit.clear();
                activeKitName = "";
            }
            info("Kit deleted: " + name);
        } else {
            error("No kits found");
        }
    }
}