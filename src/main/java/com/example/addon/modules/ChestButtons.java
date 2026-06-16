package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import org.lwjgl.glfw.GLFW;

import io.github.humbleui.skija.*;
import io.github.humbleui.types.*;
import java.awt.Color;

public class ChestButtons extends AddonModule {
    public static final ChestButtons INSTANCE = new ChestButtons();
    
    public boolean active = false;
    public final SliderOption delay = new SliderOption(this, "Delay (Ticks)", "Delay between each item transfer.", 1.0, 0.0, 20.0, 1.0);

    private boolean isDumping = false;
    private boolean isStealing = false;
    private int tickTimer = 0;
    private int stuckTicks = 0;
    private int lastSlotId = -1;

    private boolean wasMouseDown = false;
    private DirectContext skiaContext;

    public ChestButtons() {
        super("ChestButtons", "Skija Steal/Dump buttons independent of Fabric API.");

        // Dùng HudRenderCallback để vẽ đè nút lên trên thay vì dùng ButtonWidget của Minecraft
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            if (this.active) renderSkiaButtons(context);
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

    // ─── PHẦN 1: VẼ NÚT BẤM BẰNG SKIJA (FROSTED GLASS ĐẲNG CẤP) ───
    private void renderSkiaButtons(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // Chỉ vẽ khi đang mở các Rương chứa đồ (không vẽ ở túi đồ cá nhân)
        if (!(mc.currentScreen instanceof HandledScreen) || mc.currentScreen instanceof InventoryScreen) return;
        
        HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
        ScreenHandler handler = screen.getScreenHandler();
        int totalSlots = handler.slots.size();
        if (totalSlots < 36) return;

        int rows = (totalSlots - 36) / 9;
        int containerHeight = 114 + (rows * 18);
        int scaledWidth = mc.getWindow().getScaledWidth();
        int scaledHeight = mc.getWindow().getScaledHeight();
        int guiTop = (scaledHeight - containerHeight) / 2;
        int centerX = scaledWidth / 2;

        int btnWidth = 50;
        int btnHeight = 20;
        int stealX = centerX - 52;
        int stealY = guiTop - 22;
        int dumpX = centerX + 2;
        int dumpY = guiTop - 22;

        double scale = mc.getWindow().getScaleFactor();
        int mouseX = (int) (mc.mouse.getX() / scale);
        int mouseY = (int) (mc.mouse.getY() / scale);

        boolean hoverSteal = mouseX >= stealX && mouseX <= stealX + btnWidth && mouseY >= stealY && mouseY <= stealY + btnHeight;
        boolean hoverDump = mouseX >= dumpX && mouseX <= dumpX + btnWidth && mouseY >= dumpY && mouseY <= dumpY + btnHeight;

        // Vẽ Khung Skija
        drawSkiaPanel(stealX, stealY, btnWidth, btnHeight, hoverSteal, new Color(0, 255, 150));
        drawSkiaPanel(dumpX, dumpY, btnWidth, btnHeight, hoverDump, new Color(255, 80, 80));

        // Vẽ Text đè lên trên khung Skija bằng DrawContext gốc
        drawCenteredText(context, mc, "Steal", stealX, stealY, btnWidth, hoverSteal ? 0xFFFFFFFF : 0xFFDDDDDD);
        drawCenteredText(context, mc, "Dump", dumpX, dumpY, btnWidth, hoverDump ? 0xFFFFFFFF : 0xFFDDDDDD);
    }

    private void drawCenteredText(DrawContext context, MinecraftClient mc, String text, int x, int y, int w, int color) {
        int textW = mc.textRenderer.getWidth(text);
        context.drawText(mc.textRenderer, text, x + (w - textW) / 2, y + 6, color, true);
    }

    private void drawSkiaPanel(int x, int y, int w, int h, boolean hovered, Color accent) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // KHÓA AN TOÀN OPENGL: Chống crash vỡ font và nvoglv64.dll
        org.lwjgl.opengl.GL15C.glBindBuffer(org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS, 0);
        org.lwjgl.opengl.GL11C.glPixelStorei(org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT, 4);

        if (skiaContext == null) skiaContext = DirectContext.makeGL();
        skiaContext.resetAll();

        int fboId = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_BINDING);
        try (BackendRenderTarget rt = BackendRenderTarget.makeGL(mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(), 0, 8, fboId, org.lwjgl.opengl.GL30C.GL_RGBA8);
             Surface surface = Surface.makeFromBackendRenderTarget(skiaContext, rt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.getSRGB())) {

            Canvas canvas = surface.getCanvas();
            float scale = (float) mc.getWindow().getScaleFactor();
            canvas.scale(scale, scale);

            // Nền mờ Frosted Glass
            try (Paint bgPaint = new Paint()) {
                bgPaint.setColor(new Color(20, 20, 25, hovered ? 220 : 140).getRGB());
                bgPaint.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(x, y, w, h, 4f), bgPaint);
            }

