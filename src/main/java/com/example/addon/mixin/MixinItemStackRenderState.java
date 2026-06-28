package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ItemStackRenderState.class)
public abstract class MixinItemStackRenderState {

    @ModifyVariable(
        method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
        at = @At("HEAD"),
        ordinal = 2,
        argsOnly = true
    )
    private int betterchams$modifyOutlineColorItem(int outlineColor) {
        if (BetterChams.isRenderingHand && BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.handToggle.getValue()) {
            return 0xFFFFFFFF;
        }
        return outlineColor;
    }
}
