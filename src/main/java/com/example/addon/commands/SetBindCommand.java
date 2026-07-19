package com.example.addon.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.boze.api.addon.AddonCommand;
import dev.boze.api.client.module.BaseModule;
import dev.boze.api.option.BindOption;
import dev.boze.api.option.Option;
import dev.boze.api.utility.ChatHelper;
import dev.boze.api.utility.input.Bind;
import net.minecraft.commands.SharedSuggestionProvider;
import org.lwjgl.glfw.GLFW;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;

/**
 * `.set &lt;module&gt; &lt;bindoption&gt; &lt;bind&gt;` -- sets a BindOption on any addon module
 * from chat (e.g. BedAura's "AutoCraftBind"). GUI bind-setting exists but this is handy
 * for options like AutoCraft's bind that are buried in a PageOption.
 *
 * bind formats: a single letter/digit (K, 5), "mouseN" (mouse0=left, mouse1=right, ...),
 * "none"/"-1" to unbind, or a raw GLFW key code as an integer.
 */
public class SetBindCommand extends AddonCommand {
    public static final SetBindCommand INSTANCE = new SetBindCommand();

    private SetBindCommand() { super("set", "Set a module's bind option: .set <module> <bindoption> <bind>"); }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(argument("module", BaseModule.BaseModuleArgument.module())
            .then(argument("bindoption", StringArgumentType.word())
                .then(argument("bind", StringArgumentType.word())
                    .executes(ctx -> {
                        BaseModule module = BaseModule.BaseModuleArgument.getModule(ctx, "module");
                        String optName = StringArgumentType.getString(ctx, "bindoption");
                        String bindStr = StringArgumentType.getString(ctx, "bind");

                        BindOption target = null;
                        for (Option<?> opt : module.getOptions()) {
                            // match on the short name OR the full "Parent.Name" path, case-insensitive
                            if (opt instanceof BindOption bo
                                    && (bo.name.equalsIgnoreCase(optName) || bo.getFullName().equalsIgnoreCase(optName))) {
                                target = bo;
                                break;
                            }
                        }
                        if (target == null) {
                            ChatHelper.sendMsg("set", "§cNo bind option named '" + optName + "' on " + module.getTitle()
                                + ". Use .printoptions " + module.getName() + " to list them.");
                            return SINGLE_SUCCESS;
                        }

                        int[] parsed = parseBind(bindStr); // {code, isButton}
                        if (parsed == null) {
                            ChatHelper.sendMsg("set", "§cCan't parse bind '" + bindStr
                                + "'. Use a letter/digit, mouseN, none, or a GLFW code.");
                            return SINGLE_SUCCESS;
                        }
                        target.setBind(new Bind(parsed[1] == 1, parsed[0]));
                        ChatHelper.sendMsg("set", "§a" + module.getTitle() + "." + target.name + " -> "
                            + (parsed[0] == -1 ? "None" : (parsed[1] == 1 ? "Mouse " : "Key ") + parsed[0]));
                        return SINGLE_SUCCESS;
                    }))));
    }

    /** Returns {glfwCode, isButton(0/1)} or null if unparseable. */
    private static int[] parseBind(String s) {
        s = s.trim();
        if (s.equalsIgnoreCase("none") || s.equals("-1")) return new int[]{-1, 0};

        String lower = s.toLowerCase();
        if (lower.startsWith("mouse")) {
            try {
                return new int[]{Integer.parseInt(lower.substring(5)), 1};
            } catch (NumberFormatException e) { return null; }
        }

        if (s.length() == 1) {
            char c = Character.toUpperCase(s.charAt(0));
            if (c >= 'A' && c <= 'Z') return new int[]{GLFW.GLFW_KEY_A + (c - 'A'), 0};
            if (c >= '0' && c <= '9') return new int[]{GLFW.GLFW_KEY_0 + (c - '0'), 0};
        }

        try {
            return new int[]{Integer.parseInt(s), 0}; // raw GLFW key code
        } catch (NumberFormatException e) { return null; }
    }
}
