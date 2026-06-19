package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

/**
 * Vanilla Ebounce — elytra bhop bằng cơ chế vanilla:
 *   1. Nhảy khỏi mặt đất.
 *   2. Sau vài tick trên không → gửi START_FALL_FLYING để kích elytra.
 *   3. Lướt về phía đất.
 *   4. Chạm đất → nhảy ngay lập tức → quay lại bước 2.
 *
 * Event order trong 1 tick: EventTick.Pre → EventInput → physics
 * Vì vậy khi EventTick.Pre phát hiện onGround=true và chuyển phase → JUMPING,
 * EventInput cùng tick đó sẽ set jumping=true → player nhảy ngay trong tick đó.
 */
public class EBounce extends AddonModule {
    public static final EBounce INSTANCE = new EBounce();

    public final SliderOption glideDelay = new SliderOption(this, "Glide Delay",
        "Ticks to wait in the air before triggering elytra glide.", 1.0, 1.0, 5.0, 1.0);

    public final SliderOption glidePitch = new SliderOption(this, "Glide Pitch",
        "Server-side pitch sent silently when triggering glide. Positive = looking down. ~20° maximises forward speed.", 20.0, -45.0, 45.0, 1.0);

    private enum Phase { IDLE, JUMPING, TRIGGERING, GLIDING }

    private Phase phase   = Phase.IDLE;
    private int   airTicks = 0;

    // Silent pitch state: set in EventTick.Pre → player.tick() sends our pitch in movement packet
    // → restored in EventTick.Post → render() always sees real camera pitch. Never visible.
    private float   savedPitch      = 0f;
    private boolean pitchOverridden = false;

    public EBounce() {
        super("EBounce", "Vanilla elytra bounce: auto-jump on landing and re-trigger elytra glide.");
    }

    @Override public void onEnable()  { reset(); }
    @Override public void onDisable() { reset(); }

    private void reset() {
        phase    = Phase.IDLE;
        airTicks = 0;
        restorePitch();
    }

    private void restorePitch() {
        if (pitchOverridden) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) mc.player.setPitch(savedPitch);
            pitchOverridden = false;
        }
    }

    // ── Set jumping=true while in JUMPING phase ───────────────────────────────

    @EventHandler
    private void onInput(EventInput event) {
        if (phase == Phase.JUMPING) event.jumping = true;
    }

    // ── Main state machine ────────────────────────────────────────────────────

    // Restore pitch after player.tick() has sent the movement packet with our override.
    @EventHandler
    private void onTickPost(EventTick.Post event) {
        restorePitch();
    }

    @EventHandler
    private void onTickPre(EventTick.Pre event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        // Elytra must be equipped.
        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            reset();
            return;
        }

        // Override pitch silently for all active phases.
        // player.tick() fires AFTER EventTick.Pre and sends the movement packet — it will
        // pick up our overridden pitch. EventTick.Post then restores the real camera pitch
        // before render() is called, so the camera never visually snaps.
        if (phase != Phase.IDLE) {
            savedPitch = mc.player.getPitch();
            mc.player.setPitch((float)(double) glidePitch.getValue());
            pitchOverridden = true;
        }

        boolean onGround = mc.player.isOnGround();
        boolean gliding  = mc.player.isGliding();

        switch (phase) {

            case IDLE -> {
                // Wait until the player is on the ground to begin the cycle.
                if (onGround) {
                    phase    = Phase.JUMPING;
                    airTicks = 0;
                }
            }

            case JUMPING -> {
                // EventInput (fires after this) will set jumping=true.
                // Wait until the player actually leaves the ground.
                if (!onGround) {
                    airTicks++;
                    if (airTicks >= (int)(double) glideDelay.getValue()) {
                        triggerGlide(mc);
                        phase    = Phase.TRIGGERING;
                        airTicks = 0;
                    }
                }
                // If still on ground, keep setting jumping via EventInput until we lift off.
            }

            case TRIGGERING -> {
                // Wait for the server to confirm the glide flag (1-2 ticks on LAN,
                // up to ~1 RTT on multiplayer).
                airTicks++;
                if (gliding) {
                    phase    = Phase.GLIDING;
                    airTicks = 0;
                } else if (onGround) {
                    // START_FALL_FLYING was rejected (already landed) — jump again.
                    phase    = Phase.JUMPING;
                    airTicks = 0;
                } else if (airTicks >= 4) {
                    // Server is slow or packet lost; assume gliding and move on.
                    phase    = Phase.GLIDING;
                    airTicks = 0;
                }
            }

            case GLIDING -> {
                if (onGround) {
                    // Landed — bounce immediately this tick via EventInput.
                    phase    = Phase.JUMPING;
                    airTicks = 0;
                } else if (!gliding) {
                    // Glide ended mid-air (wall, water, damage...) — re-trigger.
                    airTicks++;
                    if (airTicks >= 2) {
                        triggerGlide(mc);
                        phase    = Phase.TRIGGERING;
                        airTicks = 0;
                    }
                } else {
                    airTicks = 0;
                }
            }
        }
    }

    /**
     * Sends START_FALL_FLYING to the server with an optimal silent pitch.
     *
     * The pitch is sent via PlayerMoveC2SPacket.LookAndOnGround so the server
     * sees the desired angle WITHOUT changing the client camera.
     * The server only checks !isOnGround() for START_FALL_FLYING — no velocity
     * requirement — so we must NOT override Y velocity here (doing so caused
     * ≈30 km/h instead of ≈50 km/h and a spam loop).
     */
    private void triggerGlide(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null) return;
        float yaw   = mc.player.getYaw();
        float pitch = (float)(double) glidePitch.getValue();
        mc.getNetworkHandler().sendPacket(
            new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
    }
}
