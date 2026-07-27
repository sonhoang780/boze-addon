package com.example.addon;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.boze.api.addon.AddonModule;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Named config profiles for this addon -- separate from Boze's own config.json
 * (which always holds the "current" live state). ".cfg all save <name>" snapshots
 * every module's toJson() (state + all options) into boze/addons/&lt;id&gt;/profiles/&lt;name&gt;.json;
 * ".cfg all load <name>" restores it via each module's own fromJson(), reusing the
 * same round-trip AddonModule already implements for Boze's config.json.
 */
public class ProfileManager {

    private static Path dir(String addonId) {
        return FabricLoader.getInstance().getGameDir()
            .resolve("boze").resolve("addons").resolve(addonId).resolve("profiles");
    }

    public static void save(String addonId, String name, List<AddonModule> modules) {
        JsonObject modulesObject = new JsonObject();
        for (AddonModule module : modules) {
            modulesObject.add(module.getName(), module.toJson());
        }
        JsonObject root = new JsonObject();
        root.add("modules", modulesObject);

        try {
            Path path = dir(addonId);
            Files.createDirectories(path);
            Files.writeString(path.resolve(name + ".json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write profile '" + name + "'", e);
        }
    }

    /** @return number of modules successfully restored */
    public static int load(String addonId, String name, List<AddonModule> modules) {
        Path file = dir(addonId).resolve(name + ".json");
        if (!Files.exists(file)) throw new RuntimeException("Profile '" + name + "' not found");

        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read profile '" + name + "'", e);
        }
        if (!root.has("modules")) return 0;
        JsonObject modulesObject = root.getAsJsonObject("modules");

        int restored = 0;
        for (AddonModule module : modules) {
            if (!modulesObject.has(module.getName())) continue;
            try {
                module.fromJson(modulesObject.getAsJsonObject(module.getName()));
                restored++;
            } catch (Exception ignored) {
                // One module's saved shape drifting (e.g. an option renamed since this
                // profile was saved) must not abort restoring every other module.
            }
        }
        return restored;
    }

    public static boolean exists(String addonId, String name) {
        return Files.exists(dir(addonId).resolve(name + ".json"));
    }

    public static void delete(String addonId, String name) {
        Path file = dir(addonId).resolve(name + ".json");
        if (!Files.exists(file)) throw new RuntimeException("Profile '" + name + "' not found");
        try {
            Files.delete(file);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete profile '" + name + "'", e);
        }
    }

    public static List<String> listNames(String addonId) {
        Path path = dir(addonId);
        if (!Files.exists(path)) return List.of();
        try (var stream = Files.list(path)) {
            return stream
                .map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith(".json"))
                .map(n -> n.substring(0, n.length() - ".json".length()))
                .sorted()
                .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
