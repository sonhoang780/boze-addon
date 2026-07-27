package com.example.addon.mixin;

import com.example.addon.modules.InfiniteChat;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * addMessageToQueue's real bytecode (verified via javap -c on the 26.1.2 merged jar) is:
 * {@code allMessages.addFirst(msg); while (allMessages.size() > 100) allMessages.removeLast();}
 * -- a PREVIOUS version of this mixin redirected removeLast() to a no-op while InfiniteChat was
 * enabled. That turned the trim loop into an INFINITE loop the instant allMessages passed 100
 * entries: the no-op never shrinks the list, so `size() > 100` never becomes false and the
 * `while` never exits -- confirmed via jstack on a real hang (2026-07-27): the render thread was
 * stuck inside this exact call for 2,074,359ms of CPU time, one single addMessageToQueue
 * invocation that never returned. Every chat/system message sent after the 100th while
 * InfiniteChat was on hit this, not just HoleSnap's diagnostic dump that happened to trigger it
 * that session.
 * <p>
 * Fixed by redirecting size() instead of removeLast(): reporting 0 makes the loop condition
 * false immediately (0 iterations, guaranteed to terminate) while allMessages itself is
 * untouched -- same "keep every line" result, no infinite loop possible.
 */
@Mixin(ChatComponent.class)
public abstract class MixinChatComponentInfiniteChat {

    @Redirect(
        method = "addMessageToQueue",
        at = @At(value = "INVOKE", target = "Ljava/util/List;size()I")
    )
    private int infiniteChat$keepHistory(List<?> allMessages) {
        if (InfiniteChat.INSTANCE.getState()) {
            return 0;
        }
        return allMessages.size();
    }
}
