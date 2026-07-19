package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ModeOption;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * FastWeb — cobweb-traversal bypass. Vanilla cobwebs clamp movement to a crawl by scaling
 * velocity every tick; this drives the player's position directly instead, so the web's
 * clamp never gets a real velocity to act on.
 */
public class FastWeb extends AddonModule {
    public static final FastWeb INSTANCE = new FastWeb();

    public enum Profile { Normal, SixB6TSlow, NCP }

    public final ModeOption<Profile> profile = new ModeOption<>(this, "Profile",
        "SixB6TSlow keeps the original 6b6t-tuned step; NCP stays under NoCheatPlus's real "
        + "per-tick web speed cap (see NCP_SAFE_SPEED); Normal uses the Speed slider freely.",
        Profile.Normal);

    // Read from NoCheatPlus's actual source (2026-07-18, github.com/NoCheatPlus/NoCheatPlus):
    // unlike Grim, SurvivalFly's cobweb check is a flat per-tick THRESHOLD, not a re-simulated
    // physics comparison -- checks/moving/player/SurvivalFly.java:867:
    //   hAllowedDistance = Magic.modWeb * thisMove.walkSpeed * cc.survivalFlyWalkingSpeed / 100D;
    // checks/moving/magic/Magic.java:59: modWeb = 0.105D / WALK_SPEED (WALK_SPEED = 0.221D, :48).
    // With default walkSpeed=0.2 and the default 100% config: hAllowedDistance ~= 0.105/0.221 *
    // 0.2 ~= 0.0950 blocks/tick. A horizontal delta under that never trips SurvivalFly's
    // hDistanceAboveLimit at all -- this genuinely IS a real, per-tick threshold an addon can
    // stay under, unlike Grim's full re-simulation (see collisionMove's comment). 0.08 leaves
    // ~15% margin for a server running a stricter survivalFlyWalkingSpeed than 100%.
    private static final double NCP_SAFE_SPEED = 0.08;

    public final SliderOption speed = new SliderOption(this, "Speed",
        "Blocks per tick to move through webs.", 0.2, 0.02, 0.5, 0.01);

    public final ToggleOption verticalControl = new ToggleOption(this, "VerticalControl",
        "Jump ascends, sneak descends, while inside a web.", true);

    public final ToggleOption collisionMove = new ToggleOption(this, "CollisionMove",
        "Use Entity.move(SELF, ...) instead of a raw teleport, so walls/floors inside irregular "
        + "web tunnels stop movement properly instead of clipping through them.", true);

    // NOT an anti-cheat bypass (2026-07-18, verified against GrimAC's real source --
    // predictionengine/predictions/PredictionEngineNormal.java + movementtick/
    // MovementTickerPlayer.java): Grim re-simulates real vanilla physics server-side EVERY
    // tick, including cobweb's own velocity multiplier, and flags any reported position
    // that deviates from ITS OWN computed expected position beyond a small tolerance
    // (UncertaintyHandler). It doesn't inspect packet shape or "legitimacy" -- it recomputes
    // the correct number itself, so no amount of nicer-looking packets defeats a dedicated
    // block-speed check like that. What this toggle actually does: (1) ease the vertical
    // step in/out over a few ticks instead of an instant constant velocity (avoids the most
    // visually/statistically obvious "no gravity curve" tell for GENERIC motion-sanity
    // checks, not Grim's cobweb-specific one), (2) keep Speed within roughly vanilla-sprint
    // bounds by default so it doesn't also trip a separate flat max-speed check. Framed
    // honestly as smoothing, not "AC bypass".
    public final ToggleOption smoothMotion = new ToggleOption(this, "SmoothMotion",
        "Ease vertical movement in/out over a few ticks instead of an instant step -- reduces "
        + "how obviously non-physical the motion looks, but does NOT defeat a physics-simulating "
        + "anti-cheat's own cobweb-speed check (see class comment).", true);

    private boolean forward, backward, left, right, jumping, sneaking;
    private int vertTicks = 0;

    private FastWeb() {
        super("FastWeb", "Move through cobwebs at a configurable speed instead of vanilla's crawl.");
    }

    @EventHandler
    private void onInput(EventInput event) {
        forward = event.forward;
        backward = event.backward;
        left = event.left;
        right = event.right;
        jumping = event.jumping;
        sneaking = event.sneaking;
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!inWeb(mc)) return;

        double step = switch (profile.getValue()) {
            case SixB6TSlow -> 0.04;
            case NCP -> NCP_SAFE_SPEED;
            case Normal -> speed.getValue();
        };

        // Cancels the web's own slowdown-velocity clamp -- only while not in hurt-knockback,
        // matching the reference (avoids fighting real knockback while cobweb-bypassing).
        if (mc.player.hurtTime == 0) mc.player.setDeltaMovement(0, 0, 0);

        // Combine WASD into one horizontal vector (diagonal input works, unlike an if/else
        // chain that only ever acts on one key per tick) and rotate it by yaw.
        double moveX = 0, moveZ = 0;
        if (forward) moveZ += 1;
        if (backward) moveZ -= 1;
        if (right) moveX += 1;
        if (left) moveX -= 1;

        double vx = 0, vz = 0;
        if (moveX != 0 || moveZ != 0) {
            double len = Math.sqrt(moveX * moveX + moveZ * moveZ);
            moveX /= len;
            moveZ /= len;
            double yawRad = Math.toRadians(mc.player.getYRot());
            double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
            // Forward (+moveZ) faces -sin/cos; strafe (+moveX) faces cos/sin -- standard
            // yaw-to-horizontal-direction rotation.
            vx = (moveZ * -sin + moveX * cos) * step;
            vz = (moveZ * cos + moveX * sin) * step;
        }

        double vy = 0;
        boolean vertHeld = verticalControl.getValue() && (jumping || sneaking);
        if (vertHeld) {
            vertTicks++;
            double vertStep = step;
            if (smoothMotion.getValue()) {
                // Ramp 0 -> step over VERT_EASE_TICKS instead of an instant constant velocity.
                vertStep *= Math.min(1.0, vertTicks / (double) VERT_EASE_TICKS);
            }
            vy = jumping ? vertStep : -vertStep;
        } else {
            vertTicks = 0;
        }

        if (vx != 0 || vy != 0 || vz != 0) {
            if (collisionMove.getValue()) {
                mc.player.move(MoverType.SELF, new Vec3(vx, vy, vz));
            } else {
                mc.player.setPos(mc.player.getX() + vx, mc.player.getY() + vy, mc.player.getZ() + vz);
            }
        }
    }

    private static final int VERT_EASE_TICKS = 5;

    private boolean inWeb(Minecraft mc) {
        AABB box = mc.player.getBoundingBox();
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (mc.level.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.COBWEB) return true;
                }
            }
        }
        return false;
    }
}
