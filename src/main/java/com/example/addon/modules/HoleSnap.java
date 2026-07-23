package com.example.addon.modules;

import com.example.addon.util.CustomTimer;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventPacket;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ported from BlackOut's HoleSnap (Marccccccccccccccc/BlackOut, kassuk.addon.blackout.modules.HoleSnap).
 *
 * Movement direction: EventInput's booleans are ALWAYS relative to the player's real
 * current yaw (camera), not the target -- rotating the camera toward the hole (via
 * EventRotate) turned out unreliable (silently no-op'd), which is why the first port
 * "chỉ biết đi thẳng theo camera" (2026-07-16). Fixed by computing the WASD-relative
 * (xxa, zza) impulse analytically from the REAL yaw and the direction to the hole via
 * the inverse of vanilla's yaw-rotation matrix (verified against KeyboardInput.tick /
 * calculateImpulse bytecode: moveVector.x = left(+)/right(-) impulse, moveVector.y =
 * forward(+)/backward(-) impulse, then LivingEntity.getInputVector rotates
 * (x,z)->world by yaw as worldX = x*cos(yaw) - z*sin(yaw), worldZ = z*cos(yaw) +
 * x*sin(yaw)). This makes HoleSnap walk toward the hole regardless of where the
 * camera is actually looking -- no rotation needed at all.
 *
 * Speed/Boost/Timer: the original's raw-velocity Speed/Boost settings have no direct
 * Boze equivalent (EventInput is boolean-only, see above) -- but Boze itself ships a
 * built-in CLIENT module literally named "Timer" (confirmed: BaseModule#onDisable's
 * own javadoc says "In some, restores changes, i.e. Timer"), the same tick-speed-hack
 * mechanism Meteor's Timer module is. Controlled here via ModuleManager.getClientModule
 * ("Timer") + the generic Option list (no addon-facing class for it exists, so its
 * exact option name can't be confirmed without a live client -- this grabs the first
 * SliderOption on it, which a single-purpose Timer module should only have one of).
 */
public class HoleSnap extends AddonModule {
    public static final HoleSnap INSTANCE = new HoleSnap();

    private enum HoleType { NotHole, Single, DoubleX, DoubleZ, Quad }

    private record Hole(BlockPos pos, HoleType type, BlockPos[] positions, Vec3 middle) {
        static Hole of(BlockPos pos, HoleType type) {
            return switch (type) {
                case Single -> new Hole(pos, type, new BlockPos[]{pos},
                    new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
                case DoubleX -> new Hole(pos, type, new BlockPos[]{pos, pos.offset(1, 0, 0)},
                    new Vec3(pos.getX() + 1, pos.getY(), pos.getZ() + 0.5));
                case DoubleZ -> new Hole(pos, type, new BlockPos[]{pos, pos.offset(0, 0, 1)},
                    new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1));
                case Quad -> new Hole(pos, type, new BlockPos[]{pos, pos.offset(1, 0, 0), pos.offset(0, 0, 1), pos.offset(1, 0, 1)},
                    new Vec3(pos.getX() + 1, pos.getY(), pos.getZ() + 1));
                default -> new Hole(pos, type, new BlockPos[0],
                    new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
            };
        }
    }

    public enum HoleSnapMode { Normal, Strict }

    public final ModeOption<HoleSnapMode> mode = new ModeOption<>(this, "Mode",
        "Normal = direct-velocity homing, lands exactly on hole center (see Speed). "
        + "Strict = original WASD-relative approach with a deadzone (real vanilla movement, "
        + "less precise centering) -- use if Normal's raw-velocity movement gets flagged.",
        HoleSnapMode.Normal);

