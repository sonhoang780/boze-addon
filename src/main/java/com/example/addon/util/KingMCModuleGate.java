package com.example.addon.util;

import com.example.addon.ExampleAddon;
import com.example.addon.modules.AutoShop;
import com.example.addon.modules.PhobosAutoTotem;
import com.example.addon.modules.PhobosDoubleHand;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * Truly hides AutoShop/PhobosAutoTotem/PhobosDoubleHand from the whole addon (GUI module
 * browser, .printmodule/.set command suggestions, everything ModuleManager.getModules()
 * feeds) instead of just AddonModule#isVisible (ArrayList-only, per BaseModule javadoc).
 * ModuleManager builds its module list straight off Addon#modules every call, so removing an
 * instance from that live ArrayList removes it everywhere; re-adding restores it.
 * <p>
 * Guarded on mc.player != null so this never races Addon#load() (fromJson iterates the current
 * modules list -- running before any world exists means the full list, including these three,
 * is always what gets deserialized at startup).
 * <p>
 * Subscribed directly to the addon event bus (like LiveModeCache), not as a toggleable
 * AddonModule, so it always runs regardless of any module's own state.
 */
public final class KingMCModuleGate {
    public static final KingMCModuleGate INSTANCE = new KingMCModuleGate();

    private static final AddonModule[] GATED = { AutoShop.INSTANCE, PhobosAutoTotem.INSTANCE, PhobosDoubleHand.INSTANCE };

    private KingMCModuleGate() {}

    @EventHandler
    private void onTick(EventTick.Post event) {
        if (Minecraft.getInstance().player == null) return;

        List<AddonModule> modules = ExampleAddon.INSTANCE.modules;
        boolean allowed = ServerGate.isKingMC();

        for (AddonModule module : GATED) {
            boolean present = modules.contains(module);
            if (allowed && !present) {
                modules.add(module);
            } else if (!allowed && present) {
                if (module.getState()) module.setState(false);
                modules.remove(module);
            }
        }
    }

    /** Called from ExampleAddon#shutdown so config saves always see every module, hidden or not. */
    public void restoreForSave() {
        List<AddonModule> modules = ExampleAddon.INSTANCE.modules;
        for (AddonModule module : GATED) {
            if (!modules.contains(module)) modules.add(module);
        }
    }
}
