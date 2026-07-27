package com.example.addon.mixin;

import com.example.addon.modules.AutoAccept;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds AutoAccept every plugin/system chat line (tpa request notices route through here, not
 * addPlayerMessage -- see AutoAccept's doc).
 */
@Mixin(ChatComponent.class)
public abstract class MixinChatComponentAutoAccept {

    @Inject(method = "addServerSystemMessage", at = @At("HEAD"))
    private void exampleAddon$autoAccept(Component message, CallbackInfo ci) {
        AutoAccept.INSTANCE.onServerMessage(message.getString());
    }
}
