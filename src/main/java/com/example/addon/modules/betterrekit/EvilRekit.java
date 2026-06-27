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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;

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
        restoreLastKit();
    }

    // Auto-load the last kit that was saved/loaded, called once at class init.
    private void restoreLastKit() {
        try {
            java.io.File lastKitFile = new java.io.File(FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
            if (!lastKitFile.exists()) return;
            String name = java.nio.file.Files.readString(lastKitFile.toPath()).trim();
            if (name.isEmpty()) return;
            File kitFile = new File(folder, name + ".json");
            if (!kitFile.exists()) return;
            Gson gson = new Gson();
            FileReader reader = new FileReader(kitFile);
            Type type = new TypeToken<Map<Integer, KitItem>>() {}.getType();
            activeKit = gson.fromJson(reader, type);
            activeKitName = name;
            reader.close();
        } catch (Exception ignored) {}
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.player.isCreative()) return;
        Map<Integer, KitItem> kitData = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            int slot = getHandlerSlotPlayerOnly(i);
            ItemStack stack = mc.player.inventoryMenu.getSlot(slot).getItem();
            if (!stack.isEmpty()) {
                KitItem k = new KitItem();
                k.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                k.maxCount = stack.getMaxStackSize();
                
                if (stack.has(DataComponents.CUSTOM_NAME)) {
                    k.name = stack.getHoverName().getString();
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
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            String name = f.getName().replace(".json", "");
            if (name.equals(activeKitName)) {
                ChatHelper.sendMsg("EvilRekit", "§9- " + name + " [active]");
            } else {
                ChatHelper.sendMsg("EvilRekit", "§7- " + name);
            }
        }
    }

    public void showActiveKit() {
        if (activeKitName == null || activeKitName.isEmpty()) {
            error("No kit is currently active.");
        } else {
            ChatHelper.sendMsg("EvilRekit", "§aActive kit: §9" + activeKitName);
        }
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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || activeKit.isEmpty() || !(mc.screen instanceof AbstractContainerScreen)) return;
        if (mc.screen instanceof InventoryScreen) return; 

        if (ticks < delay.getValue()) { ticks++; return; }
        ticks = 0;

        int executed = 0;
        while (executed < actionsPerTick.getValue().intValue()) {
            if (!pullFromContainerTick(mc)) break;
            executed++;
        }
    }

    private boolean pullFromContainerTick(Minecraft mc) {
        AbstractContainerMenu handler = mc.player.containerMenu;
        int containerSize = handler.slots.size() - 36;
        if (containerSize <= 0) return false;

        if (!handler.getCarried().isEmpty()) {
            return false;
        }

        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;

            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getItem();
            if (isShulkerBox(playerStack)) continue;
            if (!isCorrectItem(playerStack, kit)) {
                int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                if (containerSlot != -1) {
                    atomicSwap(mc, handler.containerId, containerSlot, playerSlot);
                    return true; 
                }
            } 
            else if (playerStack.getCount() < playerStack.getMaxStackSize()) {
                int exactSlot = findExactItemInContainer(handler, containerSize, playerStack);
                if (exactSlot != -1) {
                    atomicSwap(mc, handler.containerId, exactSlot, playerSlot);
                    return true; 
                }
                
                int bestSlot = findBestItemInContainer(handler, containerSize, kit);
                if (bestSlot != -1) {
                    ItemStack containerStack = handler.getSlot(bestSlot).getItem();
                    if (containerStack.getCount() > playerStack.getCount()) {
                        atomicSwap(mc, handler.containerId, bestSlot, playerSlot);
                        return true;
                    }
                }
            }
        }
        for (int i = 0; i < 36; i++) {
            KitItem kit = activeKit.get(i);
            if (kit == null) continue;

            int playerSlot = getPlayerHandlerSlot(containerSize, i);
            ItemStack playerStack = handler.getSlot(playerSlot).getItem();

            if (isShulkerBox(playerStack)) {
                if (!isItemCompensated(handler, containerSize, kit)) {
                    int containerSlot = findBestItemInContainer(handler, containerSize, kit);
                    int emptySlot = findEmptyUnassignedSlot(handler, containerSize);
                    if (containerSlot != -1 && emptySlot != -1) {
                        atomicSwap(mc, handler.containerId, containerSlot, emptySlot);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void atomicSwap(Minecraft mc, int syncId, int containerSlot, int playerSlot) {
        click(mc, syncId, containerSlot, 0, ContainerInput.PICKUP); 
        click(mc, syncId, playerSlot, 0, ContainerInput.PICKUP);    
        click(mc, syncId, containerSlot, 0, ContainerInput.PICKUP); 
    }

    private void click(Minecraft mc, int syncId, int slotId, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(syncId, slotId, button, type, mc.player);
    }

    private boolean isCorrectItem(ItemStack stack, KitItem kit) {
        if (stack.isEmpty()) return false;
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        return stack.getItem() == expected; 
    }

    private int findBestItemInContainer(AbstractContainerMenu handler, int containerSize, KitItem kit) {
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        int bestSlot = -1;
        int maxCount = -1;
        
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && stack.getItem() == expected) {
                if (stack.getCount() > maxCount) {
                    maxCount = stack.getCount();
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private int findExactItemInContainer(AbstractContainerMenu handler, int containerSize, ItemStack targetStack) {
        for (int i = 0; i < containerSize; i++) {
            ItemStack stack = handler.getSlot(i).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, targetStack)) return i;
        }
        return -1;
    }
    
    private int findEmptyContainerSlot(AbstractContainerMenu handler, int containerSize) {
        for (int i = 0; i < containerSize; i++) {
            if (handler.getSlot(i).getItem().isEmpty()) return i;
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
    private boolean isItemCompensated(AbstractContainerMenu handler, int containerSize, KitItem kit) {
        Item expected = BuiltInRegistries.ITEM.getValue(Identifier.parse(kit.id));
        for (int i = 0; i < 36; i++) {
            // Chỉ tìm trong các slot rác KHÔNG thuộc Kit (tránh đụng chạm)
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getItem().getItem() == expected) return true;
            }
        }
        return false;
    }

    private int findEmptyUnassignedSlot(AbstractContainerMenu handler, int containerSize) {
        for (int i = 0; i < 36; i++) {
            // Chỉ chọn slot trống và KHÔNG thuộc Kit để an toàn tuyệt đối
            if (activeKit.get(i) == null) {
                int slot = getPlayerHandlerSlot(containerSize, i);
                if (handler.getSlot(slot).getItem().isEmpty()) return slot;
            }
        }
        return -1;
    }
}