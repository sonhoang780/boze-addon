package com.example.addon.mixin;

import com.example.addon.modules.Dummy;
import com.example.addon.modules.InvMovePlus;
import com.example.addon.modules.PathFinder;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * Cancels firework-rocket use while PathFinder is enabled. Baritone's own #elytra
     * process burns fireworks for movement boost -- ElytraFly Creative doesn't need real
     * fireworks (it's velocity-controlled, not vanilla-gliding), so letting baritone's
     * attempt through would just waste/drop rockets for no effect. See PathFinder.java.
     */
    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void pathfinder$blockFirework(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!PathFinder.INSTANCE.getState()) return;
        if (player.getItemInHand(hand).getItem() != Items.FIREWORK_ROCKET) return;
        cir.setReturnValue(InteractionResult.FAIL);
    }

    /**
     * InvMovePlus GrimStrict: defers the ENTIRE click (local mutation + packet) while the
     * player is moving, instead of cancelling and later re-sending the raw
     * ServerboundContainerClickPacket. Raw re-send bypassed ViaFabricPlus's full-stack
     * capture inside this very method, causing "CONTAINER_CLICK could not be remapped"
     * errors on older-protocol servers. See InvMovePlus.deferClick / flush.
     */
    @Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
    private void invmove$deferClick(int containerId, int slotId, int buttonNum, ContainerInput input, Player player, CallbackInfo ci) {
        if (InvMovePlus.INSTANCE.deferClick(containerId, slotId, buttonNum, input)) ci.cancel();
    }
}
