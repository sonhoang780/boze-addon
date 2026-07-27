package com.example.addon.modules;

import dev.boze.api.addon.AddonModule;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-accepts incoming tpa requests. Fed by MixinChatComponentAutoAccept, which hooks
 * ChatComponent#addServerSystemMessage (plugin/system messages, not signed player chat --
 * addPlayerMessage is the separate method for that, verified via javap on the 26.1.2 merged
 * jar), so ordinary player chat containing these words can't false-trigger this.
 * <p>
 * Two message shapes recognized: EssentialsX-style English ("Name has requested to teleport
 * to you." / "...that you teleport to them.") and Vietnamese plugins that mention "dịch
 * chuyển" with the sender's name leading the sentence -- not kingmc.vn-specific, works
 * against any server using either wording.
 */
public class AutoAccept extends AddonModule {
    public static final AutoAccept INSTANCE = new AutoAccept();

    private static final Pattern TPA_EN = Pattern.compile(
        "^([A-Za-z0-9_]{1,16}) has requested (?:to teleport to you|that you teleport to (?:them|him|her))",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TPA_VI = Pattern.compile(
        "^([A-Za-z0-9_]{1,16}) .*dịch chuyển", Pattern.CASE_INSENSITIVE);

    private static final long RETRY_WINDOW_MS = 1000L;

    private String pendingRetryName = null;
    private long pendingRetryUntilMs = 0;

    private AutoAccept() {
        super("AutoAccept", "Auto /tpaccept <sender> on incoming tpa requests; retries with /tpy <sender> if the server doesn't recognize tpaccept.");
    }

    @Override
    public void onDisable() {
        pendingRetryName = null;
    }

    public void onServerMessage(String text) {
        if (!getState()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        long now = System.currentTimeMillis();
        if (pendingRetryName != null) {
            if (now > pendingRetryUntilMs) {
                pendingRetryName = null;
            } else if (isUnknownCommand(text)) {
                mc.getConnection().sendCommand("tpy " + pendingRetryName);
                pendingRetryName = null;
            }
        }

        String name = matchSenderName(text);
        if (name == null) return;

        pendingRetryName = name;
        pendingRetryUntilMs = now + RETRY_WINDOW_MS;
        mc.getConnection().sendCommand("tpaccept " + name);
    }

    private static boolean isUnknownCommand(String text) {
        return text.toLowerCase(Locale.ROOT).contains("unknown command");
    }

    private static String matchSenderName(String text) {
        Matcher m = TPA_EN.matcher(text);
        if (m.find()) return m.group(1);
        m = TPA_VI.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}
