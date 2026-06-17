package com.example.addon.mixin;

import com.example.addon.ExampleAddon;
import com.example.addon.modules.ElytraFix;
import dev.boze.api.BozeInstance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MixinMinecraftClient {

    // Accumulator for fractional tick timing (timer hack support for ElytraFix hover).
    private float timerAccumulator = 0f;

    @Inject(method = "<init>", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MinecraftClient;instance:Lnet/minecraft/client/MinecraftClient;"))
    private void onInit$setInstance(RunArgs args, CallbackInfo ci) {
        BozeInstance.INSTANCE.registerAddon(new ExampleAddon());
    }

    /**
     * Throttles game ticks when ElytraFix hover timer is active (< 1.0).
     * Injects at HEAD of tick() and cancels it when the accumulator hasn't reached 1.0,
     * so only 1-in-N ticks actually execute — e.g. speed=0.1 → ~2 ticks/second.
     *
     * If this still crashes, open MinecraftClient in your IDE decompiler, find the
     * method that processes one game tick (called from the render loop), and replace
     * "tick" below with that method's Mojang-mapped name.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void throttleGameTick(CallbackInfo ci) {
        float speed = ElytraFix.hoverTimerSpeed;
        if (speed >= 1.0f) return;
        timerAccumulator += speed;
        if (timerAccumulator < 1.0f) {
            ci.cancel();
        } else {
            timerAccumulator -= 1.0f;
        }
    }
}
