package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventInput;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * Vanilla EBounce — port thẳng từ reference EBounce (1.21.11) sang 26.1.2 Mojmap.
 * Cơ chế pitch: setXRot trong Pre → player.tick() gửi packet với pitch đó →
 * Post restore. MixinEntity intercept getXRot(F) → camera không bao giờ thấy override.
 */
public class VanillaEBounce extends AddonModule {
    public static final VanillaEBounce INSTANCE = new VanillaEBounce();

    public static volatile boolean pitchOverrideActive = false;
    public static volatile float   savedCameraPitch    = 0f;

    public final SliderOption glideDelay = new SliderOption(this, "GlideDelay",
        "Ticks to wait in the air before triggering elytra glide.", 0.0, 0.0, 5.0, 0.1);

    public final SliderOption risePitch = new SliderOption(this, "RisePitch",
        "Server-side pitch when ascending (vy >= 0). Flat ~0° = highest bounce = most speed.", 5.0, -30.0, 90.0, 1.0);

    public final SliderOption glidePitch = new SliderOption(this, "GlidePitch",
        "Server-side pitch when diving (vy < 0). ~20-30° maximises forward conversion.", 20.0, 0.0, 90.0, 1.0);

    private enum Phase { IDLE, JUMPING, TRIGGERING, GLIDING }

    private Phase phase    = Phase.IDLE;
    private int   airTicks = 0;

    public VanillaEBounce() {
        super("VanillaEBounce", "Simple elytra bounce: auto-jump on land, recast elytra. No ceiling guard, no dynamic dive.");
    }

    @Override
    public void onEnable() { reset(); }

    @Override
    public void onDisable() { reset(); }

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

        // Override pitch before player.tick() sends movement packet.
        // MixinEntity intercepts getXRot(F) so camera stays at real pitch.
        if (phase != Phase.IDLE) {
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
                if (gliding)            { phase = Phase.GLIDING;   airTicks = 0; }
                else if (onGround)      { phase = Phase.JUMPING;   airTicks = 0; }
                else if (airTicks >= 4) { phase = Phase.GLIDING;   airTicks = 0; }
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

    private float computePitch(double vy) {
        return vy >= 0
            ? (float)(double) risePitch.getValue()
            : (float)(double) glidePitch.getValue();
    }

    private void triggerGlide(Minecraft mc) {
        if (mc.getConnection() == null) return;
        float pitch = computePitch(mc.player.getDeltaMovement().y);
        mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
            mc.player.getYRot(), pitch, mc.player.onGround(), mc.player.horizontalCollision));
        mc.getConnection().send(new ServerboundPlayerCommandPacket(
            mc.player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
    }
}
