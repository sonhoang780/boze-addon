package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto-refills hotbar stacks from the main inventory so held items stay topped up.
 * One refill per tick, routed through handleContainerInput so InvMovePlus's GrimV2
 * handles it transparently. Skips maps. Refills unstackables too (moves a fresh one into
 * an emptied slot). MainHand's own totem slot is skipped so the two modules never fight
 * over it; a totem remembered in any other slot (e.g. BetterOffhand's Legit backup slot)
 * is refilled normally.
 */
public class Replenish extends AddonModule {
    public static final Replenish INSTANCE = new Replenish();

    public final SliderOption threshold = new SliderOption(this, "Threshold",
            "Refill a hotbar stack below this count (clamped to the item's max; 1 = only when empty).",
            64.0, 1.0, 64.0, 1.0);
    public final ToggleOption debug = new ToggleOption(this, "Debug",
            "Log refill actions for reporting.", false);

    // Remembered item per hotbar slot so emptied/unstackable slots can be refilled by type.
    private final Item[] remembered = new Item[9];

    public Replenish() {
        super("Replenish", "Auto-refill hotbar item stacks from your inventory.");
    }

    @Override
    public void onDisable() {
        for (int i = 0; i < remembered.length; i++) remembered[i] = null;
    }

    private void dbg(String msg) {
        if (debug.getValue()) ChatHelper.sendMsg("Replenish", msg);
    }

    // InventoryMenu slot id for an inventory index (hotbar 0-8 -> 36+i, main 9-35 -> i).
    private int menuSlot(int invIndex) {
        if (invIndex >= 0 && invIndex <= 8) return 36 + invIndex;
        return invIndex;
    }

    private void click(Minecraft mc, int slotId, int button, ContainerInput type) {
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slotId, button, type, mc.player);
    }

    // First main-inventory index (9-35) holding item; -1 if none.
    private int findSource(Minecraft mc, Item item) {
        Inventory inv = mc.player.getInventory();
        for (int i = 9; i <= 35; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Own inventory menu only (id 0); a foreign container shifts slot ids.
        if (mc.player.containerMenu.containerId != 0) return;

        Inventory inv = mc.player.getInventory();

        // A screen is open: the user is rearranging by hand. Refilling here fights the
        // drag (moving your only sword out of the hotbar got it swapped straight back,
        // 2026-07-21). Instead, let the manual layout REDEFINE remembered: a slot the
        // user leaves empty on purpose stays empty after the screen closes.
        if (mc.screen != null) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = inv.getItem(i);
                remembered[i] = s.isEmpty() ? null : s.getItem();
            }
            return;
        }
        int th = threshold.getValue().intValue();
        boolean mainHandOn = MainHand.INSTANCE.getState();
        int totemSlot = mainHandOn ? MainHand.INSTANCE.slot.getValue().intValue() : -1;

        for (int i = 0; i < 9; i++) {
            if (i == totemSlot) continue; // MainHand owns the totem slot

            ItemStack cur = inv.getItem(i);
            if (!cur.isEmpty()) remembered[i] = cur.getItem();
            Item type = !cur.isEmpty() ? cur.getItem() : remembered[i];
            if (type == null) continue;
            if (type == Items.MAP || type == Items.FILLED_MAP) continue;      // never maps
            // MainHand's own totem slot is already skipped above (i == totemSlot); a totem
            // remembered in any OTHER slot (e.g. BetterOffhand's Legit backup) is fine to refill.

            int maxStack = type.getDefaultMaxStackSize();
            int trigger = Math.min(th, maxStack);
            if (cur.getCount() >= trigger) continue;

            int src = findSource(mc, type);
            if (src < 0) continue;

            if (cur.isEmpty()) {
                // Atomic move into the empty hotbar slot (works for unstackables too).
                click(mc, menuSlot(src), i, ContainerInput.SWAP);
            } else {
                // Merge source onto the partial hotbar stack, return leftover.
                int ms = menuSlot(src);
                int hb = menuSlot(i);
                click(mc, ms, 0, ContainerInput.PICKUP);
                click(mc, hb, 0, ContainerInput.PICKUP);
                click(mc, ms, 0, ContainerInput.PICKUP);
            }
            dbg("§brefill slot " + i + " (" + type + ")");
            return; // one refill per tick
        }
    }
}
