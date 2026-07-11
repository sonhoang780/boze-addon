package com.example.addon.mixin;

import com.example.addon.modules.PathFinder;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reflection into Baritone's internal ElytraProcess#state enum (PathFinder's original
 * landing-detect approach) proved unreliable in practice -- debug logging showed it
 * never actually observed a LANDING transition before Baritone's own chat message
 * printed, for reasons not fully pinned down (see PathFinder.java's TEMP DEBUG code).
 * Baritone itself reliably prints "Path complete, picking a nearby safe landing
 * spot..." via a plain client-side system message (Player#sendSystemMessage) the
 * instant it commits to landing -- routes through ChatComponent#addClientSystemMessage,
 * not a server packet, so this has to hook the chat component directly rather than
 * EventPacket.Receive. Hardcoded string match per explicit user request: simpler and
 * more robust than continuing to chase the reflection approach.
 */
@Mixin(ChatComponent.class)
public abstract class MixinChatComponentLandingDetect {

    @Inject(method = "addClientSystemMessage", at = @At("HEAD"))
    private void exampleAddon$detectBaritoneLanding(Component message, CallbackInfo ci) {
        if (!PathFinder.INSTANCE.getState()) return;
        if (message.getString().contains("Path complete, picking a nearby safe landing spot")) {
            PathFinder.INSTANCE.setState(false);
        }
    }
}
