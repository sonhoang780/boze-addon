package com.example.addon.mixin;

import com.example.addon.modules.InfiniteChat;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Redirects the removeLast() call ChatComponent#addMessageToQueue makes once allMessages
 * exceeds MAX_CHAT_HISTORY (100, verified via javap on the 26.1.2 merged jar -- the constant
 * is inlined at compile time so the field itself can't be patched). While InfiniteChat is
 * enabled the removal is skipped, so allMessages keeps growing instead of dropping old lines.
 */
@Mixin(ChatComponent.class)
public abstract class MixinChatComponentInfiniteChat {

    @Redirect(
        method = "addMessageToQueue",
        at = @At(value = "INVOKE", target = "Ljava/util/List;removeLast()Ljava/lang/Object;")
    )
    private Object infiniteChat$keepHistory(List<?> allMessages) {
        if (InfiniteChat.INSTANCE.getState()) {
            return null;
        }
        return allMessages.removeLast();
    }
}
