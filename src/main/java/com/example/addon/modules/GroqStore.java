package com.example.addon.modules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Groq API key storage for HoodResearch's AI-answer feature. No StringOption exists in
 * the Boze API (same reason StashWebhook's Discord config is a command, not an option),
 * so the key is set via a command and persisted here (same FabricLoader game-dir JSON
 * pattern as StashWebhook).
 */
public class GroqStore {

    private static File file() {
        File dir = new File(FabricLoader.getInstance().getGameDir().toFile(), "boze/hoodresearch");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "groq.json");
    }

    private static volatile String apiKey = "";
    private static volatile boolean loaded = false;

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.exists()) return;
        try (FileReader reader = new FileReader(f)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            if (obj.has("apiKey")) apiKey = obj.get("apiKey").getAsString();
        } catch (Exception ignored) {}
    }

    public static String getApiKey() { ensureLoaded(); return apiKey; }

    public static synchronized void setApiKey(String key) {
        ensureLoaded();
        apiKey = key;
        try (FileWriter writer = new FileWriter(file())) {
            JsonObject obj = new JsonObject();
            obj.addProperty("apiKey", apiKey);
            writer.write(obj.toString());
        } catch (Exception ignored) {}
    }
}
