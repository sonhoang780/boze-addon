package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla Ebounce — elytra bhop bằng cơ chế vanilla:
 *   1. Nhảy khỏi mặt đất.
 *   2. Sau vài tick trên không → gửi START_FALL_FLYING để kích elytra.
 *   3. Lướt về phía đất.
 *   4. Chạm đất → nhảy ngay lập tức → quay lại bước 2.
 *
 * Pitch gửi qua Rot packet trong EventTick.Post (sau vanilla movement packet) →
 * entity.xRot / xRotO không bị chạm → camera không bao giờ thấy pitch override.
 */
public class EBounce extends AddonModule {
    public static final EBounce INSTANCE = new EBounce();

    public final SliderOption glideDelay = new SliderOption(this, "GlideDelay",
        "Ticks to wait in the air before triggering elytra glide.", 0.0, 0.0, 2.0, 0.1);

    public final SliderOption risePitch = new SliderOption(this, "RisePitch",
        "Pitch while ascending with room above. Flat (≈0°) climbs highest → deepest dive → most speed. CeilingGuard cuts climb before ceiling.", 5.0, -30.0, 90.0, 1.0);

    public final ToggleOption ceilingGuard = new ToggleOption(this, "CeilingGuard",
        "Raycast upward and bail into a dive when ceiling is within CeilingMargin. Fills vertical room without smashing the roof.", true);

    public final SliderOption ceilingMargin = new SliderOption(this, "CeilingMargin",
        "Head clearance (blocks) at which rise is forced into a dive. Keep SMALL — larger than tunnel headroom and guard fires permanently (stuck stamping).", 0.5, 0.1, 4.0, 0.1);

    public final ToggleOption dynamicDive = new ToggleOption(this, "DynamicDive",
        "Vary pitch through dive: steep at apex to build |vy| fast, flatten as |vy| grows for max conversion burst at cos²θ≈1.", true);

    public final SliderOption diveApexPitch = new SliderOption(this, "DiveApexPitch",
        "Dive pitch when |vy| is small (just started falling). Steep = builds vy quickly. Dynamic dive only.", 65.0, 0.0, 90.0, 1.0);

    public final SliderOption diveFastPitch = new SliderOption(this, "DiveFastPitch",
        "Dive pitch when |vy| is large. Flat (high cos²θ) = max conversion. Dynamic dive only.", 14.0, 0.0, 90.0, 1.0);

    public final SliderOption diveVyFull = new SliderOption(this, "DiveVyFull",
        "|vy| (blocks/tick) at which dive pitch reaches DiveFastPitch. Lower = flattens sooner. Dynamic dive only.", 1.1, 0.3, 2.5, 0.1);

    public final SliderOption glidePitch = new SliderOption(this, "GlidePitch",
        "Fixed dive pitch used when DynamicDive is OFF. Empirical sweet spot ≈ 25-45°.", 30.0, 0.0, 90.0, 1.0);

    private enum Phase { IDLE, JUMPING, TRIGGERING, GLIDING }

    // Camera-safe pitch override: setXRot in Pre so player.tick() sends it in movement packet;
    // MixinEntity intercepts getXRot(F) to return savedCameraPitch so render/camera unaffected.
    public static volatile boolean pitchOverrideActive = false;
    public static volatile float   savedCameraPitch    = 0f;

    private Phase phase    = Phase.IDLE;
    private int   airTicks = 0;

    private double ceilClear  = -1;
    private double floorClear = -1;

    private static final double LAUNCH_CLEARANCE = 1.0;

    public EBounce() {
        super("EBounce", "Vanilla elytra bounce: auto-jump on landing and re-trigger elytra glide.");
    }

    @Override public void onEnable()  { reset(); }
    @Override public void onDisable() { reset(); }

