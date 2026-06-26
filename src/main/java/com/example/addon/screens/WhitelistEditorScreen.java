package com.example.addon.screens;

import com.example.addon.modules.InventoryCleaner;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class WhitelistEditorScreen extends Screen {

    private static final int ROW_H    = 22;
    private static final int PADDING  = 6;
    private static final int HEADER_H = 36;
    private static final int ICON_SIZE = 16;
    private static final int COL_GAP  = 4;

    private EditBox searchBox;
    private int scrollLeft  = 0;
    private int scrollRight = 0;

    private List<Item> filteredItems  = new ArrayList<>();
    private List<Item> whitelistItems = new ArrayList<>();

    public WhitelistEditorScreen() {
        super(Component.literal("Whitelist Editor"));
    }

    @Override
    protected void init() {
        super.init();
        int boxW = Math.min(200, this.width / 2 - PADDING * 2);
        int boxX = (this.width - boxW) / 2;
        searchBox = new EditBox(this.font, boxX, PADDING, boxW, 18, Component.literal(""));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search items..."));
        searchBox.setResponder(text -> {
            scrollLeft = 0;
            rebuildFiltered(text);
        });
        this.addRenderableWidget(searchBox);
        rebuildFiltered("");
        rebuildWhitelistItems();
    }

    private void rebuildFiltered(String query) {
        String q = query.toLowerCase().trim();
        filteredItems = BuiltInRegistries.ITEM.stream()
            .filter(item -> item != Items.AIR)
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
            .map(key -> BuiltInRegistries.ITEM.getValue(Identifier.tryParse(key)))
            .filter(item -> item != null && item != Items.AIR)
            .sorted(Comparator.comparing(item -> item.getName(item.getDefaultInstance()).getString()))
            .collect(Collectors.toList());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xCC111111);
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        int colW     = (this.width - PADDING * 3 - COL_GAP) / 2;
        int leftX    = PADDING;
        int rightX   = PADDING + colW + COL_GAP;
        int contentY = HEADER_H;
        int visibleRows = (this.height - contentY - PADDING) / ROW_H;

        ctx.text(this.font, "ALL ITEMS", leftX, contentY - 12, 0xAAAAAA, false);
        ctx.text(this.font, "WHITELIST", rightX, contentY - 12, 0xAAAAAA, false);

        // Left column — all items
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollLeft;
            if (idx >= filteredItems.size()) break;
            Item item = filteredItems.get(idx);
            int y = contentY + i * ROW_H;

            boolean hovered = mouseX >= leftX && mouseX < leftX + colW && mouseY >= y && mouseY < y + ROW_H;
            if (hovered) ctx.fill(leftX, y, leftX + colW, y + ROW_H, 0x33FFFFFF);

            ctx.item(item.getDefaultInstance(), leftX + 2, y + (ROW_H - ICON_SIZE) / 2);
            ctx.text(this.font, item.getName(item.getDefaultInstance()),
                leftX + 2 + ICON_SIZE + 3, y + (ROW_H - 8) / 2, 0xFFFFFF, false);
        }

        // Right column — whitelist
        for (int i = 0; i < visibleRows; i++) {
            int idx = i + scrollRight;
            if (idx >= whitelistItems.size()) break;
            Item item = whitelistItems.get(idx);
            int y = contentY + i * ROW_H;

            boolean hovered = mouseX >= rightX && mouseX < rightX + colW && mouseY >= y && mouseY < y + ROW_H;
            if (hovered) ctx.fill(rightX, y, rightX + colW, y + ROW_H, 0x33FFFFFF);

            ctx.item(item.getDefaultInstance(), rightX + 2, y + (ROW_H - ICON_SIZE) / 2);
            ctx.text(this.font, item.getName(item.getDefaultInstance()),
                rightX + 2 + ICON_SIZE + 3, y + (ROW_H - 8) / 2, 0xFFFFFF, false);
        }

        // Scroll indicators
        if (scrollLeft > 0)
            ctx.text(this.font, "^", leftX + colW - 10, contentY, 0xAAAAAA, false);
        if (scrollLeft + visibleRows < filteredItems.size())
            ctx.text(this.font, "v", leftX + colW - 10, this.height - PADDING - 10, 0xAAAAAA, false);
        if (scrollRight > 0)
            ctx.text(this.font, "^", rightX + colW - 10, contentY, 0xAAAAAA, false);
        if (scrollRight + visibleRows < whitelistItems.size())
            ctx.text(this.font, "v", rightX + colW - 10, this.height - PADDING - 10, 0xAAAAAA, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != 0) return false;

        double mouseX = click.x();
        double mouseY = click.y();

        int colW     = (this.width - PADDING * 3 - COL_GAP) / 2;
        int leftX    = PADDING;
        int rightX   = PADDING + colW + COL_GAP;
        int contentY = HEADER_H;
        int visibleRows = (this.height - contentY - PADDING) / ROW_H;

        int rowIdx = ((int) mouseY - contentY) / ROW_H;
        if (rowIdx < 0 || rowIdx >= visibleRows) return false;

        // Click left column → add to whitelist
        if (mouseX >= leftX && mouseX < leftX + colW) {
            int idx = rowIdx + scrollLeft;
            if (idx < filteredItems.size()) {
                String key = BuiltInRegistries.ITEM.getKey(filteredItems.get(idx)).toString();
                InventoryCleaner.whitelist.add(key);
                rebuildWhitelistItems();
            }
            return true;
        }

        // Click right column → remove from whitelist
        if (mouseX >= rightX && mouseX < rightX + colW) {
            int idx = rowIdx + scrollRight;
            if (idx < whitelistItems.size()) {
                String key = BuiltInRegistries.ITEM.getKey(whitelistItems.get(idx)).toString();
                InventoryCleaner.whitelist.remove(key);
                rebuildWhitelistItems();
                if (scrollRight > 0 && scrollRight >= whitelistItems.size()) scrollRight--;
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;

        int colW     = (this.width - PADDING * 3 - COL_GAP) / 2;
        int leftX    = PADDING;
        int rightX   = PADDING + colW + COL_GAP;
        int contentY = HEADER_H;
        int visibleRows = (this.height - contentY - PADDING) / ROW_H;
        int delta    = (int) -Math.signum(verticalAmount);

        if (mouseX >= leftX && mouseX < leftX + colW) {
            scrollLeft  = Math.max(0, Math.min(scrollLeft  + delta, Math.max(0, filteredItems.size()  - visibleRows)));
        } else if (mouseX >= rightX && mouseX < rightX + colW) {
            scrollRight = Math.max(0, Math.min(scrollRight + delta, Math.max(0, whitelistItems.size() - visibleRows)));
        }
        return true;
    }

    @Override
    public void onClose() {
        InventoryCleaner.saveWhitelist();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
