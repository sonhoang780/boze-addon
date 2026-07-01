package com.example.addon.screens;

import com.example.addon.modules.BetterChams;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ImagePickerScreen extends Screen {

    private static final int PANEL_W    = 280;
    private static final int PANEL_H    = 320;
    private static final int PADDING    = 8;
    private static final int ROW_H      = 18;
    private static final int HEADER_H   = 24;
    private static final int FOOTER_H   = 28;

    private final List<Path> files = new ArrayList<>();
    private int selectedIdx  = -1;
    private int scrollOffset = 0;
    private boolean wasMouseDown = false;

    private final String folderName;
    private final String regexFilter;
    private final java.util.function.Consumer<Path> onSelect;

    public ImagePickerScreen(String folderName, String title, String regexFilter, java.util.function.Consumer<Path> onSelect) {
        super(Component.literal(title));
        this.folderName = folderName;
        this.regexFilter = regexFilter;
        this.onSelect = onSelect;
    }

    private int winX() { return (this.width  - PANEL_W) / 2; }
    private int winY() { return (this.height - PANEL_H) / 2; }

    @Override
    protected void init() {
        super.init();
        Path dir = FabricLoader.getInstance().getGameDir().resolve(folderName);
        try {
            Files.createDirectories(dir);
            files.addAll(
                Files.list(dir)
                    .filter(p -> p.getFileName().toString().matches(regexFilter))
                    .sorted()
                    .collect(Collectors.toList())
            );
        } catch (IOException e) {
            // dir creation or listing failed — show empty list
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int wx = winX(), wy = winY();
        int listY    = wy + HEADER_H;
        int listH    = PANEL_H - HEADER_H - FOOTER_H;
        int visRows  = listH / ROW_H;
        int listX    = wx + PADDING;
        int listW    = PANEL_W - PADDING * 2;

        // Panel background + border
        ctx.fill(wx, wy, wx + PANEL_W, wy + PANEL_H, 0xDD0D0D0D);
        fillOutline(ctx, wx, wy, PANEL_W, PANEL_H, 0x3CFFFFFF);

        // Title
        ctx.fill(wx, wy, wx + PANEL_W, wy + HEADER_H, 0x22FFFFFF);
        ctx.fill(wx, wy + HEADER_H - 1, wx + PANEL_W, wy + HEADER_H, 0x3CFFFFFF);
        ctx.text(this.font, this.title.getString(), wx + PADDING, wy + (HEADER_H - 8) / 2, 0xFFFFFFFF, true);

        // File list
        for (int i = 0; i < visRows; i++) {
            int idx = i + scrollOffset;
            if (idx >= files.size()) break;
            int rowY = listY + i * ROW_H;
            boolean hovered  = mouseX >= listX && mouseX < listX + listW
                            && mouseY >= rowY   && mouseY < rowY + ROW_H;
            boolean selected = idx == selectedIdx;
            if (selected)      ctx.fill(listX, rowY, listX + listW, rowY + ROW_H, 0x44FFFFFF);
            else if (hovered)  ctx.fill(listX, rowY, listX + listW, rowY + ROW_H, 0x22FFFFFF);

            String name = files.get(idx).getFileName().toString();
            int textColor = selected ? 0xFFFFFFFF : 0xFFCCCCCC;
            ctx.text(this.font, truncate(name, listW - 4), listX + 2, rowY + (ROW_H - 8) / 2, textColor, false);
        }

        // Empty state
        if (files.isEmpty()) {
            String msg = "No items in " + folderName + "/";
            ctx.text(this.font, msg,
                     wx + (PANEL_W - this.font.width(msg)) / 2,
                     listY + listH / 2 - 4,
                     0xFF888888, false);
        }

        // Scroll indicators
        if (scrollOffset > 0)
            ctx.text(this.font, "^", wx + PANEL_W - PADDING - 6, listY + 2, 0xFFAAAAAA, false);
        if (scrollOffset + visRows < files.size())
            ctx.text(this.font, "v", wx + PANEL_W - PADDING - 6, listY + listH - 12, 0xFFAAAAAA, false);

        // Footer separator
        int footerY = wy + PANEL_H - FOOTER_H;
        ctx.fill(wx, footerY, wx + PANEL_W, footerY + 1, 0x3CFFFFFF);

        // [Select] button
        boolean hasSelection = selectedIdx >= 0 && selectedIdx < files.size();
        int btnW  = 80;
        int btnH  = 16;
        int selBX = wx + PANEL_W / 2 - btnW - 4;
        int selBY = footerY + (FOOTER_H - btnH) / 2;
        boolean hoverSel = hasSelection && mouseX >= selBX && mouseX < selBX + btnW
                        && mouseY >= selBY && mouseY < selBY + btnH;
        ctx.fill(selBX, selBY, selBX + btnW, selBY + btnH,
                 !hasSelection ? 0xFF111111 : hoverSel ? 0xFF444444 : 0xFF222222);
        fillOutline(ctx, selBX, selBY, btnW, btnH, hasSelection ? 0x3CFFFFFF : 0x1AFFFFFF);
        String selLabel = "Select";
        ctx.text(this.font, selLabel,
                 selBX + (btnW - this.font.width(selLabel)) / 2, selBY + (btnH - 8) / 2,
                 hasSelection ? 0xFFFFFFFF : 0xFF666666, false);

        // [Close] button
        int clsBX = wx + PANEL_W / 2 + 4;
        int clsBY = selBY;
        boolean hoverCls = mouseX >= clsBX && mouseX < clsBX + btnW
                        && mouseY >= clsBY && mouseY < clsBY + btnH;
        ctx.fill(clsBX, clsBY, clsBX + btnW, clsBY + btnH, hoverCls ? 0xFF444444 : 0xFF222222);
        fillOutline(ctx, clsBX, clsBY, btnW, btnH, 0x3CFFFFFF);
        String clsLabel = "Close";
        ctx.text(this.font, clsLabel,
                 clsBX + (btnW - this.font.width(clsLabel)) / 2, clsBY + (btnH - 8) / 2,
                 0xFFFFFFFF, false);

        // Click handling
        boolean mouseDown  = GLFW.glfwGetMouseButton(
            minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean justPressed = mouseDown && !wasMouseDown;
        wasMouseDown = mouseDown;

        if (justPressed) {
            if (hoverCls) {
                this.onClose();
            } else if (hoverSel && hasSelection) {
                if (onSelect != null) onSelect.accept(files.get(selectedIdx));
                this.onClose();
            } else {
                int rowIdx = (mouseY - listY) / ROW_H;
                if (mouseY >= listY && mouseY < listY + listH
                        && mouseX >= listX && mouseX < listX + listW
                        && rowIdx >= 0) {
                    int idx = rowIdx + scrollOffset;
                    if (idx < files.size()) selectedIdx = idx;
                }
            }
        }

        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (super.mouseScrolled(mouseX, mouseY, hAmount, vAmount)) return true;
        int listH   = PANEL_H - HEADER_H - FOOTER_H;
        int visRows = listH / ROW_H;
        int d = (int) -Math.signum(vAmount);
        scrollOffset = Math.max(0, Math.min(scrollOffset + d, Math.max(0, files.size() - visRows)));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void fillOutline(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,         y,         x + w,     y + 1,     color);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     color);
        ctx.fill(x,         y,         x + 1,     y + h,     color);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     color);
    }

    private String truncate(String s, int maxPx) {
        if (this.font.width(s) <= maxPx) return s;
        String ellipsis = "...";
        while (s.length() > 0 && this.font.width(s + ellipsis) > maxPx)
            s = s.substring(0, s.length() - 1);
        return s + ellipsis;
    }
}
