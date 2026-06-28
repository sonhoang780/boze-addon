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

    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/render/GuiRenderer;<init>(Lnet/minecraft/client/renderer/state/gui/GuiRenderState;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;Ljava/util/List;)V"))
    private static void musichud$registerSkiaPip(Args args) {
        MultiBufferSource.BufferSource bufferSource = args.get(1);
        List<PictureInPictureRenderer<?>> original = args.get(4);
        List<PictureInPictureRenderer<?>> modified = new ArrayList<>(original);
        modified.add(new SkiaPipRenderer(bufferSource));
        args.set(4, modified);
    }

    @Inject(method = "processBlurEffect", at = @At("HEAD"), cancellable = true)
    private void musichud$renderLiquidGlass(CallbackInfo ci) {
        if (LiquidGlassHud.INSTANCE.isActive()) {
            ci.cancel();
            LiquidGlassHud.INSTANCE.render();
        } else if (SkijaBackdropBlur.INSTANCE.isActive()) {
            ci.cancel();
            SkijaBackdropBlur.INSTANCE.render();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true, require = 0)
    private void fakefly$suppressBobView(CameraRenderState cameraRenderState, PoseStack matrices, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null
                && FakeFly.INSTANCE.getState()
                && (FakeFly.INSTANCE.isFlying() || mc.player.isFallFlying())) {
            ci.cancel();
        }
    }

    @Inject(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V"
        )
    )
    private void betterchams$reprocessHandOutline(net.minecraft.client.DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        if (!com.example.addon.modules.BetterChams.INSTANCE.getState() || !com.example.addon.modules.BetterChams.INSTANCE.handToggle.getValue()) return;
        if (!com.example.addon.modules.BetterChams.INSTANCE.bloomToggle.getValue() && com.example.addon.modules.BetterChams.INSTANCE.fillMode.getValue() == com.example.addon.modules.BetterChams.FillMode.Off) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        net.minecraft.resources.Identifier handOutlineId = net.minecraft.resources.Identifier.fromNamespaceAndPath("example-addon", "hand_outline");
        if (!com.example.addon.modules.BetterChams.INSTANCE.bloomToggle.getValue() && com.example.addon.modules.BetterChams.INSTANCE.fillMode.getValue() != com.example.addon.modules.BetterChams.FillMode.Off) {
            handOutlineId = net.minecraft.resources.Identifier.fromNamespaceAndPath("example-addon", "fill_only_hand_outline");
        }
        net.minecraft.client.renderer.ShaderManager shaderManager = mc.getShaderManager();
        net.minecraft.client.renderer.PostChain activePostChain = shaderManager.getPostChain(handOutlineId, net.minecraft.client.renderer.LevelTargetBundle.MAIN_TARGETS);
        if (activePostChain == null) return;

        com.mojang.blaze3d.pipeline.RenderTarget outlineTarget = ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).getEntityOutlineTarget();
        if (outlineTarget == null) return;

        // Render the custom frag shader if active
        if (com.example.addon.modules.BetterChams.INSTANCE.fillMode.getValue() == com.example.addon.modules.BetterChams.FillMode.Shader) {
            com.example.addon.rendering.ChamsCustomShader.renderCustomShader();
        }

        activePostChain.process(outlineTarget, com.mojang.blaze3d.resource.GraphicsResourceAllocator.UNPOOLED);
    }

    @Inject(
        method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"
        )
    )
    private void betterchams$flushHandOutline(net.minecraft.client.renderer.state.level.CameraRenderState state, float tickDelta, org.joml.Matrix4fc projMat, CallbackInfo ci) {
        if (!com.example.addon.modules.BetterChams.INSTANCE.getState() || !com.example.addon.modules.BetterChams.INSTANCE.handToggle.getValue()) return;
        if (!com.example.addon.modules.BetterChams.INSTANCE.bloomToggle.getValue() && com.example.addon.modules.BetterChams.INSTANCE.fillMode.getValue() == com.example.addon.modules.BetterChams.FillMode.Off) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.renderBuffers() == null || mc.renderBuffers().outlineBufferSource() == null) return;

        com.mojang.blaze3d.pipeline.RenderTarget outlineTarget = ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).getEntityOutlineTarget();

        if (outlineTarget == null) {
            ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).invokeInitOutline();
            outlineTarget = ((com.example.addon.mixin.LevelRendererAccessor) mc.levelRenderer).getEntityOutlineTarget();
        }

        if (outlineTarget != null) {
            com.example.addon.modules.BetterChams.isRenderingHand = true;
            com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride = outlineTarget.getColorTextureView();
            com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride = outlineTarget.getDepthTextureView();

            mc.renderBuffers().outlineBufferSource().endOutlineBatch();

            com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride = null;
            com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride = null;
            com.example.addon.modules.BetterChams.isRenderingHand = false;
        }
    }
}
