package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.example.addon.render.GelUuidCarrier;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndCrystalRenderer.class)
public abstract class MixinEndCrystalRenderer {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;F)V",
        at = @At("RETURN")
    )
    private void betterchamss$setOutlineColor(
        EndCrystal crystal,
        EndCrystalRenderState state,
        float tickDelta,
        CallbackInfo ci
    ) {
        BetterChams bc = BetterChams.INSTANCE;
        if (!bc.getState() || !bc.crystalToggle.getValue() || !bc.isInRange(crystal)) return;
        // No "everything off, skip" check here: FillMode.Off is a real ACTIVE flat-fill
        // mode (see BetterChams.writeMainParams's comment), not "fill disabled" -- there
        // is no config where getState()+crystalToggle is true and truly nothing renders,
        // so skipping on glow/flare/outline/opacity alone silently dropped the flat fill.
        bc.reportGlowDistance(crystal);
        // CRYSTAL_OUTLINE_COLOR (not ENTITY_OUTLINE_COLOR) -- distinct sub-marker so
        // glow_resolve.fsh can tell Crystal (4 InnerGlow layers) apart from Player
        // (8 layers) in Complex outline mode. See BetterChams.CRYSTAL_OUTLINE_COLOR.
        state.outlineColor = BetterChams.CRYSTAL_OUTLINE_COLOR;
        BetterChams.silhouetteThisFrame = true;

        // Stash the crystal UUID so MixinModelFeatureRenderer can key the captured
        // wireframe cubes to this crystal at flush time (same mechanism as the player,
        // see MixinAvatarRenderer). The actual cube capture happens there, where the
        // model is posed -- not here, where it isn't yet.
        ((com.example.addon.render.GelUuidCarrier) (Object) state).exampleAddon$setGelUuid(crystal.getUUID());
    }

    // EndCrystalModel has no renderType() override -> inherits EntityModel's default,
    // RenderTypes.entityCutout(id) (alpha-TEST, no GL_BLEND). The alpha byte the
    // tintedColor() redirect (MixinModelFeatureRenderer#exampleAddon$crystalOpacity)
    // writes is therefore silently discarded by the pipeline -- opacity had zero visual
    // effect no matter what alpha we computed (2026-07-16 root cause, found via javap on
    // EntityModel's ctor lambda -> RenderTypes.entityCutout). Force translucent RenderType
    // here so that redirect's alpha actually blends, mirroring the isInvisible trick
    // already used to get the Player body onto its translucent branch.
    @Redirect(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/EndCrystalRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/resources/Identifier;IIILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V")
    )
    private <S> void exampleAddon$crystalTranslucentForOpacity(
        SubmitNodeCollector collector,
        Model<? super S> model,
        S state,
        PoseStack poseStack,
        Identifier id,
        int light,
        int overlay,
        int outlineColor,
        ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
    ) {
        BetterChams bc = BetterChams.INSTANCE;
        if (bc.getState() && bc.opacity.getValue() < 0.999
                && state instanceof GelUuidCarrier carrier && carrier.exampleAddon$getGelUuid() != null) {
            collector.submitModel(model, state, poseStack, RenderTypes.entityTranslucent(id), light, overlay, -1, null, outlineColor, crumblingOverlay);
            return;
        }
        collector.submitModel(model, state, poseStack, id, light, overlay, outlineColor, crumblingOverlay);
    }
}
