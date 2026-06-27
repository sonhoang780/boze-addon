package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.ColorOption;
import dev.boze.api.render.ColorMaker;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public class VersionHUD extends AddonModule {
    public static final VersionHUD INSTANCE = new VersionHUD();
    public boolean active = false;

    private double posX = com.example.addon.util.HudPositions.getX("VersionHUD", 100.0);
    private double posY = com.example.addon.util.HudPositions.getY("VersionHUD", 100.0);
    public final ColorOption textColor = new ColorOption(this, "Text Color", "Color of the version text.", ColorMaker.staticColor(255, 0, 255), 1.0f);

    private boolean isDraggingHUD = false;
    private double dragOffsetX = 0, dragOffsetY = 0;
    private boolean wasMouseDownEditor = false;

    private VersionHUD() {
        super("VersionHUD", "Renders the addon version. Uses HUD Editor.");
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("example-addon", "version-hud"), (context, tracker) -> {
            if (this.active) render(context);
        });
    }

    @Override public void onEnable() { this.active = true; }
    @Override public void onDisable() { this.active = false; }

    private void drawOutline(GuiGraphicsExtractor context, int x, int y, int w, int h, int color) {
        context.fill(x - 1, y - 1, x + w + 1, y, color);
        context.fill(x - 1, y + h, x + w + 1, y + h + 1, color);
        context.fill(x - 1, y, x, y + h, color);
        context.fill(x + w, y, x + w + 1, y + h, color);
    }

    private void render(GuiGraphicsExtractor context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui && !(mc.screen instanceof MusicHUD.FontScreen)) return;

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
        int w = mc.font.width(text);
        int h = mc.font.lineHeight;

        double x = posX;
        double y = posY;

        if (HUDEditor.INSTANCE.active) {
            double scale = mc.getWindow().getGuiScale();
            double mx = mc.mouseHandler.xpos() / scale;
            double my = mc.mouseHandler.ypos() / scale;
            boolean mouseDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

            if (mouseDown && !wasMouseDownEditor) {
                if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                    if (HUDEditor.draggingHUD.isEmpty() || HUDEditor.draggingHUD.equals("VersionHUD")) {
                        isDraggingHUD = true; HUDEditor.draggingHUD = "VersionHUD";
                        dragOffsetX = mx - x; dragOffsetY = my - y;
                    }
                }
            } else if (!mouseDown) {
                if (isDraggingHUD) { HUDEditor.draggingHUD = ""; com.example.addon.util.HudPositions.save("VersionHUD", posX, posY); }
                isDraggingHUD = false;
            }

            if (isDraggingHUD && mouseDown) {
                x = mx - dragOffsetX; y = my - dragOffsetY;
                int screenW = mc.getWindow().getGuiScaledWidth();
                int screenH = mc.getWindow().getGuiScaledHeight();
                x = Math.max(0, Math.min(x, screenW - w));
                y = Math.max(0, Math.min(y, screenH - h));
                posX = x; posY = y;
                drawOutline(context, (int)x, (int)y, w, h, 0xFF00FF00);
            } else if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
                drawOutline(context, (int)x, (int)y, w, h, 0xFFFFFF00);
            }
            wasMouseDownEditor = mouseDown;
        }

        int colorInt = textColor.getValue().color.getPacked();
        context.text(mc.font, text, (int)x, (int)y, colorInt, true);
    }
}