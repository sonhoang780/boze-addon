package com.example.addon.mixin;

import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces createFromService()'s deny-list AddressCheck with an allow-all one,
 * so the client never blocks any server address (bypasses the blocked-server list).
 */
@Mixin(AddressCheck.class)
public interface MixinAddressCheck {

    @Inject(method = "createFromService", at = @At("HEAD"), cancellable = true)
    private static void exampleAddon$allowAll(CallbackInfoReturnable<AddressCheck> cir) {
        cir.setReturnValue(new AddressCheck() {
            @Override public boolean isAllowed(ResolvedServerAddress address) { return true; }
            @Override public boolean isAllowed(ServerAddress address) { return true; }
        });
    }
}
