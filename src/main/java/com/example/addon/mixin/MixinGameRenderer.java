package com.example.addon.mixin;

import com.example.addon.modules.LiquidGlassHud;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    /**
     * Intercepts MC's blur pass to run the MusicHUD liquid-glass GPU passes
     * (separable Gaussian blur + Snell-law refraction) when active.
     * The normal blur is cancelled so the glass pass is not blurred afterward.
     */
    @Inject(method = "renderBlur", at = @At("HEAD"), cancellable = true)
    private void musichud$renderLiquidGlass(CallbackInfo ci) {
        if (LiquidGlassHud.INSTANCE.isActive()) {
            ci.cancel();
            LiquidGlassHud.INSTANCE.render();
        }
    }
}
