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

    public enum Fly { Baritone, DStarLite }
    public final dev.boze.api.option.ModeOption<Fly> fly = new dev.boze.api.option.ModeOption<>(this, "Fly",
        "Baritone: today's behavior (drive #goal/#elytra via reflection). DStarLite: self-contained engine, no Baritone required -- set a goal with the `goal` command.", Fly.Baritone);
    public final SliderOption verticalMargin = new SliderOption(this, "Vertical Margin",
        "Blocks of Y-difference from the target waypoint within which neither Space nor Shift is auto-pressed.", 3.0, 0.5, 15.0, 0.5);
    public final SliderOption lookahead = new SliderOption(this, "Lookahead",
        "How many nodes ahead of baritone's current path position to read the vertical signal from. Higher = smoother/less reactive, lower = tighter to sudden path turns.", 5.0, 1.0, 20.0, 1.0);
    public final SliderOption yawTurnRate = new SliderOption(this, "Yaw Turn Rate",
        "Max degrees per tick the D* Lite engine may rotate the (hidden) flight yaw toward the next waypoint.", 15.0, 2.0, 45.0, 1.0);

    private com.example.addon.pathfinding.NetherPathfinder netherPathfinder;
    // Level the current netherPathfinder was built for -- if mc.level ever differs
    // (portal/dimension change, reconnect), the old NetherPathfinder is querying a
    // detached Level and must be dropped instead of trusted (Finding 1, final review).
    private net.minecraft.world.level.Level netherPathfinderLevel;

    // ControlRocket-pattern camera decouple (see MixinEntity.java) -- while true, the
    // renderer sees savedCameraYaw/Pitch instead of the entity's real yaw/pitch
    // fields, which are temporarily pointed at the next D* Lite waypoint so
    // ElytraFly Creative's velocity calc flies that direction without visibly
    // spinning the player's actual view.
    public static volatile boolean cameraOverrideActive = false;
    public static volatile float savedCameraYaw = 0f;
    public static volatile float savedCameraPitch = 0f;

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

    public PathFinder() {
        super("PathFinder", "Use boze elytrafly so you dont waste fireworks (but still have to bring at least 5 fireworks to make baritone work.");
    }

    @Override
    public void onEnable() {
        netherPathfinder = null;
        netherPathfinderLevel = null;
        cameraOverrideActive = false;

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
        if (netherPathfinder != null) netherPathfinder.clear();
        windowStartPos = null;
        windowTicks = 0;
        escapeTicksRemaining = 0;
        if (cameraOverrideActive) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.setYRot(savedCameraYaw);
                mc.player.setXRot(savedCameraPitch);
                mc.player.yRotO = savedCameraYaw;
                mc.player.xRotO = savedCameraPitch;
            }
            cameraOverrideActive = false;
        }
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

    /** Sets the D* Lite goal; called by GoalCommand. No-op if Fly isn't DStarLite. */
    public void setGoal(net.minecraft.core.BlockPos goal) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            dev.boze.api.utility.ChatHelper.sendMsg("PathFinder", "§cCan't set goal: not in a world.");
            return;
        }
        if (fly.getValue() != Fly.DStarLite) {
            dev.boze.api.utility.ChatHelper.sendMsg("PathFinder", "§cGoal ignored: set Fly=DStarLite first (currently Baritone).");
            return;
        }
        if (netherPathfinder == null || mc.level != netherPathfinderLevel) {
            netherPathfinder = new com.example.addon.pathfinding.NetherPathfinder(mc.level);
            netherPathfinderLevel = mc.level;
        }
        netherPathfinder.setGoal(mc.player.blockPosition(), goal);
    }

    @EventHandler
    private void onTick(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA || mc.player.onGround()) {
            releaseKeys();
            windowStartPos = null;
            windowTicks = 0;
            escapeTicksRemaining = 0;
            return;
        }

        if (fly.getValue() == Fly.DStarLite) {
            onTickDStarLite(mc);
            return;
        }

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
     * Fly=DStarLite tick: forward-hold + Space/Shift reused unchanged from the
     * Baritone path, waypoint source swapped for NetherPathfinder, plus a
     * short forward raycast safety backstop independent of planner
     * correctness, plus camera-safe yaw/pitch (see class-level fields and
     * MixinEntity.java).
     */
    private void onTickDStarLite(Minecraft mc) {
        // Finding 1 (final review): NetherPathfinder/NetherGraph capture a Level once;
        // a portal/dimension change or reconnect swaps mc.level to a new, detached
        // instance. Querying the old one either throws (swallowed below) or returns
        // stale terrain. Detect it here and drop the stale planner instead of flying
        // blind -- deliberately NOT auto-recreating the goal in the new world, the
        // user re-running `goal` after a dimension change is the correct behavior.
        if (netherPathfinder != null && mc.level != netherPathfinderLevel) {
            netherPathfinder.clear();
            netherPathfinder = null;
            netherPathfinderLevel = null;
            releaseKeys();
            windowStartPos = null;
            windowTicks = 0;
            escapeTicksRemaining = 0;
            dev.boze.api.utility.ChatHelper.sendMsg("PathFinder", "§cWorld changed -- D* Lite goal lost, use goal again.");
            return;
        }

        Vec3 pos = mc.player.position();

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

        if (netherPathfinder == null || !netherPathfinder.isActive()) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return;
        }

        net.minecraft.core.BlockPos waypoint;
        try {
            waypoint = netherPathfinder.tick(mc.player.blockPosition());
        } catch (Exception e) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return;
        }
        if (waypoint == null) {
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return;
        }

        // Safety backstop: short raycast ahead, independent of planner
        // correctness -- cut forward and escape immediately if something solid
        // is directly in front, rather than trusting the planner never errs.
        Vec3 look = mc.player.getLookAngle();
        AABB ahead = mc.player.getBoundingBox().move(look.x * 2.0, look.y * 2.0, look.z * 2.0);
        if (!mc.level.noCollision(mc.player, ahead)) {
            escapeStrafeLeft = clearerSideIsLeft(mc);
            escapeTicksRemaining = ESCAPE_TICKS;
            mc.options.keyUp.setDown(false);
            mc.options.keyJump.setDown(false);
            mc.options.keyShift.setDown(false);
            return;
        }

        mc.options.keyUp.setDown(true);

        double dx = waypoint.getX() + 0.5 - pos.x;
        double dz = waypoint.getZ() + 0.5 - pos.z;
        double dy = waypoint.getY() + 0.5 - pos.y;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        savedCameraYaw = mc.player.getYRot();
        savedCameraPitch = mc.player.getXRot();

        float targetYawRaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawDelta = net.minecraft.util.Mth.wrapDegrees(targetYawRaw - savedCameraYaw);
        float maxTurn = yawTurnRate.getValue().floatValue();
        yawDelta = Math.max(-maxTurn, Math.min(maxTurn, yawDelta));
        float targetYaw = savedCameraYaw + yawDelta;
        float targetPitch = (float) Math.max(-60.0, Math.min(60.0, -Math.toDegrees(Math.atan2(dy, Math.max(horizDist, 0.001)))));

        mc.player.setYRot(targetYaw);
        mc.player.setXRot(targetPitch);
        mc.player.setYBodyRot(targetYaw);
        cameraOverrideActive = true;

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

    @EventHandler
    private void onTickPost(EventTick.Post event) {
        if (!cameraOverrideActive) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { cameraOverrideActive = false; return; }
        mc.player.setYRot(savedCameraYaw);
        mc.player.setXRot(savedCameraPitch);
        mc.player.yRotO = savedCameraYaw;
        mc.player.xRotO = savedCameraPitch;
        cameraOverrideActive = false;
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
