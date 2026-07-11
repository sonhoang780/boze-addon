package com.example.addon.mixin;

import com.example.addon.modules.ControlRocket;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ControlRocket's MuteElytra option (ported from lambda-client's ElytraFly.mute, which cancels
 * ClientEvent.Sound for SoundEvents.ITEM_ELYTRA_FLYING). Boze has no equivalent sound event
 * to hook, so this cancels playback directly at the SoundManager -- the same choke point
 * every played sound instance passes through, regardless of who's making it.
 */
@Mixin(SoundManager.class)
public abstract class MixinSoundManager {

    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
        at = @At("HEAD"), cancellable = true)
    private void controlrocket$muteElytra(SoundInstance instance, CallbackInfoReturnable<?> cir) {
        if (ControlRocket.muteElytraSound
                && instance.getIdentifier().equals(SoundEvents.ELYTRA_FLYING.location())) {
            cir.setReturnValue(null);
        }
    }
}
