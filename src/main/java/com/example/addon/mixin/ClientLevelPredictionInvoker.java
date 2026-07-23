package com.example.addon.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ClientLevel#getBlockStatePredictionHandler() is package-private -- exposes it so a fake
 * ServerboundUseItemPacket (NoSlow's Grim/GrimNew modes) can mint a sequence id from the SAME
 * counter MultiPlayerGameMode's real useItem()/destroyBlock() use. Grim's BadPacketsH
 * (grim.badpackets.unexpected_sequence) requires every use-item/block-place/block-break
 * sequence to be exactly lastSequence+1 in one shared stream -- an independent local counter
 * would desync from it and flag immediately.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelPredictionInvoker {
    @Invoker("getBlockStatePredictionHandler")
    BlockStatePredictionHandler invokeGetBlockStatePredictionHandler();
}
