package com.example.addon.mixin;

import com.example.addon.modules.Dummy;
import com.example.addon.modules.InvMovePlus;
import com.example.addon.modules.MoreKnockback;
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
        // MoreKnockback's sprint-reset W-tap fires here, HEAD -- so its STOP/START sprint
        // packets go out just before the attack's own interact packet (correct order for the
        // server to apply sprint-knockback to this hit).
        MoreKnockback.INSTANCE.onAttack(target);
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
     * InvMovePlus GrimV2: lies to the server around the click (spoofed input packet +
     * sprint-state toggle, see InvMovePlus.beforeClick/afterClick) instead of deferring it --
     * the click always proceeds through the real handleContainerInput, same tick.
     */
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void invmove$beforeClick(int containerId, int slotId, int buttonNum, ContainerInput input, Player player, CallbackInfo ci) {
        InvMovePlus.INSTANCE.beforeClick(containerId, slotId, buttonNum, input);
    }

    @Inject(method = "handleContainerInput", at = @At("TAIL"))
    private void invmove$afterClick(int containerId, int slotId, int buttonNum, ContainerInput input, Player player, CallbackInfo ci) {
        InvMovePlus.INSTANCE.afterClick();
    }
}
