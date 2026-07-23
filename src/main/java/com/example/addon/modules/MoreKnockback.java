package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.SliderOption;
import dev.boze.api.option.ToggleOption;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Port of ThunderHack-Reborn's MoreKnockback (thunder.hack.features.modules.combat.MoreKnockback).
 * On a melee attack against a living entity, does a sprint-reset W-tap (STOP/START sprint
 * packets bracketing the hit) so the server applies the extra sprint-knockback to that hit --
 * but only when the hit ISN'T going to crit (a crit would take priority over the W-tap).
 *
 * Adaptations to Boze / real MC 26.1.2 (each ThunderHack dep mapped, nothing dropped silently):
 * - Hooked at MixinMultiPlayerGameMode#attack (fires exactly once per real attack, HEAD, before
 *   the interact packet goes out) instead of ThunderHack's PacketEvent.Send +
 *   Criticals.getEntity/getInteractType packet inspection -- 26.1.2's ServerboundInteractPacket
 *   is a bare record with NO attack/interact action component (verified via decompile), so the
 *   packet can't be classified as an ATTACK the way older PlayerInteractEntityC2SPacket could.
 *   The mixin hook gives the target entity directly and unambiguously.
 * - ClientCommandC2SPacket.Mode.{START,STOP}_SPRINTING -> ServerboundPlayerCommandPacket.Action.
 * - MovementUtility.isMoving() -> reads mc.player.input.keyPresses (the real Input record).
 * - MathUtility.random(0,100) -> ThreadLocalRandom.
 * - canCrit()'s ModuleManager.criticals / elytraPlus cross-module checks -> pure vanilla crit
 *   conditions (this addon has no Criticals/ElytraPlus module to query); the physical
 *   "would this be a crit" test (airborne + falling, not in a crit-blocking state, cooldown
 *   charged) is kept verbatim. mc.player.lastSprinting (a ThunderHack accessor field) has no
 *   Mojmap equivalent and isn't needed -- setSprinting(true) + the packet pair already reset
 *   the server's sprint-knockback state.
 */
public class MoreKnockback extends AddonModule {
    public static final MoreKnockback INSTANCE = new MoreKnockback();

    public final ToggleOption inMove = new ToggleOption(this, "InMove",
            "Also W-tap while you're already moving (off = only when standing still).", true);
    public final SliderOption hurtTime = new SliderOption(this, "HurtTime",
            "Only W-tap if the target's hurt-cooldown is at or below this (0-10 ticks).", 10.0, 0.0, 10.0, 1.0);
    public final SliderOption chance = new SliderOption(this, "Chance",
            "Percent of eligible hits to W-tap on.", 100.0, 0.0, 100.0, 1.0);

    public MoreKnockback() {
        super("MoreKnockback", "Sprint-reset W-taps your melee hits for extra knockback (skips crits).");
    }

    /** Called from MixinMultiPlayerGameMode#attack HEAD, once per real attack. */
    public void onAttack(Entity target) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        if (!(inMove.getValue() || !isMoving(mc))) return;               // (!isMoving || inMove)
        if (target instanceof EndCrystal) return;
        if (!(target instanceof LivingEntity lent)) return;
        if (lent.hurtTime > hurtTime.getValue()) return;
        if (ThreadLocalRandom.current().nextInt(0, 101) < 100 - chance.getValue().intValue()) return;
        if (canCrit(mc)) return;

        if (mc.player.isSprinting()) send(mc, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
        send(mc, ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        send(mc, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
        send(mc, ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        mc.player.setSprinting(true);
    }

    private void send(Minecraft mc, ServerboundPlayerCommandPacket.Action action) {
        mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, action));
    }

    private boolean isMoving(Minecraft mc) {
        Input in = mc.player.input.keyPresses;
        return in.forward() || in.backward() || in.left() || in.right();
    }

    /** Would this attack be a crit right now? If so, skip the W-tap (crit wins). */
    private boolean canCrit(Minecraft mc) {
        boolean reasonForSkipCrit =
                mc.player.getAbilities().flying
                || mc.player.isFallFlying()
                || mc.player.hasEffect(MobEffects.BLINDNESS)
                || mc.level.getBlockState(BlockPos.containing(mc.player.position())).getBlock() == Blocks.COBWEB
                || mc.player.isInLava()
                || mc.player.isUnderWater();

        if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) return false;
        if (!reasonForSkipCrit) return !mc.player.onGround() && mc.player.fallDistance > 0f;
        return false;
    }
}
