package com.example.addon.mixin;

import com.example.addon.modules.FakeFly;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses arm-swing animation during FakeFly elytra gliding.
 * interactItem() calls swingHand() each time a firework rocket fires.
 * Without this, the arm swings every ~8 ticks continuously while keys are held.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "swingHand(Lnet/minecraft/util/Hand;)V", at = @At("HEAD"), cancellable = true)
    private void fakefly$suppressArmSwing(Hand hand, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player == (Object) this
                && FakeFly.INSTANCE.getState()
                && mc.player.isGliding()) {
            ci.cancel();
        }
    }
}
