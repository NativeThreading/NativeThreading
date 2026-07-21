package com.github.uright008.pc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.LEVEL_GAMEMASTERS;

public final class ParallelCommandRegistration {

    private ParallelCommandRegistration() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(createRoot("parallel"));
        dispatcher.register(createRoot("pc"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createRoot(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder
                .<CommandSourceStack>literal(name)
                .requires(Commands.hasPermission(LEVEL_GAMEMASTERS))
                .executes(ParallelCommandRegistration::showOverview);

        for (ParallelSubCommand sub : ParallelCommand.subCommands().values()) {
            LiteralArgumentBuilder<CommandSourceStack> node = LiteralArgumentBuilder.literal(sub.getName());
            sub.build(node);
            root.then(node);
        }
        return root;
    }

    private static int showOverview(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(
                () -> Component.literal(ParallelCommandResponses.overview(ParallelCommand.subCommands().values())),
                false);
        return 1;
    }
}
