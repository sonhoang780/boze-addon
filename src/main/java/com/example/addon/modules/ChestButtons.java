package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class ChestButtons extends AddonModule {
    public static final ChestButtons INSTANCE = new ChestButtons();
    
    // Đã bổ sung biến active để theo dõi trạng thái module
    public boolean active = false;

    public final SliderOption delay = new SliderOption(this, "Delay (Ticks)", "", 1.0, 0.0, 20.0, 1.0);

    private boolean isDumping = false;
    private boolean isStealing = false;
    private int tickTimer = 0;
    private int stuckTicks = 0;
    private int lastSlotId = -1;

    private ChestButtons() {
        super("ChestButtons", "Steal/Dump");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (this.active && screen instanceof HandledScreen && !(screen instanceof InventoryScreen)) {
                HandledScreen<?> handledScreen = (HandledScreen<?>) screen;
                ScreenHandler handler = handledScreen.getScreenHandler();
                
                int totalSlots = handler.slots.size();
                if (totalSlots < 36) return; 

                int rows = (totalSlots - 36) / 9;
                int containerHeight = 114 + (rows * 18);
                int guiTop = (scaledHeight - containerHeight) / 2;
                int centerX = scaledWidth / 2;

                isDumping = false;
                isStealing = false;

                // Nút [Steal]
                Screens.getButtons(screen).add(
                    ButtonWidget.builder(Text.literal("Steal"), button -> {
                        isStealing = true;
                        isDumping = false;
                        tickTimer = 0;
                    }).dimensions(centerX - 52, guiTop - 22, 50, 20).build()
                );

                // Nút [Dump]
                Screens.getButtons(screen).add(
                    ButtonWidget.builder(Text.literal("Dump"), button -> {
                        isDumping = true;
                        isStealing = false;
                        tickTimer = 0;
                    }).dimensions(centerX + 2, guiTop - 22, 50, 20).build()
                );
            }
        });
    }

    @Override
    public void onEnable() {
        this.active = true;
        isDumping = false;
        isStealing = false;
    }

    @Override
    public void onDisable() {
        this.active = false;
        isDumping = false;
        isStealing = false;
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (!(mc.currentScreen instanceof HandledScreen) || mc.currentScreen instanceof InventoryScreen) {
            isDumping = false;
            isStealing = false;
            return;
        }

        if (!isDumping && !isStealing) return;

        if (tickTimer > 0) {
            tickTimer--;
            return;
        }

        HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
        ScreenHandler handler = screen.getScreenHandler();
        int totalSlots = handler.slots.size();
        if (totalSlots < 36) return;

        int invStart = totalSlots - 36;
        int invEnd = totalSlots - 9; 

        boolean moved = false;

        if (isDumping) {
            for (int i = invStart; i < invEnd; i++) {
                Slot slot = handler.slots.get(i);
                if (slot != null && slot.hasStack()) {
                    antiStuckCheck(slot.id);
                    if (stuckTicks > 5) continue; 

                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                    tickTimer = delay.getValue().intValue();
                    moved = true;
                    break;
                }
            }
        } 
        else if (isStealing) {
            for (int i = 0; i < invStart; i++) {
                Slot slot = handler.slots.get(i);
                if (slot != null && slot.hasStack()) {
                    antiStuckCheck(slot.id);
                    if (stuckTicks > 5) continue; 

                    mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
                    tickTimer = delay.getValue().intValue();
                    moved = true;
                    break;
                }
            }
        }

        if (!moved) {
            tickTimer = 10;
            stuckTicks = 0;
        }
    }

    private void antiStuckCheck(int slotId) {
        if (lastSlotId == slotId) {
            stuckTicks++;
        } else {
            lastSlotId = slotId;
            stuckTicks = 0;
        }
    }
}