    public final SliderOption range = new SliderOption(this, "Range",
        "Horizontal range for finding holes.", 3.0, 0.0, 5.0, 1.0);
    public final SliderOption downRange = new SliderOption(this, "DownRange",
        "Vertical range for finding holes.", 3.0, 0.0, 5.0, 1.0);
    public final SliderOption depth = new SliderOption(this, "Depth",
        "How deep a hole has to be.", 3.0, 1.0, 5.0, 1.0);
    public final ToggleOption jump = new ToggleOption(this, "Jump",
        "Jump toward the hole while snapping.", false);
    public final SliderOption jumpCooldown = new SliderOption(this, "JumpCooldown",
        "Ticks between jumps.", 5.0, 0.0, 20.0, 1.0, (java.util.function.BooleanSupplier) jump::getValue);
    public final ToggleOption collDisable = new ToggleOption(this, "CollisionDisable",
        "Disable HoleSnap after too many blocked movement attempts in a row.", true);
    public final ToggleOption rubberbandDisable = new ToggleOption(this, "RubberbandDisable",
        "Disable HoleSnap on the first server position correction (rubberband).", true);
    public final ToggleOption autoDisable = new ToggleOption(this, "AutoDisable",
        "Automatically disable once you've actually fallen into the hole.", true);

    public final SliderOption timerSpeed = new SliderOption(this, "Timer",
        "Boze's Timer module multiplier to run at while HoleSnap is active (1.0 = normal speed).",
        1.0, 0.5, 10.0, 0.1);
    public final ToggleOption boost = new ToggleOption(this, "Boost",
        "Use a higher Timer multiplier for the first few ticks after enabling.", false);
    public final SliderOption boostedTimerSpeed = new SliderOption(this, "BoostedTimer",
        "Timer multiplier used during the initial boost window.", 2.0, 0.5, 10.0, 0.1,
        (java.util.function.BooleanSupplier) boost::getValue);
    public final SliderOption boostTicks = new SliderOption(this, "BoostTicks",
        "How many ticks to hold the boosted Timer multiplier for after enabling.", 3.0, 1.0, 20.0, 1.0,
        (java.util.function.BooleanSupplier) boost::getValue);

    // Direct per-tick horizontal step. BlackOut's original default (0.2873) assumes no
    // velocity-based AC -- this server's checks already forced FastWeb down to a known
    // safe ceiling of 0.08/tick raw velocity (see FastWeb NCP tuning), so default here
    // matches that instead of BlackOut's, or the server rubberbands the spike back every
    // tick and HoleSnap looks like it's standing still (2026-07-20).
    public final SliderOption speed = new SliderOption(this, "Speed",
        "Per-tick horizontal step toward the hole center (blocks/tick). Keep <=0.08 to avoid velocity-check rubberband on this server.", 0.08, 0.02, 0.3, 0.01);

    private static final int COLLISION_LIMIT = 15;

    private int collisions = 0;
    private int jumpTicks = 0;
    private int boostTicksLeft = 0;

    // Fake server-side yaw toward the hole, camera hidden -- same technique
    // ControlRocket/EBounce+ already use (see MixinEntity): set the entity's raw
    // yRot/xRot fields (what sendMovementPackets() reads) to face the hole in Pre,
    // then restore the real camera rotation in Post before the frame renders.
    // MixinEntity intercepts getYRot(F)/getXRot(F) (the render-interpolated getters)
    // while cameraOverrideActive is true so the visible camera never snaps (user
    // request 2026-07-16: "xoay yaw/pitch nhưng không bị xoay cam, method giống
    // FakeFly"). onInput's own WASD-relative math still runs independently on top
    // of whatever yaw is active that tick -- harmless overlap, not a conflict.
    public static volatile boolean cameraOverrideActive = false;
    public static volatile float savedCameraYaw = 0f;
    public static volatile float savedCameraPitch = 0f;

    private HoleSnap() {
        super("HoleSnap", "Auto-walks into the nearest 1x1/2x1/2x2 hole and snaps into place.");
    }

    @Override
    public void onEnable() {
        collisions = 0;
        jumpTicks = 0;
        boostTicksLeft = boost.getValue() ? boostTicks.getValue().intValue() : 0;
    }

