package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class HUDEditor extends AddonModule {
    public static final HUDEditor INSTANCE = new HUDEditor();
    public boolean active = false;
    
    // Biến static này giúp khóa mục tiêu: Khi 2 HUD đè lên nhau, mày chỉ kéo 1 cái thôi chứ không bị dính chùm
    public static String draggingHUD = ""; 

    public HUDEditor() {
        super("HUDEditor", "Turn on or set a bind to visually drag HUDs on your screen.");
    }

    @Override 
    public void onEnable() {
        this.active = true;
        draggingHUD = "";
        Minecraft mc = Minecraft.getInstance();
        
        // Mở màn hình Editor để giải phóng chuột (Cho phép kéo thả)
        mc.execute(() -> {
            if (mc.screen == null) {
                mc.setScreen(new HUDEditorScreen());
            }
        });
    }

    @Override 
    public void onDisable() {
        this.active = false;
        draggingHUD = "";
        Minecraft mc = Minecraft.getInstance();
        
        // Đóng màn hình Editor
        mc.execute(() -> {
            if (mc.screen instanceof HUDEditorScreen) {
                mc.setScreen(null);
            }
        });
    }

    public static class HUDEditorScreen extends Screen {
        public HUDEditorScreen() {
            super(Component.literal("HUD Editor"));
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
            int screenW = this.width;
            int screenH = this.height;
            
            // Vẽ trục tọa độ Dấu Cộng chia màn hình làm 4 phần (Màu Cyan phản quang)
            context.fill(screenW / 2, 0, screenW / 2 + 1, screenH, 0x8800FFFF);
            context.fill(0, screenH / 2, screenW, screenH / 2 + 1, 0x8800FFFF);
            
            super.extractRenderState(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean isPauseScreen() {
            return false; // Không dừng game khi đang chỉnh HUD
        }

        @Override
        public void onClose() {
            super.onClose();
            // Nếu người dùng bấm phím ESC để thoát, tự động tắt module HUDEditor đi
            if (HUDEditor.INSTANCE.active) {
                HUDEditor.INSTANCE.active = false;
            }
        }
    }
}