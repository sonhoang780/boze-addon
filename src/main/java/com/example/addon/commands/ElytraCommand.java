package com.example.addon.commands;

import com.example.addon.modules.PathFinder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.boze.api.addon.AddonCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

public class ElytraCommand extends AddonCommand {
    public static final ElytraCommand INSTANCE = new ElytraCommand();

    private ElytraCommand() {
        super("elytra", "Start/stop PathFinder's elytra flight-follow");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            String msg;
            if (!PathFinder.INSTANCE.getState()) {
                msg = "[PathFinder] Module is disabled — enable PathFinder first.";
            } else if (PathFinder.INSTANCE.goal == null) {
                msg = "[PathFinder] No goal set — use #goal <x> [y] <z> first.";
            } else {
                PathFinder.INSTANCE.flying = !PathFinder.INSTANCE.flying;
                if (!PathFinder.INSTANCE.flying) {
                    PathFinder.INSTANCE.currentPath = null;
                    PathFinder.INSTANCE.pathCursor = 0;
                }
                msg = "[PathFinder] Flight " + (PathFinder.INSTANCE.flying ? "started" : "stopped") + ".";
            }
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(msg));
            }
            return SINGLE_SUCCESS;
        });
    }
}
