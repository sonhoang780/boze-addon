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

    @org.spongepowered.asm.mixin.injection.Redirect(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderManager;getPostChain(Lnet/minecraft/resources/Identifier;Ljava/util/Set;)Lnet/minecraft/client/renderer/PostChain;"
        )
    )
    private net.minecraft.client.renderer.PostChain betterchams$redirectGetPostChain(
        net.minecraft.client.renderer.ShaderManager instance,
        net.minecraft.resources.Identifier id,
        java.util.Set<net.minecraft.resources.Identifier> externalTargets
    ) {
        if (id.getPath().equals("entity_outline") && com.example.addon.modules.BetterChams.INSTANCE.getState()) {
            // Flare rides the glow-style chain (it needs the blurred silhouette field as
            // its flame canvas), so the cheap fill_only route only applies when flare is off.
            if (!com.example.addon.modules.BetterChams.INSTANCE.glowToggle.getValue() && !com.example.addon.modules.BetterChams.INSTANCE.flareToggle.getValue()
                    && (com.example.addon.modules.BetterChams.INSTANCE.fillMode.getValue() != com.example.addon.modules.BetterChams.FillMode.Off || com.example.addon.modules.BetterChams.INSTANCE.outlineToggle.getValue())) {
                return instance.getPostChain(net.minecraft.resources.Identifier.fromNamespaceAndPath("example-addon", "fill_only_outline"), externalTargets);
            }
        }
        return instance.getPostChain(id, externalTargets);
    }

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"
        )
    )
    private void betterchams$addSmokePostChain(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local com.mojang.blaze3d.framegraph.FrameGraphBuilder builder) {
        // GlowBlur now runs inside MixinGameRenderer.reprocessHandOutline, right
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
