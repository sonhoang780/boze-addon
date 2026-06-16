package com.example.addon.modules;

import com.example.addon.modules.betterrekit.EvilRekit;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

public class InventorySorter extends AddonModule {
    public static final InventorySorter INSTANCE = new InventorySorter();

    public final SliderOption delay = new SliderOption(this, "Delay", "", 1.0, 0.0, 10.0, 1.0);
    public final ToggleOption ignoreHotbar = new ToggleOption(this, "Ignore Hotbar", "", false);
    public final ToggleOption silent = new ToggleOption(this, "Silent", "Sort even if you dont open inventory", true);

    private int ticks = 0;
    public boolean active = false;
    
    public InventorySorter() {
        super("InventorySorter", "Tự động gom và sắp xếp kho đồ theo EvilRekit.");
    }

    @Override public void onEnable() { this.active = true; }
    @Override public void onDisable() { this.active = false; }

    @EventHandler
    private void onTick(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (!this.active || mc.player == null || EvilRekit.INSTANCE.activeKit.isEmpty()) return;

        if (!silent.getValue() && !(mc.currentScreen instanceof InventoryScreen)) return;

        // Nếu người chơi đang mở Rương/Shulker/Lò nung,... thì DỪNG NGAY để tránh click loạn slot hòm
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return;

        // Xử lý Trễ tick
        if (ticks < delay.getValue()) {
            ticks++;
            return;
        }
        ticks = 0;

        // DÙNGcurrentScreenHandler ĐỂ TƯƠNG THÍCH CHUẨN XÁC VỚI MỌI TRẠNG THÁI ĐÓNG/MỞ
        ScreenHandler handler = mc.player.currentScreenHandler;
        int syncId = handler.syncId;

        // Lấy thông tin vật phẩm đang nằm trên con trỏ chuột của người chơi
        ItemStack cursorStack = handler.getCursorStack();

        if (cursorStack.isEmpty()) {
            // ── PHASE 1: CHUỘT ĐANG TRỐNG -> ĐI TÌM MÓN SAI VỊ TRÍ ĐỂ NHẤC LÊN ──
            for (int i = 0; i < 36; i++) {
                if (ignoreHotbar.getValue() && i <= 8) continue;

                EvilRekit.KitItem kitItem = EvilRekit.INSTANCE.activeKit.get(i);
                if (kitItem == null) continue;

                int targetSlot = getHandlerSlot(i);
                ItemStack currentStack = handler.getSlot(targetSlot).getStack();

                if (isShulkerBox(currentStack)) continue;

                Item expectedItem = Registries.ITEM.get(Identifier.of(kitItem.id));
                boolean isCorrect = !currentStack.isEmpty() && currentStack.isOf(expectedItem);
                
                if (kitItem.name != null && isCorrect) {
                    isCorrect = currentStack.getName().getString().equals(kitItem.name);
                }

                // Nếu ô này sai item hoặc đang trống, tìm item đúng từ ô khác bốc lên chuột
                if (!isCorrect) {
                    int sourceSlot = findSourceSlot(handler, expectedItem, kitItem.name, i);
                    if (sourceSlot != -1) {
                        // Click 1 phát duy nhất để nhấc vật phẩm lên con trỏ chuột
                        mc.interactionManager.clickSlot(syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
                        return;
                    }
                }
            }
        } else {
            // ── PHASE 2: CHUỘT ĐANG GIỮ ĐỒ -> ĐẶT NÓ XUỐNG VỊ TRÍ ĐÚNG THEO KIT ──
            if (isShulkerBox(cursorStack)) {
                int emptySlot = findEmptySlot(handler);
                if (emptySlot != -1) mc.interactionManager.clickSlot(syncId, emptySlot, 0, SlotActionType.PICKUP, mc.player);
                return;
            }

            Item cursorItem = cursorStack.getItem();

            for (int i = 0; i < 36; i++) {
                if (ignoreHotbar.getValue() && i <= 8) continue;

                EvilRekit.KitItem kitItem = EvilRekit.INSTANCE.activeKit.get(i);
                if (kitItem == null) continue;

                Item expectedItem = Registries.ITEM.get(Identifier.of(kitItem.id));
                if (cursorItem == expectedItem) {
                    int targetSlot = getHandlerSlot(i);
                    ItemStack targetStack = handler.getSlot(targetSlot).getStack();

                    boolean isTargetCorrect = !targetStack.isEmpty() && targetStack.isOf(expectedItem);
                    if (kitItem.name != null && isTargetCorrect) {
                        isTargetCorrect = targetStack.getName().getString().equals(kitItem.name);
                    }

                    // Nếu ô mục tiêu đang trống hoặc chứa sai món -> Ấn đặt xuống ngay lập tức
                    if (!isTargetCorrect) {
                        mc.interactionManager.clickSlot(syncId, targetSlot, 0, SlotActionType.PICKUP, mc.player);
                        return;
                    }
                }
            }

            // Nếu món đồ trên chuột không nằm trong yêu cầu của Kit, ném tạm vào ô trống bất kỳ
            int emptySlot = findEmptySlot(handler);
            if (emptySlot != -1) {
                mc.interactionManager.clickSlot(syncId, emptySlot, 0, SlotActionType.PICKUP, mc.player);
            }
        }
    }

    private int findSourceSlot(ScreenHandler handler, Item targetItem, String targetName, int skipInvSlot) {
        int fallback = -1;
        for (int i = 0; i < 36; i++) {
            if (i == skipInvSlot) continue;
            if (ignoreHotbar.getValue() && i <= 8) continue;

            int slotI = getHandlerSlot(i);
            ItemStack stack = handler.getSlot(slotI).getStack();

            if (isShulkerBox(stack)) continue;

            if (!stack.isEmpty() && stack.isOf(targetItem)) {
                if (targetName != null && stack.getName().getString().equals(targetName)) {
                    return slotI;
                }
                fallback = slotI;
            }
        }
        return fallback;
    }

    private int findEmptySlot(ScreenHandler handler) {
        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;
            int slotId = getHandlerSlot(i);
            if (handler.getSlot(slotId).getStack().isEmpty()) return slotId;
        }
        return -1;
    }

    private int getHandlerSlot(int invSlot) {
        if (invSlot >= 0 && invSlot <= 8) return 36 + invSlot;  
        if (invSlot >= 9 && invSlot <= 35) return invSlot;      
        return -1;
    }

    private boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem && ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock;
    }
}