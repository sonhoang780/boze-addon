package com.example.addon;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/**
 * Renames old option keys to new ones in the Boze addon config JSON before
 * Boze reads it. Call once at the top of ExampleAddon.initialize() so renamed
 * options survive across addon updates without losing the user's saved values.
 */
public class ConfigMigrator {

    /**
     * @param addonId  the addon's string ID (matches the "id" field in config.json)
     * @param renames  moduleName → { oldOptionName → newOptionName }
     */
    public static void migrate(String addonId, Map<String, Map<String, String>> renames) {
        Path configPath = FabricLoader.getInstance().getGameDir()
                .resolve("boze").resolve("addons").resolve(addonId).resolve("config.json");

        if (!Files.exists(configPath)) return;

        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();

            if (!root.has("modules")) return;
            JsonObject modules = root.getAsJsonObject("modules");

            boolean changed = false;
            for (Map.Entry<String, Map<String, String>> moduleEntry : renames.entrySet()) {
                String moduleName = moduleEntry.getKey();
                if (!modules.has(moduleName)) continue;

                JsonObject moduleObj = modules.getAsJsonObject(moduleName);
                for (Map.Entry<String, String> rename : moduleEntry.getValue().entrySet()) {
                    String oldName = rename.getKey();
                    String newName = rename.getValue();
                    if (moduleObj.has(oldName) && !moduleObj.has(newName)) {
                        moduleObj.add(newName, moduleObj.get(oldName));
                        moduleObj.remove(oldName);
                        changed = true;
                    }
                }
            }

            if (changed) {
                String migrated = new GsonBuilder().setPrettyPrinting().create().toJson(root);
                Files.writeString(configPath, migrated, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // If migration fails, Boze falls back to defaults — acceptable over a crash.
        }
    }
}