            // Viền phát sáng nhẹ
            try (Paint strokePaint = new Paint()) {
                strokePaint.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), hovered ? 255 : 100).getRGB());
                strokePaint.setMode(PaintMode.STROKE);
                strokePaint.setStrokeWidth(1.2f);
                strokePaint.setAntiAlias(true);
                canvas.drawRRect(RRect.makeXYWH(x, y, w, h, 4f), strokePaint);
            }
            skiaContext.flush();
        }

        // Mở khóa lại các hiệu ứng Depth/Blend cho Minecraft chạy tiếp và chống tàng hình GUI
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_BLEND);
        org.lwjgl.opengl.GL11C.glBlendFunc(org.lwjgl.opengl.GL11C.GL_SRC_ALPHA, org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_STENCIL_TEST);
    }

    // ─── PHẦN 2: BẮT CLICK VÀ THỰC THI LOGIC TRONG ONTICK ───
    @EventHandler
    private void onTick(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (!(mc.currentScreen instanceof HandledScreen) || mc.currentScreen instanceof InventoryScreen) {
            isDumping = false;
            isStealing = false;
            return;
        }

        HandledScreen<?> screen = (HandledScreen<?>) mc.currentScreen;
        ScreenHandler handler = screen.getScreenHandler();
        int totalSlots = handler.slots.size();
        if (totalSlots < 36) return;

        // Xử lý Click chuột qua phần cứng GLFW thay vì dùng ButtonWidget của Minecraft
        double scale = mc.getWindow().getScaleFactor();
        int mouseX = (int) (mc.mouse.getX() / scale);
        int mouseY = (int) (mc.mouse.getY() / scale);
        boolean mouseDown = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (mouseDown && !wasMouseDown) {
            int rows = (totalSlots - 36) / 9;
            int containerHeight = 114 + (rows * 18);
            int scaledWidth = mc.getWindow().getScaledWidth();
            int scaledHeight = mc.getWindow().getScaledHeight();
            int guiTop = (scaledHeight - containerHeight) / 2;
            int centerX = scaledWidth / 2;

            int stealX = centerX - 52;
            int stealY = guiTop - 22;
            int dumpX = centerX + 2;
            int dumpY = guiTop - 22;

            if (mouseX >= stealX && mouseX <= stealX + 50 && mouseY >= stealY && mouseY <= stealY + 20) {
                isStealing = true;
                isDumping = false;
                tickTimer = 0;
            } else if (mouseX >= dumpX && mouseX <= dumpX + 50 && mouseY >= dumpY && mouseY <= dumpY + 20) {
                isDumping = true;
                isStealing = false;
                tickTimer = 0;
            }
        }
        wasMouseDown = mouseDown;

        // Xử lý luồng lấy đồ (Giữ nguyên logic cũ nhưng bổ sung ngắt luồng chuẩn xác)
        if (!isDumping && !isStealing) return;

        if (tickTimer > 0) {
            tickTimer--;
            return;
        }

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

        // Tự động dừng luồng nếu đã chuyển xong toàn bộ đồ
        if (!moved) {
            isDumping = false;
            isStealing = false;
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