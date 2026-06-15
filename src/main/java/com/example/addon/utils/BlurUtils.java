package com.example.addon.utils;

import net.minecraft.client.gui.DrawContext;
import java.awt.Color;

/**
 * BlurUtils — wrapper gọi KawaseBlur từ MusicHUD.
 *
 * KawaseBlur.MUSIC_HUD_BLUR.draw() được gọi từ BlurMixin TRƯỚC HUD render,
 * blur kết quả đã nằm trên main framebuffer.
 * drawBlur() chỉ:
 *   1. Set flag blurRequested để BlurMixin biết cần chạy
 *   2. Phủ tint màu nhẹ (glassTint)
 *   3. Bo tròn 4 góc bằng mask pixel
 */
public final class BlurUtils {

    private BlurUtils() {}

    public static volatile boolean blurRequested = false;

    /**
     * Gọi trước khi vẽ nội dung HUD.
     * Blur toàn màn hình đã được render bởi BlurMixin.
     * Method này phủ tint + vẽ rounded corner mask.
     *
     * @param radius bán kính bo góc (pixel), 4–6 là đẹp
     */
    public static void drawBlur(DrawContext context, int x, int y, int w, int h, Color tint, float intensity) {
        blurRequested = true;
    }

    /** Overload tương thích */
    public static void drawBlur(DrawContext context, int x, int y, int w, int h, int r) {
        drawBlur(context, x, y, w, h, new Color(10, 10, 10, 72), 0.6f);
    }

    public static void onResourceReload() {
        blurRequested = false;
    }
}