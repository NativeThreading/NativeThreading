package com.github.uright008.nt.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.LEVEL_GAMEMASTERS;

public final class NtCommandRegistration {

    private NtCommandRegistration() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(createRoot("nt"));
        dispatcher.register(createRoot("native-threading"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRoot(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder
                .<CommandSourceStack>literal(name)
                .requires(Commands.hasPermission(LEVEL_GAMEMASTERS))
                .executes(NtCommandRegistration::showOverview);

        for (NtSubCommand sub : NtCommands.subCommands().values()) {
            LiteralArgumentBuilder<CommandSourceStack> node = LiteralArgumentBuilder.literal(sub.getName());
            sub.build(node);
            root.then(node);
        }
        return root;
    }

    private static int showOverview(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(
                () -> Component.literal(NtCommandResponses.overview(NtCommands.subCommands().values())),
                false);
        return 1;
    }
}
