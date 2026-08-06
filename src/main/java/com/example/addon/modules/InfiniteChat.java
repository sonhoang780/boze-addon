package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.event.EventTick;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Vanilla ChatComponent caps allMessages at 100 entries (MAX_CHAT_HISTORY, addMessageToQueue
 * calls removeLast() once size > 100 -- verified via javap on the 26.1.2 merged jar, the
 * constant is compile-time inlined so it can't be patched by reflection). This module flips
 * a flag that MixinChatComponentInfiniteChat reads to skip that removeLast() call, so old
 * chat lines stay scrollable instead of being discarded.
 * <p>
 * MixinGuiInfiniteChat additionally suppresses the clearMessages() wipe Gui#onDisconnected does,
 * so history also survives switching servers.
 */
public class InfiniteChat extends AddonModule {
    public static final InfiniteChat INSTANCE = new InfiniteChat();

    public final ToggleOption spam = new ToggleOption(this, "Spam",
        "Test-only: floods local chat with client-side system messages (never sent to the server) to push past the 100-line cap.", false);

    private int spamCount;

    public InfiniteChat() {
        super("InfiniteChat", "Keeps unlimited chat history instead of the vanilla 100-message cap.");
    }

    @Override
    public void onDisable() {
        spamCount = 0;
    }

    @EventHandler
    private void onTick(EventTick.Post event) {
        if (!spam.getValue()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Client-only (Player#sendSystemMessage), same path Notification uses -- never hits the server.
        for (int i = 0; i < 5; i++) {
            mc.player.sendSystemMessage(Component.literal("[InfiniteChat spam] #" + (++spamCount)));
        }
    }
}
