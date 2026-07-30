package com.example.addon.util;

import com.example.addon.modules.bedaura.BedAura;
import com.example.addon.modules.betterrekit.EvilRekit;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.CraftingMenu;

/** Shared by MixinAbstractContainerScreen and MixinContainerBackground -- plain class, not a
 *  mixin, so it can hold a normal public static method both can call. Lives outside
 *  com.example.addon.mixin: that whole package is claimed by example-addon.mixins.json's
 *  "package" field, and Mixin refuses to load any non-mixin class from it directly. */
public final class SilentGuiHelper {
    private SilentGuiHelper() {}

    public static boolean shouldHide(Object screen) {
        if (screen instanceof InventoryScreen) return false; // never hide the player's own inventory
        Object menu = ((AbstractContainerScreen<?>) screen).getMenu();
        if (menu instanceof CraftingMenu && BedAura.INSTANCE.silent.getValue() && BedAura.INSTANCE.isAutoCraftRunning()) {
            return true;
        }
        return EvilRekit.INSTANCE.silentContainer.getValue() && EvilRekit.INSTANCE.isAutoActive();
    }
}
