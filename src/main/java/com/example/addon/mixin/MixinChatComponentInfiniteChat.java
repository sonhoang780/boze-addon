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

    // addMessageToQueue's `allMessages` (raw history) isn't what you actually scroll through --
    // that's `trimmedMessages`, filled by addMessageToDisplayQueue via its OWN separate
    // `while (trimmedMessages.size() > 100) removeLast()` loop. Patching only addMessageToQueue
    // left the visible/scrollable list still capped at 100 lines, so a busy chat still looked
    // trimmed even with unlimited raw history. Same size()->0 trick, same guaranteed-terminating
    // reasoning as above, just on the second cap.
    // ordinal=2 (re-verified via javap -c on the 26.1.2 merged jar, 2026-07-31 -- the PREVIOUS
    // ordinal=1 here was wrong and left trimmedMessages still capped at 100, which is exactly
    // why manual spam-testing couldn't scroll past ~100 lines back). The method has THREE
    // List.size() call sites, not two: ordinal 0 is `lines.size()` in the wrap-loop condition,
    // ordinal 1 is a SECOND `lines.size()` call used only to compute `isLastLine` inside the
    // loop body, and ordinal 2 -- after the loop -- is `trimmedMessages.size()` in the actual
    // trim while-loop. That's the one that needs redirecting.
    @Redirect(
        method = "addMessageToDisplayQueue",
        at = @At(value = "INVOKE", target = "Ljava/util/List;size()I", ordinal = 2)
    )
    private int infiniteChat$keepDisplayHistory(List<?> trimmedMessages) {
        if (InfiniteChat.INSTANCE.getState()) {
            return 0;
        }
        return trimmedMessages.size();
    }
}
