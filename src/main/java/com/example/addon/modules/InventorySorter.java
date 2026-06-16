package com.example.addon.modules;

import com.example.addon.modules.betterrekit.EvilRekit;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
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

    public final SliderOption delay = new SliderOption(this, "Delay", "Tick delay", 1.0, 0.0, 10.0, 1.0);
    public final SliderOption actionsPerTick = new SliderOption(this, "Actions/Tick", "Max actions per tick", 1.0, 1.0, 5.0, 1.0);
    public final ToggleOption ignoreHotbar = new ToggleOption(this, "Ignore Hotbar", "", false);
    public final ToggleOption silent = new ToggleOption(this, "Silent", "Sort without opening inventory", true);

    private int ticks = 0;
    public boolean active = false;

    public InventorySorter() {
        super("InventorySorter", "O(N) Fast Packet Sort (Atomic & Swap).");
    }

    @Override 
    public void onEnable() { 
        this.active = true;
        if (EvilRekit.INSTANCE.activeKit.isEmpty()) {
            try {
                java.io.File lastKitFile = new java.io.File(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(), "boze/last_kit_save.txt");
                if (lastKitFile.exists()) {
                    String lastName = java.nio.file.Files.readString(lastKitFile.toPath()).trim();
                    if (!lastName.isEmpty()) EvilRekit.INSTANCE.loadKit(lastName);
                }
            } catch (Exception ignored) {}

            if (EvilRekit.INSTANCE.activeKit.isEmpty()) {
                ChatHelper.sendMsg("InventorySorter", "§eĐang chờ nạp Kit... Module vẫn BẬT.");
            }
        }
    }

    @Override public void onDisable() { this.active = false; }

    @EventHandler
    private void onTick(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (!this.active || mc.player == null || EvilRekit.INSTANCE.activeKit.isEmpty()) return;
        if (!silent.getValue() && !(mc.currentScreen instanceof InventoryScreen)) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) return;

        if (ticks < delay.getValue()) {
            ticks++;
            return;
        }
        ticks = 0;

        int executed = 0;
        while (executed < actionsPerTick.getValue().intValue()) {
            if (!sortTick(mc)) break;
            executed++;
        }
    }

    private boolean sortTick(MinecraftClient mc) {
        ScreenHandler handler = mc.player.currentScreenHandler;

        // 1. CHỐNG KẸT CHUỘT: Nếu chuột đang cầm rác do server lag, ném nó đi ngay lập tức!
        if (!handler.getCursorStack().isEmpty()) {
            int empty = findEmptySlot(handler);
            if (empty != -1) click(empty, 0, SlotActionType.PICKUP);
            else click(-999, 0, SlotActionType.PICKUP);
            return true;
        }

        // 2. GOM RÁC ĐỒNG BỘ: Chạy nhanh để gộp các stack lẻ tẻ lại
        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;
            int slotI = getHandlerSlot(i);
            ItemStack stackI = handler.getSlot(slotI).getStack();
            if (stackI.isEmpty() || isShulkerBox(stackI) || stackI.getCount() >= stackI.getMaxCount()) continue;

            for (int j = i + 1; j < 36; j++) {
                if (ignoreHotbar.getValue() && j <= 8) continue;
                int slotJ = getHandlerSlot(j);
                ItemStack stackJ = handler.getSlot(slotJ).getStack();
                if (!stackJ.isEmpty() && stackI.isOf(stackJ.getItem()) && stackI.getName().getString().equals(stackJ.getName().getString())) {
                    atomicSwap(slotJ, slotI); // Gộp 2 cục vào nhau bằng Atomic
                    return true;
                }
            }
        }

        // 3. SẮP XẾP O(N) TỐC ĐỘ BÀN THỜ
        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;

            int targetSlot = getHandlerSlot(i);
            ItemStack currentStack = handler.getSlot(targetSlot).getStack();
            if (isShulkerBox(currentStack)) continue;

            EvilRekit.KitItem kit = EvilRekit.INSTANCE.activeKit.get(i);
            if (isCorrectItem(currentStack, kit)) continue;

            // Tìm vị trí của đồ chuẩn để đắp vào ô này
            int sourceInvSlot = findItemForKit(handler, kit);
            if (sourceInvSlot != -1) {
                int sourceHandlerSlot = getHandlerSlot(sourceInvSlot);

                // 🔥 TUYỆT KỸ PACKET: Tráo thẳng Main Inv với Hotbar bằng 1 Packet duy nhất
                if (i <= 8 && sourceInvSlot >= 9) {
                    click(sourceHandlerSlot, i, SlotActionType.SWAP);
                    return true;
                }
                if (sourceInvSlot <= 8 && i >= 9) {
                    click(targetSlot, sourceInvSlot, SlotActionType.SWAP);
                    return true;
                }

                // Nếu không dính líu đến Hotbar, xài Atomic Swap
                atomicSwap(sourceHandlerSlot, targetSlot);
                return true;
            }
        }
        return false;
    }

    // 🔥 HOÁN VỊ NGUYÊN TỬ: Nén 3 click vào 1 tick, chuột luôn trống rỗng sau khi xong!
    private void atomicSwap(int slot1, int slot2) {
        click(slot1, 0, SlotActionType.PICKUP); // Nhấc A
        click(slot2, 0, SlotActionType.PICKUP); // Đặt A vào B, nhấc B lên
        click(slot1, 0, SlotActionType.PICKUP); // Trả B về chỗ cũ của A
    }

    private void click(int slotId, int button, SlotActionType type) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slotId, button, type, mc.player);
    }

    private boolean isCorrectItem(ItemStack stack, EvilRekit.KitItem kit) {
        if (kit == null) return true;
        if (stack.isEmpty()) return false;
        Item expected = Registries.ITEM.get(Identifier.of(kit.id));
        return stack.isOf(expected);
    }

    private int findItemForKit(ScreenHandler handler, EvilRekit.KitItem targetKit) {
        if (targetKit == null) return -1;
        Item expected = Registries.ITEM.get(Identifier.of(targetKit.id));

        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;

            int slotI = getHandlerSlot(i);
            ItemStack stack = handler.getSlot(slotI).getStack();
            if (stack.isEmpty() || isShulkerBox(stack)) continue;

            if (stack.isOf(expected)) {
                EvilRekit.KitItem itsOwnKit = EvilRekit.INSTANCE.activeKit.get(i);
                if (isCorrectItem(stack, itsOwnKit)) continue; // Kệ cmn đồ đã đúng vị trí
                return i; // Trả về Inv Slot (0-35)
            }
        }
        return -1;
    }

    private int findEmptySlot(ScreenHandler handler) {
        for (int i = 0; i < 36; i++) {
            if (ignoreHotbar.getValue() && i <= 8) continue;
            int slotId = getHandlerSlot(i);
            if (handler.getSlot(slotId).getStack().isEmpty() && EvilRekit.INSTANCE.activeKit.get(i) == null) return slotId;
        }
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