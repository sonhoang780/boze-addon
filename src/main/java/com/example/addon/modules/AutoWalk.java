package com.example.addon.modules;

import com.example.addon.util.BaritoneUtils;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;

/**
 * AutoWalk — holds forward (or backward) movement every tick.
 *
 * Boze's own built-in AutoWalk holds its movement key unconditionally, which fights
 * Baritone: when EBounce+'s obstacle-passer (or PathFinder) hands control to Baritone to
 * walk around something, Boze's AutoWalk keeps forcing its own direction on top of
 * whatever Baritone's input override is trying to do, so the player never actually
 * follows Baritone's path (reported in-game). This module auto-pauses itself instead --
 * every tick Baritone is actively driving movement (BaritoneUtils.isActive(), the same
 * check EBounce+'s obstacle-passer uses), it does nothing and lets Baritone through
 * uncontested.
 */
public class AutoWalk extends AddonModule {
    public static final AutoWalk INSTANCE = new AutoWalk();

    public final ToggleOption backward = new ToggleOption(this, "Backward",
        "Walk backward instead of forward.", false);

    public AutoWalk() {
        super("AutoWalk", "Holds forward (or backward) movement every tick. Auto-pauses while Baritone is actively driving movement (e.g. EBounce+'s ObstaclePassing) so the two don't fight over input.");
    }

    @EventHandler
    private void onInput(EventInput event) {
        if (BaritoneUtils.isActive()) return;
        if (backward.getValue()) {
            event.backward = true;
            event.forward = false;
        } else {
            event.forward = true;
            event.backward = false;
        }
    }
}
