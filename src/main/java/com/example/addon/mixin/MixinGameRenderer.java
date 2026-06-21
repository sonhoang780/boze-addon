package com.example.addon.mixin;

import com.example.addon.modules.FakeFly;
import com.example.addon.modules.LiquidGlassHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
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

    /**
     * Suppresses the first-person view-bob in EVERY FakeFly flight state (real gliding AND
     * ChestplateMode). bobView translates on X and rolls on Z proportional to movement, which is
     * exactly the left/right hand sway the user sees while flying. Gating only on isGliding() left
     * the sway on during ChestplateMode (isGliding=false), so we also check FakeFly.isFlying().
     * require=0: if MC renamed bobView this silently skips rather than crashing.
     */
    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
    private void fakefly$suppressBobView(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null
                && FakeFly.INSTANCE.getState()
                && (FakeFly.INSTANCE.isFlying() || mc.player.isGliding())) {
            ci.cancel();
        }
    }
}