    private void reset() {
        phase    = Phase.IDLE;
        airTicks = 0;
        if (pitchOverrideActive) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.setXRot(savedCameraPitch);
            pitchOverrideActive = false;
        }
    }

    @EventHandler
    private void onInput(EventInput event) {
        if (phase == Phase.JUMPING) event.jumping = true;
    }

    @EventHandler
    private void onTickPost(EventTick.Post event) {
        if (pitchOverrideActive) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.setXRot(savedCameraPitch);
            pitchOverrideActive = false;
        }
    }

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            reset(); return;
        }

        if (ceilingGuard.getValue()) {
            ceilClear  = footprintClearance(mc, true);
            floorClear = footprintClearance(mc, false);
        } else {
            ceilClear = floorClear = -1;
        }

        // Set pitch override BEFORE state machine so player.tick() (runs after Pre)
        // sends the movement packet with override pitch. MixinEntity hides the override
        // from camera via getXRot(F) interception. Restored in Post.
        if (phase == Phase.GLIDING || phase == Phase.TRIGGERING) {
            savedCameraPitch = mc.player.getXRot();
            mc.player.setXRot(computePitch(mc.player.getDeltaMovement().y));
            pitchOverrideActive = true;
        }

        boolean onGround = mc.player.onGround();
        boolean gliding  = mc.player.isFallFlying();

        switch (phase) {
            case IDLE -> {
                if (onGround) { phase = Phase.JUMPING; airTicks = 0; }
            }
            case JUMPING -> {
                if (!onGround) {
                    airTicks++;
                    if (airTicks >= (int)(double) glideDelay.getValue()) {
                        triggerGlide(mc);
                        phase = Phase.TRIGGERING; airTicks = 0;
                    }
                }
            }
            case TRIGGERING -> {
                airTicks++;
                if (gliding)            { phase = Phase.GLIDING;  airTicks = 0; }
                else if (onGround)      { phase = Phase.JUMPING;  airTicks = 0; }
                else if (airTicks >= 4) { phase = Phase.GLIDING;  airTicks = 0; }
            }
            case GLIDING -> {
                if (onGround) {
                    phase = Phase.JUMPING; airTicks = 0;
                } else if (!gliding) {
                    airTicks++;
                    if (airTicks >= 2) { triggerGlide(mc); phase = Phase.TRIGGERING; airTicks = 0; }
                } else {
                    airTicks = 0;
                }
            }
        }
    }

    private void triggerGlide(Minecraft mc) {
        if (mc.getConnection() == null) return;
        float pitch = computePitch(mc.player.getDeltaMovement().y);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
            mc.player.getYRot(), pitch, mc.player.onGround(), mc.player.horizontalCollision));
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }

    private float computePitch(double vy) {
        boolean nearFloor   = floorClear >= 0 && floorClear < LAUNCH_CLEARANCE;
        boolean ceilingNear = ceilClear  >= 0 && ceilClear <= ceilingMargin.getValue();

        if (vy >= 0) {
            if (ceilingNear && !nearFloor) {
                // fall through to dive branch
            } else {
                return (float)(double) risePitch.getValue();
            }
        }

        if (!dynamicDive.getValue()) return (float)(double) glidePitch.getValue();

        double apex = diveApexPitch.getValue();
        double fast = diveFastPitch.getValue();
        double full = Math.max(0.05, diveVyFull.getValue());
        double t    = Math.min(1.0, -vy / full);
        return (float)(apex + (fast - apex) * t);
    }

    private double footprintClearance(Minecraft mc, boolean up) {
        var box = mc.player.getBoundingBox();
        final double scan = 12.0;
        double baseY = up ? box.maxY : box.minY;
        double[][] pts = {
            { mc.player.getX(), mc.player.getZ() },
            { box.minX, box.minZ }, { box.minX, box.maxZ },
            { box.maxX, box.minZ }, { box.maxX, box.maxZ },
        };
        double best = -1;
        for (double[] pt : pts) {
            Vec3 from = new Vec3(pt[0], baseY, pt[1]);
            Vec3 to   = new Vec3(pt[0], baseY + (up ? scan : -scan), pt[1]);
            HitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) continue;
            double c = Math.abs(hit.getLocation().y - baseY);
            if (best < 0 || c < best) best = c;
        }
        return best;
    }
}
