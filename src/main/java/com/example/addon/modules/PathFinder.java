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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * PathFinder — no longer a standalone pathfinder. The vendored native
 * (dev.babbaj.pathfinder) nether-ceiling implementation never worked reliably and was
 * removed entirely (it also shadowed baritone's OWN bundled copy of the same
 * package/class, which broke `#elytra` with a NoSuchMethodError). The self-contained
 * D* Lite engine (NetherPathfinder/NetherGraph/DStarLite, `goal` command) was removed
 * too -- too many correctness issues (flew past the goal with no arrival check, and a
 * 1-block/26-neighbor search grid that couldn't converge within its iteration budget
 * for the multi-thousand-block distances elytra travel actually needs, causing
 * sustained lag) to be worth carrying alongside the Baritone-driven mode below.
 * Replaced with the "củ chuối" (janky-but-works) approach: let baritone's own `#goal` +
 * `#elytra` compute and RENDER the path (baritone does this itself, no code needed here
 * for either) while this module:
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
 *   5. Only takes over movement while baritone's #elytra process is genuinely
 *      isActive() -- an equipped elytra alone isn't enough (see onTick). Hands full
 *      control back to baritone (disabling ElytraFly Creative entirely) the moment it
 *      enters its own State.LANDING, since baritone's careful vanilla-glide touchdown
 *      needs no fireworks and would otherwise fight ElytraFly's velocity control.
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
 * These internal classes (ElytraBehavior/PathManager/NetherPath/ElytraProcess.State, all
 * under baritone.process(.elytra)) are NOT part of baritone's public `api` module and
 * could change shape in a future baritone build -- every reflective call here is
 * wrapped to fail closed (return null/false, caller falls back or does nothing) rather
 * than throw.
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

    // Stuck detection + auto-escape: baritone's #elytra path can hug terrain closely
    // enough to wedge the player inside a block (nether-ceiling flying especially),
    // and grinding forward against solid ground never breaks free on its own --
    // previously required manually pressing S + (A or D) to back out (2026-07-04).
    private static final int STUCK_WINDOW_TICKS = 10;   // ~0.5s @ 20tps
    private static final double STUCK_DIST_THRESHOLD = 0.3; // blocks of net movement expected per window
    private static final int ESCAPE_TICKS = 14;          // ~0.7s of back+strafe

    private Vec3 windowStartPos = null;
    private int windowTicks = 0;
    private int escapeTicksRemaining = 0;
    private boolean escapeStrafeLeft = true;

    // True only while this module actually holds movement keys this tick (baritone's
    // #elytra process genuinely isActive() and not landing). Lets releaseKeys() fire
    // ONCE on the falling edge instead of every tick -- calling it unconditionally every
    // tick fights the player's OWN real key input even when they never asked this module
    // to fly them anywhere (e.g. walking normally without an elytra, or wearing one
    // without ever running #elytra).
    private boolean wasControllingMovement = false;

    // True while ElytraFly was forced off specifically for a baritone landing (not the
    // separate enabledElytraFly, which tracks whether PathFinder's OWN onEnable turned it
    // on in the first place) -- restored the instant baritone leaves State.LANDING.
    private boolean elytraFlyDisabledForLanding = false;

    public PathFinder() {
        super("PathFinder", "Use boze elytrafly so you dont waste fireworks (but still have to bring at least 5 fireworks to make baritone work.");
    }

    @Override
    public void onEnable() {
        wasControllingMovement = false;

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
        wasControllingMovement = false;
        windowStartPos = null;
        windowTicks = 0;
        escapeTicksRemaining = 0;
        // FIX: KHÔNG trả ElytraFly về mode cũ (Control) nữa -- giữ nguyên "Creative"
        // (cùng fix đã áp cho ElytraFix.restoreFlyMode). Chỉ dọn dẹp tham chiếu.
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
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    /**
     * Hands control back to the player: releases keys ONCE (only if we actually held them
     * last tick -- calling releaseKeys() unconditionally every tick would keep stomping the
     * player's own real key input even while this module has nothing to do) and clears the
     * stuck-detection window so a stale one doesn't leak into the next active period.
     */
    private void stopControllingMovement() {
        if (wasControllingMovement) {
            releaseKeys();
            wasControllingMovement = false;
        }
        windowStartPos = null;
        windowTicks = 0;
        escapeTicksRemaining = 0;
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA || mc.player.onGround()) {
            stopControllingMovement();
            restoreElytraFlyIfDisabledForLanding();
            return;
        }

        // Only take over movement while baritone's #elytra process is genuinely running
        // (isActive()) -- an equipped elytra alone used to be enough to hold forward
        // unconditionally below, which auto-flew the player even when #elytra was never
        // run (or had already finished). Also the fix for releaseKeys() spam: previously
        // called every tick whenever the guard above failed, which fought the player's
        // own real key input even while just walking without an elytra.
        Object elytraProcess = getElytraProcess();
        if (elytraProcess == null || !isProcessActive(elytraProcess)) {
            stopControllingMovement();
            restoreElytraFlyIfDisabledForLanding();
            return;
        }

        // Baritone reached the end of its path and is doing its own careful vanilla-glide
        // touchdown (no fireworks, precise control -- see "Path complete, picking a nearby
        // safe landing spot..." in chat). ElytraFly's Creative velocity control fights that,
        // so disable it and let go of our own held keys for the duration of the landing;
        // restored the instant baritone leaves the landing state (new path, or done).
        if (isElytraLanding(elytraProcess)) {
            stopControllingMovement();
            disableElytraFlyForLanding();
            return;
        }
        restoreElytraFlyIfDisabledForLanding();
        wasControllingMovement = true;

        Vec3 pos = mc.player.position();

        // Escape maneuver in progress: back off + strafe away from the wall instead of
        // grinding forward into it.
        if (escapeTicksRemaining > 0) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(true);
            mc.options.keyLeft.setDown(escapeStrafeLeft);
            mc.options.keyRight.setDown(!escapeStrafeLeft);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            escapeTicksRemaining--;
            windowStartPos = pos;
            windowTicks = 0;
            return;
        }
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);

        // Stuck detection: sample net displacement over a fixed tick window while
        // airborne with an elytra equipped. Near-zero net movement despite forward
        // being held means we're wedged against terrain, not just ascending/turning
        // slowly. Pick whichever side (left/right of current facing) is actually open
        // before committing to strafe there, so the escape doesn't just wedge deeper.
        if (windowStartPos == null) {
            windowStartPos = pos;
            windowTicks = 0;
        } else if (++windowTicks >= STUCK_WINDOW_TICKS) {
            if (pos.distanceTo(windowStartPos) < STUCK_DIST_THRESHOLD) {
                escapeStrafeLeft = clearerSideIsLeft(mc);
                escapeTicksRemaining = ESCAPE_TICKS;
                windowStartPos = pos;
                windowTicks = 0;
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keyShift.setDown(false);
                return;
            }
            windowStartPos = pos;
            windowTicks = 0;
        }

        BlockPos target = readLookaheadWaypoint(elytraProcess, lookahead.getValue().intValue());
        if (target == null) {
            target = readCurrentDestination(elytraProcess);
        }
        if (target == null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return;
        }

        double dx = target.getX() + 0.5 - pos.x;
        double dz = target.getZ() + 0.5 - pos.z;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double dy = target.getY() - mc.player.getY();
        double margin = verticalMargin.getValue();

        // Path is overwhelmingly vertical (climbing/descending a shaft): release forward
        // so ElytraFly Creative's direction calc takes its pure-vertical branch
        // (targetPitch = ±90°, straight up/down) instead of the shallow diagonal climb it
        // computes when forward is ALSO held (its "hasH" branch caps pitch at
        // upSpeed*90° -- a partial angle, not actually vertical). Holding both fought
        // baritone's real near-vertical path shape and showed up as jitter.
        if (Math.abs(dy) > margin && Math.abs(dy) > horizDist * 2.0) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(dy > 0);
            mc.options.keyShift.setDown(dy < 0);
            return;
        }

        // Forward: hold it, advancing in whatever direction the player is currently
        // facing (their own mouse look, or baritone's yaw if it manages to hold it --
        // either way "go forward" is the correct action while following a path).
        mc.options.keyUp.setDown(true);

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
     * True if strafing LEFT of the player's current facing has more open space than
     * strafing right (tested via AABB collision against the world a couple blocks out
     * to each side), so the escape maneuver picks the direction that doesn't just wedge
     * the player into more terrain.
     */
    private static boolean clearerSideIsLeft(Minecraft mc) {
        Vec3 look = mc.player.getLookAngle();
        // up(0,1,0) x forward = (look.z, 0, -look.x): perpendicular, horizontal, "left".
        Vec3 left = new Vec3(look.z, 0, -look.x).normalize();
        double testDist = 1.5;
        AABB box = mc.player.getBoundingBox();
        AABB leftBox = box.move(left.x * testDist, 0, left.z * testDist);
        AABB rightBox = box.move(-left.x * testDist, 0, -left.z * testDist);
        boolean leftClear = mc.level.noCollision(mc.player, leftBox);
        boolean rightClear = mc.level.noCollision(mc.player, rightBox);
        if (leftClear != rightClear) return leftClear;
        return mc.player.getRandom().nextBoolean();
    }

    /**
     * IBaritoneProcess#isActive() -- true only while baritone's #elytra command is
     * genuinely running a path right now, false once it's finished/never started. Every
     * baritone process (elytra included) implements this. Fail closed like every other
     * reflective call here: any exception means "treat as not active."
     */
    private static boolean isProcessActive(Object elytraProcess) {
        try {
            return (boolean) elytraProcess.getClass().getMethod("isActive").invoke(elytraProcess);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ElytraProcess#state (private, an internal State enum -- NOT part of the public
     * IElytraProcess API) == State.LANDING: baritone finished its path and is doing its
     * own careful vanilla-glide touchdown, logging "Path complete, picking a nearby safe
     * landing spot..." (github.com/cabaletta/baritone, process/ElytraProcess.java). Not
     * part of the public API and could change shape in a future baritone build -- fails
     * closed like every other reflective call here (treat as "not landing").
     */
    private static boolean isElytraLanding(Object elytraProcess) {
        try {
            java.lang.reflect.Field stateField = elytraProcess.getClass().getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(elytraProcess);
            return state instanceof Enum<?> e && "LANDING".equals(e.name());
        } catch (Throwable t) {
            return false;
        }
    }

    private void disableElytraFlyForLanding() {
        if (elytraFlyDisabledForLanding) return;
        try {
            if (ModuleManager.getState(MODULE_ELYTRA_FLY)) {
                ModuleManager.setState(MODULE_ELYTRA_FLY, false);
            }
        } catch (Exception ignored) {}
        elytraFlyDisabledForLanding = true;
    }

    private void restoreElytraFlyIfDisabledForLanding() {
        if (!elytraFlyDisabledForLanding) return;
        try {
            ModuleManager.setState(MODULE_ELYTRA_FLY, true);
        } catch (Exception ignored) {}
        elytraFlyDisabledForLanding = false;
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
