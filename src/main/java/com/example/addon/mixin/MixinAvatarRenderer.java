package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.example.addon.modules.KillEffect;
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

        // No "everything off, skip" check here: FillMode.Off is a real ACTIVE flat-fill
        // mode (see BetterChams.writeMainParams's comment), not "fill disabled" -- there
        // is no config where getState()+selfToggle/playerToggle is true and truly
        // nothing renders, so skipping on glow/flare/outline/opacity alone silently
        // dropped the flat fill (and Opacity) whenever every other effect was off.

        bc.reportGlowDistance(player);
        state.outlineColor = BetterChams.ENTITY_OUTLINE_COLOR;
        BetterChams.silhouetteThisFrame = true;
        // Stash the real UUID onto the render state -- states carry no entity back-ref
        // by design, but GelParticleSystem needs a stable per-player key at flush time
        // (ModelFeatureRenderer.renderModel, see MixinModelFeatureRenderer) to keep a
        // particle's Brownian history continuous across frames instead of respawning
        // it from scratch every frame.
        ((com.example.addon.render.GelUuidCarrier) (Object) state).exampleAddon$setGelUuid(player.getUUID());

        // Opacity < 1: the fill overlay alone can't make the body see-through -- the
        // real model still renders fully opaque underneath it (user report
        // 2026-07-13: "texture của nhân vật còn nguyên"). Setting isInvisible (while
        // isInvisibleToPlayer stays false) routes LivingEntityRenderer.submit into its
        // ghost-translucent branch (translucent RenderType + a tint constant --
        // verified in submit()'s bytecode: `iload translucent ? 654311423 : -1`, i.e.
        // 0x26FFFFFF, fed through ARGB.multiply with the model's own tint right before
        // submitModel). submitModel STILL runs, so the silhouette/outline/JFA pipeline
        // keeps working. MixinLivingEntityRendererGhost redirects that ARGB.multiply
        // call to substitute the Opacity slider's alpha for vanilla's fixed 0x26,
        // giving a real continuous slider instead of the old hardcoded ~15%.
        if (bc.opacity.getValue() < 0.999) {
            state.isInvisible = true;
        }
    }

    /**
     * KillEffect: hide the dying player's real model (routing submit into the
     * ghost-translucent branch, same mechanism BetterChams uses for Opacity) and stash
     * the UUID so MixinModelFeatureRenderer can capture the posed cubes for the burst.
     * Deliberately SEPARATE from betterchamss$setOutlineColor: it must run whether or
     * not BetterChams is enabled, and shares the same GelUuidCarrier slot (same player
     * UUID either way, so no conflict if both fire).
     */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("RETURN")
    )
    private void exampleAddon$killEffect(
        Avatar entity,
        AvatarRenderState state,
        float tickDelta,
        CallbackInfo ci
    ) {
        if (!(entity instanceof AbstractClientPlayer player)) return;
        if (!KillEffect.INSTANCE.isExploding(player.getUUID())) return;
        ((com.example.addon.render.GelUuidCarrier) (Object) state).exampleAddon$setGelUuid(player.getUUID());
        state.isInvisible = true;
        if (KillEffect.INSTANCE.debug.getValue()) dev.boze.api.utility.ChatHelper.sendMsg("KillEffect",
                "§bextractRenderState(player): isInvisible=true set, id=" + player.getUUID());
        // LivingEntityRenderer.extractRenderState (super, already ran) set these from the
        // real entity's still-incrementing deathTime -- state.deathTime > 0 drives the
        // fall-over topple angle (LivingEntityRenderer.setupRotations) and hasRedOverlay
        // stays true for the WHOLE death animation, not just a brief hit-flash. Freezing
        // both is what keeps the body standing in its last pose while it fades instead of
        // toppling over red the whole time.
        state.deathTime = 0f;
        state.hasRedOverlay = false;
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
        int tint = -1; // opaque white -- vanilla default, unchanged unless Opacity applies below
        BetterChams bc = BetterChams.INSTANCE;
        if (BetterChams.isRenderingHand && bc.getState() && bc.handToggle.getValue()) {
            outlineColor = BetterChams.HAND_OUTLINE_COLOR;
            BetterChams.silhouetteThisFrame = true;
            // renderHand's own RenderType is RenderTypes.entityTranslucent(texture)
            // (verified via javap on AvatarRenderer.renderHand's bytecode,
            // minecraft-merged-1c9175fa40-26.1.2.jar) -- already alpha-blending, so
            // unlike the player body (which needs isInvisible to force vanilla's
            // ghost-translucent branch) the hand needs NO RenderType swap at all, just
            // the tint's alpha byte reduced directly.
            if (bc.opacity.getValue() < 0.999) {
                int alpha = Math.round((float) (bc.opacity.getValue() * 255.0)) & 0xFF;
                tint = (alpha << 24) | 0xFFFFFF;
            }
        }
        collector.submitModelPart(part, poseStack, renderType, light, overlay, sprite, false, false, tint, null, outlineColor);
    }
}
