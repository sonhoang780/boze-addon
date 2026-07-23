package com.example.addon.util;

import dev.boze.api.client.ModuleManager;
import dev.boze.api.client.module.BaseModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.Option;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;

/**
 * Continuously tracks ElytraFly's "Mode" and AutoMine's "Instant" ModeOption values, every
 * tick, for the whole session -- independent of whether PathFinder/ElytraFix/BedAura are
 * enabled. Subscribed directly to the addon event bus (via BozeInstance.subscribe, not as a
 * toggleable AddonModule), so it always runs.
 * <p>
 * Exists because Boze resets a ModeOption to its defaultValue the instant the OWNING module's
 * enabled state flips off->on (confirmed 2026-07-20, reproduced with zero addon modules
 * involved). Capturing inside PathFinder/ElytraFix/BedAura's own onEnable can already be too
 * late if that reset happened earlier in the session (e.g. the user manually toggling the
 * module). Polling every tick means we always have the last genuinely-observed value from
 * right before any reset, ready to restore to.
 * <p>
 * PathFinder/ElytraFix call {@link #suppressElytraFly} / {@link #unsuppressElytraFly} around
 * their own forced "Creative" override so this cache doesn't mistake that override for the
 * user's real setting; BedAura does the same with {@link #suppressAutoMine} /
 * {@link #unsuppressAutoMine}. Counters (not booleans) because PathFinder and ElytraFix can
 * both be suppressing ElytraFly's mode at once.
 */
public final class LiveModeCache {
    public static final LiveModeCache INSTANCE = new LiveModeCache();

    private static final String MODULE_ELYTRA_FLY = "ElytraFly";
    private static final String MODULE_AUTOMINE = "AutoMine";

    private volatile String elytraFlyMode = null;
    private volatile String autoMineInstantMode = null;

    private int elytraFlySuppressCount = 0;
    private int autoMineSuppressCount = 0;

    private LiveModeCache() {}

    public void suppressElytraFly() { elytraFlySuppressCount++; }
    public void unsuppressElytraFly() { if (elytraFlySuppressCount > 0) elytraFlySuppressCount--; }
    public void suppressAutoMine() { autoMineSuppressCount++; }
    public void unsuppressAutoMine() { if (autoMineSuppressCount > 0) autoMineSuppressCount--; }

    @EventHandler
    private void onTick(EventTick.Post event) {
        if (Minecraft.getInstance().player == null) return;
        if (elytraFlySuppressCount == 0) {
            String v = readMode(MODULE_ELYTRA_FLY, "Mode");
            if (v != null) elytraFlyMode = v;
        }
        if (autoMineSuppressCount == 0) {
            String v = readMode(MODULE_AUTOMINE, "Instant");
            if (v != null) autoMineInstantMode = v;
        }
    }

    private static String readMode(String moduleName, String optionName) {
        try {
            BaseModule mod = ModuleManager.getClientModule(moduleName);
            if (mod == null) return null;
            for (Option<?> opt : mod.getOptions()) {
                if (opt instanceof ModeOption<?> mo && mo.name.equalsIgnoreCase(optionName)) {
                    return mo.getModeName();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public String getElytraFlyMode() { return elytraFlyMode; }
    public String getAutoMineInstantMode() { return autoMineInstantMode; }
}
