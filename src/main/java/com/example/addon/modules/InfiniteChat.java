package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;

/**
 * Vanilla ChatComponent caps allMessages at 100 entries (MAX_CHAT_HISTORY, addMessageToQueue
 * calls removeLast() once size > 100 -- verified via javap on the 26.1.2 merged jar, the
 * constant is compile-time inlined so it can't be patched by reflection). This module flips
 * a flag that MixinChatComponentInfiniteChat reads to skip that removeLast() call, so old
 * chat lines stay scrollable instead of being discarded.
 */
public class InfiniteChat extends AddonModule {
    public static final InfiniteChat INSTANCE = new InfiniteChat();

    public InfiniteChat() {
        super("InfiniteChat", "Keeps unlimited chat history instead of the vanilla 100-message cap.");
    }
}
