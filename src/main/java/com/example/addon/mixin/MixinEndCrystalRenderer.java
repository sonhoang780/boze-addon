package com.example.addon.mixin;

import com.example.addon.modules.BetterChams;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
        if (bc.getState() && bc.crystalToggle.getValue() && bc.isInRange(crystal)) {
            state.outlineColor = 0xFFFFFFFF;
        }
    }
}
