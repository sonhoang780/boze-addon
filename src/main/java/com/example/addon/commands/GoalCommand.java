package com.example.addon.commands;

import com.example.addon.modules.PathFinder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.boze.api.addon.AddonCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class GoalCommand extends AddonCommand {
    public static final GoalCommand INSTANCE = new GoalCommand();

    private GoalCommand() {
        super("goal", "Set the PathFinder nether-ceiling destination");
    }

    private static int suggestPlayerX(com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        var p = Minecraft.getInstance().player;
        if (p != null) b.suggest((int) Math.floor(p.getX()));
        return 0;
    }

    private static int suggestPlayerY(com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        var p = Minecraft.getInstance().player;
        if (p != null) b.suggest((int) Math.floor(p.getY()));
        return 0;
    }

    private static int suggestPlayerZ(com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        var p = Minecraft.getInstance().player;
        if (p != null) b.suggest((int) Math.floor(p.getZ()));
        return 0;
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(argument("x", IntegerArgumentType.integer())
            .suggests((ctx, b) -> { suggestPlayerX(b); return b.buildFuture(); })
            .then(argument("z", IntegerArgumentType.integer())
                .suggests((ctx, b) -> { suggestPlayerZ(b); return b.buildFuture(); })
                .executes(ctx -> setGoal(
                    IntegerArgumentType.getInteger(ctx, "x"),
                    null,
                    IntegerArgumentType.getInteger(ctx, "z"),
                    null))
                .then(literal("seed")
                    .then(argument("seedValue", LongArgumentType.longArg())
                        .executes(ctx -> setGoal(
                            IntegerArgumentType.getInteger(ctx, "x"),
                            null,
                            IntegerArgumentType.getInteger(ctx, "z"),
                            LongArgumentType.getLong(ctx, "seedValue")))))
                .then(argument("y", IntegerArgumentType.integer())
                    .suggests((ctx, b) -> { suggestPlayerY(b); return b.buildFuture(); })
                    .executes(ctx -> setGoal(
                        IntegerArgumentType.getInteger(ctx, "x"),
                        IntegerArgumentType.getInteger(ctx, "z"),
                        IntegerArgumentType.getInteger(ctx, "y"),
                        null))
                    .then(literal("seed")
                        .then(argument("seedValue", LongArgumentType.longArg())
                            .executes(ctx -> setGoal(
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "z"),
                                IntegerArgumentType.getInteger(ctx, "y"),
                                LongArgumentType.getLong(ctx, "seedValue")))))
                )
            )
        );
    }

    // Note the 3-arg (x,y,z) branch calls this with (x, z, y, seed) parameter order
    // (see the "y" sub-branch above) — normalize here.
    private int setGoal(int x, Integer zIfYPresent, int zOrY, Long seed) {
        int x1 = x;
        int y1;
        int z1;
        if (zIfYPresent != null) {
            // 3-arg form: params were (x, z, y)
            z1 = zIfYPresent;
            y1 = zOrY;
        } else {
            // 2-arg form: params were (x, z, null)
            z1 = zOrY;
            // Native isInBounds() requires y < maxHeight, so the ceiling itself is invalid.
            y1 = PathFinder.INSTANCE.maxHeight.getValue().intValue() - 1;
        }
        PathFinder.INSTANCE.goal = new BlockPos(x1, y1, z1);
        PathFinder.INSTANCE.updateSeed(seed);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[PathFinder] Goal set to " + x1 + ", " + y1 + ", " + z1
                    + (seed != null ? " (seed " + seed + ", unseen terrain generated)" : " (no seed, unseen terrain treated as solid)")));
        }
        return SINGLE_SUCCESS;
    }
}
