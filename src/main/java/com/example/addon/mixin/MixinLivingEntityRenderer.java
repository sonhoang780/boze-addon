package com.example.addon.mixin;

import com.example.addon.modules.ElytraFix;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After LivingEntityRenderer populates the render state for the local player,
 * override state.pitch with ElytraFix.headPitchOverrideDeg (when active).
 *
 * state.pitch feeds directly into BipedEntityModel.setAngles() → head.pitch,
 * making the head visually nod toward the throw direction in F5 (third-person).
 *
 * The camera reads mc.player.getPitch() directly, bypassing this render state,
 * so it stays at the real pitch throughout — no first-person camera snap.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRenderer {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("RETURN")
    )
    private <T extends LivingEntity, S extends LivingEntityRenderState>
    void elytrafix$overrideHeadPitch(T entity, S state, float tickDelta, CallbackInfo ci) {
        if (!Float.isNaN(ElytraFix.headPitchOverrideDeg)
                && entity == Minecraft.getInstance().player) {
            state.xRot = ElytraFix.headPitchOverrideDeg;
        }
    }
}
