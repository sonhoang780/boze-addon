package com.example.addon.mixin;

import com.example.addon.modules.FakeFly;
import com.example.addon.modules.LiquidGlassHud;
import com.example.addon.modules.SkijaBackdropBlur;
import com.example.addon.screens.SkiaPipRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.ArrayList;
import java.util.List;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    /**
     * Injects a {@link SkiaPipRenderer} into the GuiRenderer's picture-in-picture
     * renderer list (the 5th constructor argument) so MusicHUD's Skija content can be
     * registered via GuiRenderState.addPicturesInPictureState and get correct Z-order
     * (sorted alongside HUD/Screen elements by extraction order) instead of always
     * drawing on top of everything like an end-of-frame hook would.
     */
    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/render/GuiRenderer;<init>(Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;Ljava/util/List;)V"))
    private static void musichud$registerSkiaPip(Args args) {
        MultiBufferSource.BufferSource bufferSource = args.get(1);
        List<PictureInPictureRenderer<?>> original = args.get(4);
        List<PictureInPictureRenderer<?>> modified = new ArrayList<>(original);
        modified.add(new SkiaPipRenderer(bufferSource));
        args.set(4, modified);
    }

    /**
     * Intercepts MC's blur pass to run the MusicHUD liquid-glass GPU passes
     * (separable Gaussian blur + Snell-law refraction) when active.
     * The normal blur is cancelled so the glass pass is not blurred afterward.
     */
    @Inject(method = "processBlurEffect", at = @At("HEAD"), cancellable = true)
    private void musichud$renderLiquidGlass(CallbackInfo ci) {
        if (LiquidGlassHud.INSTANCE.isActive()) {
            ci.cancel();
            LiquidGlassHud.INSTANCE.render();
        } else if (SkijaBackdropBlur.INSTANCE.isActive()) {
            // Blur mode: cancel MC's full-screen blur and run our panel-only Skija blur.
            ci.cancel();
            SkijaBackdropBlur.INSTANCE.render();
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
    private void fakefly$suppressBobView(CameraRenderState cameraRenderState, PoseStack matrices, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null
                && FakeFly.INSTANCE.getState()
                && (FakeFly.INSTANCE.isFlying() || mc.player.isFallFlying())) {
            ci.cancel();
        }
    }
}
