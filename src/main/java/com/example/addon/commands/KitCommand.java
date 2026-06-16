package com.example.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.boze.api.addon.AddonCommand;
import net.minecraft.command.CommandSource;
import com.example.addon.modules.betterrekit.EvilRekit;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class KitCommand extends AddonCommand {
    public static final KitCommand INSTANCE = new KitCommand();

    private KitCommand() {
        super("kit", "Rekit manager");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        SuggestionProvider<CommandSource> suggestKits = (ctx, b) -> {
            String input = b.getRemaining().toLowerCase();
            for (String kit : EvilRekit.INSTANCE.getKitNames()) {
                if (kit.toLowerCase().startsWith(input)) {
                    b.suggest(kit);
                }
            }
            return b.buildFuture();
        };

        // Đã sửa thành "save"
        builder.then(literal("save")
            .then(argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    EvilRekit.INSTANCE.saveKit(name);
                    return SINGLE_SUCCESS;
                })
            )
        );

        // Đã gắn gợi ý vào "load"
        builder.then(literal("load")
            .then(argument("name", StringArgumentType.word())
                .suggests(suggestKits)
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    EvilRekit.INSTANCE.loadKit(name);
                    return SINGLE_SUCCESS;
                })
            )
        );

        builder.then(literal("list")
            .executes(ctx -> {
                EvilRekit.INSTANCE.listKits();
                return SINGLE_SUCCESS;
            })
        );

        // Đã gắn gợi ý vào "delete"
        builder.then(literal("delete")
            .then(argument("name", StringArgumentType.word())
                .suggests(suggestKits)
                .executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    EvilRekit.INSTANCE.deleteKit(name);
                    return SINGLE_SUCCESS;
                })
            )
        );
    }
}