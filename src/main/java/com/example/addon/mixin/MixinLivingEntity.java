package com.example.addon.mixin;

import com.example.addon.modules.ControlRocket;
import com.example.addon.modules.IgnoreClimb;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses arm-swing animation during ControlRocket elytra gliding.
 * interactItem() calls swingHand() each time a firework rocket fires.
 * Without this, the arm swings every ~8 ticks continuously while keys are held.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"), cancellable = true)
    private void fakefly$suppressArmSwing(InteractionHand hand, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player == (Object) this
                && ControlRocket.INSTANCE.getState()
                && mc.player.isFallFlying()) {
            ci.cancel();
        }
    }

    /** IgnoreClimb: force the local player to never be treated as climbing (see IgnoreClimb.java). */
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true, require = 0)
    private void ignoreClimb$forceFalse(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player == (Object) this && IgnoreClimb.INSTANCE.getState()) {
            cir.setReturnValue(false);
        }
    }
}
