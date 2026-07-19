package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hand-held item opacity. ItemStackRenderState$ItemSubmit.submit() has no alpha
 * param at all (verified: only outlineColor + per-tintIndex color layers), so the
 * arm-opacity redirect (MixinAvatarRenderer) never touched the item mesh itself --
 * the arm faded, the item stayed fully opaque (user report, 2026-07-16: "Opacity
 * không hoạt động khi tay cầm items").
 *
 * Real injection points found via javap on ItemFeatureRenderer.renderItem (26.1.2):
 * quadInstance.setColor(getLayerColorSafe(tintLayers, materialInfo)) is the exact
 * same per-quad tint mechanism as ModelSubmit.tintedColor() used for the arm/crystal.
 * But same blocker as the crystal case: a quad's own itemRenderType (baked in,
 * MaterialInfo.itemRenderType()) must actually support blending or the alpha byte is
 * ignored by the GPU. Items route through one of Sheets' 3 fixed cutout/translucent
 * pairs (cutoutItemSheet/cutoutBlockItemSheet + their translucent counterparts,
 * verified via javap on Sheets.class) -- swapping to the matching translucent sheet
 * (mirrors the isInvisible/entityTranslucent trick already used for the player body
 * and the RenderTypes.entityTranslucent force used for the crystal) plus forcing
 * hasTranslucency() true so the ItemSubmit is dispatched through the translucent
 * pass (matching how vanilla already handles genuinely-translucent items) makes the
 * alpha byte actually blend.
 *
 * getLayerColorSafe/hasTranslucency are both private static -- reimplemented here
 * from their (trivial, fully public-API) bytecode bodies rather than needing an
 * accessor mixin, since @Redirect fully replaces the call site.
 */
@Mixin(ItemFeatureRenderer.class)
public abstract class MixinItemFeatureRenderer {

    private static boolean exampleAddon$handOpacityActive() {
        BetterChams bc = BetterChams.INSTANCE;
        return BetterChams.isRenderingHand && bc.getState() && bc.handToggle.getValue() && bc.opacity.getValue() < 0.999;
    }

    @Redirect(
        method = {
            "renderSolid(Lnet/minecraft/client/renderer/SubmitNodeCollection;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/OutlineBufferSource;)V",
            "renderTranslucent(Lnet/minecraft/client/renderer/SubmitNodeCollection;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/OutlineBufferSource;)V"
        },
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;hasTranslucency(Lnet/minecraft/client/renderer/SubmitNodeStorage$ItemSubmit;)Z")
    )
    private static boolean exampleAddon$forceTranslucent(SubmitNodeStorage.ItemSubmit submit) {
        if (exampleAddon$handOpacityActive()) return true;
        for (BakedQuad quad : submit.quads()) {
            if (quad.materialInfo().itemRenderType().hasBlending()) return true;
        }
        return false;
    }

    @Redirect(
        method = "renderItem(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/OutlineBufferSource;Lnet/minecraft/client/renderer/SubmitNodeStorage$ItemSubmit;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;itemRenderType()Lnet/minecraft/client/renderer/rendertype/RenderType;")
    )
    private RenderType exampleAddon$swapItemRenderType(BakedQuad.MaterialInfo info) {
        RenderType base = info.itemRenderType();
        if (!exampleAddon$handOpacityActive()) return base;
        if (base == Sheets.cutoutItemSheet()) return Sheets.translucentItemSheet();
        if (base == Sheets.cutoutBlockItemSheet()) return Sheets.translucentBlockItemSheet();
        return base;
    }

    @Redirect(
        method = "renderItem(Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/OutlineBufferSource;Lnet/minecraft/client/renderer/SubmitNodeStorage$ItemSubmit;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;getLayerColorSafe([ILnet/minecraft/client/resources/model/geometry/BakedQuad$MaterialInfo;)I")
    )
    private static int exampleAddon$applyHandOpacity(int[] tintLayers, BakedQuad.MaterialInfo info) {
        int base = info.isTinted() ? exampleAddon$safeLayer(tintLayers, info.tintIndex()) : -1;
        if (!exampleAddon$handOpacityActive()) return base;
        int alpha = Math.round((float) (BetterChams.INSTANCE.opacity.getValue() * 255.0)) & 0xFF;
        return (alpha << 24) | (base & 0xFFFFFF);
    }

    private static int exampleAddon$safeLayer(int[] layers, int idx) {
        if (idx < 0 || idx >= layers.length) return -1;
        return layers[idx];
    }
}
