package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.client.module.BaseModule;
import dev.boze.api.event.EventModuleToggle;
import dev.boze.api.option.ToggleOption;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces Boze core's own "<Module> has been enabled/disabled" toggle message with
 * "[Boze] [+] Module" (pink prefix, green/red +/-, white module name). Boze's own message can't be suppressed or
 * reformatted -- EventModuleToggle (the event AddonModule#setState posts after every
 * toggle) has no cancel() field (see decompiled boze-api source), and the message
 * itself never reaches Minecraft's own ChatComponent at all (verified: hooking every
 * public entry point AND the shared private addMessage() it all funnels through still
 * never intercepted it -- Boze's own toggle-message renderer is closed-source and
 * evidently entirely separate from vanilla chat). So this prints an ADDITIONAL line
 * alongside the original rather than replacing it.
 */
public class Notification extends AddonModule {
    public static final Notification INSTANCE = new Notification();

    // Best-effort guess at Boze's own built-in notification module's name, in case one
    // exists and can be turned off from here -- silently no-ops (like every other
    // ModuleManager call in this addon against a module that may not exist) if wrong
    // or absent. The [+]/[-] line above still gets added either way.
    private static final String[] BOZE_NOTIFICATION_MODULE_GUESSES = { "Notifications", "ModuleNotifications", "Notification" };

    private static final TextColor BOZE_PREFIX_COLOR = TextColor.fromRgb(0xFFB3C6);

    public final ToggleOption keepNoti = new ToggleOption(this, "KeepNoti",
        "Keep every past toggle line in chat. Turn off to have a module's newest toggle line replace its previous one instead of stacking both.", true);

    // Last toggle-line we printed per module name; only populated while KeepNoti is off
    private final Map<String, Component> lastLineByModule = new HashMap<>();

    private static Field allMessagesField;
    private static Method refreshTrimmedMessagesMethod;

    public Notification() {
        super("Notification", "Prints [+]/[-] Module lines for every module toggle (addon-side, alongside Boze's own message).");
    }

    @Override
    public void onEnable() {
        for (String name : BOZE_NOTIFICATION_MODULE_GUESSES) {
            try {
                if (ModuleManager.getState(name)) ModuleManager.setState(name, false);
            } catch (Exception ignored) {}
        }
    }

    @EventHandler
    private void onModuleToggle(EventModuleToggle event) {
        BaseModule module = event.getModule();
        if (module == null) return;
        boolean enabled = module.getState();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        MutableComponent line = Component.literal("[Boze] ").setStyle(Style.EMPTY.withColor(BOZE_PREFIX_COLOR))
            .append(Component.literal("[").withStyle(net.minecraft.ChatFormatting.WHITE))
            .append(Component.literal(enabled ? "+" : "-").withStyle(enabled ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED))
            .append(Component.literal("] ").withStyle(net.minecraft.ChatFormatting.WHITE))
            .append(Component.literal(module.getName()).withStyle(net.minecraft.ChatFormatting.WHITE));

        if (!keepNoti.getValue()) {
            Component previous = lastLineByModule.put(module.getName(), line);
            if (previous != null) removeChatLine(mc, previous);
        }

        mc.player.sendSystemMessage(line);
    }

    /**
     * Removes a previously sent line by identity. ChatComponent#deleteMessage only matches on
     * MessageSignature (none exists for unsigned system messages -- would wipe every unsigned
     * line, not just ours), so this reaches into the private allMessages list directly and
     * re-derives trimmedMessages the way refreshTrimmedMessages() does (both private, no public
     * equivalent -- verified via javap on the 26.1.2 merged jar).
     */
    private static void removeChatLine(Minecraft mc, Component line) {
        try {
            ChatComponent chat = mc.gui.getChat();
            if (allMessagesField == null) {
                allMessagesField = ChatComponent.class.getDeclaredField("allMessages");
                allMessagesField.setAccessible(true);
                refreshTrimmedMessagesMethod = ChatComponent.class.getDeclaredMethod("refreshTrimmedMessages");
                refreshTrimmedMessagesMethod.setAccessible(true);
            }
            @SuppressWarnings("unchecked")
            List<GuiMessage> allMessages = (List<GuiMessage>) allMessagesField.get(chat);
            if (allMessages.removeIf(m -> m.content() == line)) {
                refreshTrimmedMessagesMethod.invoke(chat);
            }
        } catch (Exception ignored) {}
    }
}
