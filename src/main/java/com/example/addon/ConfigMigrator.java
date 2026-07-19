package com.example.addon;

import com.google.gson.*;
import dev.boze.api.addon.AddonModule;
import dev.boze.api.option.Option;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
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

    /**
     * Renames a module's own top-level key in config.json (e.g. after a Java class rename)
     * -- separate from {@link #migrate}, which only renames option keys WITHIN an existing
     * module section and has no way to move the section itself under a new name. Call
     * BEFORE any {@link #migrate} call that targets the module's new name, since that call
     * only finds the section once it's already sitting under the new key.
     *
     * Also fixes up the module's persisted "title" field, which is SEPARATE from the JSON
     * key and from the live class's registered name -- Boze's own UI reads this cached
     * field for display, so moving the section (or renaming the Java class) alone leaves
     * it showing the OLD name forever. This half is intentionally NOT gated on "did we
     * just move the section this call" -- it runs every launch as long as the module is
     * sitting under newModuleName, so it still gets fixed even on a later run after the
     * key move already happened (the key-move itself is a one-time, idempotent no-op past
     * the first successful run).
     *
     * @param addonId      the addon's string ID (matches the "id" field in config.json)
     * @param oldModuleName the module's previous registered name
     * @param newModuleName the module's current registered name
     */
    public static void renameModule(String addonId, String oldModuleName, String newModuleName) {
        Path configPath = FabricLoader.getInstance().getGameDir()
                .resolve("boze").resolve("addons").resolve(addonId).resolve("config.json");

        if (!Files.exists(configPath)) return;

        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();

            if (!root.has("modules")) return;
            JsonObject modules = root.getAsJsonObject("modules");

            boolean changed = false;

            if (modules.has(oldModuleName) && !modules.has(newModuleName)) {
                modules.add(newModuleName, modules.get(oldModuleName));
                modules.remove(oldModuleName);
                changed = true;
            }

            if (modules.has(newModuleName)) {
                JsonObject moduleObj = modules.getAsJsonObject(newModuleName);
                if (moduleObj.has("title") && !newModuleName.equals(moduleObj.get("title").getAsString())) {
                    moduleObj.addProperty("title", newModuleName);
                    changed = true;
                }
            }

            if (changed) {
                String migrated = new GsonBuilder().setPrettyPrinting().create().toJson(root);
                Files.writeString(configPath, migrated, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // Same tradeoff as migrate(): a failure here just means the module starts at
            // defaults instead of its saved settings -- no worse than before this existed.
        }
    }

    /**
     * Boze's AddonModule#fromJson does {@code object.get(setting.name).getAsJsonObject()}
     * with no {@code .has()} check — an option added in a code update that isn't yet in
     * the user's saved config.json throws NullPointerException. Addon#fromJson's loop over
     * modules has no per-module try/catch, so that NPE aborts the WHOLE loop: every module
     * that would have been restored AFTER the broken one in iteration order is silently
     * left at defaults too, even though its own saved settings on disk are perfectly fine.
     * <br>
     * Call once at the end of ExampleAddon.initialize(), after every modules.add(...) —
     * by then each module's Option instances already exist (constructed in the module's
     * own constructor at class-init time) with their compiled-in default values, so we can
     * fill any option missing from the saved JSON with a fresh {@code toJson()} snapshot
     * of that default, guaranteeing Boze's own loader never hits a missing key.
     */
    public static void fillMissingOptions(String addonId, List<AddonModule> modules) {
        Path configPath = FabricLoader.getInstance().getGameDir()
                .resolve("boze").resolve("addons").resolve(addonId).resolve("config.json");

        if (!Files.exists(configPath)) return;

        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();

            if (!root.has("modules")) return;
            JsonObject modulesObject = root.getAsJsonObject("modules");

            boolean changed = false;
            for (AddonModule module : modules) {
                // Module not in the saved file at all (brand new, or currently
                // unregistered when last saved) — Addon#fromJson's own .has() guard
                // already skips it safely; nothing to backfill.
                if (!modulesObject.has(module.getName())) continue;

                JsonObject moduleObj = modulesObject.getAsJsonObject(module.getName());
                for (Option<?> option : module.options) {
                    if (!moduleObj.has(option.name)) {
                        moduleObj.add(option.name, option.toJson());
                        changed = true;
                    }
                }
            }

            if (changed) {
                String filled = new GsonBuilder().setPrettyPrinting().create().toJson(root);
                Files.writeString(configPath, filled, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // Same tradeoff as migrate(): a failure here just means Boze's own loader
            // hits its original NPE-on-missing-key behavior again — no worse than before
            // this method existed.
        }
    }

    /**
     * Force-writes every module's CURRENT live option values into config.json right now,
     * instead of waiting on whatever Boze's own auto-save timing normally is -- insurance
     * against losing changes to an alt-F4/crash. Unlike {@link #fillMissingOptions}, this
     * overwrites existing keys too, not just missing ones.
     *
     * @param addonId the addon's string ID (matches the "id" field in config.json)
     * @param modules the live module list (option values read directly off each module's
     *                Option instances, not off whatever was last written to disk)
     */
    public static void saveAllNow(String addonId, List<AddonModule> modules) {
        Path configPath = FabricLoader.getInstance().getGameDir()
                .resolve("boze").resolve("addons").resolve(addonId).resolve("config.json");

        JsonObject root;
        if (Files.exists(configPath)) {
            try {
                root = JsonParser.parseString(Files.readString(configPath, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                root = new JsonObject();
            }
        } else {
            root = new JsonObject();
        }
        if (!root.has("id")) root.addProperty("id", addonId);

        JsonObject modulesObject = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
        if (!root.has("modules")) root.add("modules", modulesObject);

        for (AddonModule module : modules) {
            JsonObject moduleObj = modulesObject.has(module.getName())
                ? modulesObject.getAsJsonObject(module.getName())
                : new JsonObject();
            if (!modulesObject.has(module.getName())) modulesObject.add(module.getName(), moduleObj);

            moduleObj.addProperty("enabled", module.getState());
            for (Option<?> option : module.options) {
                moduleObj.add(option.name, option.toJson());
            }
        }

        try {
            Files.createDirectories(configPath.getParent());
            String written = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(configPath, written, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write config.json", e);
        }
    }
}
