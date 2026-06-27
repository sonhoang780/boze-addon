package com.example.addon.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;

public class MusicScreen extends Screen {
    private EditBox searchBox;

    public MusicScreen() {
        super(Component.literal("KingThon Music Player"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 1. Tạo ô nhập văn bản (TextBox)
        searchBox = new EditBox(this.font, centerX - 100, centerY - 20, 200, 20, Component.literal("Search..."));
        searchBox.setMaxLength(100);
        this.setInitialFocus(searchBox);
        this.addRenderableWidget(searchBox);

        // 2. Tạo nút [Play]
        this.addRenderableWidget(Button.builder(Component.literal("▶ Search & Play"), button -> {
            playMusicAndClose();
        }).bounds(centerX - 100, centerY + 10, 95, 20).build());

        // 3. Tạo nút [Close]
        this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> {
            this.onClose();
        }).bounds(centerX + 5, centerY + 10, 95, 20).build());
    }

    private void playMusicAndClose() {
        String query = searchBox.getValue().trim();
        if (!query.isEmpty()) {
            PlayMusic.INSTANCE.searchAndPlayFromGUI(query);
            this.onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Làm tối mờ background phía sau (Kính mờ đen)
        context.fill(0, 0, this.width, this.height, new Color(10, 10, 10, 180).getRGB());
        
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        
        // Vẽ khối Menu nền
        context.fill(centerX - 120, centerY - 50, centerX + 120, centerY + 45, new Color(20, 20, 20, 255).getRGB());
        
        // [FIX LỖI drawBorder]: Dựng viền thủ công bằng 4 đường fill cực kỳ an toàn
        int x0 = centerX - 120;
        int y0 = centerY - 50;
        int w = 240;
        int h = 95;
        int borderColor = new Color(0, 255, 150, 255).getRGB();
        
        context.fill(x0, y0, x0 + w, y0 + 1, borderColor);          // Cạnh trên
        context.fill(x0, y0 + h - 1, x0 + w, y0 + h, borderColor);  // Cạnh dưới
        context.fill(x0, y0 + 1, x0 + 1, y0 + h - 1, borderColor);  // Cạnh trái
        context.fill(x0 + w - 1, y0 + 1, x0 + w, y0 + h - 1, borderColor); // Cạnh phải

        // Tiêu đề
        context.centeredText(this.font, "KINGTHON MUSIC PLAYER", centerX, centerY - 40, 0xFFFFFF);

        super.extractRenderState(context, mouseX, mouseY, delta);

        // [FIX LỖI KeyInput]: Kiểm tra phím Enter bằng lệnh GLFW trực tiếp tại luồng Render
        long windowHandle = Minecraft.getInstance().getWindow().handle();
        if (GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS || 
            GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS) {
            playMusicAndClose();
        }
    }
}