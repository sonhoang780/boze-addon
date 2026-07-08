package com.example.addon.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.boze.api.addon.AddonCommand;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;

import com.example.addon.modules.PathFinder;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * `goal <x> <z>` (Y = player's current Y) or `goal <x> <y> <z>` (explicit Y).
 * Boze's own command prefix, deliberately NOT Baritone's `#` chat-command
 * style -- this sets the D* Lite engine's goal (PathFinder module,
 * Fly=DStarLite), unrelated to Baritone's own #goal.
 */
public class GoalCommand extends AddonCommand {
    public static final GoalCommand INSTANCE = new GoalCommand();

    private GoalCommand() { super("goal", "Set the D* Lite fly-to goal (PathFinder module, Fly=DStarLite)."); }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(argument("x", DoubleArgumentType.doubleArg())
            // goal <x> <z> -- Y defaults to the player's current Y
            .then(argument("z2", DoubleArgumentType.doubleArg())
                .executes(ctx -> {
                    double x = DoubleArgumentType.getDouble(ctx, "x");
                    double z = DoubleArgumentType.getDouble(ctx, "z2");
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.player == null) return SINGLE_SUCCESS;
                    int y = mc.player.blockPosition().getY();
                    PathFinder.INSTANCE.setGoal(new BlockPos((int) x, y, (int) z));
                    return SINGLE_SUCCESS;
                }))
            // goal <x> <y> <z> -- explicit Y
            .then(argument("y", DoubleArgumentType.doubleArg())
                .then(argument("z3", DoubleArgumentType.doubleArg())
                    .executes(ctx -> {
                        double x = DoubleArgumentType.getDouble(ctx, "x");
                        double y = DoubleArgumentType.getDouble(ctx, "y");
                        double z = DoubleArgumentType.getDouble(ctx, "z3");
                        PathFinder.INSTANCE.setGoal(new BlockPos((int) x, (int) y, (int) z));
                        return SINGLE_SUCCESS;
                    }))));
    }
}
