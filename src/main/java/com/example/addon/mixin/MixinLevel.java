package com.example.addon.mixin;

import com.example.addon.modules.chestscan.ChestScan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Instantly drops a tracked ChestScan chest from the store the moment its block changes
 * to something else (broken by anyone, or the block ID otherwise changes) -- reacting to
 * the real block-update packet instead of waiting for a render-tick to notice via
 * getBlockState. Complements the reconnect-safety fix in ChestScan#onWorldRender (which
 * only prunes once the chunk has genuinely (re)loaded) -- this fires the moment the
 * client actually learns the block changed, chunk-load timing doesn't matter here since
 * a setBlock call only happens for a position whose chunk is already loaded.
 */
@Mixin(Level.class)
public abstract class MixinLevel {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), require = 0)
    private void chestscan$onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                       CallbackInfoReturnable<Boolean> cir) {
        var store = ChestScan.INSTANCE.getStore();
        if (store.get(pos) == null) return;
        if (!(state.getBlock() instanceof ChestBlock)) {
            store.remove(pos);
        }
    }
}
