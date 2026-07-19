package com.example.addon.mixin;

import com.example.addon.util.CustomTimer;
import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Self-written Timer-hack hook. Vanilla's real Timer class was renamed to
 * DeltaTracker.Timer in this MC version (verified via javap, 26.1.2 -- no "Timer"
 * class exists anymore). advanceGameTime(long) computes deltaTicks = elapsedRealMs /
 * targetMsptProvider.apply(msPerTick) every frame (also verified via javap) -- a
 * smaller returned mspt means deltaTicks accumulates faster, so more ticks get
 * simulated per real second. Dividing that result by our multiplier is the exact
 * mechanism classic Timer-hack modules (Meteor's included) use, just against this
 * version's renamed class.
 */
@Mixin(DeltaTracker.Timer.class)
public abstract class MixinDeltaTrackerTimer {

    @Redirect(
        method = "advanceGameTime(J)I",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/floats/FloatUnaryOperator;apply(F)F")
    )
    private float exampleAddon$scaleTimer(FloatUnaryOperator provider, float msPerTick) {
        float target = provider.apply(msPerTick);
        double speed = CustomTimer.multiplier;
        if (speed == 1.0) return target;
        return (float) (target / speed);
    }
}
