package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.example.addon.modules.KillEffect;
import com.example.addon.render.GelUuidCarrier;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Gives BetterChams' Opacity slider real, continuous control over the ghost alpha
 * applied to chammed players -- see MixinAvatarRenderer's isInvisible comment for the
 * full mechanism. LivingEntityRenderer.submit(S,...) computes, right before its
 * submitModel call: `ARGB.multiply(translucent ? 0x26FFFFFF : -1, getModelTint(state))`
 * (verified via javap bytecode against minecraft-merged-043a8b3edf-26.1.2.jar --
 * 654311423 == 0x26FFFFFF). 0x26FFFFFF is vanilla's own hardcoded ~15% ghost alpha;
 * this redirects that ONE constant to the slider's value, leaving the opaque (-1)
 * branch and every other ARGB.multiply call in the game untouched.
 *
 * currentSubmitId is captured via a plain @Inject HEAD (natural method params, always
 * reliably bound) instead of adding extra captured-local params directly to the
 * @Redirect handler -- that "surrogate extra params" technique silently mis-binds on
 * some Mixin/ASM combinations, which was reproduced here: KillEffect's dying-entity
 * branch below never matched (id came back null), so every death silently fell through
 * to vanilla's flat ~15% ghost alpha applied INSTANTLY instead of a gradual fade --
 * looked exactly like "the body just vanishes", not a fade bug in the math itself.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class MixinLivingEntityRendererGhost {

    private static UUID currentSubmitId;

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At("HEAD")
    )
    private void exampleAddon$captureSubmitId(LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camState, CallbackInfo ci) {
        currentSubmitId = state instanceof GelUuidCarrier carrier ? carrier.exampleAddon$getGelUuid() : null;
        if (currentSubmitId != null && KillEffect.INSTANCE.isExploding(currentSubmitId) && KillEffect.INSTANCE.debug.getValue()) {
            dev.boze.api.utility.ChatHelper.sendMsg("KillEffect", "§dsubmit() HEAD: currentSubmitId=" + currentSubmitId + " isInvisible=" + state.isInvisible);
        }
    }

    @Redirect(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;multiply(II)I")
    )
    private int betterchams$ghostTint(int ghostOrOpaque, int modelTint) {
        BetterChams bc = BetterChams.INSTANCE;
        // Only the vanilla ghost branch (0x26FFFFFF) is ours to touch -- the opaque
        // (-1) branch must stay untouched for every non-chammed entity (villagers,
        // mobs, other addons' invisible-entity tricks, etc).
        if (ghostOrOpaque != 0x26FFFFFF) {
            if (currentSubmitId != null && KillEffect.INSTANCE.isExploding(currentSubmitId) && KillEffect.INSTANCE.debug.getValue()) {
                dev.boze.api.utility.ChatHelper.sendMsg("KillEffect", "§credirect: ghostOrOpaque=" + Integer.toHexString(ghostOrOpaque) + " (NOT 0x26FFFFFF, opaque branch taken) id=" + currentSubmitId);
            }
            return ARGB.multiply(ghostOrOpaque, modelTint);
        }

        // KillEffect's dying-player fade takes priority when active for this entity --
        // its own continuous 1->0 alpha over Duration, not BetterChams' static slider.
        UUID id = currentSubmitId;
        if (id != null && KillEffect.INSTANCE.isExploding(id)) {
            float fadeAlpha = KillEffect.INSTANCE.getFadeAlpha(id);
            int alpha = Math.round(fadeAlpha * 255.0f) & 0xFF;
            if (KillEffect.INSTANCE.debug.getValue()) {
                dev.boze.api.utility.ChatHelper.sendMsg("KillEffect", "§aredirect: ghost branch matched, id=" + id + " fadeAlpha=" + fadeAlpha + " alpha=" + alpha);
            }
            return ARGB.multiply((alpha << 24) | 0xFFFFFF, modelTint);
        }

        // Deliberately NOT gated on ers.outlineColor == ENTITY_OUTLINE_COLOR anymore:
        // Boze-marked friends (`.friends add`) never carry our outlineColor marker by
        // the time this redirect fires (Boze's own friend handling appears to run
        // after/instead of ours), so that check silently excluded friends from Opacity
        // even though MixinAvatarRenderer already sets state.isInvisible=true for them
        // unconditionally (user report 2026-07-15). The OLD isInvisible-based ghost
        // never depended on outlineColor either -- this restores that same
        // independence instead of re-deriving Boze's friend logic.
        if (bc.getState() && bc.opacity.getValue() < 0.999) {
            int alpha = Math.round((float) (bc.opacity.getValue() * 255.0)) & 0xFF;
            return ARGB.multiply((alpha << 24) | 0xFFFFFF, modelTint);
        }
        return ARGB.multiply(ghostOrOpaque, modelTint);
    }
}
