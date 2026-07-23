package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import dev.boze.api.utility.ChatHelper;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts the shulker boxes sitting inside an open chest (single or double) by their
 * display name, ordered special-chars -> digits -> letters, and compacts them into a
 * contiguous block from the top-left slot (a shulker can land in a slot that was empty,
 * not just swap with another shulker) -- any slot holding a non-shulker item is a fixed
 * obstacle and is never touched or skipped over.
 *
 * Runs continuously while the chest GUI is open: one selection-sort pass, a few swaps per
 * tick (each swap is the standard 3-click PICKUP atomic swap the other sorters use). Stays
 * enabled after finishing so it keeps sorting/compacting live as shulkers are added or
 * removed -- only announces "sorted" once per completion, not every idle tick.
 */
public class StashArrange extends AddonModule {
    public static final StashArrange INSTANCE = new StashArrange();

    public final SliderOption delay = new SliderOption(this, "Delay", "Ticks between swap batches.", 0.0, 0.0, 10.0, 1.0);
    public final SliderOption actionsPerTick = new SliderOption(this, "Actions/Tick", "Max swaps per batch.", 5.0, 1.0, 5.0, 1.0);
    public final ToggleOption descending = new ToggleOption(this, "Descending", "Reverse the order (letters -> digits -> special).", false);

    private int ticks = 0;
    private boolean announcedDone = false;

    public StashArrange() {
        super("StashArrange", "Arrange shulker boxes in an open chest by name (special, number, alphabet).");
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { setState(false); return; }
        if (mc.player.isCreative()) return;

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!(menu instanceof ChestMenu chest)) return; // only real chests (double chest is still ChestMenu, 6 rows)

        if (!menu.getCarried().isEmpty()) return; // wait for the cursor to clear before swapping

        if (ticks < delay.getValue().intValue()) { ticks++; return; }
        ticks = 0;

        Container chestContainer = chest.getContainer();
        // Chest-side slots (ascending): which currently hold a shulker (the items to place),
        // and which are eligible DESTINATIONS -- shulker-holding OR empty. A slot holding a
        // non-shulker item is neither; it's a fixed obstacle, never a target, never skipped
        // over as if it weren't there.
        List<Integer> shulkerSlots = new ArrayList<>();
        List<Integer> eligible = new ArrayList<>();
        for (int k = 0; k < menu.slots.size(); k++) {
            Slot s = menu.getSlot(k);
            if (s.container != chestContainer) continue; // skip the player-inventory slots
            ItemStack it = s.getItem();
            if (isShulkerBox(it)) { shulkerSlots.add(k); eligible.add(k); }
            else if (it.isEmpty()) eligible.add(k);
        }
        if (shulkerSlots.size() < 2) { announcedDone = false; return; }

        // Compacted destinations: the first N eligible positions, ascending -- this is what
        // pulls shulkers up into any earlier empty slot instead of leaving gaps in place.
        List<Integer> targets = eligible.subList(0, shulkerSlots.size());

        // Desired key order for those destinations.
        List<Integer> sorted = new ArrayList<>(shulkerSlots);
        Comparator<Integer> byName = Comparator.comparing(k -> nameKey(menu.getSlot(k).getItem()), StashArrange::compareNames);
        if (descending.getValue()) byName = byName.reversed();
        sorted.sort(byName);

        int budget = actionsPerTick.getValue().intValue();
        boolean anySwap = false;
        // Selection sort: for each target position i, ensure it holds the item whose key
        // ranks i-th. Match by key string (shulkers can share a name), so a correctly-keyed
        // box already in place is left untouched. Source search scans the WHOLE chest (not
        // just the compacted target zone) since a not-yet-moved shulker can currently sit
        // past the compacted block, in a slot beyond position N.
        for (int i = 0; i < targets.size() && budget > 0; i++) {
            int targetPos = targets.get(i);
            String want = nameKey(menu.getSlot(sorted.get(i)).getItem());
            if (nameKey(menu.getSlot(targetPos).getItem()).equals(want)) continue;

            int src = -1;
            for (int k = 0; k < menu.slots.size(); k++) {
                Slot s = menu.getSlot(k);
                if (s.container != chestContainer || k == targetPos) continue;
                if (nameKey(s.getItem()).equals(want)) { src = k; break; }
            }
            if (src == -1) continue; // shouldn't happen; keys came from these same slots

            atomicSwap(menu.containerId, targetPos, src);
            anySwap = true;
            budget--;
        }

        if (anySwap) {
            announcedDone = false;
        } else if (!announcedDone) {
            ChatHelper.sendMsg("StashArrange", "§aShulkers sorted.");
            announcedDone = true;
        }
    }

    private void atomicSwap(int menuId, int a, int b) {
        click(menuId, a);
        click(menuId, b);
        click(menuId, a);
    }

    private void click(int menuId, int slotId) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleContainerInput(menuId, slotId, 0, ContainerInput.PICKUP, mc.player);
    }

    private static String nameKey(ItemStack stack) {
        return stack.isEmpty() ? "" : stack.getHoverName().getString();
    }

    /**
     * Ordering: special characters, then digits, then letters. Compared char-by-char --
     * each char's category (0 special, 1 digit, 2 letter) wins first, ties break on the
     * value (letters case-insensitive). A shorter prefix sorts before its extension.
     */
    private static int compareNames(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            char ca = a.charAt(i), cb = b.charAt(i);
            int catA = category(ca), catB = category(cb);
            if (catA != catB) return Integer.compare(catA, catB);
            char va = Character.toLowerCase(ca), vb = Character.toLowerCase(cb);
            if (va != vb) return Character.compare(va, vb);
        }
        return Integer.compare(a.length(), b.length());
    }

    private static int category(char c) {
        if (Character.isLetter(c)) return 2;
        if (Character.isDigit(c)) return 1;
        return 0;
    }

    private boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }
}
