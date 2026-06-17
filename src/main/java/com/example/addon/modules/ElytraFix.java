package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.event.EventInteract;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.Interaction;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class ElytraFix extends AddonModule {
    public static final ElytraFix INSTANCE = new ElytraFix();

    public enum SwapMode { Normal, Alt, Silent }

    public final SliderOption durability = new SliderOption(this, "Durability %", "Start repairing when below this %.", 50.0, 1.0, 99.0, 1.0);
    public final ModeOption<SwapMode> swapMode = new ModeOption<>(this, "Swap Mode", "Mode to swap to EXP bottles.", SwapMode.Alt);
    public final ToggleOption fastExp = new ToggleOption(this, "Fast Exp", "Throw EXP bottles every tick instead of every 2 ticks. Repairs faster but sends more packets.", false);

    private static final String MODULE_AUTO_ARMOR = "AutoArmor";

    private boolean isRepairing = false;
    private boolean wasAutoArmorOn = false;
    private int ticks = 0;

    // ─── TRẠNG THÁI NÉM EXP (rotate-then-throw đồng bộ qua EventInteract) ───
    private boolean pendingThrow = false;
    private float throwYaw = 0f;
    private float throwPitch = 0f;
    private int pendingExpSlot = -1;

    // Trạng thái đổi elytra từ kho đồ
    private boolean isSwappingElytra = false;
    private int swapElytraTicks = 0;
    private int pendingElytraSlot = -1;

    public ElytraFix() {
        super("ElytraFix", "Auto repair Elytra using EXP bottles with Trajectory Prediction.");
    }

    @Override
    public void onEnable() {
        isRepairing = false;
        wasAutoArmorOn = false;
        ticks = 0;
        isSwappingElytra = false;
        swapElytraTicks = 0;
        pendingThrow = false;
        pendingExpSlot = -1;
        pendingElytraSlot = -1;
    }

    @Override
    public void onDisable() {
        isRepairing = false;
        pendingThrow = false;
        pendingExpSlot = -1;
        cancelElytraSwap();
        if (wasAutoArmorOn) {
            setAutoArmorEnabled(true);
            wasAutoArmorOn = false;
        }
    }

    private void info(String msg) {
        ChatHelper.sendMsg("ElytraFix", "§a" + msg);
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // Mỗi tick reset lịch ném (sẽ set lại bên dưới nếu đủ điều kiện).
        pendingThrow = false;

        // ───────────────────────────────────────────────────────────────
        // FIX LỖI #1 (KẸT CHUỘT / GUI HỎNG):
        // TUYỆT ĐỐI không clickSlot / dùng item khi đang MỞ BẤT KỲ MÀN HÌNH NÀO.
        // Bản cũ chạy clickSlot trên playerScreenHandler ngay cả khi người chơi
        // đang tự tay thao tác trong kho đồ → 2 bên cùng tăng revision của cùng
        // 1 ScreenHandler → DESYNC → màn hình inventory vỡ (chỉ còn tooltip, slot
        // biến mất), chuột kẹt, không đóng được. Chặn ở đây là hết.
        // ───────────────────────────────────────────────────────────────
        if (mc.currentScreen != null) {
            return;
        }

        // 0. QUÉT KHO ĐỒ TỰ MẶC ELYTRA — CHỈ KHI ĐỨNG ĐẤT.
        //    (Cởi elytra ra giữa lúc bay sẽ làm rớt; chỉ đổi khi đang đứng đất.)
        if (!isSwappingElytra && !isRepairing && mc.player.isOnGround()) {
            tryEquipBetterElytraFromInventory(mc);
        }

        if (isSwappingElytra) {
            handleElytraSwapSequence(mc);
            return;
        }

        // 1. CHECK ELYTRA EQUIPPED
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA)) {
            if (isRepairing) stopRepairing();
            return;
        }

        // 2. CALCULATE DURABILITY
        int maxDamage = chest.getMaxDamage();
        int damage = chest.getDamage();
        float pct = ((float) (maxDamage - damage) / maxDamage) * 100f;

        if (pct <= durability.getValue() && !isRepairing) {
            isRepairing = true;
            if (isAutoArmorEnabled()) {
                wasAutoArmorOn = true;
                setAutoArmorEnabled(false);
            }
        }

        if (pct >= 100f && isRepairing) {
            stopRepairing();
            return;
        }

        if (!isRepairing) return;

        // ─── ĐANG SỬA ───
        // FIX LỖI #2 (KHÓA GLIDING): KHÔNG còn setVelocity(ZERO) mỗi tick nữa.
        // Việc ép velocity = 0 chính là thứ khóa cứng không cho bay/gliding.
        // Không cần "đứng yên": chai exp ném ra được CỘNG vận tốc của người chơi
        // (ThrownEntity kế thừa velocity của chủ), nên ném thẳng lên khi đang bay
        // thì chai cũng bay cùng hướng và rơi lại gần người → vẫn nhặt được orb.

        // 3. FIREWORK / BOOST CHECK
        if (mc.player.getVelocity().length() > 1.5) return;

        // 4. PREVENT PACKET SPAM
        if (!fastExp.getValue() && ticks < 2) {
            ticks++;
            return;
        }
        ticks = 0;

        // 5. FIND EXP BOTTLE
        int expSlot = findExpSlot();
        if (expSlot == -1) return;

        // 6. UNEQUIP OTHER ARMOR (dồn exp vào elytra) — đã được bọc trong guard
        //    "currentScreen == null" ở trên nên an toàn, không đụng GUI người chơi.
        unequipArmor(mc);

        // 7. TÍNH GÓC NÉM THEO TRẠNG THÁI THỰC TẾ:
        //    FIX LỖI #2: dùng isOnGround() thay vì isGliding().
        //    - Trên không (bay/rơi)  → pitch -90 (NHÌN LÊN): chai bay lên, rơi xuống người.
        //    - Đứng đất               → pitch +90 (CÚI XUỐNG): chai vỡ ngay dưới chân.
        //    (MC: pitch -90 = nhìn lên, +90 = nhìn xuống.)
        throwYaw = mc.player.getYaw();
        throwPitch = mc.player.isOnGround() ? 90f : -90f;
        pendingExpSlot = expSlot;

        // 8. ĐẶT LỊCH NÉM — xoay + ném thật do EventInteract thực thi (rotate trước, ném sau).
        pendingThrow = true;
    }

    @EventHandler
    private void onInteract(EventInteract event) {
        if (!pendingThrow) return;
        final int slot = pendingExpSlot;
        if (slot == -1) { pendingThrow = false; return; }

        pendingThrow = false;
        pendingExpSlot = -1;

        event.addInteraction(new Interaction(() -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.interactionManager == null) return;
            if (mc.currentScreen != null) return; // an toàn: không dùng item khi có GUI
            InvHelper.swapToSlot(slot, getBozeSwapType());
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvHelper.swapBack();
        }, throwYaw, throwPitch));
    }

    private void stopRepairing() {
        isRepairing = false;
        pendingThrow = false;
        pendingExpSlot = -1;
        info("Elytra fully repaired!");
        if (wasAutoArmorOn) {
            setAutoArmorEnabled(true);
            wasAutoArmorOn = false;
        }
    }

    // ─── ĐỔI ELYTRA TỪ KHO ĐỒ (chỉ chạy khi đứng đất + không có GUI mở) ───

    private void tryEquipBetterElytraFromInventory(MinecraftClient mc) {
        int bestSlot = findElytraBelowThresholdInInventory(mc);
        if (bestSlot == -1) return;

        ItemStack equipped = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (equipped.isOf(Items.ELYTRA)) {
            float equippedPct = durabilityPercentOf(equipped);
            ItemStack candidate = mc.player.playerScreenHandler.getSlot(bestSlot).getStack();
            if (durabilityPercentOf(candidate) <= equippedPct) return;
        }

        if (isAutoArmorEnabled()) {
            wasAutoArmorOn = true;
            setAutoArmorEnabled(false);
            return; // đợi tick sau cho chắc state đã đổi
        }

        isSwappingElytra = true;
        swapElytraTicks = 0;
        pendingElytraSlot = bestSlot;
    }

    private void handleElytraSwapSequence(MinecraftClient mc) {
        // Nếu người chơi vừa mở GUI giữa chừng → hủy an toàn (guard ở onTick cũng đã chặn).
        if (mc.currentScreen != null) { cancelElytraSwap(); return; }

        swapElytraTicks++;

        if (swapElytraTicks == 1) {
            ItemStack equipped = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (!equipped.isEmpty()) {
                int empty = findEmptyInvSlot(mc);
                if (empty == -1) { cancelElytraSwap(); return; }
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, empty, 0, SlotActionType.PICKUP, mc.player);
            }
            return;
        }

        if (swapElytraTicks == 2) {
            if (pendingElytraSlot != -1) {
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, pendingElytraSlot, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 6, 0, SlotActionType.PICKUP, mc.player);
            }
            return;
        }

        cancelElytraSwap();
        info("Equipped a lower-durability elytra from inventory for repair.");
    }

    private void cancelElytraSwap() {
        isSwappingElytra = false;
        swapElytraTicks = 0;
        pendingElytraSlot = -1;
    }

    private int findElytraBelowThresholdInInventory(MinecraftClient mc) {
        return InvHelper.find(stack -> {
            if (!stack.isOf(Items.ELYTRA)) return false;
            return durabilityPercentOf(stack) <= durability.getValue();
        });
    }

    private float durabilityPercentOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getMaxDamage() <= 0) return 0f;
        return ((float) (stack.getMaxDamage() - stack.getDamage()) / stack.getMaxDamage()) * 100f;
    }

    private void unequipArmor(MinecraftClient mc) {
        int[] armorSlots = {5, 7, 8}; // Helmet, Legs, Boots (bỏ Chest=6 / elytra)
        for (int slot : armorSlots) {
            if (!mc.player.playerScreenHandler.getSlot(slot).getStack().isEmpty()) {
                int empty = findEmptyInvSlot(mc);
                if (empty != -1) {
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                    mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, empty, 0, SlotActionType.PICKUP, mc.player);
                }
            }
        }
    }

    private int findEmptyInvSlot(MinecraftClient mc) {
        ScreenHandler handler = mc.player.playerScreenHandler;
        for (int i = 9; i <= 44; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }

    private int findExpSlot() {
        if (swapMode.getValue() == SwapMode.Alt) {
            return InvHelper.find(Items.EXPERIENCE_BOTTLE);
        } else {
            return InvHelper.findInHotbar(Items.EXPERIENCE_BOTTLE);
        }
    }

    private SwapType getBozeSwapType() {
        switch (swapMode.getValue()) {
            case Normal: return SwapType.Normal;
            case Silent: return SwapType.Silent;
            case Alt: default: return SwapType.Alt;
        }
    }

    private boolean isAutoArmorEnabled() {
        try {
            return ModuleManager.getState(MODULE_AUTO_ARMOR);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void setAutoArmorEnabled(boolean state) {
        try {
            ModuleManager.setState(MODULE_AUTO_ARMOR, state);
        } catch (IllegalArgumentException ignored) {
        }
    }
}