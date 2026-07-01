package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin(AvatarRenderer.class)
public abstract class MixinAvatarRenderer {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("RETURN")
    )
    private void betterchamss$setOutlineColor(
        Avatar entity,
        AvatarRenderState state,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (!(entity instanceof AbstractClientPlayer player)) return;
        Minecraft mc = Minecraft.getInstance();
        BetterChams bc = BetterChams.INSTANCE;
        if (!bc.getState()) return;

        if (player == mc.player) {
            if (!bc.selfToggle.getValue()) return;
            if (mc.options.getCameraType().isFirstPerson()) return;
        } else {
            if (!bc.playerToggle.getValue()) return;
            if (!bc.isInRange(player)) return;
        }

        state.outlineColor = 0xFFFFFFFF;
    }

    @Redirect(
        method = "renderHand",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"
        )
    )
    private void betterchams$redirectArmSubmit(
        SubmitNodeCollector collector,
        ModelPart part,
        PoseStack poseStack,
        RenderType renderType,
        int light,
        int overlay,
        TextureAtlasSprite sprite
    ) {
        int outlineColor = 0;
        if (BetterChams.isRenderingHand && BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.handToggle.getValue()) {
            outlineColor = 0xFFFFFFFF;
        }
        collector.submitModelPart(part, poseStack, renderType, light, overlay, sprite, false, false, -1, null, outlineColor);
    }
}
