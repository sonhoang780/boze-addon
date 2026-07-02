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
 *   3. Reads a LIVE lookahead waypoint from baritone's actual computed path (not the
 *      player's camera pitch -- pitch is driven by the player's own mouse look, which
 *      fights/overrides anything baritone might try to aim, so it's useless as a
 *      navigation signal; and not just the final goal either -- see below) and compares
 *      its Y to the player's current Y to decide Space (ascend) / Shift (descend).
 *   4. A mixin (MixinMultiPlayerGameMode) cancels firework-rocket use packets while this
 *      module is enabled, since baritone's own elytra process tries to burn fireworks
 *      for boost that ElytraFly Creative doesn't need and that would otherwise
 *      waste/drop items.
 *
 * Vertical signal, two layers (both reflection-only -- see readLookaheadWaypoint /
 * readCurrentDestination -- no compile-time baritone types are referenced and nothing is
 * vendored under the `baritone` package, deliberately, to avoid repeating the
 * class-shadowing bug that broke `#elytra` in the first place):
 *
 *   Primary: IElytraProcess#currentDestination() only exposes the FINAL goal, not the
 *   path baritone actually flies to avoid dead ends/terrain -- and worse, if `#goal` was
 *   given without a Y, baritone hardcodes that goal's Y to 64 internally (verified
 *   against ElytraProcess.java), a value with no relation to the real path shape. So the
 *   primary signal instead reaches into ElytraProcess's private `behavior` field
 *   (ElytraBehavior) and its public `pathManager` (PathManager#getPath(): NetherPath,
 *   PathManager#getNear(): int = index of the path node closest to the player) to read
 *   a LIVE waypoint a few nodes ahead of the player's current position on the path
 *   baritone is actually flying right now -- this moves as baritone's own path updates,
 *   and reflects real ascend/descend/dead-end-avoidance decisions, not a static goal.
 *
 *   Fallback: if #elytra isn't active yet (behavior null) or the path is momentarily
 *   empty (still calculating), fall back to currentDestination()'s static goal Y so
 *   there's still SOME signal rather than none.
 *
 * These internal classes (ElytraBehavior/PathManager/NetherPath, all under
 * baritone.process(.elytra)) are NOT part of baritone's public `api` module and could
 * change shape in a future baritone build -- every reflective call here is wrapped to
 * fail closed (return null, caller falls back or does nothing) rather than throw.
 */
public class PathFinder extends AddonModule {

    public static final PathFinder INSTANCE = new PathFinder();

    private static final String MODULE_ELYTRA_FLY = "ElytraFly";

    public final SliderOption verticalMargin = new SliderOption(this, "Vertical Margin",
        "Blocks of Y-difference from the target waypoint within which neither Space nor Shift is auto-pressed.", 3.0, 0.5, 15.0, 0.5);
    public final SliderOption lookahead = new SliderOption(this, "Lookahead",
        "How many nodes ahead of baritone's current path position to read the vertical signal from. Higher = smoother/less reactive, lower = tighter to sudden path turns.", 5.0, 1.0, 20.0, 1.0);

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

        Object elytraProcess = getElytraProcess();
        BlockPos target = elytraProcess != null
            ? readLookaheadWaypoint(elytraProcess, lookahead.getValue().intValue())
            : null;
        if (target == null && elytraProcess != null) {
            target = readCurrentDestination(elytraProcess);
        }
        if (target == null) {
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return;
        }

        double dy = target.getY() - mc.player.getY();
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
     * baritone.api.BaritoneAPI#getProvider() -> IBaritoneProvider#getPrimaryBaritone()
     * -> IBaritone#getElytraProcess(). Null if baritone isn't loaded/available at all.
     */
    private static Object getElytraProcess() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            return baritone.getClass().getMethod("getElytraProcess").invoke(baritone);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Static fallback: IElytraProcess#currentDestination() -- the final goal only, and
     * (if `#goal` was given without a Y) hardcoded to Y=64 internally by baritone,
     * unrelated to the real path shape. Only used when the live path below isn't
     * available yet.
     */
    private static BlockPos readCurrentDestination(Object elytraProcess) {
        try {
            Object dest = elytraProcess.getClass().getMethod("currentDestination").invoke(elytraProcess);
            return dest instanceof BlockPos bp ? bp : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Live waypoint `lookaheadNodes` ahead of the player's current position on
     * baritone's actual computed path -- reaches past the public IElytraProcess API into
     * ElytraProcess's private `behavior` field (ElytraBehavior) and its public
     * `pathManager` (PathManager#getPath(): NetherPath, PathManager#getNear(): int).
     * Returns null (caller falls back to the static destination) if #elytra isn't
     * actively flying yet, the path is momentarily empty, or any of this internal shape
     * doesn't match (baritone build changed) -- every step is reflection, nothing here
     * is compile-time-checked against real baritone types.
     */
    private static BlockPos readLookaheadWaypoint(Object elytraProcess, int lookaheadNodes) {
        try {
            java.lang.reflect.Field behaviorField = elytraProcess.getClass().getDeclaredField("behavior");
            behaviorField.setAccessible(true);
            Object behavior = behaviorField.get(elytraProcess);
            if (behavior == null) return null;

            Object pathManager = behavior.getClass().getField("pathManager").get(behavior);
            Object path = pathManager.getClass().getMethod("getPath").invoke(pathManager);
            if (path == null) return null;

            int size = (int) path.getClass().getMethod("size").invoke(path);
            if (size == 0) return null;

            int near = (int) pathManager.getClass().getMethod("getNear").invoke(pathManager);
            int idx = Math.max(0, Math.min(near + lookaheadNodes, size - 1));
            Object pos = path.getClass().getMethod("get", int.class).invoke(path, idx);
            return pos instanceof BlockPos bp ? bp : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
