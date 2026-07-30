package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(ShaderManager.class)
public class MixinShaderManager {

    /**
     * Intercepts the retrieval of the 'minecraft:entity_outline' postchain.
     * When BetterChams is active, we return null here so that the LevelRenderer
     * does NOT add the postchain to its FrameGraph. This keeps entity outlines
     * (world entities AND hand, since they share the same entityOutlineTarget)
     * completely raw (unresolved) at this point.
     * `MixinGameRenderer#betterchams$reprocessHandOutline` later manually runs
     * the real postchain ONCE on the combined raw outlines. This must fire
     * regardless of handToggle -- it resolves world entities (crystal/player/
     * self) too, not just hand, so gating it on the hand-specific toggle left
     * world entities unresolved (raw white) whenever hand chams was disabled.
     */
    @Inject(method = "getPostChain", at = @At("HEAD"), cancellable = true)
    private void betterchams$interceptPostChain(Identifier identifier, Set<Identifier> set, CallbackInfoReturnable<PostChain> cir) {
        if (identifier.equals(Identifier.fromNamespaceAndPath("minecraft", "entity_outline"))) {
            // No "is anything active" sub-check here: FillMode.Off is a real ACTIVE
            // flat-fill mode now (see BetterChams.writeMainParams's comment), not "fill
            // disabled" -- there's no config where getState() is true and truly nothing
            // renders, so the old glow/flare/outline/fillMode/opacity check let vanilla's
            // raw entity_outline chain run whenever only flat fill was active, painting
            // an unresolved near-white silhouette instead of the addon's own fill color.
            if (BetterChams.INSTANCE.getState()) {
                // Return null to prevent the vanilla entity_outline from running in the FrameGraph
                cir.setReturnValue(null);
            }
        }
    }
}
