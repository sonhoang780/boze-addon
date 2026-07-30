package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class MixinItemInHandRenderer {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"))
    private void betterchams$startHand(float tickDelta, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, LocalPlayer localPlayer, int light, CallbackInfo ci) {
        if (!BetterChams.INSTANCE.getState() || !BetterChams.INSTANCE.handToggle.getValue()) return;
        // No "everything off, skip" check here: FillMode.Off is a real ACTIVE flat-fill
        // mode now (see BetterChams.writeMainParams's comment), not "fill disabled" --
        // there is no config where getState()+handToggle is true and truly nothing
        // renders, so skipping on glow/flare/outline/opacity alone silently dropped the
        // flat fill (and, previously, Opacity) whenever every OTHER effect was off
        // (user report 2026-07-30: "Opacity = 1 thì mất FillOpacity màu hồng").

        BetterChams.isRenderingHand = true;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.levelRenderer != null) {
            com.mojang.blaze3d.pipeline.RenderTarget outlineTarget = ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).getEntityOutlineTarget();
            if (outlineTarget == null) {
                ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).invokeInitOutline();
            }
        }
    }

    @Inject(method = "renderHandsWithItems", at = @At("RETURN"))
    private void betterchams$endHand(float tickDelta, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, LocalPlayer localPlayer, int light, CallbackInfo ci) {
        BetterChams.isRenderingHand = false;
    }
}
