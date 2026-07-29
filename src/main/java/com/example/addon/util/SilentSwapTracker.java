package com.example.addon.util;

import net.minecraft.world.item.ItemStack;

/**
 * Tracks the addon's persistent silent-swap holds -- MainHand and PhobosDoubleHand's
 * SwapType.Silent "silentHeld" state, held open-ended across many ticks (not the
 * momentary swap+swapBack-in-the-same-call pattern every other module uses, which
 * never lasts long enough to be worth surfacing in a HUD). SilentSwapOverlay reads
 * this to show what's actually held while it's hidden from other players' view.
 */
public final class SilentSwapTracker {
    private SilentSwapTracker() {}

    private static String activeModule = null;
    private static ItemStack activeItem = ItemStack.EMPTY;

    public static void set(String moduleName, ItemStack item) {
        activeModule = moduleName;
        activeItem = item.copy();
    }

    /** No-op if a DIFFERENT module is currently active, so one module's release can't clear another's hold. */
    public static void clear(String moduleName) {
        if (moduleName.equals(activeModule)) {
            activeModule = null;
            activeItem = ItemStack.EMPTY;
        }
    }

    public static boolean isActive() {
        return activeModule != null;
    }

    public static String moduleName() {
        return activeModule;
    }

    public static ItemStack item() {
        return activeItem;
    }
}
