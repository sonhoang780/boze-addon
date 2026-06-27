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
 * EBounce+ — "Ground Build" recast mode.
 *
 * Cơ chế: thay vì đợi chạm đất thật, mỗi ~6 tick khi vy ≈ 0 và sát sàn thì
 * gửi fake StatusOnly(onGround=true) → server dừng elytra khi chạy aiStep →
 * tick sau jump thật + START_FALL_FLYING → sprint-jump boost +0.2 b/t horizontal.
 * Không có landing friction nên speed tích lũy đến ~84 b/s ngoài trời.
 *
 * Pitch cố định 72.4° suốt chu kỳ (kể cả khi bay lên) → chu kỳ ngắn (~6 tick),
 * bounce cao ~0.34 block, không cần ceiling guard.
 */
public class EBouncePlus extends AddonModule {
    public static final EBouncePlus INSTANCE = new EBouncePlus();

    public final SliderOption glidePitch = new SliderOption(this, "GlidePitch",
        "Fixed pitch throughout the cycle. Reference: 72.4° (nearly vertical dive, fast cycle). Lower = slower build, higher altitude.", 72.4, 0.0, 90.0, 0.1);

    public final SliderOption groundThreshold = new SliderOption(this, "GroundThreshold",
        "Floor distance (blocks) to trigger recast. Reference triggers at alt ~0.01-0.15. Increase if recast misfires mid-air.", 0.15, 0.02, 1.0, 0.01);

    public final SliderOption recastVY = new SliderOption(this, "RecastVY",
        "Max vy (b/t) to allow recast. Reference triggers at vy ≈ 0. Positive = recast while still rising slightly.", 0.08, 0.0, 0.5, 0.01);

    public static volatile boolean pitchOverrideActive = false;
    public static volatile float   savedCameraPitch    = 0f;

    // -1 = no recast pending; 1 = StatusOnly sent, waiting to send START_FALL_FLYING
    private int     recastPhase = 0;
    private boolean pendingFold = false;
    private boolean doJump      = false;

    public EBouncePlus() {
        super("EBounce+", "Recast ground-build: fake StatusOnly → sprint-jump → START_FALL_FLYING every ~6 ticks. 80+ b/s open air.");
    }

    @Override
    public void onEnable() {
        recastPhase = 0;
        pendingFold = false;
        doJump      = false;
        pitchOverrideActive = false;
        // Auto-start: if on ground with elytra equipped, trigger recast immediately
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null
                && mc.player.onGround()
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            pendingFold = true;
        }
    }

    @Override
    public void onDisable() {
        recastPhase = 0;
        pendingFold = false;
        doJump      = false;
        if (pitchOverrideActive) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.setXRot(savedCameraPitch);
            pitchOverrideActive = false;
        }
    }

    // ── EventInput: apply jump + AssistW ────────────────────────────────────

    @EventHandler
    private void onInput(EventInput event) {
        if (doJump) {
            event.jumping = true;
            doJump = false;
        }

    }

    // ── EventTick.Post: send packets AFTER vanilla movement packet ───────────

    @EventHandler
    private void onTickPost(EventTick.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        // Phase 1: StatusOnly sent last tick; this tick player.tick() jumped →
        //          onGround=false → server will accept START_FALL_FLYING.
        if (recastPhase == 1) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(
                mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            recastPhase = 0;
        }

        // Phase 0 + fold trigger: send StatusOnly(true) now, jump next tick.
        if (pendingFold) {
            mc.getConnection().send(
                new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
            doJump      = true; // EventInput next tick will jump
            recastPhase = 1;    // EventTick.Post next tick sends START_FALL_FLYING
            pendingFold = false;
        }

        // Restore real camera pitch (was set to override in Pre for physics packet).
        if (pitchOverrideActive) {
            mc.player.setXRot(savedCameraPitch);
            pitchOverrideActive = false;
        }
    }

    // ── EventTick.Pre: pitch override + recast detection ────────────────────

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() != Items.ELYTRA) {
            reset(); return;
        }
        // Maintain sprint so every jump is a sprint-jump (+0.2 b/t boost)
        mc.player.setSprinting(true);

        // Set pitch override so player.tick() sends it in movement packet.
        // MixinEntity intercepts getXRot(F) → camera unaffected.
        if (mc.player.isFallFlying()) {
            savedCameraPitch = mc.player.getXRot();
            mc.player.setXRot((float)(double) glidePitch.getValue());
            pitchOverrideActive = true;
        }

        // If not yet gliding, trigger elytra once we're airborne.
        if (!mc.player.isFallFlying()) {
            if (!mc.player.onGround()) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
            return;
        }

        // Don't queue another recast while one is already in flight.
        if (recastPhase != 0 || pendingFold) return;

        // Recast condition: near floor AND vy is small (approaching ground level).
        double floor = floorClear(mc);
        double vy    = mc.player.getDeltaMovement().y;

        boolean nearGround = floor >= 0 && floor < groundThreshold.getValue();
        boolean vyOk       = vy <= recastVY.getValue();

        if ((nearGround && vyOk) || mc.player.horizontalCollision) {
            pendingFold = true;
        }
    }

    private void reset() {
        recastPhase = 0;
        pendingFold = false;
        doJump      = false;
    }

    // 5-ray footprint downward scan (same as EBounce, returns distance to floor)
    private double floorClear(Minecraft mc) {
        var box = mc.player.getBoundingBox();
        final double scan = 8.0;
        double baseY = box.minY;
        double[][] pts = {
            { mc.player.getX(),  mc.player.getZ()  },
            { box.minX, box.minZ }, { box.minX, box.maxZ },
            { box.maxX, box.minZ }, { box.maxX, box.maxZ },
        };
        double best = -1;
        for (double[] pt : pts) {
            Vec3 from = new Vec3(pt[0], baseY,        pt[1]);
            Vec3 to   = new Vec3(pt[0], baseY - scan, pt[1]);
            HitResult hit = mc.level.clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) continue;
            double c = Math.abs(hit.getLocation().y - baseY);
            if (best < 0 || c < best) best = c;
        }
        return best;
    }
}
