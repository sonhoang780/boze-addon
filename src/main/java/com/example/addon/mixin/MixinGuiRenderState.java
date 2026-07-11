package com.example.addon.mixin;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GuiRenderState#blurBeforeThisStratum() throws IllegalStateException("Can only blur
 * once per frame") the SECOND time anything calls it in the same frame (verified via
 * javap -c: `if (firstStratumAfterBlur != Integer.MAX_VALUE) throw ...`) -- it does NOT
 * silently no-op like its name/MusicHUD's blur-claiming code assumed.
 *
 * <p>MusicHUD calls this itself every frame it's active (to claim the blur slot when no
 * open screen otherwise would -- see MusicHUD's render() comment), which is exactly
 * "something else" from vanilla Screen's own point of view: when a real screen with
 * Menu Background Blurriness >= 1 opens afterward in the SAME frame (e.g. Esc's
 * PauseScreen, which calls this itself via Screen#extractBackground), vanilla's own
 * call has no guard and crashes the whole render frame. MusicHUD's own call already
 * try/catches this exception (safe for OUR code), but vanilla's internal call cannot --
 * this mixin makes the method idempotent for every caller instead, which is the
 * behavior "no-op past the first call" was always supposed to mean.
 */
@Mixin(GuiRenderState.class)
public class MixinGuiRenderState {

    @Shadow private int firstStratumAfterBlur;

    @Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
    private void exampleAddon$noopIfAlreadyBlurredThisFrame(CallbackInfo ci) {
        if (firstStratumAfterBlur != Integer.MAX_VALUE) ci.cancel();
    }
}
