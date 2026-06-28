package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiBufferSource.BufferSource.class)
public abstract class MixinBufferSource {
    @Inject(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V", at = @At("HEAD"))
    private void betterchams$beforeEndBatch(RenderType type, BufferBuilder builder, CallbackInfo ci) {
        if (BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.handToggle.getValue() && BetterChams.isRenderingHand) {
            // Check if the current render type is an outline type
            if (type.isOutline()) {
                Minecraft mc = Minecraft.getInstance();
                com.mojang.blaze3d.pipeline.RenderTarget outlineTarget = ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).getEntityOutlineTarget();
                if (outlineTarget != null) {
                    com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride = outlineTarget.getColorTextureView();
                    com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride = outlineTarget.getDepthTextureView();
                }
            }
        }
    }

    @Inject(method = "endBatch(Lnet/minecraft/client/renderer/rendertype/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder;)V", at = @At("RETURN"))
    private void betterchams$afterEndBatch(RenderType type, BufferBuilder builder, CallbackInfo ci) {
        if (BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.handToggle.getValue() && BetterChams.isRenderingHand) {
            if (type.isOutline()) {
                com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride = null;
                com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride = null;
            }
        }
    }
}
