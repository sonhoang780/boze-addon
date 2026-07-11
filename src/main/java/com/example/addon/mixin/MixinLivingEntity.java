package com.example.addon.mixin;

import com.example.addon.modules.ControlRocket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
}
