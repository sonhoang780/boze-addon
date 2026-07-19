package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives BetterChams' Opacity slider real, continuous control over the ghost alpha
 * applied to chammed players -- see MixinAvatarRenderer's isInvisible comment for the
 * full mechanism. LivingEntityRenderer.submit(S,...) computes, right before its
 * submitModel call: `ARGB.multiply(translucent ? 0x26FFFFFF : -1, getModelTint(state))`
 * (verified via javap bytecode against minecraft-merged-043a8b3edf-26.1.2.jar --
 * 654311423 == 0x26FFFFFF). 0x26FFFFFF is vanilla's own hardcoded ~15% ghost alpha;
 * this redirects that ONE constant to the slider's value, leaving the opaque (-1)
 * branch and every other ARGB.multiply call in the game untouched.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRendererGhost {

    @Redirect(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;multiply(II)I")
    )
    private int betterchams$ghostTint(int ghostOrOpaque, int modelTint) {
        BetterChams bc = BetterChams.INSTANCE;
        // Only the vanilla ghost branch (0x26FFFFFF) is ours to touch -- the opaque
        // (-1) branch must stay untouched for every non-chammed entity (villagers,
        // mobs, other addons' invisible-entity tricks, etc).
        //
        // Deliberately NOT gated on ers.outlineColor == ENTITY_OUTLINE_COLOR anymore:
        // Boze-marked friends (`.friends add`) never carry our outlineColor marker by
        // the time this redirect fires (Boze's own friend handling appears to run
        // after/instead of ours), so that check silently excluded friends from Opacity
        // even though MixinAvatarRenderer already sets state.isInvisible=true for them
        // unconditionally (user report 2026-07-15). The OLD isInvisible-based ghost
        // never depended on outlineColor either -- this restores that same
        // independence instead of re-deriving Boze's friend logic.
        if (ghostOrOpaque == 0x26FFFFFF && bc.getState() && bc.opacity.getValue() < 0.999) {
            int alpha = Math.round((float) (bc.opacity.getValue() * 255.0)) & 0xFF;
            return ARGB.multiply((alpha << 24) | 0xFFFFFF, modelTint);
        }
        return ARGB.multiply(ghostOrOpaque, modelTint);
    }
}
