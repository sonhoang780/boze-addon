package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.utility.ChatHelper;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.function.BooleanSupplier;

/**
 * Navigates a server /shop GUI to buy Totems, End Crystals, or Exp Bottles.
 * Slots are hardcoded (shop GUI layout is fixed, name-scan was unreliable).
 *
 * Flow: /shop -> click GEAR(13) -> click item(13/10/16) -> repeat
 * (Totem: Confirm(23); Crystal/EXP: Set64(17) then Confirm(23)) for the
 * configured amount -> close GUI, disable.
 */
public class AutoShop extends AddonModule {
    public static final AutoShop INSTANCE = new AutoShop();

    public enum ShopMode { Totem, Crystal, EXP }

    public final ModeOption<ShopMode> mode = new ModeOption<>(this, "Mode",
        "Which item to buy.", ShopMode.Totem);

    public final SliderOption totemAmount = new SliderOption(this, "TotemAmount",
        "How many totems to buy.", 1.0, 1.0, 10.0, 1.0,
        (BooleanSupplier) () -> mode.getValue() == ShopMode.Totem);
    public final SliderOption crystalStacks = new SliderOption(this, "CrystalStacks",
        "How many stacks (64) of end crystals to buy.", 1.0, 1.0, 10.0, 1.0,
        (BooleanSupplier) () -> mode.getValue() == ShopMode.Crystal);
    public final SliderOption expStacks = new SliderOption(this, "EXPStacks",
        "How many stacks (64) of exp bottles to buy.", 1.0, 1.0, 10.0, 1.0,
        (BooleanSupplier) () -> mode.getValue() == ShopMode.EXP);

    public final SliderOption actionDelay = new SliderOption(this, "ActionDelay",
        "Ticks between each shop GUI click.", 4.0, 1.0, 20.0, 1.0);

    private static final int SLOT_GEAR = 13;
    private static final int SLOT_ITEM_TOTEM = 13;
    private static final int SLOT_ITEM_CRYSTAL = 10;
    private static final int SLOT_ITEM_EXP = 16;
    private static final int SLOT_SET_64 = 17;
    private static final int SLOT_CONFIRM = 23;
    private static final int STEP_TIMEOUT_TICKS = 100; // 5s @ 20tps -- abort if the GUI never advances

    private enum State { IDLE, WAIT_SHOP, CLICK_GEAR, CLICK_ITEM, DECIDE, SET_64, CONFIRM }

    private State state = State.IDLE;
    private int ticks = 0;
    private int timeoutTicks = 0;
    private int purchasesRemaining = 0;

    public AutoShop() {
        super("AutoShop", "Automates the server /shop GUI to buy totems, end crystals, or exp bottles.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) { setState(false); return; }

        purchasesRemaining = switch (mode.getValue()) {
            case Totem -> totemAmount.getValue().intValue();
            case Crystal -> crystalStacks.getValue().intValue();
            case EXP -> expStacks.getValue().intValue();
        };
        ticks = 0;
        timeoutTicks = 0;
        mc.getConnection().sendCommand("shop");
        state = State.WAIT_SHOP;
    }

    @Override
    public void onDisable() {
        state = State.IDLE;
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        if (state == State.IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { setState(false); return; }

        if (ticks < actionDelay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        boolean screenOpen = mc.screen instanceof AbstractContainerScreen && !(mc.screen instanceof InventoryScreen);
        if (state != State.WAIT_SHOP && !screenOpen) { abort("shop GUI closed unexpectedly"); return; }

        switch (state) {
            case WAIT_SHOP -> {
                if (screenOpen) { state = State.CLICK_GEAR; timeoutTicks = 0; }
                else if (++timeoutTicks > STEP_TIMEOUT_TICKS) abort("shop GUI never opened");
            }
            case CLICK_GEAR -> { click(mc, SLOT_GEAR); state = State.CLICK_ITEM; }
            case CLICK_ITEM -> {
                int slot = switch (mode.getValue()) {
                    case Totem -> SLOT_ITEM_TOTEM;
                    case Crystal -> SLOT_ITEM_CRYSTAL;
                    case EXP -> SLOT_ITEM_EXP;
                };
                click(mc, slot);
                state = State.DECIDE;
            }
            case DECIDE -> {
                if (purchasesRemaining <= 0) { finish(mc); return; }
                state = (mode.getValue() == ShopMode.Totem) ? State.CONFIRM : State.SET_64;
            }
            case SET_64 -> { click(mc, SLOT_SET_64); state = State.CONFIRM; }
            case CONFIRM -> {
                click(mc, SLOT_CONFIRM);
                purchasesRemaining--;
                state = State.DECIDE;
            }
            default -> {}
        }
    }

    private void finish(Minecraft mc) {
        mc.setScreen(null);
        ChatHelper.sendMsg("AutoShop", "Done.");
        setState(false);
    }

    private void abort(String reason) {
        ChatHelper.sendMsg("AutoShop", "Aborted: " + reason);
        setState(false);
    }

    private void click(Minecraft mc, int slot) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }
}
