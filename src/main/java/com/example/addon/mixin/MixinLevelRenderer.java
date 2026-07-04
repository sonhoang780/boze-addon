package com.example.addon.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @org.spongepowered.asm.mixin.Shadow
    private net.minecraft.client.renderer.LevelTargetBundle targets;

    @Inject(method = "shouldShowEntityOutlines", at = @At("RETURN"), cancellable = true)
    private void betterchams$forceOutline(CallbackInfoReturnable<Boolean> cir) {
        if (com.example.addon.modules.BetterChams.INSTANCE.getState()) {
            // If entityOutlineTarget is null (common in multiplayer with no glowing entities),
            // force-initialize it so hand chams work correctly
            if (cir.getReturnValue() == Boolean.FALSE) {
                RenderTarget target = ((LevelRendererAccessor)(Object)this).getEntityOutlineTarget();
                if (target == null) {
                    ((LevelRendererAccessor)(Object)this).invokeInitOutline();
                }
            }
            cir.setReturnValue(true);
        }
    }
    @Inject(
        method = "renderLevel",
        at = @At("TAIL")
    )
    private void betterchams$flushWorldOutlinesLate(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.renderBuffers() != null && mc.renderBuffers().outlineBufferSource() != null) {
            mc.renderBuffers().outlineBufferSource().endOutlineBatch();
        }
    }

    // NOTE: there used to be a Redirect here that swapped renderLevel's
    // entity_outline chain for fill_only_outline in the fill/outline-only combo.
    // That made the silhouettes resolve TWICE: once in the FrameGraph via that
    // redirect, then again in MixinGameRenderer.reprocessHandOutline -- whose
    // second dilation traced the FIRST pass's ring (not the body) while the first
    // ring's pixels were dropped as stale swap content, rendering the outline as a
    // detached ring floating one radius off the body ("outline khong bam sat",
    // 2026-07-04). MixinShaderManager already nulls the chain for every active
    // combo, and reprocessHandOutline resolves everything exactly once.

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"
        )
    )
    private void betterchams$addSmokePostChain(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local com.mojang.blaze3d.framegraph.FrameGraphBuilder builder) {
        // JfaField now runs inside MixinGameRenderer.reprocessHandOutline, right
        // before the ONE chain that actually resolves everything -- doing it here fed
        // a chain (entity_outline.json) that MixinShaderManager nulls whenever the
        // module is on, and it ran before the hand was flushed into the target.

        if (com.example.addon.modules.TungTungSahur.INSTANCE.fadingOut && com.example.addon.modules.TungTungSahur.INSTANCE.smokeFadeAlpha > 0) {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.renderer.PostChain smokeChain = mc.getShaderManager().getPostChain(net.minecraft.resources.Identifier.fromNamespaceAndPath("example-addon", "tung_smoke"), net.minecraft.client.renderer.LevelTargetBundle.MAIN_TARGETS);
            if (smokeChain != null) {
                com.example.addon.modules.TungTungSahur.INSTANCE.updateSmokeParams(mc, smokeChain);
                smokeChain.addToFrame(builder, mc.getWindow().getWidth(), mc.getWindow().getHeight(), this.targets);
            }
        }

        // CustomSky compositing: the actual sky pixels were already rendered offscreen
        // into CustomSky.SKY_TEXTURE by CustomSkyRenderer.tick() (raw-GL, same proven
        // pattern as ChamsCustomShader) -- this PostChain pass just blends that texture
        // over minecraft:main wherever MainDepth reads far/no-geometry (true sky pixels
        // only, terrain untouched).
        if (com.example.addon.modules.CustomSky.INSTANCE.getState()
                && com.example.addon.modules.CustomSky.INSTANCE.mode.getValue() != com.example.addon.modules.CustomSky.Mode.Off) {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.renderer.PostChain skyChain = mc.getShaderManager().getPostChain(net.minecraft.resources.Identifier.fromNamespaceAndPath("example-addon", "custom_sky"), net.minecraft.client.renderer.LevelTargetBundle.MAIN_TARGETS);
            if (skyChain != null) {
                skyChain.addToFrame(builder, mc.getWindow().getWidth(), mc.getWindow().getHeight(), this.targets);
            }
        }
    }
}
