package com.example.addon.commands;

import com.example.addon.ExampleAddon;
import com.example.addon.ProfileManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.boze.api.addon.AddonCommand;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.utility.ChatHelper;
import net.minecraft.commands.SharedSuggestionProvider;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * ".cfg all save <name>" / ".cfg all load <name>" -- named snapshots of every module's
 * live settings, separate from Boze's own config.json (which only ever holds the
 * "current" state). See ProfileManager for the storage format.
 */
public class ConfigProfileCommand extends AddonCommand {
    public static final ConfigProfileCommand INSTANCE = new ConfigProfileCommand();

    private ConfigProfileCommand() { super("cfg", "Save/load named config profiles for this addon."); }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        SuggestionProvider<SharedSuggestionProvider> suggestNames = (ctx, b) -> {
            String input = b.getRemaining().toLowerCase();
            for (String name : ProfileManager.listNames(ExampleAddon.ID)) {
                if (name.toLowerCase().startsWith(input)) b.suggest(name);
            }
            return b.buildFuture();
        };

        builder.then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal("all")
            .then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal("save")
                .then(argument("name", StringArgumentType.word())
                    .suggests(suggestNames)
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        try {
                            ProfileManager.save(ExampleAddon.ID, name, ModuleManager.getAddonModules());
                            ChatHelper.sendMsg("Cfg", "§aSaved profile '" + name + "' ("
                                + ModuleManager.getAddonModules().size() + " module(s)).");
                        } catch (Exception e) {
                            ChatHelper.sendMsg("Cfg", "§cSave failed: " + e.getMessage());
                        }
                        return SINGLE_SUCCESS;
                    })))
            .then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal("load")
                .then(argument("name", StringArgumentType.word())
                    .suggests(suggestNames)
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        try {
                            int restored = ProfileManager.load(ExampleAddon.ID, name, ModuleManager.getAddonModules());
                            ChatHelper.sendMsg("Cfg", "§aLoaded profile '" + name + "' (" + restored + " module(s) restored).");
                        } catch (Exception e) {
                            ChatHelper.sendMsg("Cfg", "§cLoad failed: " + e.getMessage());
                        }
                        return SINGLE_SUCCESS;
                    })))
            .then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal("delete")
                .then(argument("name", StringArgumentType.word())
                    .suggests(suggestNames)
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name");
                        try {
                            ProfileManager.delete(ExampleAddon.ID, name);
                            ChatHelper.sendMsg("Cfg", "§aDeleted profile '" + name + "'.");
                        } catch (Exception e) {
                            ChatHelper.sendMsg("Cfg", "§cDelete failed: " + e.getMessage());
                        }
                        return SINGLE_SUCCESS;
                    })))
        );
    }
}
