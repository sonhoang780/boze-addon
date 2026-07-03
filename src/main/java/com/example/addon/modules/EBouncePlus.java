package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import net.minecraft.network.chat.Component;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
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

    public final SliderOption glidePitch = new SliderOption(this, "Pitch",
        "", 90, 0.0, 90.0, 1.0);

    public final SliderOption groundThreshold = new SliderOption(this, "GroundThreshold",
        "Floor distance (blocks) to trigger recast. Reference triggers at alt ~0.01-0.15. Increase if recast misfires mid-air.", 0.15, 0.02, 1.0, 0.01);

    public final SliderOption recastVY = new SliderOption(this, "RecastVY",
        "Max vy (b/t) to allow recast. Reference triggers at vy ≈ 0. Positive = recast while still rising slightly.", 0.08, 0.0, 0.5, 0.01);

    public final ToggleOption debug = new ToggleOption(this, "Debug",
        "Print chat lines on every recast trigger, jump-hold fire, and unexpected glide-stop so the exact branch causing weird behaviour can be pinned down.", false);

    public static volatile boolean pitchOverrideActive = false;
    public static volatile float   savedCameraPitch    = 0f;

    // -1 = no recast pending; 1 = StatusOnly sent, waiting to send START_FALL_FLYING
    private int     recastPhase         = 0;
    private boolean pendingFold         = false;
    private boolean doJump              = false;
    // Initial-takeoff pacing: 0 = may send START_FALL_FLYING now; >0 = ticks left to
    // wait for the glide flag echo before retrying. Bounded retry (every 5 ticks) --
    // the meteor-style unconditional per-tick resend broke takeoff on this server,
    // reverted back to the gated version.
    private int takeoffCooldown = 0;
    // Purely for --Debug logging (edge detection on isFallFlying); never read by
    // behavior logic, so it can't reintroduce the earlier grace-period regression.
    private boolean wasFlyingForLog = false;

    public EBouncePlus() {
        super("EBounce+", "Infinite Durability for recast, Fuck your boze client.");
    }

    @Override
    public void onEnable() {
        recastPhase = 0;
        pendingFold = false;
        doJump      = false;
        takeoffCooldown = 0;
        wasFlyingForLog = false;
        pitchOverrideActive = false;
        // No one-shot jump here anymore -- onTickPre now holds jump continuously every
        // tick the player is genuinely on the ground (see below), which covers both the
        // enable-while-grounded case AND any later real landing during the session.
    }

    @Override
    public void onDisable() {
        recastPhase = 0;
        pendingFold = false;
        doJump      = false;
        takeoffCooldown = 0;
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
            if (debug.getValue()) {
                debugLog(mc, "RECAST resolve: START_FALL_FLYING resent (onGround="
                    + mc.player.onGround() + " speed=" + horizontalSpeed(mc) + ")");
            }
        }

        // Phase 0 + fold trigger: send StatusOnly(true) now, jump next tick.
        if (pendingFold) {
            mc.getConnection().send(
                new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
            doJump      = true; // EventInput next tick will jump
            recastPhase = 1;    // EventTick.Post next tick sends START_FALL_FLYING
            pendingFold = false;
        }

        // Initial takeoff: airborne but not gliding and no recast dance in flight →
        // ask the server to start fall flying. Post runs after this tick's movement
        // packet, so the server already sees onGround=false and accepts the command.
        // Retry every 5 ticks until the glide flag echoes back (covers a lost/early
        // send under latency) — bounded, not per-tick spam. The meteor-client style
        // unconditional per-tick resend (no cooldown) broke takeoff on this server;
        // reverted back to this gated version.
        if (!mc.player.isFallFlying() && !mc.player.onGround()
                && recastPhase == 0 && !pendingFold
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) {
            if (takeoffCooldown == 0) {
                mc.getConnection().send(new ServerboundPlayerCommandPacket(
                    mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
                takeoffCooldown = 5;
            } else {
                takeoffCooldown--;
            }
        } else if (mc.player.onGround()) {
            takeoffCooldown = 0;
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

        // Genuinely standing on the ground with NO recast cycle in flight and NOT
        // currently gliding: hold jump every tick, same as EBounce.java's JUMPING phase.
        // Without this, a real landing (isFallFlying already cleared by the server) had
        // nothing to trigger a fresh jump. Gated tightly on !isFallFlying/!recastPhase/
        // !pendingFold: bounce height is only ~0.34 block, so the real client onGround
        // flag routinely flickers true for a tick in the MIDDLE of an otherwise-healthy
        // recast cycle (client physics briefly touches the floor even while the fake
        // StatusOnly dance is still managing the server side). Without this guard the
        // real jump fired here fights the recast state machine's own jump and permanently
        // knocks the module out of gliding after the first bounce.
        if (mc.player.onGround() && !mc.player.isFallFlying()
                && recastPhase == 0 && !pendingFold) {
            doJump = true;
            if (debug.getValue()) {
                debugLog(mc, "GROUND-JUMP-HOLD fired (onGround, not flying, idle state)");
            }
        }

        // Debug only: flag any tick where gliding stops WITHOUT us having initiated a
        // recast (recastPhase==0, no pendingFold) -- this is exactly the "đứng dậy" event
        // reported: something OTHER than our own fold/strike sequence is clearing
        // isFallFlying (a real hard landing on terrain, a server-side rubber-band, or a
        // rejection we're not accounting for).
        if (debug.getValue()) {
            boolean isFlyingNow = mc.player.isFallFlying();
            if (wasFlyingForLog && !isFlyingNow && recastPhase == 0 && !pendingFold) {
                debugLog(mc, "UNEXPECTED STOP: isFallFlying true->false with no recast in flight"
                    + " (onGround=" + mc.player.onGround()
                    + " hCollision=" + mc.player.horizontalCollision
                    + " floor=" + floorClear(mc)
                    + " vy=" + mc.player.getDeltaMovement().y
                    + " speed=" + horizontalSpeed(mc) + ")");
            }
            wasFlyingForLog = isFlyingNow;
        }

        // Not yet gliding: nothing else to do in Pre. The initial-takeoff
        // START_FALL_FLYING is sent from EventTick.Post — it MUST go out AFTER the
        // tick's vanilla movement packet, otherwise the server still holds
        // onGround=true from the previous move packet and silently rejects the
        // command (canGlide needs !onGround). Sending from Pre was why takeoff
        // never caught: the one gated send always arrived too early and died.
        if (!mc.player.isFallFlying()) {
            return;
        }
        takeoffCooldown = 0;

        // Don't queue another recast while one is already in flight.
        if (recastPhase != 0 || pendingFold) return;

        // Recast condition: near floor AND vy is small (approaching ground level).
        double floor = floorClear(mc);
        double vy    = mc.player.getDeltaMovement().y;

        boolean nearGround = floor >= 0 && floor < groundThreshold.getValue();
        boolean vyOk       = vy <= recastVY.getValue();

        // horizontalCollision only counts as a recast trigger near ground — otherwise
        // clipping a block edge mid-dive (far from floor) falsely re-triggers recast
        // while still accelerating.
        if (nearGround && (vyOk || mc.player.horizontalCollision)) {
            pendingFold = true;
            if (debug.getValue()) {
                debugLog(mc, "RECAST TRIGGER (floor=" + floor + " vy=" + vy
                    + " vyOk=" + vyOk + " hCollision=" + mc.player.horizontalCollision
                    + " speed=" + horizontalSpeed(mc) + ")");
            }
        }
    }

    private void debugLog(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[EBounce+] " + msg));
        }
    }

    private static double horizontalSpeed(Minecraft mc) {
        var v = mc.player.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z) * 20.0; // b/t -> b/s
    }

    private void reset() {
        recastPhase = 0;
        pendingFold = false;
        doJump      = false;
        takeoffCooldown = 0;
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
