package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.client.module.BaseModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.Option;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.interaction.InvHelper;
import dev.boze.api.utility.interaction.SwapType;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class ElytraFix extends AddonModule {
    public static final ElytraFix INSTANCE = new ElytraFix();

    public enum SwapMode { Normal, Alt, Silent }
    public enum HoverMode { Blatant, Legit }

    public final SliderOption durability = new SliderOption(this, "Durability %", "Start repairing when below this %.", 50.0, 1.0, 99.0, 1.0);
    public final ModeOption<SwapMode> swapMode = new ModeOption<>(this, "Swap Mode", "Mode to swap to EXP bottles.", SwapMode.Alt);
    public final ToggleOption fastExp = new ToggleOption(this, "Fast Exp", "Ném exp dồn dập hơn (chờ hấp thụ ngắn hơn). Vẫn không ném phí nhờ cơ chế chờ hấp thụ.", false);
    public final ToggleOption hoverWhileRepair = new ToggleOption(this, "Hover While Repairing", "Đứng yên giữa không trung lúc sửa để chai exp rơi trúng người.", true);
    // Only applies when ElytraFly is on. Blatant = Creative (stable hover, blocks Baritone).
    // Legit = ControlRocket (fireworks maintain position, looks more vanilla).
    public final ModeOption<HoverMode> hoverMode = new ModeOption<>(this, "Hover Mode", "ElytraFly hover style during repair.", HoverMode.Blatant);

    // Tên module trong Boze core (đổi nếu Boze đặt tên khác).
    private static final String MODULE_AUTO_ARMOR = "AutoArmor";
    private static final String MODULE_ELYTRA_FLY = "ElytraFly";

    // Timer speed exposed to MixinMinecraftClient: 1.0 = normal, 0.1 = hover-slow.
    public static volatile float hoverTimerSpeed = 1.0f;

    private boolean isRepairing = false;
    private boolean wasAutoArmorOn = false;

    // ── CƠ CHẾ TIẾT KIỆM EXP ──
    // Ném 1 chai → CHỜ tới khi độ bền elytra THỰC SỰ tăng (orb đã được hấp thụ)
    // rồi mới ném chai kế. Tránh ném 20 chai liền trước khi chai đầu kịp vỡ/nhặt.
    private int damageAtLastThrow = -1;    // -1 = không có cú ném đang chờ hấp thụ
    private long absorbWaitStartMs = 0L;   // real-time stamp; independent of timer speed

    // Two-phase throw: first tick schedules (timer→1.0), second tick executes.
    private boolean pendingThrow = false;

    // ── SPEED & MODE SAVE/RESTORE (ElytraFly options during repair) ──
    private SliderOption savedSpeedOption = null;
    private double savedSpeedValue = 1.0;
    private ModeOption<?> savedFlyModeOption = null;
    private String savedFlyModeName = null;

    // True when ElytraFix enabled ElytraFly (Baritone case) — must disable it when done.
    private boolean enabledElytraFlyForRepair = false;

    // ── LEGIT HOVER: periodic rocket fire ──
    private static final long LEGIT_ROCKET_INTERVAL_MS = 2500L;
    private long lastRocketFiredAt = 0L;

    // ── ĐỔI ELYTRA ──
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
        damageAtLastThrow = -1;
        absorbWaitStartMs = 0L;
        pendingThrow = false;
        hoverTimerSpeed = 1.0f;
        savedSpeedOption = null;
        savedFlyModeOption = null;
        savedFlyModeName = null;
        enabledElytraFlyForRepair = false;
        lastRocketFiredAt = 0L;
        isSwappingElytra = false;
        swapElytraTicks = 0;
        pendingElytraSlot = -1;
    }

    @Override
    public void onDisable() {
        isRepairing = false;
        damageAtLastThrow = -1;
        pendingThrow = false;
        hoverTimerSpeed = 1.0f;
        restoreSpeed();
        restoreFlyMode();
        disableElytraFlyIfEnabled();
        cancelElytraSwap();
        if (wasAutoArmorOn) {
            setAutoArmorEnabled(true);
            wasAutoArmorOn = false;
        }
    }

    private void info(String msg) {
        ChatHelper.sendMsg("ElytraFix", "§a" + msg);
    }

    // Boze ElytraFly đang bật? Đây chính là "trạng thái khác" (không phải vanilla
    // gliding) cho phép đổi elytra giữa không trung mà KHÔNG rớt, vì ElytraFly giữ
    // người chơi bằng velocity chứ không phụ thuộc cờ isGliding.
    private boolean isElytraFlyOn() {
        try {
            return ModuleManager.getState(MODULE_ELYTRA_FLY);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // Không động kho đồ / dùng item khi đang mở GUI (chống kẹt chuột / desync).
        if (mc.currentScreen != null) return;

        boolean elytraFlyOn = isElytraFlyOn();

        // 0. ĐỔI ELYTRA TỪ KHO ĐỒ:
        //    Cho phép khi ĐỨNG ĐẤT, HOẶC khi ElytraFly đang bật (bay được mà không
        //    rớt — đây là phần còn thiếu mà bạn nói tới). KHÔNG đổi khi đang vanilla
        //    gliding mà không có ElytraFly (sẽ rớt).
        boolean canSwap = mc.player.isOnGround() || elytraFlyOn || mc.player.isGliding();
        if (!isSwappingElytra && !isRepairing && canSwap) {
            tryEquipWornElytraForRepair(mc);
        }

        if (isSwappingElytra) {
            handleElytraSwapSequence(mc);
            return;
        }

        // 1. ELYTRA ĐANG MẶC?
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA)) {
            if (isRepairing) stopRepairing();
            return;
        }

        // 2. ĐỘ BỀN
        int maxDamage = chest.getMaxDamage();
        int damage = chest.getDamage();
        float pct = ((float) (maxDamage - damage) / maxDamage) * 100f;

        if (pct <= durability.getValue() && !isRepairing) {
            isRepairing = true;
            damageAtLastThrow = -1;
            absorbWaitStartMs = 0L;
            if (hoverWhileRepair.getValue()) {
                // If mid-air and ElytraFly is off (Baritone flying), enable it so we can hover.
                if (!elytraFlyOn && !mc.player.isOnGround()) {
                    try { ModuleManager.setState(MODULE_ELYTRA_FLY, true); } catch (Exception ignored) {}
                    enabledElytraFlyForRepair = true;
                    elytraFlyOn = true; // update local var so elytraFlyHovering is correct this tick
                }
                saveAndReduceSpeed();
                if (elytraFlyOn) saveAndSwitchFlyMode();
            }
            if (isAutoArmorEnabled()) {
                wasAutoArmorOn = true;
                setAutoArmorEnabled(false);
            }
        }

        if (pct >= 100f && isRepairing) {
            stopRepairing();
            if (!mc.player.isOnGround() && mc.getNetworkHandler() != null) {
                if (elytraFlyOn) {
                    // ElytraFly resumes on the very next tick — behaves identically to
                    // disabling the module mid-repair (which never falls). Don't kick or
                    // re-send START_FALL_FLYING; those packets were causing the fall.
                } else {
                    // Vanilla gliding: server may have deactivated gliding due to inventory
                    // changes during repair; kick forward and re-trigger.
                    float yaw = mc.player.getYaw();
                    double rad = Math.toRadians(yaw);
                    mc.player.setVelocity(-Math.sin(rad) * 0.6, -0.1, Math.cos(rad) * 0.6);
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
                        mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }
            return;
        }

        if (!isRepairing) return;

        // FastXP: stop throwing early — let floor XP finish the repair.
        // When we enabled ElytraFly ourselves (Baritone case): stop at 80% and disable ElytraFly
        // so Baritone resumes and the player moves around picking up orbs naturally.
        // Otherwise (ElytraFly was already on): stop at 90%.
        if (fastExp.getValue()) {
            float threshold = enabledElytraFlyForRepair ? 80f : 90f;
            if (pct >= threshold) {
                if (enabledElytraFlyForRepair) {
                    restoreSpeed();
                    restoreFlyMode();
                    disableElytraFlyIfEnabled();
                }
                return;
            }
        }

        // ─────────────── ĐANG SỬA ───────────────

        // "FLYING" = đang thực sự bay. FIX điều kiện: KHÔNG dùng !isOnGround() nữa
        // (nhảy lên cũng thỏa → vô lý). Dùng: vanilla gliding HOẶC ElytraFly đang bay.
        boolean flying = mc.player.isGliding() || (elytraFlyOn && !mc.player.isOnGround());
        // True when ElytraFly holds the player airborne (vs vanilla gliding or on-ground).
        boolean elytraFlyHovering = hoverWhileRepair.getValue() && elytraFlyOn && !mc.player.isOnGround();

        if (elytraFlyHovering) {
            // ElytraFly Speed slider is set to 0.1 via saveAndReduceSpeed(). No timer needed.
            hoverTimerSpeed = 1.0f;
            pendingThrow = false;

            // Legit: simulate ControlRocket — fire a rocket every ~2.5s.
            // Rocket gives max-velocity boost for 2-3s (creative-like stable flight),
            // then velocity decays back to normal. Repeat to maintain altitude.
            if (hoverMode.getValue() == HoverMode.Legit) {
                long now = System.currentTimeMillis();
                if (now - lastRocketFiredAt >= LEGIT_ROCKET_INTERVAL_MS) {
                    int rocketSlot = InvHelper.find(Items.FIREWORK_ROCKET);
                    if (rocketSlot != -1) {
                        InvHelper.swapToSlot(rocketSlot, getBozeSwapType());
                        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                        InvHelper.swapBack();
                        lastRocketFiredAt = now;
                        return; // Skip exp throw this tick — can't use two items in one tick
                    }
                }
            }
        } else {
            hoverTimerSpeed = 1.0f;
            pendingThrow = false;
        }

        // 3. PHÁO HOA / BOOST: vận tốc còn lớn thì khoan ném.
        if (mc.player.getVelocity().length() > 1.5) return;

        // 4. CỔNG TIẾT KIỆM EXP: nếu đang chờ chai trước được hấp thụ thì KHÔNG ném thêm.
        if (damageAtLastThrow >= 0) {
            if (damage < damageAtLastThrow) {
                damageAtLastThrow = -1;
                absorbWaitStartMs = 0L;
            } else {
                long maxWaitMs = fastExp.getValue() ? 0L : (flying ? 3000L : 1000L);
                if (System.currentTimeMillis() - absorbWaitStartMs < maxWaitMs) return;
                damageAtLastThrow = -1;
                absorbWaitStartMs = 0L;
            }
        }

        // 5. TÌM CHAI EXP.
        int expSlot = findExpSlot();
        if (expSlot == -1) return;

        // 7. CỞI GIÁP KHÁC ĐỂ DỒN EXP VÀO ELYTRA.
        unequipArmor(mc);

        // 8. NÉM ĐÚNG HƯỚNG.
        //    Trên không → pitch -90 (ngước lên); đứng đất → pitch +90 (xuống).
        //    anchor=true khi đang bay+hover: ghim vị trí server để chai không kế thừa vận tốc.
        //    silentRotate=true khi đứng đất: gửi rotation packet lên server nhưng không xoay camera.
        float pitch = flying ? -90f : 90f;
        boolean anchor = flying && hoverWhileRepair.getValue();
        boolean silentRotate = !flying;
        throwExp(mc, expSlot, mc.player.getYaw(), pitch, anchor, silentRotate);

        // Ghi nhận để chờ hấp thụ trước khi ném chai tiếp theo.
        damageAtLastThrow = damage;
        absorbWaitStartMs = System.currentTimeMillis();
    }

    /**
     * Throws an exp bottle at the given server-side yaw/pitch.
     * silentRotate=true  → rotation packet goes to the server only; camera stays put (on-ground).
     * silentRotate=false → camera is also rotated to match (flying, so the player sees it).
     */
    private void throwExp(MinecraftClient mc, int slot, float yaw, float pitch, boolean anchor, boolean silentRotate) {
        if (mc.interactionManager == null || mc.getNetworkHandler() == null) return;
        var net = mc.getNetworkHandler();

        if (!silentRotate) {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        }

        boolean onGround = mc.player.isOnGround();
        boolean hColl = mc.player.horizontalCollision;

        if (anchor) {
            // GHIM VỊ TRÍ SERVER: 2 gói Full giống nhau → delta 0 → chai không bị cộng vận tốc lướt.
            double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
            net.sendPacket(new PlayerMoveC2SPacket.Full(x, y, z, yaw, pitch, onGround, hColl));
            net.sendPacket(new PlayerMoveC2SPacket.Full(x, y, z, yaw, pitch, onGround, hColl));
        } else {
            // Tell server the throw direction before the use-item packet.
            net.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, hColl));
        }

        InvHelper.swapToSlot(slot, getBozeSwapType());
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        InvHelper.swapBack();
    }

    private void stopRepairing() {
        isRepairing = false;
        damageAtLastThrow = -1;
        absorbWaitStartMs = 0L;
        pendingThrow = false;
        hoverTimerSpeed = 1.0f;
        restoreSpeed();
        restoreFlyMode();
        disableElytraFlyIfEnabled();
        info("Elytra fully repaired!");
        if (wasAutoArmorOn) {
            setAutoArmorEnabled(true);
            wasAutoArmorOn = false;
        }
    }

    // ─── ĐỔI ELYTRA TỪ KHO ĐỒ ĐỂ SỬA ───

    /**
     * Nếu elytra ĐANG MẶC độ bền CAO HƠN threshold (không cần sửa) NHƯNG kho đồ /
     * hotbar có elytra độ bền THẤP HƠN threshold (cần sửa) → ĐỔI CHỖ để mang cái
     * mòn ra mặc mà sửa. Nếu cái đang mặc cũng đã dưới threshold thì sửa nó luôn.
     */
    private void tryEquipWornElytraForRepair(MinecraftClient mc) {
        int slot = findLowestWornElytraInInventory(mc);
        if (slot == -1) return;

        ItemStack equipped = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (equipped.isOf(Items.ELYTRA)) {
            if (durabilityPercentOf(equipped) <= durability.getValue()) return; // cái đang mặc cũng cần sửa → sửa luôn
            // ngược lại: đang mặc cái tốt, kho có cái mòn → đổi chỗ.
        }

        if (isAutoArmorEnabled()) {
            wasAutoArmorOn = true;
            setAutoArmorEnabled(false);
            return; // đợi tick sau cho chắc state đã đổi
        }

        isSwappingElytra = true;
        swapElytraTicks = 0;
        pendingElytraSlot = slot;
    }

    private void handleElytraSwapSequence(MinecraftClient mc) {
        if (mc.currentScreen != null) { cancelElytraSwap(); return; }

        swapElytraTicks++;

        // Atomic single-tick swap: the armor slot (6) is NEVER left empty, so the
        // player doesn't lose elytra gliding state even for a single tick.
        //   Click 1: pick up worn elytra from pendingElytraSlot → cursor has worn, slot is empty
        //   Click 2: click armor slot 6 → worn goes in, previously-equipped item comes to cursor
        //   Click 3: click pendingElytraSlot (now empty) → places displaced item back there
        if (swapElytraTicks == 1) {
            if (pendingElytraSlot == -1) { cancelElytraSwap(); return; }
            int syncId = mc.player.playerScreenHandler.syncId;
            mc.interactionManager.clickSlot(syncId, pendingElytraSlot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, 6, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(syncId, pendingElytraSlot, 0, SlotActionType.PICKUP, mc.player);
            return;
        }

        cancelElytraSwap();
        info("Swapped in a worn elytra from inventory to repair it.");
    }

    private void cancelElytraSwap() {
        isSwappingElytra = false;
        swapElytraTicks = 0;
        pendingElytraSlot = -1;
    }

    /** Quét inventory + hotbar (slot 9-44), trả về ô chứa elytra ĐỘ BỀN THẤP NHẤT
     *  mà vẫn dưới threshold; -1 nếu không có. */
    private int findLowestWornElytraInInventory(MinecraftClient mc) {
        ScreenHandler h = mc.player.playerScreenHandler;
        int best = -1;
        float bestPct = Float.MAX_VALUE;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = h.getSlot(i).getStack();
            if (!s.isOf(Items.ELYTRA)) continue;
            float pct = durabilityPercentOf(s);
            if (pct <= durability.getValue() && pct < bestPct) {
                bestPct = pct;
                best = i;
            }
        }
        return best;
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

    private void saveAndReduceSpeed() {
        savedSpeedOption = findSpeedOption();
        if (savedSpeedOption != null) {
            savedSpeedValue = savedSpeedOption.getValue();
            savedSpeedOption.setValue(0.1);
        }
    }

    private void restoreSpeed() {
        if (savedSpeedOption != null) {
            savedSpeedOption.setValue(savedSpeedValue);
            savedSpeedOption = null;
        }
    }

    private void saveAndSwitchFlyMode() {
        // Legit mode keeps ElytraFly's Mode unchanged — it uses periodic rocket firing instead.
        if (hoverMode.getValue() != HoverMode.Blatant) return;
        try {
            BaseModule fly = ModuleManager.getClientModule(MODULE_ELYTRA_FLY);
            if (fly == null) return;
            for (Option<?> opt : fly.getOptions()) {
                if (opt instanceof ModeOption<?> mo && mo.name.equalsIgnoreCase("mode")) {
                    savedFlyModeOption = mo;
                    savedFlyModeName = mo.getModeName();
                    mo.setValueByName("Creative");
                    return;
                }
            }
        } catch (Exception ignored) {}
    }

    private void restoreFlyMode() {
        if (savedFlyModeOption != null && savedFlyModeName != null) {
            savedFlyModeOption.setValueByName(savedFlyModeName);
        }
        savedFlyModeOption = null;
        savedFlyModeName = null;
    }

    private SliderOption findSpeedOption() {
        try {
            BaseModule fly = ModuleManager.getClientModule(MODULE_ELYTRA_FLY);
            if (fly == null) return null;
            for (Option<?> opt : fly.getOptions()) {
                if (opt instanceof SliderOption sl && sl.name.equalsIgnoreCase("speed")) return sl;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void disableElytraFlyIfEnabled() {
        if (enabledElytraFlyForRepair) {
            try { ModuleManager.setState(MODULE_ELYTRA_FLY, false); } catch (Exception ignored) {}
            enabledElytraFlyForRepair = false;
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
