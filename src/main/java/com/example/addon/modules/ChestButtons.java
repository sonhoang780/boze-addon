package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;

public class ChestButtons extends AddonModule {
    public static final ChestButtons INSTANCE = new ChestButtons();
    
    // Đã bổ sung biến active để theo dõi trạng thái module
    public boolean active = false;

    public final SliderOption delay = new SliderOption(this, "DelayTicks", "", 1.0, 0.0, 20.0, 1.0);

    private boolean isDumping = false;
    private boolean isStealing = false;
    private int tickTimer = 0;
    private int stuckTicks = 0;
    private int lastSlotId = -1;

    private ChestButtons() {
        super("ChestButtons", "Steal/Dump");

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (this.active && screen instanceof AbstractContainerScreen && !(screen instanceof InventoryScreen)) {
                AbstractContainerScreen<?> handledScreen = (AbstractContainerScreen<?>) screen;
                AbstractContainerMenu handler = handledScreen.getMenu();
                
                int totalSlots = handler.slots.size();
                if (totalSlots < 36) return; 

                int rows = (totalSlots - 36) / 9;
                int containerHeight = 114 + (rows * 18);
                int guiTop = (scaledHeight - containerHeight) / 2;
                int centerX = scaledWidth / 2;

                isDumping = false;
                isStealing = false;

                // Nút [Steal]
                Screens.getWidgets(screen).add(
                    Button.builder(Component.literal("Steal"), button -> {
                        isStealing = true;
                        isDumping = false;
                        tickTimer = 0;
                    }).bounds(centerX - 52, guiTop - 22, 50, 20).build()
                );

                // Nút [Dump]
                Screens.getWidgets(screen).add(
                    Button.builder(Component.literal("Dump"), button -> {
                        isDumping = true;
                        isStealing = false;
                        tickTimer = 0;
                    }).bounds(centerX + 2, guiTop - 22, 50, 20).build()
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
        Minecraft mc = Minecraft.getInstance();

        if (!(mc.screen instanceof AbstractContainerScreen) || mc.screen instanceof InventoryScreen) {
            isDumping = false;
            isStealing = false;
            return;
        }

        if (!isDumping && !isStealing) return;

        if (tickTimer > 0) {
            tickTimer--;
            return;
        }

        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) mc.screen;
        AbstractContainerMenu handler = screen.getMenu();
        int totalSlots = handler.slots.size();
        if (totalSlots < 36) return;

        int invStart = totalSlots - 36;
        int invEnd = totalSlots - 9; 

        boolean moved = false;

        if (isDumping) {
            for (int i = invStart; i < invEnd; i++) {
                Slot slot = handler.slots.get(i);
                if (slot != null && slot.hasItem()) {
                    antiStuckCheck(slot.index);
                    if (stuckTicks > 5) continue; 

                    mc.gameMode.handleContainerInput(handler.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                    tickTimer = delay.getValue().intValue();
                    moved = true;
                    break;
                }
            }
        } 
        else if (isStealing) {
            for (int i = 0; i < invStart; i++) {
                Slot slot = handler.slots.get(i);
                if (slot != null && slot.hasItem()) {
                    antiStuckCheck(slot.index);
                    if (stuckTicks > 5) continue; 

                    mc.gameMode.handleContainerInput(handler.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
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