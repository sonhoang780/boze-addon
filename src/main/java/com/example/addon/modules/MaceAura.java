package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

/**
 * MaceAura — auto-attacks enemies from above while ElytraFlying.
 *
 * Bypass (6b6t NCP):
 *   Server plugin confiscates mace if (a) player attacks while isGliding=true,
 *   or (b) START_FALL_FLYING is sent while holding mace.
 *
 *   Before each attack we inject one PlayerMoveC2SPacket.Full(onGround=true).
 *   Server clears the player's isFallFlying flag → attack is processed as
 *   non-gliding → no confiscation.  Client state is never touched; the next
 *   natural movement packet from the game restores normal elytra state.
 *   No START_FALL_FLYING is ever sent by this module.
 *
 *   Height bonus: 6b6t grants full mace bonus when attacker.Y − target.Y >= 10.
 */
public class MaceAura extends AddonModule {
    public static final MaceAura INSTANCE = new MaceAura();

    public final SliderOption range       = new SliderOption(this, "Range",         "Horizontal attack range (blocks).",           6.0, 3.0, 8.0,  0.5);
    public final SliderOption vertRange   = new SliderOption(this, "VerticalRange",  "Max vertical distance to search for target.", 20.0, 5.0, 30.0, 1.0);
    public final SliderOption minHeight   = new SliderOption(this, "MinHeight",      "Min Y-delta above target to trigger attack.", 10.0, 8.0, 20.0, 0.5);
    public final SliderOption attackDelay = new SliderOption(this, "Delay",          "Milliseconds between attacks.",               200.0, 100.0, 1000.0, 50.0);
    public final ToggleOption autoTarget  = new ToggleOption(this, "AutoTarget",     "Auto-select nearest player. Off = crosshair only.", true);

    private long lastAttackMs = 0;

    public MaceAura() {
        super("MaceAura", "Auto-attacks from above with mace. onGround-spoof bypass for NCP isGliding detection on 6b6t.");
    }

    @Override
    public void onEnable() {
        lastAttackMs = 0;
    }

    @Override
    public void onDisable() {}

    @EventHandler
    private void onTick(EventTick.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (mc.getNetworkHandler() == null) return;

        if (!mc.player.getMainHandStack().isOf(Items.MACE)) return;

        long now = System.currentTimeMillis();
        if (now - lastAttackMs < attackDelay.getValue().longValue()) return;

        AbstractClientPlayerEntity target = findTarget(mc);
        if (target == null) return;

        double heightDelta = mc.player.getY() - target.getY();
        if (heightDelta < minHeight.getValue()) return;

        // Bypass: spoof onGround=true so server clears isGliding before processing attack
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.player.getYaw(), mc.player.getPitch(),
            true,                          // onGround spoofed
            mc.player.horizontalCollision  // preserve real horizontal collision
        ));

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        lastAttackMs = now;
    }

    private AbstractClientPlayerEntity findTarget(MinecraftClient mc) {
        double r  = range.getValue();
        double vr = vertRange.getValue();

        if (!autoTarget.getValue()) {
            if (mc.targetedEntity instanceof AbstractClientPlayerEntity p) {
                double dy = mc.player.getY() - p.getY();
                if (hDist(mc.player, p) <= r && dy >= 0 && dy <= vr) return p;
            }
            return null;
        }

        AbstractClientPlayerEntity best     = null;
        double                     bestDist = Double.MAX_VALUE;
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            double dy = mc.player.getY() - p.getY();
            if (dy < 0 || dy > vr) continue;
            double hd = hDist(mc.player, p);
            if (hd > r) continue;
            if (hd < bestDist) { bestDist = hd; best = p; }
        }
        return best;
    }

    private static double hDist(net.minecraft.entity.Entity a, net.minecraft.entity.Entity b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
