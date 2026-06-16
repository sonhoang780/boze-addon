package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ColorOption;
import dev.boze.api.render.ColorMaker;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

public class VersionHUD extends AddonModule {
    public static final VersionHUD INSTANCE = new VersionHUD();
    public boolean active = false;

    public final SliderOption posX = new SliderOption(this, "X Position", "Horizontal position.", 100.0, 0.0, 2000.0, 1.0);
    public final SliderOption posY = new SliderOption(this, "Y Position", "Vertical position.", 100.0, 0.0, 1000.0, 1.0);
    public final ColorOption textColor = new ColorOption(this, "Text Color", "Color of the version text.", ColorMaker.staticColor(255, 0, 255), 1.0f);

    private boolean isDraggingHUD = false;
    private double dragOffsetX = 0, dragOffsetY = 0;
    private boolean wasMouseDownEditor = false;

    private VersionHUD() {
        super("VersionHUD", "Renders the addon version. Uses HUD Editor.");
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            if (this.active) render(context);
        });
    }

    @Override public void onEnable() { this.active = true; }
    @Override public void onDisable() { this.active = false; }

    private void drawOutline(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x - 1, y - 1, x + w + 1, y, color);
        context.fill(x - 1, y + h, x + w + 1, y + h + 1, color);
        context.fill(x - 1, y, x, y + h, color);
        context.fill(x + w, y, x + w + 1, y + h, color);
    }

    private void render(DrawContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden && !(mc.currentScreen instanceof MusicHUD.FontScreen)) return;

        String version = FabricLoader.getInstance().getModContainer("boze-addon")
                         .map(c -> c.getMetadata().getVersion().getFriendlyString())
                         .orElse("Unknown");

        if (version.equals("Unknown")) {
            version = FabricLoader.getInstance().getAllMods().stream()
                .filter(mod -> mod.getMetadata().getVersion().getFriendlyString().contains("boze-addon"))
                .findFirst()
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("Unknown");
        }
        
        String text = version;
        int w = mc.textRenderer.getWidth(text);
        int h = mc.textRenderer.fontHeight;

        double x = posX.getValue();
        double y = posY.getValue();

        // ── LOGIC HUD EDITOR ──
        if (HUDEditor.INSTANCE.active) {
            double scale = mc.getWindow().getScaleFactor();
            double mx = mc.mouse.getX() / scale;
            double my = mc.mouse.getY() / scale;
            boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

            if (mouseDown && !wasMouseDownEditor) {
                if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                    if (HUDEditor.draggingHUD.isEmpty() || HUDEditor.draggingHUD.equals("VersionHUD")) {
                        isDraggingHUD = true; HUDEditor.draggingHUD = "VersionHUD";
                        dragOffsetX = mx - x; dragOffsetY = my - y;
                    }
                }
            } else if (!mouseDown) {
                if (isDraggingHUD) HUDEditor.draggingHUD = "";
                isDraggingHUD = false;
            }

            if (isDraggingHUD && mouseDown) {
                x = mx - dragOffsetX; y = my - dragOffsetY;
                posX.setValue(x); posY.setValue(y);
                drawOutline(context, (int)x, (int)y, w, h, 0xFF00FF00);
            } else if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                drawOutline(context, (int)x, (int)y, w, h, 0xFFFFFF00);
            }
            wasMouseDownEditor = mouseDown;
        }

        int colorInt = textColor.getValue().color.getPacked();
        context.drawText(mc.textRenderer, text, (int)x, (int)y, colorInt, true);
    }
}