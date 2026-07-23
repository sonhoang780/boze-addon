package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import com.example.addon.modules.KillEffect;
import com.example.addon.render.GelParticleSystem;
import com.example.addon.render.GelUuidCarrier;
import com.example.addon.render.KillEffectParticleSystem;
import com.example.addon.render.ModelCubeCapture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * renderModel is where model.setupAnim(state) + the actual per-cube walk happen
 * (verified via javap/bytecode against SubmitNodeStorage.ModelSubmit's flush path --
 * LivingEntityRenderer.submit only stores a ModelSubmit record; this private method,
 * called later during ModelFeatureRenderer's solid/translucent render pass, is what
 * actually poses and draws it). TAIL is deliberately read-only/non-invasive: by the
 * time it fires, submit.model()'s ModelPart tree (head/body/arms/legs, all public
 * fields on HumanoidModel) is posed exactly as just rendered for this entity/frame.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class MixinModelFeatureRenderer {

    @Inject(method = "renderModel", at = @At("TAIL"))
    private void exampleAddon$gelParticles(
        SubmitNodeStorage.ModelSubmit submit,
        RenderType type,
        VertexConsumer consumer,
        OutlineBufferSource outlineBufferSource,
        MultiBufferSource.BufferSource bufferSource,
        CallbackInfo ci
    ) {
        if (!BetterChams.INSTANCE.getState() || BetterChams.INSTANCE.outlineMode.getValue() != BetterChams.OutlineMode.Complex) return;

        // REVERTED (2026-07-16): a "type.toString().contains(\"outline\")" filter was
        // added here on the theory that vanilla's dedicated outline-buffer pass was
        // the source of the crystal's flat-Y ghost cage. It also matched the pass this
        // addon actually needs -- Complex mode's wireframe cage stopped rendering
        // entirely for BOTH players and crystals (user screenshot, 2026-07-16). The
        // ghost-cage bug is still open; needs a different, verified distinguishing
        // signal before touching this again -- do not reintroduce a type-name guess
        // without confirming the actual RenderType identifier against real source.

        Object modelObj = submit.model();
        boolean isHumanoid = modelObj instanceof HumanoidModel<?>;
        boolean isCrystal = modelObj instanceof net.minecraft.client.model.object.crystal.EndCrystalModel;
        // Bail BEFORE touching submit.state(): renderModel also fires for GUI item
        // rendering (GuiItemAtlas.drawToSlot), where submit.state() is a Float, not an
        // EntityRenderState -- casting it to GelUuidCarrier there crashed (ClassCast,
        // Float -> GelUuidCarrier, 2026-07-15). Only our two model types carry the
        // stashed UUID; everything else returns here.
        if (!isHumanoid && !isCrystal) return;
        if (!(submit.state() instanceof GelUuidCarrier carrier)) return;
        UUID id = carrier.exampleAddon$getGelUuid();
        if (id == null) return;

        // submit.model() is posed exactly as just rendered this frame -- build the
        // camera-relative base pose the same way for player and crystal.
        PoseStack basePose = new PoseStack();
        basePose.mulPose(submit.pose().pose());

        // Physics/bounds/geometry capture only -- NOT rendering. An earlier version
        // also drew here via bufferSource.getBuffer(...), which crashed ("Not
        // building!") pulling shared debug RenderTypes from this nested pipeline stage;
        // see GelParticleSystem's class doc. Rendering now happens from a separate,
        // proven-safe LevelRenderEvents hook instead.
        if (isHumanoid) {
            GelParticleSystem.INSTANCE.update(id, (HumanoidModel<?>) modelObj, basePose);
        } else {
            GelParticleSystem.INSTANCE.updateCrystal(id, (net.minecraft.client.model.object.crystal.EndCrystalModel) modelObj, basePose);
        }
    }

    /**
     * KillEffect one-shot capture. Separate from the Gel inject above (which is gated on
     * BetterChams + Complex): this fires whenever KillEffect owes a dying player a cube
     * capture, independent of BetterChams. submit.model() is posed exactly as rendered
     * this frame, so this is the moment to snapshot the body's shape and spawn the burst.
     * Players only (HumanoidModel); markCaptured clears the pending flag so it fires once.
     */
    @Inject(method = "renderModel", at = @At("TAIL"))
    private void exampleAddon$killEffectCapture(
        SubmitNodeStorage.ModelSubmit submit,
        RenderType type,
        VertexConsumer consumer,
        OutlineBufferSource outlineBufferSource,
        MultiBufferSource.BufferSource bufferSource,
        CallbackInfo ci
    ) {
        KillEffect ke = KillEffect.INSTANCE;
        if (!ke.getState()) return;
        Object modelObj = submit.model();
        if (!(modelObj instanceof HumanoidModel<?> humanoid)) return;
        if (!(submit.state() instanceof GelUuidCarrier carrier)) return;
        UUID id = carrier.exampleAddon$getGelUuid();
        if (id == null || !ke.needsCapture(id)) return;

        PoseStack basePose = new PoseStack();
        basePose.mulPose(submit.pose().pose());

        var cubes = ModelCubeCapture.captureHumanoid(humanoid, basePose, null);
        if (cubes.isEmpty()) return; // not posed usefully yet -- keep the flag, retry next frame

        net.minecraft.client.Camera camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        var camPosD = camera.position();
        org.joml.Vector3f camPos = new org.joml.Vector3f((float) camPosD.x, (float) camPosD.y, (float) camPosD.z);

        KillEffectParticleSystem.INSTANCE.spawn(
            cubes, camPos,
            ke.glowColor.getValue().color,
            ke.getParticleCount(), ke.getExplodeSpeed(), ke.getDuration()
        );
        ke.markCaptured(id);
    }

    /**
     * Crystal opacity. Unlike the player (LivingEntityRenderer's ghost-translucent
     * submit branch, redirected in MixinLivingEntityRendererGhost), EndCrystalRenderer
     * extends plain EntityRenderer -- no isInvisible/ghost mechanic exists for it, and
     * its glass model is ALREADY translucent (real alpha blending is active), so no
     * RenderType swap is needed at all. The tint feeding every Model.renderToBuffer
     * call is submit.tintedColor() (verified via javap on ModelFeatureRenderer.
     * renderModel's bytecode, minecraft-merged-1c9175fa40-26.1.2.jar) -- redirecting
     * its return value directly controls the crystal's real alpha. Independent of
     * Complex/Simple outline mode (same as player Opacity), so this is a SEPARATE
     * redirect, not folded into the Complex-gated inject above. Gated on the
     * GelUuidCarrier marker MixinEndCrystalRenderer stashes (range/toggle already
     * checked there) rather than re-deriving isInRange here without an entity ref.
     */
    @Redirect(
        method = "renderModel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeStorage$ModelSubmit;tintedColor()I")
    )
    private int exampleAddon$crystalOpacity(SubmitNodeStorage.ModelSubmit<?> submit) {
        int base = submit.tintedColor();
        if (!(submit.model() instanceof net.minecraft.client.model.object.crystal.EndCrystalModel)) return base;
        BetterChams bc = BetterChams.INSTANCE;
        if (!bc.getState() || bc.opacity.getValue() >= 0.999) return base;
        if (!(submit.state() instanceof GelUuidCarrier carrier) || carrier.exampleAddon$getGelUuid() == null) return base;
        int alpha = Math.round((float) (bc.opacity.getValue() * 255.0)) & 0xFF;
        return (alpha << 24) | (base & 0xFFFFFF);
    }
}
