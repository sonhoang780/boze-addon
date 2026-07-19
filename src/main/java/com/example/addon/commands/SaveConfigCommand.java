package com.example.addon.commands;

import com.example.addon.AddonConfig;
import com.example.addon.ConfigMigrator;
import com.example.addon.ExampleAddon;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.boze.api.addon.AddonCommand;
import dev.boze.api.client.ModuleManager;
import dev.boze.api.utility.ChatHelper;
import net.minecraft.commands.SharedSuggestionProvider;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * `.saveconfig` -- force-writes every addon module's CURRENT live option values to
 * config.json right now (see ConfigMigrator#saveAllNow), instead of relying on whatever
 * Boze's own auto-save timing normally is. Insurance against losing changes to a crash
 * or alt-F4.
 *
 * Deliberately does NOT touch Boze's own core-module settings: those live in a separate,
 * undocumented binary NBT profile (boze/configs/&lt;profile&gt;_prof.nbt, confirmed by
 * inspecting the real install) that Boze's own closed-source loader owns exclusively --
 * no public API exposes a "save now" for it, and there's no way to serialize/verify that
 * format from addon code without risking corrupting the user's real settings. Boze
 * already auto-saves that itself; this command only covers what this addon can safely
 * read and write.
 */
public class SaveConfigCommand extends AddonCommand {
    public static final SaveConfigCommand INSTANCE = new SaveConfigCommand();

    private SaveConfigCommand() { super("saveconfig", "Force-save this addon's config now (Boze's own core-module settings are saved separately by Boze itself)."); }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(ctx -> {
            try {
                ConfigMigrator.saveAllNow(ExampleAddon.ID, ModuleManager.getAddonModules());
                AddonConfig.save();
                ChatHelper.sendMsg("SaveConfig", "§aSaved " + ModuleManager.getAddonModules().size()
                    + " module(s) to config.json. Boze's own core-module settings save separately -- Boze already handles those itself.");
            } catch (Exception e) {
                ChatHelper.sendMsg("SaveConfig", "§cSave failed: " + e.getMessage());
            }
            return SINGLE_SUCCESS;
        });
    }
}
