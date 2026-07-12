package com.example.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.boze.api.addon.AddonCommand;
import dev.boze.api.utility.ChatHelper;
import net.minecraft.commands.SharedSuggestionProvider;

import com.example.addon.modules.GroqStore;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * `.research key <APIKEY>` -- sets the free Groq API key HoodResearch's AI-answer
 * feature uses (console.groq.com, no cost). Same command-not-option pattern as
 * StashFinderCommand (Boze's option system has no text-input option type).
 */
public class HoodResearchCommand extends AddonCommand {
    public static final HoodResearchCommand INSTANCE = new HoodResearchCommand();

    private HoodResearchCommand() { super("research", "Configure HoodResearch's Groq API key."); }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(LiteralArgumentBuilder.<SharedSuggestionProvider>literal("key")
            .then(argument("apiKey", StringArgumentType.word())
                .executes(ctx -> {
                    String key = StringArgumentType.getString(ctx, "apiKey");
                    GroqStore.setApiKey(key);
                    ChatHelper.sendMsg("HoodResearch", "§aGroq API key set.");
                    return SINGLE_SUCCESS;
                })));
    }
}
