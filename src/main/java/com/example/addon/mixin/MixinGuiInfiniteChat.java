package com.example.addon.mixin;

import com.example.addon.modules.InfiniteChat;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gui#onDisconnected() calls chat.clearMessages(true), which wipes allMessages, trimmedMessages,
 * the deletion queue and (because the flag is true) recentChat -- that, not the 100-message cap,
 * is why history vanished when switching servers (verified via javap on the 26.1.2 merged jar:
 * these are the only three classes that touch clearMessages, and this is its only disconnect-path
 * caller). Skipped while InfiniteChat is enabled.
 * <p>
 * Scoped to this call site on purpose: KeyboardHandler's F3+D clear-chat hotkey routes through
 * the same ChatComponent method and must keep working, since that one is a deliberate user action.
 */
@Mixin(Gui.class)
public abstract class MixinGuiInfiniteChat {

    @Redirect(
        method = "onDisconnected",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;clearMessages(Z)V")
    )
    private void infiniteChat$keepHistoryAcrossServers(ChatComponent chat, boolean clearHistory) {
        if (InfiniteChat.INSTANCE.getState()) return;
        chat.clearMessages(clearHistory);
    }
}
