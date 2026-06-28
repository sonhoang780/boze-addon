package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OutlineBufferSource.class)
public class MixinOutlineBufferSource {

    @Inject(method = "getBuffer", at = @At("HEAD"))
    private void betterchams$onGetBuffer(RenderType renderType, CallbackInfoReturnable<com.mojang.blaze3d.vertex.VertexConsumer> cir) {
        if (BetterChams.isRenderingHand && BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.handToggle.getValue()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.levelRenderer != null) {
                com.mojang.blaze3d.pipeline.RenderTarget outlineTarget = ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).getEntityOutlineTarget();
                if (outlineTarget != null) {
                    RenderSystem.outputColorTextureOverride = outlineTarget.getColorTextureView();
                    RenderSystem.outputDepthTextureOverride = outlineTarget.getDepthTextureView();
                }
            }
        }
    }

    @Inject(method = "getBuffer", at = @At("RETURN"))
    private void betterchams$onGetBufferReturn(RenderType renderType, CallbackInfoReturnable<com.mojang.blaze3d.vertex.VertexConsumer> cir) {
        if (BetterChams.isRenderingHand && BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.handToggle.getValue()) {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
        }
    }
}
