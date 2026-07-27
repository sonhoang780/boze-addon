package com.example.addon.modules.stashfinder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Discord webhook config (url + user id to ping) and sender. No StringOption exists in
 * the Boze API, so config is set via StashFinderCommand and persisted to a small JSON
 * file (same FabricLoader game-dir pattern as StashFinderStore), not a module Option.
 */
public class StashWebhook {

    private static File file() {
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/stashfinder");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "webhook.json");
    }

    private static volatile String webhookUrl = "";
    private static volatile String userId = "";
    private static volatile boolean loaded = false;

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.exists()) return;
        try (FileReader reader = new FileReader(f)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            if (obj.has("url")) webhookUrl = obj.get("url").getAsString();
            if (obj.has("userId")) userId = obj.get("userId").getAsString();
        } catch (Exception ignored) {}
    }

    private static synchronized void save() {
        try (FileWriter writer = new FileWriter(file())) {
            JsonObject obj = new JsonObject();
            obj.addProperty("url", webhookUrl);
            obj.addProperty("userId", userId);
            writer.write(obj.toString());
        } catch (Exception ignored) {}
    }

    public static String getWebhookUrl() { ensureLoaded(); return webhookUrl; }
    public static String getUserId() { ensureLoaded(); return userId; }

    public static void setWebhookUrl(String url) {
        ensureLoaded();
        webhookUrl = url;
        save();
    }

    public static void setUserId(String id) {
        ensureLoaded();
        userId = id;
        save();
    }

    // Chunks near each other can all cross their threshold within the same second or two
    // as the player flies through (e.g. a big base spanning a few chunks) -- without a
    // gate that's a burst of near-simultaneous Discord messages. One send per 5s window;
    // extra finds inside the window are simply dropped (that chunk stays marked evaluated
    // in the store regardless, so it's not lost -- just not re-announced).
    private static final long PING_INTERVAL_MS = 5000;
    private static volatile long lastSentAtMs = 0;

    private static synchronized boolean tryClaimSendSlot() {
        long now = System.currentTimeMillis();
        if (now - lastSentAtMs < PING_INTERVAL_MS) return false;
        lastSentAtMs = now;
        return true;
    }

    // Must run on the main thread (mc.getCurrentServer()/getSingleplayerServer() aren't
    // thread-safe off it) -- called before the runAsync hop in send(), not inside it.
    private static String serverLabel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null) return mc.getCurrentServer().ip;
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
            return "Singleplayer: " + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "Unknown";
    }

    /**
     * Fires a "Stash Found!" embed matching the Meteor-style layout: title, coordinates
     * line, one field per container/animal type that actually met its threshold. Runs
     * entirely off the render thread (CompletableFuture.runAsync -- same pattern as
     * SpotifyIntegration/GifHUD/PlayMusic's own HttpURLConnection calls). Rate-limited to
     * one send per 5s to avoid spamming Discord.
     */
    public static void send(int x, int z, String dimension, Map<String, Integer> countsInOrder) {
        String url = getWebhookUrl();
        if (url == null || url.isBlank()) return;
        if (!tryClaimSendSlot()) return;

        String server = serverLabel();

        CompletableFuture.runAsync(() -> {
            try {
                JsonObject embed = new JsonObject();
                embed.addProperty("title", "Stash Found!");
                embed.addProperty("description", "Coordinates: X: " + x + " Z: " + z + " (" + dimension + ")");
                embed.addProperty("color", 0x2ECC71);

                JsonArray fields = new JsonArray();
                JsonObject serverField = new JsonObject();
                serverField.addProperty("name", "Server");
                serverField.addProperty("value", server);
                serverField.addProperty("inline", false);
                fields.add(serverField);
                for (Map.Entry<String, Integer> e : countsInOrder.entrySet()) {
                    JsonObject field = new JsonObject();
                    field.addProperty("name", e.getKey());
                    field.addProperty("value", String.valueOf(e.getValue()));
                    field.addProperty("inline", true);
                    fields.add(field);
                }
                embed.add("fields", fields);

                JsonArray embeds = new JsonArray();
                embeds.add(embed);

                JsonObject payload = new JsonObject();
                String id = getUserId();
                payload.addProperty("content", (id == null || id.isBlank()) ? "" : "<@" + id + ">");
                payload.add("embeds", embeds);

                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                int code = conn.getResponseCode(); // drain/trigger the request
                conn.disconnect();
                if (code / 100 == 2) {
                    notify("§aStash reported to Discord (" + x + ", " + z + ").");
                } else {
                    notify("§cWebhook rejected: HTTP " + code + " (check URL with .stashfinder webhook).");
                }
            } catch (Exception ex) {
                notify("§cWebhook send failed: " + ex.getMessage());
            }
        });
    }

    // Failures were silently swallowed before -- a dead/expired webhook URL produced
    // zero feedback while chunks were still marked evaluated. Chat must run on the
    // render thread; send() runs async, so hop back via mc.execute.
    private static void notify(String msg) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) mc.execute(() -> dev.boze.api.utility.ChatHelper.sendMsg("StashFinder", msg));
    }

    public static Map<String, Integer> orderedCounts() {
        return new LinkedHashMap<>();
    }
}
