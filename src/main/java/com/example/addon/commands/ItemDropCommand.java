package com.example.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.boze.api.addon.AddonCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ItemDropCommand extends AddonCommand {
    public static final ItemDropCommand INSTANCE = new ItemDropCommand();

    private ItemDropCommand() {
        super("itemdrop", "Drop items from inventory");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        // Gợi ý danh sách tất cả các item có trong game từ a-z
        SuggestionProvider<SharedSuggestionProvider> suggestItems = (ctx, b) -> {
            String input = b.getRemaining().toLowerCase();
            List<String> items = new ArrayList<>();
            
            // Duyệt thẳng qua danh sách Item để né việc lấy KeySet (né ResourceLocation)
            for (Item item : BuiltInRegistries.ITEM) {
                items.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
            items.sort(String::compareTo);
            
            for (String itemStr : items) {
                if (itemStr.toLowerCase().contains(input)) {
                    b.suggest(itemStr);
                }
            }
            return b.buildFuture();
        };

        // Lệnh: itemdrop all (Vứt hết sạch đồ trong im lặng)
        builder.then(literal("all")
            .executes(ctx -> {
                dropAllItems();
                return SINGLE_SUCCESS;
            })
        );

        // Lệnh: itemdrop dropitem <tên_item> (Chỉ vứt item chỉ định)
        builder.then(literal("dropitem")
            .then(argument("item", StringArgumentType.greedyString())
                .suggests(suggestItems)
                .executes(ctx -> {
                    String itemName = StringArgumentType.getString(ctx, "item");
                    dropSpecificItem(itemName);
                    return SINGLE_SUCCESS;
                })
            )
        );
    }

    private void dropAllItems() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        int containerId = mc.player.containerMenu.containerId;

        for (int invSlot = 0; invSlot < 36; invSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (!stack.isEmpty()) {
                int handlerSlot = invToHandlerSlot(invSlot);
                mc.gameMode.handleContainerInput(containerId, handlerSlot, 1, ContainerInput.THROW, mc.player);
            }
        }
    }

    private void dropSpecificItem(String itemName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;

        int containerId = mc.player.containerMenu.containerId;

        for (int invSlot = 0; invSlot < 36; invSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (!stack.isEmpty()) {
                // Ép item thành chuỗi giống hệt cách InventoryCleaner.java đang làm
                String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                
                // Cho phép nhận diện cả "minecraft:stone" lẫn "stone"
                if (key.equalsIgnoreCase(itemName) || key.substring(key.indexOf(':') + 1).equalsIgnoreCase(itemName)) {
                    int handlerSlot = invToHandlerSlot(invSlot);
                    mc.gameMode.handleContainerInput(containerId, handlerSlot, 1, ContainerInput.THROW, mc.player);
                }
            }
        }
    }

    // Chuyển đổi ID khe đồ của Inventory thành ID khe trên giao diện để gửi Packet
    private static int invToHandlerSlot(int invSlot) {
        return (invSlot <= 8) ? 36 + invSlot : invSlot;
    }
}