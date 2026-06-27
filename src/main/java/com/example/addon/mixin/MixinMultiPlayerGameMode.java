package com.example.addon.mixin;

import com.example.addon.modules.Dummy;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards the local player's attack on the client-only {@link Dummy} entity to its
 * combat simulation. Vanilla {@code attack(Player, Entity)} only sends an interact packet
 * (the server resolves damage), so a client-spawned dummy would otherwise never take a hit.
 * Hooking HEAD here fires once per attack click regardless of attack-cooldown charge.
 */
@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {

    @Inject(method = "attack", at = @At("HEAD"))
    private void dummy$onAttack(Player player, Entity target, CallbackInfo ci) {
        Dummy.INSTANCE.onAttacked(target);
    }
}
