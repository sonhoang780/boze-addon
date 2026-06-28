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
     * does NOT add the postchain to its FrameGraph. This keeps the world entities'
     * outlines completely raw (un-bloomed) in the entityOutlineTarget.
     * Later, we will draw the Hand outline into the same target, and manually
     * run the postchain ONCE on the combined raw outlines.
     */
    @Inject(method = "getPostChain", at = @At("HEAD"), cancellable = true)
    private void betterchams$interceptPostChain(Identifier identifier, Set<Identifier> set, CallbackInfoReturnable<PostChain> cir) {
        if (identifier.equals(Identifier.fromNamespaceAndPath("minecraft", "entity_outline"))) {
            if (BetterChams.INSTANCE.getState() && BetterChams.INSTANCE.handToggle.getValue()) {
                if (BetterChams.INSTANCE.bloomToggle.getValue() || BetterChams.INSTANCE.fillMode.getValue() != BetterChams.FillMode.Off) {
                    // Return null to prevent the vanilla entity_outline from running in the FrameGraph
                    cir.setReturnValue(null);
                }
            }
        }
    }
}
