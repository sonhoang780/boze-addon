package com.example.addon.mixin;

import com.example.addon.modules.GifGUI;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercept DrawContext.fill() để thay nền panel Boze GUI bằng GIF.
 *
 * "Tờ giấy bị thủng 7 chỗ":
 * - Boze vẽ nền mỗi category bằng fill() với màu tối
 * - Ta cancel fill đó và thay bằng đoạn GIF tương ứng với vị trí panel
 * - Kết quả: GIF lộ ra đúng bên trong từng category, ngoài category vẫn tối
 *
 * Detect panel Boze: fill có w>50, h>30, màu tối (r,g,b < 70), alpha > 100
 */
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {

    // CHỈ GIỮ LẠI Overload 5-param: fill(x1, y1, x2, y2, color)
    @Inject(method = "fill(IIIII)V", at = @At("HEAD"), cancellable = true)
    private void onFill5(int x1, int y1, int x2, int y2, int color, CallbackInfo ci) {
        replaceFillWithGif(x1, y1, x2, y2, color, ci);
    }

    private void replaceFillWithGif(int x1, int y1, int x2, int y2, int color, CallbackInfo ci) {
        if (!GifGUI.INSTANCE.active) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen == null) return;

        // Chỉ intercept khi đang ở Boze ClickGUI
        String name = mc.currentScreen.getClass().getName().toLowerCase();
        boolean isBoze = name.contains("clickgui") || name.contains("boze")
            || name.contains("bozegui") || name.contains("bzgui");
        if (!isBoze) return;

        int w = x2 - x1;
        int h = y2 - y1;

        // Filter: chỉ replace fill đủ lớn (panel category, không phải viền/separator)
        if (w < 50 || h < 30) return;

        // Phân tích màu
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >>  8) & 0xFF;
        int b =  color        & 0xFF;

        // Detect panel background Boze: màu tối (dark gray/black), alpha > 100
        boolean isDarkPanel = a > 100 && r < 70 && g < 70 && b < 70;
        if (!isDarkPanel) return;

        // Lấy frame GIF hiện tại
        var frameId = GifGUI.INSTANCE.getCurrentFrameId();
        if (frameId == null) return;

        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        DrawContext ctx = (DrawContext)(Object)this;

        // Vẽ đúng vùng GIF tương ứng với vị trí panel trên màn hình
        // UV = (x1, y1) → lấy đúng phần của GIF tại vị trí này
        // texture size = screenW × screenH → GIF phủ toàn màn hình
        ctx.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            frameId,
            x1, y1,           // vị trí vẽ trên màn hình
            (float) x1,       // u offset
            (float) y1,       // v offset
            w, h,             // kích thước vùng vẽ
            screenW, screenH  // kích thước texture đầy đủ
        );

        // Dim nhẹ để text dễ đọc, dùng màu gốc nhưng alpha thấp hơn
        int dimAlpha = (int)(double) GifGUI.INSTANCE.dimOverlay.getValue();
        if (dimAlpha > 0) {
            // Vẽ overlay tối lên trên GIF trong panel
            int dimColor = (Math.min(dimAlpha, 200) << 24) | (r << 16) | (g << 8) | b;
            ctx.fill(x1, y1, x2, y2, dimColor);
        }

        // Cancel fill gốc → GIF thay thế nền panel
        ci.cancel();
    }
}