    @Override
    public void onDisable() {
        CustomTimer.multiplier = 1.0; // never leave the game permanently sped up
        Minecraft mc = Minecraft.getInstance();
        if (cameraOverrideActive && mc.player != null) {
            mc.player.setYRot(savedCameraYaw);
            mc.player.setXRot(savedCameraPitch);
            mc.player.yRotO = savedCameraYaw;
            mc.player.xRotO = savedCameraPitch;
            mc.player.setYHeadRot(savedCameraYaw);
            mc.player.yHeadRotO = savedCameraYaw;
            mc.player.yBob = savedCameraYaw;
            mc.player.yBobO = savedCameraYaw;
            mc.player.xBob = savedCameraPitch;
            mc.player.xBobO = savedCameraPitch;
        }
        cameraOverrideActive = false;
    }

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (playerInHole(mc)) return;
        Hole hole = findHole(mc);
        if (hole == null) return;

        savedCameraYaw = mc.player.getYRot();
        savedCameraPitch = mc.player.getXRot();

        double dx = hole.middle().x - mc.player.getX();
        double dz = hole.middle().z - mc.player.getZ();
        if (dx * dx + dz * dz < 0.0001) return;
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        mc.player.setYRot(targetYaw);
        // 2026-07-19 fix ("tay nghiêng theo hướng di chuyển"): unlike ControlRocket (whose
        // targetYaw IS the real flight direction, so forcing yBodyRot there matches actual
        // movement), HoleSnap moves via WASD-relative strafing that can point anywhere
        // relative to targetYaw -- forcing the body to face the hole while actually walking
        // sideways/backward relative to that is what read as a stuck/tilted arm. Leave
        // yBodyRot alone; vanilla's own movement-follows-body smoothing orients it from
        // real walking, same as normal unassisted movement.
        cameraOverrideActive = true;
    }

