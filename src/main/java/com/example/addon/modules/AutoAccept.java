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
 * Confirmed live on kingmc.vn (screenshot 2026-07-27): "You have received a teleport request
 * from &lt;name&gt; which expires in 30 seconds. ... Type /tpaccept to accept the request." --
 * /tpaccept here takes NO argument (that's literally what the server tells you to type), so
 * TPA_REQUEST_FROM below just sends plain "/tpaccept". EssentialsX-style ("Name has requested
 * to teleport to you") and Vietnamese ("... dịch chuyển ...") variants are kept as a fallback
 * for other servers that DO take a name argument -- unconfirmed against a real server, so
 * lower priority than the confirmed pattern.
 */
public class AutoAccept extends AddonModule {
    public static final AutoAccept INSTANCE = new AutoAccept();

    // "received a" is load-bearing: without it this also matches the server's OWN post-accept
    // confirmation line ("You accepted the teleport request from X."), which re-triggered another
    // /tpaccept, which produced another confirmation line, forever -- a feedback loop that got a
    // real player spam-kicked (screenshot 2026-07-27: 4x "You accepted..." for one request).
    private static final Pattern TPA_REQUEST_FROM = Pattern.compile(
        "received a teleport request from ([A-Za-z0-9_]{1,16})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TPA_EN = Pattern.compile(
        "^([A-Za-z0-9_]{1,16}) has requested (?:to teleport to you|that you teleport to (?:them|him|her))",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TPA_VI = Pattern.compile(
        "^([A-Za-z0-9_]{1,16}) .*dịch chuyển", Pattern.CASE_INSENSITIVE);

    private static final long RETRY_WINDOW_MS = 1000L;

    private String pendingRetryName = null;
    private long pendingRetryUntilMs = 0;

    private AutoAccept() {
        super("AutoAccept", "Auto /tpaccept on incoming tpa requests; retries with /tpy <sender> if the server doesn't recognize tpaccept.");
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

        Matcher m = TPA_REQUEST_FROM.matcher(text);
        if (m.find()) {
            pendingRetryName = m.group(1);
            pendingRetryUntilMs = now + RETRY_WINDOW_MS;
            mc.getConnection().sendCommand("tpaccept");
            return;
        }

        String name = matchNamedSenderName(text);
        if (name == null) return;

        pendingRetryName = name;
        pendingRetryUntilMs = now + RETRY_WINDOW_MS;
        mc.getConnection().sendCommand("tpaccept " + name);
    }

    private static boolean isUnknownCommand(String text) {
        return text.toLowerCase(Locale.ROOT).contains("unknown command");
    }

    private static String matchNamedSenderName(String text) {
        Matcher m = TPA_EN.matcher(text);
        if (m.find()) return m.group(1);
        m = TPA_VI.matcher(text);
        if (m.find()) return m.group(1);
        return null;
    }
}
