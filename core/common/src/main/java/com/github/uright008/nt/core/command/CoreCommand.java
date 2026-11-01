package com.github.uright008.nt.core.command;

import com.github.uright008.nt.core.CoreConfig;
import com.github.uright008.nt.core.NtThreadPool;
import com.github.uright008.nt.core.PoolImplementation;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class CoreCommand implements NtSubCommand {

    @Override
    public String getName() { return "core"; }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder
                .executes(this::showStatus)
                .then(Commands.literal("reload")
                        .executes(this::reloadConfig))
                .then(Commands.literal("pool")
                        .executes(this::showPool)
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (PoolImplementation impl : PoolImplementation.values())
                                        b.suggest(impl.name());
                                    return b.buildFuture();
                                })
                                .executes(this::setPool)))
                .then(Commands.literal("parallelism")
                        .executes(this::showParallelism)
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(this::setParallelism)));
    }

    @Override
    public String getStatusLine() {
        return "\u00a77  Core:      " + CoreConfig.poolImplementation().name()
                + " x" + NtThreadPool.getParallelism();
    }

    @Override
    public void executePaper(Consumer<String> output, String[] args) {
        if (args.length == 0) {
            output.accept(statusText());
            return;
        }
        switch (args[0]) {
            case "reload" -> {
                CoreConfig.reloadConfig();
                NtThreadPool.shutdownAll();
                output.accept("\u00a7aCore config reloaded — pools restarted on next use.");
            }
            case "pool" -> {
                if (args.length < 2) {
                    output.accept("\u00a7a" + CoreConfig.poolImplementation().name());
                    return;
                }
                PoolImplementation impl;
                try {
                    impl = PoolImplementation.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    output.accept("\u00a7cInvalid pool type: " + args[1]);
                    return;
                }
                CoreConfig.setPoolImplementation(impl);
                NtThreadPool.shutdownAll();
                output.accept("\u00a7aPool set to " + impl.name() + " — pools restarted on next use.");
            }
            case "parallelism" -> {
                if (args.length < 2) {
                    output.accept("\u00a7a" + NtThreadPool.getParallelism() + " workers");
                    return;
                }
                int count;
                try {
                    count = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    output.accept("\u00a7cNot a number: " + args[1]);
                    return;
                }
                if (count < 1) {
                    output.accept("\u00a7cParallelism must be >= 1");
                    return;
                }
                CoreConfig.setPoolParallelism(count);
                NtThreadPool.shutdownAll();
                output.accept("\u00a7aParallelism set to " + count + " — pools restarted on next use.");
            }
            default -> output.accept("\u00a7cUnknown argument: " + args[0]
                    + " \u00a77(usage: /nt core [pool <type>|parallelism <n>|reload])");
        }
    }

    @Override
    public List<String> tabCompletePaper(String[] args) {
        if (args.length <= 1) {
            return List.of("reload", "pool", "parallelism");
        }
        if (args.length == 2 && "pool".equals(args[0])) {
            List<String> names = new ArrayList<>();
            for (PoolImplementation impl : PoolImplementation.values()) {
                names.add(impl.name());
            }
            return names;
        }
        return List.of();
    }

    private static String statusText() {
        return "\u00a7e/nt core\n"
                + "\u00a77  Pool:          \u00a7a" + CoreConfig.poolImplementation().name() + "\n"
                + "\u00a77  Parallelism:   \u00a7a" + NtThreadPool.getParallelism() + "\n"
                + "\u00a77Usage: /nt core [pool <type>|parallelism <n>|reload]";
    }

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        Component msg = Component.literal(statusText());
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int showPool(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7a" + CoreConfig.poolImplementation().name()), false);
        return 1;
    }

    private int setPool(CommandContext<CommandSourceStack> ctx) {
        String type = StringArgumentType.getString(ctx, "type");
        PoolImplementation impl;
        try {
            impl = PoolImplementation.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("\u00a7cInvalid pool type: " + type));
            return 0;
        }
        CoreConfig.setPoolImplementation(impl);
        NtThreadPool.shutdownAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aPool set to " + impl.name() + " — pools restarted on next use."), false);
        return 1;
    }

    private int showParallelism(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7a" + NtThreadPool.getParallelism() + " workers"), false);
        return 1;
    }

    private int setParallelism(CommandContext<CommandSourceStack> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        CoreConfig.setPoolParallelism(count);
        NtThreadPool.shutdownAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aParallelism set to " + count + " — pools restarted on next use."), false);
        return 1;
    }

    private int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        CoreConfig.reloadConfig();
        NtThreadPool.shutdownAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aCore config reloaded — pools restarted on next use."), false);
        return 1;
    }
}
