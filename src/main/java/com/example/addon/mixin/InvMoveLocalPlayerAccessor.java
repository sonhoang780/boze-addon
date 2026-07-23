package com.example.addon.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes LocalPlayer's private lastSentInput field so InvMovePlus can correct the client's
 * own delta-encoding bookkeeping after manually sending a spoofed ServerboundPlayerInputPacket
 * (see InvMovePlus.spoofStationaryInput) -- without this, the next real (unchanged) input tick
 * would wrongly think nothing changed and skip resending the true state.
 */
@Mixin(LocalPlayer.class)
public interface InvMoveLocalPlayerAccessor {
    @Accessor("lastSentInput")
    void invMove$setLastSentInput(Input input);
}
