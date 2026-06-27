package com.example.addon.screens;

import com.example.addon.modules.InventoryCleaner;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class WhitelistEditorScreen extends Screen {

    private static final int PANEL_W        = 520;
    private static final int PANEL_H        = 340;
    private static final int PADDING        = 8;
    private static final int CONTENT_Y_OFF  = 68;  // rows start at winY + this
    private static final int ROW_H          = 20;
    private static final int ICON_SIZE      = 16;

    private EditBox searchBox;
    private int scrollLeft  = 0;
    private int scrollRight = 0;
    private boolean wasMouseDown = false;

    private List<Item> filteredItems  = new ArrayList<>();
    private List<Item> whitelistItems = new ArrayList<>();

    public WhitelistEditorScreen() {
        super(Component.literal("Whitelist Editor"));
    }

    private int winX() { return (this.width  - PANEL_W) / 2; }
    private int winY() { return (this.height - PANEL_H) / 2; }

    @Override
    protected void init() {
        super.init();
        int wx = winX(), wy = winY();
        searchBox = new EditBox(this.font, wx + PADDING, wy + 28,
                                PANEL_W - PADDING * 2 - 2, 16, Component.empty());
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search items..."));
        searchBox.setResponder(text -> { scrollLeft = 0; rebuildFiltered(text); });
        this.addRenderableWidget(searchBox);
        rebuildFiltered("");
        rebuildWhitelistItems();
    }

    private void rebuildFiltered(String query) {
        String q = query.toLowerCase().trim();
        filteredItems = BuiltInRegistries.ITEM.stream()
            .filter(item -> item != Items.AIR)
            .filter(item -> !InventoryCleaner.whitelist.contains(
                BuiltInRegistries.ITEM.getKey(item).toString()))
            .filter(item -> {
                if (q.isEmpty()) return true;
                String name = item.getName(item.getDefaultInstance()).getString().toLowerCase();
                String key  = BuiltInRegistries.ITEM.getKey(item).toString().toLowerCase();
                return name.contains(q) || key.contains(q);
            })
            .sorted(Comparator.comparing(item -> item.getName(item.getDefaultInstance()).getString()))
            .collect(Collectors.toList());
    }

    private void rebuildWhitelistItems() {
        whitelistItems = InventoryCleaner.whitelist.stream()
            .map(key -> {
                Identifier id = Identifier.tryParse(key);
                return id != null ? BuiltInRegistries.ITEM.getValue(id) : null;
            })
            .filter(item -> item != null && item != Items.AIR)
            .sorted(Comparator.comparing(item -> item.getName(item.getDefaultInstance()).getString()))
            .collect(Collectors.toList());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int wx = winX(), wy = winY();
        int colW     = (PANEL_W - PADDING * 3) / 2;
        int leftX    = wx + PADDING;
        int rightX   = wx + PADDING * 2 + colW;
        int divX     = wx + PADDING + colW + PADDING / 2;
        int contentY = wy + CONTENT_Y_OFF;
        int visibleRows = (PANEL_H - CONTENT_Y_OFF - PADDING - 14) / ROW_H;

        // Panel background
        ctx.fill(wx, wy, wx + PANEL_W, wy + PANEL_H, 0xDD0D0D0D);

        // Outer border
        ctx.fill(wx,           wy,           wx + PANEL_W,     wy + 1,            0x3CFFFFFF);
        ctx.fill(wx,           wy + PANEL_H, wx + PANEL_W,     wy + PANEL_H + 1,  0x3CFFFFFF);
        ctx.fill(wx,           wy,           wx + 1,           wy + PANEL_H,      0x3CFFFFFF);
        ctx.fill(wx + PANEL_W, wy,           wx + PANEL_W + 1, wy + PANEL_H,      0x3CFFFFFF);

        // Horizontal separators
        ctx.fill(wx, wy + 22, wx + PANEL_W, wy + 23, 0x3CFFFFFF);
        ctx.fill(wx, wy + 50, wx + PANEL_W, wy + 51, 0x3CFFFFFF);

        // Vertical column divider
        ctx.fill(divX, wy + 51, divX + 1, wy + PANEL_H - 10, 0x3CFFFFFF);

        // Title
        ctx.text(this.font, "Whitelist Editor", wx + PADDING, wy + 7, 0xFFFFFFFF, true);

        // Close button
        int closeX = wx + PANEL_W - 52;
        int closeY = wy + 4;
        boolean hoverClose = mouseX >= closeX && mouseX <= closeX + 44
                          && mouseY >= closeY && mouseY <= closeY + 14;
        ctx.fill(closeX, closeY, closeX + 44, closeY + 14, hoverClose ? 0xFF444444 : 0xFF222222);
        fillOutline(ctx, closeX, closeY, 44, 14, 0x3CFFFFFF);
        ctx.text(this.font, "Close",
                 closeX + (44 - this.font.width("Close")) / 2, closeY + 3, 0xFFFFFFFF, true);

        // Column headers
        ctx.text(this.font, "ALL ITEMS", leftX,  wy + 54, 0xFFAAAAAA, false);
        ctx.text(this.font, "WHITELIST", rightX, wy + 54, 0xFFAAAAAA, false);

        // Left column
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollLeft;
            if (idx >= filteredItems.size()) break;
            Item item = filteredItems.get(idx);
            int rowY = contentY + i * ROW_H;
            boolean hovered = mouseX >= leftX && mouseX < leftX + colW
                           && mouseY >= rowY  && mouseY < rowY + ROW_H;
            if (hovered) ctx.fill(leftX, rowY, leftX + colW, rowY + ROW_H, 0x33FFFFFF);
            ctx.item(item.getDefaultInstance(), leftX + 2, rowY + 2);
            ctx.text(this.font, item.getName(item.getDefaultInstance()),
                     leftX + 2 + ICON_SIZE + 3, rowY + (ROW_H - 8) / 2, 0xFFFFFFFF, false);
        }

        // Right column
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollRight;
            if (idx >= whitelistItems.size()) break;
            Item item = whitelistItems.get(idx);
            int rowY = contentY + i * ROW_H;
            boolean hovered = mouseX >= rightX && mouseX < rightX + colW
                           && mouseY >= rowY   && mouseY < rowY + ROW_H;
            if (hovered) ctx.fill(rightX, rowY, rightX + colW, rowY + ROW_H, 0x33FFFFFF);
            ctx.item(item.getDefaultInstance(), rightX + 2, rowY + 2);
            ctx.text(this.font, item.getName(item.getDefaultInstance()),
                     rightX + 2 + ICON_SIZE + 3, rowY + (ROW_H - 8) / 2, 0xFFFFFFFF, false);
        }

        // Scroll indicators
        if (scrollLeft > 0)
            ctx.text(this.font, "^", leftX + colW - 10, contentY, 0xFFAAAAAA, false);
        if (scrollLeft + visibleRows < filteredItems.size())
            ctx.text(this.font, "v", leftX + colW - 10, wy + PANEL_H - 12, 0xFFAAAAAA, false);
        if (scrollRight > 0)
            ctx.text(this.font, "^", rightX + colW - 10, contentY, 0xFFAAAAAA, false);
        if (scrollRight + visibleRows < whitelistItems.size())
            ctx.text(this.font, "v", rightX + colW - 10, wy + PANEL_H - 12, 0xFFAAAAAA, false);

        // GLFW click detection (same pattern as MusicHUD.FontScreen)
        boolean mouseDown = GLFW.glfwGetMouseButton(
            minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean justPressed = mouseDown && !wasMouseDown;
        wasMouseDown = mouseDown;

        if (justPressed) {
            int rowIdx   = (mouseY - contentY) / ROW_H;
            boolean inRowArea = mouseY >= contentY && rowIdx >= 0 && rowIdx < visibleRows;

            if (hoverClose) {
                this.onClose();
            } else if (inRowArea && mouseX >= leftX && mouseX < leftX + colW) {
                int idx = rowIdx + scrollLeft;
                if (idx < filteredItems.size()) {
                    String key = BuiltInRegistries.ITEM.getKey(filteredItems.get(idx)).toString();
                    InventoryCleaner.whitelist.add(key);
                    rebuildWhitelistItems();
                    rebuildFiltered(searchBox.getValue());
                    if (scrollLeft > 0 && scrollLeft >= filteredItems.size()) scrollLeft--;
                }
            } else if (inRowArea && mouseX >= rightX && mouseX < rightX + colW) {
                int idx = rowIdx + scrollRight;
                if (idx < whitelistItems.size()) {
                    String key = BuiltInRegistries.ITEM.getKey(whitelistItems.get(idx)).toString();
                    InventoryCleaner.whitelist.remove(key);
                    rebuildWhitelistItems();
                    rebuildFiltered(searchBox.getValue());
                    if (scrollRight > 0 && scrollRight >= whitelistItems.size()) scrollRight--;
                }
            }
        }

        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    private void fillOutline(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x,         y,         x + w,     y + 1,     color);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     color);
        ctx.fill(x,         y,         x + 1,     y + h,     color);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     color);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        int wx = winX(), wy = winY();
        int colW     = (PANEL_W - PADDING * 3) / 2;
        int leftX    = wx + PADDING;
        int rightX   = wx + PADDING * 2 + colW;
        int contentY = wy + CONTENT_Y_OFF;
        int visibleRows = (PANEL_H - CONTENT_Y_OFF - PADDING - 14) / ROW_H;
        int d = (int) -Math.signum(verticalAmount);

        if (mouseX >= leftX && mouseX < leftX + colW && mouseY >= contentY) {
            scrollLeft  = Math.max(0, Math.min(scrollLeft  + d,
                          Math.max(0, filteredItems.size()  - visibleRows)));
        } else if (mouseX >= rightX && mouseX < rightX + colW && mouseY >= contentY) {
            scrollRight = Math.max(0, Math.min(scrollRight + d,
                          Math.max(0, whitelistItems.size() - visibleRows)));
        }
        return true;
    }

    @Override
    public void onClose() {
        InventoryCleaner.saveWhitelist();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