    @EventHandler
    private void onTickPost(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!cameraOverrideActive || mc.player == null) return;
        mc.player.setYRot(savedCameraYaw);
        mc.player.setXRot(savedCameraPitch);
        mc.player.yRotO = savedCameraYaw;
        mc.player.xRotO = savedCameraPitch;
        // Player.aiStep() unconditionally does `yHeadRot = getYRot()` every tick (real
        // source, verified javap) -- it runs between Pre's fake yRot and this restore,
        // burning targetYaw into yHeadRot. getViewYRot() (camera/hand render) reads
        // yHeadRot, not yRot, so leaving it unrestored is what caused the hand to point
        // toward the movement direction instead of the real look direction (2026-07-19,
        // "tay lệch khi holesnap đi về bên phải").
        mc.player.setYHeadRot(savedCameraYaw);
        mc.player.yHeadRotO = savedCameraYaw;
        // Freeze the hand's own rotation-smoothing (xBob/yBob, read by
        // ItemInHandRenderer on top of getViewYRot). applyInput() runs mid-tick and
        // pulls them toward the raw fake yaw; left unrestored, the moving targetYaw
        // made the hand sway continuously (2026-07-19 "tay cứ đung đưa").
        mc.player.yBob = savedCameraYaw;
        mc.player.yBobO = savedCameraYaw;
        mc.player.xBob = savedCameraPitch;
        mc.player.xBobO = savedCameraPitch;
        cameraOverrideActive = false;
    }

    @EventHandler
    private void onTimerTick(EventTick.Post event) {
        double desired = (boost.getValue() && boostTicksLeft > 0) ? boostedTimerSpeed.getValue() : timerSpeed.getValue();
        if (boost.getValue() && boostTicksLeft > 0) boostTicksLeft--;
        CustomTimer.multiplier = desired;
    }

    @EventHandler
    private void onPacket(EventPacket.Receive event) {
        if (rubberbandDisable.getValue() && event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            setState(false);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[HoleSnap] disabled: rubberbanding"));
        }
    }

    // HIGHEST priority: original BlackOut ran onMove at EventPriority.HIGHEST so no
    // other movement-affecting module could override it after the fact -- same lib
    // (meteordevelopment.orbit) backs Boze's EventHandler too, confirmed via javap.
    @EventHandler(priority = meteordevelopment.orbit.EventPriority.HIGHEST)
    private void onInput(EventInput event) {
        if (mode.getValue() == HoleSnapMode.Strict) {
            onInputStrict(event);
        } else {
            onInputNormal(event);
        }
    }

    // Normal mode: direct-velocity homing (2026-07-20 "vẫn không căn được vào center của
    // hole"). BlackOut's real onMove sets ((IVec3) event.movement) directly every tick,
    // each axis clamped to whichever is smaller in magnitude -- the fixed step or the
    // remaining distance (`Math.abs(x) < Math.abs(dX) ? x : dX`) -- so it lands EXACTLY
    // on hole.middle and never needs a separate "already inside" phase. Strict mode (see
    // onInputStrict) keeps the old WASD-relative approach for whoever needs real vanilla
    // movement instead of raw-velocity (e.g. a server flags the instant-set spike).
    private void onInputNormal(EventInput event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Real "fell into the hole" check (mirrors BlackOut's HoleUtils.inHole): the
        // player's OWN feet block is one of a valid hole's cells.
        Hole inHole = holePlayerIsIn(mc);
        Hole hole = inHole != null ? inHole : findHole(mc);
        if (hole == null) {
            setState(false);
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[HoleSnap] disabled: no hole found"));
            return;
        }

        event.forward = false;
        event.backward = false;
        event.left = false;
        event.right = false;

        double dx = hole.middle().x - mc.player.getX();
        double dz = hole.middle().z - mc.player.getZ();

        if (dx == 0.0 && dz == 0.0) {
            // Exactly centered already (this is only reachable once a previous tick's
            // clamped step landed dead-on, same as BlackOut's getX()==middle.x check).
            Vec3 v = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(0.0, v.y, 0.0);
            if (inHole != null && autoDisable.getValue()) setState(false);
            return;
        }

        double step = speed.getValue();
        double vx = Math.abs(step) < Math.abs(dx) ? Math.copySign(step, dx) : dx;
        double vz = Math.abs(step) < Math.abs(dz) ? Math.copySign(step, dz) : dz;

        AABB attempt = mc.player.getBoundingBox().move(vx, 0, vz);
        if (collides(mc, attempt)) {
            collisions++;
            if (collDisable.getValue() && collisions >= COLLISION_LIMIT) {
                setState(false);
                // Diagnostic dump (2026-07-17): user reports HoleSnap keeps ramming the
                // SAME blocked hole even with 2+ other holes visibly clear nearby ("hole
                // 2 và hole 3 không bị collide thì không chọn"). Unknown whether findHole
                // genuinely never sees holes 2/3 at all (getHole()'s rigid "wall must be
                // on the west/north side" assumption can reject a real, human-obvious
                // hole shaped the other way) or whether they're found but still lose out
                // somehow -- don't guess further, dump every Hole candidate in range plus
                // its climb-penalty status and score so the next repro has ground truth
                // instead of a fourth theory.
                dumpHoleCandidates(mc);
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[HoleSnap] disabled: collided"));
                return;
            }
        } else {
            collisions = 0;
        }

        Vec3 v = mc.player.getDeltaMovement();
        mc.player.setDeltaMovement(vx, v.y, vz);

        if (jump.getValue()) {
            if (jumpTicks > 0) {
                jumpTicks--;
            } else if (collides(mc, mc.player.getBoundingBox().move(0, -0.05, 0))) {
                jumpTicks = jumpCooldown.getValue().intValue();
                event.jumping = true;
            }
        }
    }

    // Strict mode: the pre-2026-07-20 mechanism, kept for servers that flag Normal's
    // instant-set raw velocity. Real vanilla WASD movement via event.forward/left/right,
    // computed WASD-relative from the player's REAL yaw (see class javadoc) with a 0.15
    // deadzone; once standing in the hole cell, switches to a closed-loop velocity nudge
    // to settle into the exact center (imprecise close to center vs Normal, but each step
    // is ordinary vanilla-speed movement).
    private void onInputStrict(EventInput event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Hole inHole = holePlayerIsIn(mc);
        if (inHole != null) {
            event.forward = false;
            event.backward = false;
            event.left = false;
            event.right = false;

            double dx = inHole.middle().x - mc.player.getX();
            double dz = inHole.middle().z - mc.player.getZ();
            double err2 = dx * dx + dz * dz;
            double centerTol = 0.012;
            if (err2 > centerTol * centerTol) {
                double gain = 0.35;       // P-gain; <1 so it decelerates approaching target
                double maxSpeed = 0.08;   // clamp so it never lurches
                double vx = Math.max(-maxSpeed, Math.min(maxSpeed, dx * gain));
                double vz = Math.max(-maxSpeed, Math.min(maxSpeed, dz * gain));
                Vec3 v = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(vx, v.y, vz);
            } else {
                Vec3 v = mc.player.getDeltaMovement();
                mc.player.setDeltaMovement(0.0, v.y, 0.0);
                if (autoDisable.getValue()) setState(false);
            }
            return;
        }

        Hole hole = findHole(mc);
        if (hole == null) {
            setState(false);
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[HoleSnap] disabled: no hole found"));
            return;
        }

        double dx = hole.middle().x - mc.player.getX();
        double dz = hole.middle().z - mc.player.getZ();

        AABB attempt = mc.player.getBoundingBox().move(
            Math.signum(dx) * 0.05, 0, Math.signum(dz) * 0.05);
        if (collides(mc, attempt)) {
            collisions++;
            if (collDisable.getValue() && collisions >= COLLISION_LIMIT) {
                setState(false);
                dumpHoleCandidates(mc);
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[HoleSnap] disabled: collided"));
                return;
            }
        } else {
            collisions = 0;
        }

        double yawRad = Math.toRadians(mc.player.getYRot());
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double xxa = dx * cos + dz * sin;   // left(+) / right(-)
        double zza = -dx * sin + dz * cos;  // forward(+) / backward(-)
        double deadzone = 0.15;

        event.forward = zza > deadzone;
        event.backward = zza < -deadzone;
        event.left = xxa > deadzone;
        event.right = xxa < -deadzone;

        if (jump.getValue()) {
            if (jumpTicks > 0) {
                jumpTicks--;
            } else if (collides(mc, mc.player.getBoundingBox().move(0, -0.05, 0))) {
                jumpTicks = jumpCooldown.getValue().intValue();
                event.jumping = true;
            }
        }
    }

    /** The hole the player's own feet block belongs to, or null (mirrors BlackOut's HoleUtils.inHole). */
    private Hole holePlayerIsIn(Minecraft mc) {
        BlockPos pos = mc.player.blockPosition();
        int d = depth.getValue().intValue();
        BlockPos[] offsets = {
            pos, pos.offset(-1, 0, 0), pos.offset(0, 0, -1), pos.offset(-1, 0, -1)
        };
        for (BlockPos p : offsets) {
            Hole h = getHole(mc, p, d);
            if (h == null) continue;
            for (BlockPos cell : h.positions()) {
                if (cell.equals(pos)) return h;
            }
        }
        return null;
    }

    private boolean playerInHole(Minecraft mc) {
        return holePlayerIsIn(mc) != null;
    }

    // Raw distance isn't enough to rank holes -- a farther hole with a clear straight-
    // line approach is a better pick than a closer one behind a block lip, since walking
    // straight into a lip triggers CollisionDisable's counter (visible in-game: repeated
    // "[HoleSnap] disabled: collided" when Jump is off and the nearest-by-distance hole
    // happened to be the one requiring a step up, 2026-07-16 clip). This penalty steers
    // selection toward the directly-walkable hole when one exists in range, instead of
    // blindly picking whichever is a few blocks closer.
    private static final double CLIMB_PENALTY = 1000.0;

    private Hole findHole(Minecraft mc) {
        Hole closest = null;
        double closestScore = Double.MAX_VALUE;
        int r = range.getValue().intValue();
        int dr = downRange.getValue().intValue();
        int d = depth.getValue().intValue();

        BlockPos base = mc.player.blockPosition();
        for (int x = -r; x <= r; x++) {
            for (int y = -dr; y <= 0; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = base.offset(x, y, z);
                    Hole hole = getHole(mc, pos, d);
                    if (hole == null) continue;
                    double score = hole.middle().distanceToSqr(mc.player.position());
                    if (pathRequiresClimb(mc, hole)) score += CLIMB_PENALTY;
                    if (score < closestScore) {
                        closestScore = score;
                        closest = hole;
                    }
                }
            }
        }
        return closest;
    }

    /** Diagnostic-only: logs every Hole candidate findHole() would see this tick, with its climb-penalty status. */
    private void dumpHoleCandidates(Minecraft mc) {
        int r = range.getValue().intValue();
        int dr = downRange.getValue().intValue();
        int d = depth.getValue().intValue();
        BlockPos base = mc.player.blockPosition();
        int found = 0;
        for (int x = -r; x <= r; x++) {
            for (int y = -dr; y <= 0; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = base.offset(x, y, z);
                    Hole hole = getHole(mc, pos, d);
                    if (hole == null) continue;
                    found++;
                    String blockDetail = climbBlockDetail(mc, hole);
                    double dist = Math.sqrt(hole.middle().distanceToSqr(mc.player.position()));
                    mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§7[HoleSnap] candidate: " + hole.type() + " @" + hole.pos()
                            + " dist=" + String.format("%.1f", dist) + " climbBlocked=" + (blockDetail != null)
                            + (blockDetail != null ? " (" + blockDetail + ")" : "")));
                }
            }
        }
        if (found == 0) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7[HoleSnap] candidate dump: no Hole objects found in range at all"));
        }
    }

    /** True if the player's real bounding box would hit a solid block anywhere along the straight path to the hole (a lip/rim requiring a jump to cross, OR a drop that needs descending). */
    private boolean pathRequiresClimb(Minecraft mc, Hole hole) {
        Vec3 from = mc.player.position();
        Vec3 to = hole.middle();
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.05) return false;

        // The runtime movement is HORIZONTAL walking (onInput sets event.forward/left/
        // right, which drive normal ground movement; the only vertical motion is an
        // optional Jump). So the real approach is: walk horizontally until above the
        // hole's X/Z, THEN fall in -- NOT a straight diagonal line down to the hole
        // floor. Earlier versions of this check swept a 3D diagonal (interpolating Y
        // down to hole.middle.y); that diagonal threads through gaps a horizontal walk
        // can't (e.g. angling down through a shaft opening past a wall), so a hole whose
        // horizontal approach is walled off read as "clear" and kept winning on raw
        // distance -- exactly the repeated "đâm đầu vào hole 1 dù liên tục bị collide"
        // report (2026-07-17), where onInput's own collide check (a pure-horizontal
        // 0.05 nudge) fired every tick but this predictor disagreed. Sweeping the
        // player's real bounding box HORIZONTALLY at the current feet height, along the
        // whole path to the hole's X/Z, is the same motion onInput actually performs,
        // so the two can no longer disagree: if walking toward this hole hits a wall,
        // it gets CLIMB_PENALTY and a clear farther hole wins.
        int steps = Math.max(2, (int) Math.ceil(horiz * 2));
        // NOTE (2026-07-17): a -0.1 HORIZONTAL shrink was tried here as an experiment
        // (theory: full-width AABB clipping a nearby wall corner unrelated to the hole
        // itself) and made ZERO difference on a real repro (identical dist/climbBlocked
        // values before and after) -- reverted. climbBlockDetail()'s blockingPos then
        // showed the real culprit: consistently at playerY+1 (e.g. player at y=45,
        // blocking block at y=46) -- a low CEILING right above the player's head, not a
        // wall or the hole's own rim. Real vanilla movement tolerates lightly grazing a
        // low ceiling (a 2-tall gap only leaves ~0.2 clearance above a 1.8-tall player);
        // trimming the TOP of the swept box by a small margin mirrors that tolerance
        // without touching the bottom/sides, so a genuine wall or hole-rim obstruction
        // (at floor/eye height) is still caught exactly as before.
        AABB full = mc.player.getBoundingBox();
        AABB box = new AABB(full.minX, full.minY, full.minZ, full.maxX, full.maxY - 0.1, full.maxZ);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            AABB swept = box.move(dx * t, 0, dz * t);
            if (collides(mc, swept)) return true;
        }
        return false;
    }

    /** Diagnostic-only: like pathRequiresClimb, but describes the FIRST blocking step (fraction + world position) instead of just true/false. Null if clear. */
    private String climbBlockDetail(Minecraft mc, Hole hole) {
        Vec3 from = mc.player.position();
        Vec3 to = hole.middle();
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 0.05) return null;
        int steps = Math.max(2, (int) Math.ceil(horiz * 2));
        AABB full = mc.player.getBoundingBox();
        AABB box = new AABB(full.minX, full.minY, full.minZ, full.maxX, full.maxY - 0.1, full.maxZ);
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            AABB swept = box.move(dx * t, 0, dz * t);
            if (collides(mc, swept)) {
                return "step " + i + "/" + steps + " t=" + String.format("%.2f", t)
                    + " sweptCenter=(" + String.format("%.2f,%.2f,%.2f", swept.getCenter().x, swept.getCenter().y, swept.getCenter().z) + ")"
                    + " playerY=" + String.format("%.2f", from.y) + " holeY=" + to.y
                    + " blockingPos=" + findBlockingPos(mc, swept);
            }
        }
        return null;
    }

    private Hole getHole(Minecraft mc, BlockPos pos, int depth) {
        if (!isHole(mc, pos, depth) || !isBlock(mc, pos.west()) || !isBlock(mc, pos.north())) return null;

        boolean x = isHole(mc, pos.east(), depth) && isBlock(mc, pos.east().north()) && isBlock(mc, pos.east(2));
        boolean z = isHole(mc, pos.south(), depth) && isBlock(mc, pos.south().west()) && isBlock(mc, pos.south(2));

        if (!x && !z && isBlock(mc, pos.east()) && isBlock(mc, pos.south())) {
            return Hole.of(pos, HoleType.Single);
        }
        if (x && z && isHole(mc, pos.south().east(), depth)
                && isBlock(mc, pos.east().east().south()) && isBlock(mc, pos.south().south().east())) {
            return Hole.of(pos, HoleType.Quad);
        }
        if (x && !z && isBlock(mc, pos.south()) && isBlock(mc, pos.south().east())) {
            return Hole.of(pos, HoleType.DoubleX);
        }
        if (z && !x && isBlock(mc, pos.east()) && isBlock(mc, pos.south().east())) {
            return Hole.of(pos, HoleType.DoubleZ);
        }
        return null;
    }

    private boolean isHole(Minecraft mc, BlockPos pos, int depth) {
        if (!isBlock(mc, pos.below())) return false;
        for (int i = 0; i < depth; i++) {
            if (isBlock(mc, pos.above(i))) return false;
        }
        return true;
    }

    private boolean isBlock(Minecraft mc, BlockPos pos) {
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    private boolean collides(Minecraft mc, AABB box) {
        Level level = mc.level;
        return level.getBlockCollisions(mc.player, box).iterator().hasNext();
    }

    /** Diagnostic-only: scans every block cell overlapping {@code box} and returns the first one with a non-empty collision shape (the actual offending block, not just the swept AABB's center). */
    private String findBlockingPos(Minecraft mc, AABB box) {
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!mc.level.getBlockState(p).getCollisionShape(mc.level, p).isEmpty()) {
                        return p + "(" + mc.level.getBlockState(p).getBlock() + ")";
                    }
                }
            }
        }
        return "none found (entity-only collision?)";
    }
}
