package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.client.module.BaseModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.Option;
import dev.boze.api.option.SliderOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * PathFinder — no longer a standalone pathfinder. The vendored native
 * (dev.babbaj.pathfinder) nether-ceiling implementation never worked reliably and was
 * removed entirely (it also shadowed baritone's OWN bundled copy of the same
 * package/class, which broke `#elytra` with a NoSuchMethodError). Replaced with the
 * "củ chuối" (janky-but-works) approach: let baritone's own `#goal` + `#elytra` compute
 * and RENDER the path (baritone does this itself, no code needed here for either) while
 * this module:
 *   1. Forces Boze's own ElytraFly module into Creative mode so the player is
 *      velocity-controlled (not vanilla-gliding) and can't fall out of the sky.
 *   2. Auto-holds the forward key so the player advances in whatever direction they're
 *      currently facing.
 *   3. Reads baritone's own current elytra destination Y (NOT the player's camera pitch
 *      -- pitch is driven by the player's own mouse look, which fights/overrides
 *      anything baritone might try to aim, so it's useless as a navigation signal) via
 *      reflection into baritone's public API (baritone.api.BaritoneAPI ->
 *      IBaritoneProvider -> IBaritone -> IElytraProcess#currentDestination()) and
 *      compares it to the player's current Y to decide Space (ascend) / Shift (descend).
 *      Reflection only -- no compile-time baritone types are referenced, and nothing is
 *      vendored under the `baritone` package, specifically to avoid repeating the
 *      class-shadowing bug that broke `#elytra` in the first place.
 *   4. A mixin (MixinMultiPlayerGameMode) cancels firework-rocket use packets while this
 *      module is enabled, since baritone's own elytra process tries to burn fireworks
 *      for boost that ElytraFly Creative doesn't need and that would otherwise
 *      waste/drop items.
 *
 * Limitation: IElytraProcess only exposes the FINAL destination, not the intermediate
 * waypoints of the computed path -- there is no public baritone API for that. So the
 * vertical signal is "above/below the final goal", not "above/below the next terrain
 * feature the path is about to hug". Good enough for coarse ascend/descend assistance;
 * the player is still expected to steer around anything baritone's path threads through
 * that this coarse signal wouldn't anticipate.
 */
public class PathFinder extends AddonModule {

    public static final PathFinder INSTANCE = new PathFinder();

    private static final String MODULE_ELYTRA_FLY = "ElytraFly";

    public final SliderOption verticalMargin = new SliderOption(this, "Vertical Margin",
        "Blocks of Y-difference from baritone's current elytra destination within which neither Space nor Shift is auto-pressed.", 3.0, 0.5, 15.0, 0.5);

    // True only if THIS module enabled ElytraFly (vs. it already being on) -- mirrors
    // ElytraFix's save/restore pattern so we don't clobber a state the user set manually.
    private boolean enabledElytraFly = false;
    private ModeOption<?> flyModeOption = null;
    private String savedFlyModeName = null;

    public PathFinder() {
        super("PathFinder", "Steers along baritone's #goal/#elytra path: forces ElytraFly Creative, auto-holds forward, and auto-presses Space/Shift from baritone's own elytra destination Y. Also blocks baritone's firework use while enabled.");
    }

    @Override
    public void onEnable() {
        enabledElytraFly = false;
        flyModeOption = null;
        savedFlyModeName = null;

        boolean wasOn;
        try {
            wasOn = ModuleManager.getState(MODULE_ELYTRA_FLY);
        } catch (IllegalArgumentException e) {
            wasOn = false;
        }
        if (!wasOn) {
            try {
                ModuleManager.setState(MODULE_ELYTRA_FLY, true);
                enabledElytraFly = true;
            } catch (Exception ignored) {}
        }

        try {
            BaseModule fly = ModuleManager.getClientModule(MODULE_ELYTRA_FLY);
            if (fly != null) {
                for (Option<?> opt : fly.getOptions()) {
                    if (opt instanceof ModeOption<?> mo && mo.name.equalsIgnoreCase("mode")) {
                        flyModeOption = mo;
                        savedFlyModeName = mo.getModeName();
                        mo.setValueByName("Creative");
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDisable() {
        releaseKeys();
        if (flyModeOption != null && savedFlyModeName != null) {
            try { flyModeOption.setValueByName(savedFlyModeName); } catch (Exception ignored) {}
        }
        flyModeOption = null;
        savedFlyModeName = null;
        if (enabledElytraFly) {
            try { ModuleManager.setState(MODULE_ELYTRA_FLY, false); } catch (Exception ignored) {}
            enabledElytraFly = false;
        }
    }

    private void releaseKeys() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA || mc.player.onGround()) {
            releaseKeys();
            return;
        }

        // Forward: hold it, advancing in whatever direction the player is currently
        // facing (their own mouse look, or baritone's yaw if it manages to hold it --
        // either way "go forward" is the correct action while following a path).
        mc.options.keyUp.setDown(true);

        BlockPos destination = readBaritoneDestination();
        if (destination == null) {
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return;
        }

        double dy = destination.getY() - mc.player.getY();
        double margin = verticalMargin.getValue();
        if (dy > margin) {
            mc.options.keyJump.setDown(true);
            mc.options.keyShift.setDown(false);
        } else if (dy < -margin) {
            mc.options.keyShift.setDown(true);
            mc.options.keyJump.setDown(false);
        } else {
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
        }
    }

    /**
     * Reflection-only path to baritone's current elytra destination:
     * baritone.api.BaritoneAPI#getProvider() -> IBaritoneProvider#getPrimaryBaritone()
     * -> IBaritone#getElytraProcess() -> IElytraProcess#currentDestination() (null if
     * #elytra isn't currently active). No baritone types are referenced at compile
     * time and nothing is vendored under the `baritone` package -- deliberately, so
     * this addon's jar can never shadow baritone's own bundled classes again (that bug
     * is what broke `#elytra` with a NoSuchMethodError before this rewrite).
     */
    private static BlockPos readBaritoneDestination() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            Object elytra = baritone.getClass().getMethod("getElytraProcess").invoke(baritone);
            Object dest = elytra.getClass().getMethod("currentDestination").invoke(elytra);
            return dest instanceof BlockPos bp ? bp : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
