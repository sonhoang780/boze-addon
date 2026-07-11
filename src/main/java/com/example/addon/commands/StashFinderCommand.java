package com.example.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.boze.api.addon.AddonCommand;
import dev.boze.api.utility.ChatHelper;
import net.minecraft.commands.SharedSuggestionProvider;

import com.example.addon.modules.stashfinder.StashWebhook;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * `.stashfinder webhook <url>` / `.stashfinder userid <id>` -- Boze's option system has
 * no text-input option type, so StashFinder's Discord config goes through a command
 * instead (same AddonCommand mechanism as GoalCommand/KitCommand).
 */
public class StashFinderCommand extends AddonCommand {
    public static final StashFinderCommand INSTANCE = new StashFinderCommand();

    private StashFinderCommand() { super("stashfinder", "Configure StashFinder's Discord webhook."); }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<SharedSuggestionProvider>literal("webhook")
                .then(argument("url", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String url = StringArgumentType.getString(ctx, "url");
                        StashWebhook.setWebhookUrl(url);
                        ChatHelper.sendMsg("StashFinder", "§aWebhook URL set.");
                        return SINGLE_SUCCESS;
                    })))
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<SharedSuggestionProvider>literal("userid")
                .then(argument("id", StringArgumentType.word())
                    .executes(ctx -> {
                        String id = StringArgumentType.getString(ctx, "id");
                        StashWebhook.setUserId(id);
                        ChatHelper.sendMsg("StashFinder", "§aUser ID set (will be pinged on stash found).");
                        return SINGLE_SUCCESS;
                    })))
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<SharedSuggestionProvider>literal("test")
                .executes(ctx -> {
                    if (StashWebhook.getWebhookUrl().isBlank()) {
                        ChatHelper.sendMsg("StashFinder", "§cNo webhook URL set. Use .stashfinder webhook <url> first.");
                    } else {
                        StashWebhook.send(0, 0, java.util.Map.of("Test", 1));
                        ChatHelper.sendMsg("StashFinder", "§7Test webhook queued (result will be reported in chat; rate-limited to one send per 5s).");
                    }
                    return SINGLE_SUCCESS;
                }))
            .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<SharedSuggestionProvider>literal("reset")
                .executes(ctx -> {
                    com.example.addon.modules.stashfinder.StashFinder.INSTANCE.resetCurrentWorldStore();
                    ChatHelper.sendMsg("StashFinder", "§aScan history cleared for this world -- all chunks will be re-scanned.");
                    return SINGLE_SUCCESS;
                }));
    }
}
