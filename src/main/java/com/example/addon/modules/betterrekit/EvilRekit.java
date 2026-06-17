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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.component.DataComponentTypes; // CHUẨN MINECRAFT 1.21

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

    public final SliderOption delay = new SliderOption(this, "Delay", "Tick delay", 1.0, 0.0, 10.0, 1.0);
    public final SliderOption actionsPerTick = new SliderOption(this, "Frequence", "", 1.0, 1.0, 5.0, 1.0);

    public Map<Integer, KitItem> activeKit = new HashMap<>();
    public String activeKitName = "";
    private int ticks = 0;
    private final File folder;

    public EvilRekit() {
        super("EvilRekit", "Better Regear");
        folder = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/evilrekit");
        if (!folder.exists()) folder.mkdirs();
    }

    public static class KitItem {
        public String id;
        public String name;
        public int maxCount;
    }

    private void info(String msg) { ChatHelper.sendMsg("EvilRekit", "§a" + msg); }
    private void error(String msg) { ChatHelper.sendMsg("EvilRekit", "§c" + msg); }

    // HÀM MỚI BỔ SUNG ĐỂ SỬA LỖI KITCOMMAND.JAVA
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

    public void saveKit(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        Map<Integer, KitItem> kitData = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            int slot = getHandlerSlotPlayerOnly(i);
            ItemStack stack = mc.player.playerScreenHandler.getSlot(slot).getStack();
            if (!stack.isEmpty()) {
                KitItem k = new KitItem();
                k.id = Registries.ITEM.getId(stack.getItem()).toString();
                k.maxCount = stack.getMaxCount();
                
                if (stack.contains(DataComponentTypes.CUSTOM_NAME)) {
                    k.name = stack.getName().getString();
                }
                
                kitData.put(i, k);
            }
        }
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            FileWriter writer = new FileWriter(new File(folder, name + ".json"));
            gson.toJson(kitData, writer);
            writer.close();
            activeKit = kitData;
            activeKitName = name;
            
            java.io.File lastKitFile = new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
            java.nio.file.Files.writeString(lastKitFile.toPath(), name);
            
            info("Kit saved and activated: " + name);
        } catch (Exception e) {
            error("Error saving kit!");
            e.printStackTrace();
        }
    }

    public void loadKit(String name) {
        try {
            File file = new File(folder, name + ".json");
            if (!file.exists()) { error("Kit not found: " + name); return; }
            Gson gson = new Gson();
            FileReader reader = new FileReader(file);
            Type type = new TypeToken<Map<Integer, KitItem>>() {}.getType();
            activeKit = gson.fromJson(reader, type);
            activeKitName = name;
            
            java.io.File lastKitFile = new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
            java.nio.file.Files.writeString(lastKitFile.toPath(), name);
            
            reader.close();
            info("Kit loaded: " + name);
        } catch (Exception e) {
            error("Error occurred while reading kit!");
            e.printStackTrace();
        }
    }

    public void listKits() {
        File[] files = folder.listFiles();
        if (files == null || files.length == 0) { info("You don't have any kits."); return; }
        info("Available kits:");
        for (File f : files) if (f.getName().endsWith(".json")) info("- " + f.getName().replace(".json", ""));
    }

    public void deleteKit(String name) {
        File file = new File(folder, name + ".json");
        if (file.exists() && file.delete()) {
            if (name.equals(activeKitName)) { activeKit.clear(); activeKitName = ""; }
            info("Kit deleted: " + name);
        } else {
            error("Failed to delete kit.");
        }
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || activeKit.isEmpty() || !(mc.currentScreen instanceof HandledScreen)) return;
        if (mc.currentScreen instanceof InventoryScreen) return; 

        if (ticks < delay.getValue()) { ticks++; return; }
        ticks = 0;

        int executed = 0;
        while (executed < actionsPerTick.getValue().intValue()) {
            if (!pullFromContainerTick(mc)) break;
            executed++;
        }
    }

    private boolean pullFromContainerTick(MinecraftClient mc) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        int containerSize = handler.slots.size() - 36;
        if (containerSize <= 0) return false;

        if (!handler.getCursorStack().isEmpty()) {
            return false;
        }

        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;

            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getStack();
            if (isShulkerBox(playerStack)) {
                // Nhét item "đen đủi" vào slot rỗng không thuộc kit nào để bù đắp
                if (!isItemCompensated(handler, containerSize, kit)) {
                    int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                    int emptySlot = findEmptyUnassignedSlot(handler, containerSize);
                    if (containerSlot != -1 && emptySlot != -1) {
                        atomicSwap(mc, handler.syncId, containerSlot, emptySlot);
                        return true;
                    }
                }
                continue;
            }
            if (!isCorrectItem(playerStack, kit)) {
                int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                if (containerSlot != -1) {
                    atomicSwap(mc, handler.syncId, containerSlot, playerSlot);
                    return true; 
                }
            } 
            else if (playerStack.getCount() < playerStack.getMaxCount()) {
                int exactSlot = findExactItemInContainer(handler, containerSize, playerStack);
                if (exactSlot != -1) {
                    atomicSwap(mc, handler.syncId, exactSlot, playerSlot);
                    return true; 
                }
                
                int bestSlot = findBestItemInContainer(handler, containerSize, kit);
                if (bestSlot != -1) {
                    ItemStack containerStack = handler.getSlot(bestSlot).getStack();
                    if (containerStack.getCount() > playerStack.getCount()) {
                        atomicSwap(mc, handler.syncId, bestSlot, playerSlot);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void atomicSwap(MinecraftClient mc, int syncId, int containerSlot, int playerSlot) {
        click(mc, syncId, containerSlot, 0, SlotActionType.PICKUP); 
        click(mc, syncId, playerSlot, 0, SlotActionType.PICKUP);    
        click(mc, syncId, containerSlot, 0, SlotActionType.PICKUP); 
    }

    private void click(MinecraftClient mc, int syncId, int slotId, int button, SlotActionType type) {
        mc.interactionManager.clickSlot(syncId, slotId, button, type, mc.player);
    }

    private boolean isCorrectItem(ItemStack stack, KitItem kit) {
        if (stack.isEmpty()) return false;
        Item expected = Registries.ITEM.get(Identifier.of(kit.id));
        return stack.isOf(expected); 
    }

    private int findBestItemInContainer(ScreenHandler handler, int containerSize, KitItem kit) {
        Item expected = Registries.ITEM.get(Identifier.of(kit.id));
        int bestSlot = -1;
        int maxCount = -1;
        
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.isOf(expected)) {
                if (stack.getCount() > maxCount) {
                    maxCount = stack.getCount();
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private int findExactItemInContainer(ScreenHandler handler, int containerSize, ItemStack targetStack) {
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, targetStack)) return i;
        }
        return -1;
    }
    
    private int findEmptyContainerSlot(ScreenHandler handler, int containerSize) {
        for (int i = 0; i < containerSize; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private int getPlayerHandlerSlot(int containerSize, int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return containerSize + 27 + invSlot; 
        if (invSlot >= 9 && invSlot <= 35) return containerSize + (invSlot - 9);
        return -1;
    }

    private int getHandlerSlotPlayerOnly(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;  
        if (invSlot >= 9 && invSlot <= 35) return invSlot;      
        return -1;
    }
    private boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock;
    }
    private boolean isItemCompensated(ScreenHandler handler, int containerSize, KitItem kit) {
        Item expected = Registries.ITEM.get(Identifier.of(kit.id));
        for (int i = 0; i < 36; i++) {
            // Chỉ tìm trong các slot rác KHÔNG thuộc Kit (tránh đụng chạm)
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getStack().isOf(expected)) return true;
            }
        }
        return false;
    }

    private int findEmptyUnassignedSlot(ScreenHandler handler, int containerSize) {
        for (int i = 0; i < 36; i++) {
            // Chỉ chọn slot trống và KHÔNG thuộc Kit để an toàn tuyệt đối
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getStack().isEmpty()) return slot;
            }
        }
        return -1;
    }
}