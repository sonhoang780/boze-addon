package com.example.addon.mixin;

import com.example.addon.modules.ElytraFix;
import com.example.addon.modules.KillEffect;
import com.example.addon.render.GelUuidCarrier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
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

    /**
     * KillEffect for monsters: hide the model + stash the UUID so MixinModelFeatureRenderer
     * captures the burst. Enemy-gated, so players (handled by MixinAvatarRenderer) never
     * double-fire here.
     */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
        at = @At("RETURN")
    )
    private <T extends LivingEntity, S extends LivingEntityRenderState>
    void exampleAddon$killEffectMonster(T entity, S state, float tickDelta, CallbackInfo ci) {
        if (!(entity instanceof Enemy)) return;
        if (!KillEffect.INSTANCE.isExploding(entity.getUUID())) return;
        ((GelUuidCarrier) (Object) state).exampleAddon$setGelUuid(entity.getUUID());
        state.isInvisible = true;
        if (KillEffect.INSTANCE.debug.getValue()) dev.boze.api.utility.ChatHelper.sendMsg("KillEffect",
                "§bextractRenderState(monster): isInvisible=true set, id=" + entity.getUUID());
        // extractRenderState just set these from the real entity's still-incrementing
        // deathTime -- state.deathTime > 0 drives the fall-over topple angle
        // (LivingEntityRenderer.setupRotations) and hasRedOverlay stays true for the WHOLE
        // death animation, not just a brief hit-flash (verified via decompile: state.
        // hasRedOverlay = entity.hurtTime > 0 || entity.deathTime > 0). Freezing both keeps
        // the body standing in its last pose while it fades instead of toppling over red.
        state.deathTime = 0f;
        state.hasRedOverlay = false;
    }
}
