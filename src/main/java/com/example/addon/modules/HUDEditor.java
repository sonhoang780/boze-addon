package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

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
        MinecraftClient mc = MinecraftClient.getInstance();
        
        // Mở màn hình Editor để giải phóng chuột (Cho phép kéo thả)
        mc.execute(() -> {
            if (mc.currentScreen == null) {
                mc.setScreen(new HUDEditorScreen());
            }
        });
    }

    @Override 
    public void onDisable() {
        this.active = false;
        draggingHUD = "";
        MinecraftClient mc = MinecraftClient.getInstance();
        
        // Đóng màn hình Editor
        mc.execute(() -> {
            if (mc.currentScreen instanceof HUDEditorScreen) {
                mc.setScreen(null);
            }
        });
    }

    public static class HUDEditorScreen extends Screen {
        public HUDEditorScreen() {
            super(Text.literal("HUD Editor"));
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            int screenW = this.width;
            int screenH = this.height;
            
            // Vẽ trục tọa độ Dấu Cộng chia màn hình làm 4 phần (Màu Cyan phản quang)
            context.fill(screenW / 2, 0, screenW / 2 + 1, screenH, 0x8800FFFF);
            context.fill(0, screenH / 2, screenW, screenH / 2 + 1, 0x8800FFFF);
            
            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public boolean shouldPause() {
            return false; // Không dừng game khi đang chỉnh HUD
        }

        @Override
        public void close() {
            super.close();
            // Nếu người dùng bấm phím ESC để thoát, tự động tắt module HUDEditor đi
            if (HUDEditor.INSTANCE.active) {
                HUDEditor.INSTANCE.active = false;
            }
        }
    }
}