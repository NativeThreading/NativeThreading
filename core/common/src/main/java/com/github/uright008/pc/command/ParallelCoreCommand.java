package com.github.uright008.pc.command;

import com.github.uright008.pc.ParallelCoreConfig;
import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.PoolImplementation;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ParallelCoreCommand implements ParallelSubCommand {

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
        return "\u00a77  Core:      " + ParallelCoreConfig.poolImplementation().name()
                + " x" + ParallelThreadPool.getParallelism();
    }

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        Component msg = Component.literal(
                "\u00a7e/parallel core\n"
                + "\u00a77  Pool:          \u00a7a" + ParallelCoreConfig.poolImplementation().name() + "\n"
                + "\u00a77  Parallelism:   \u00a7a" + ParallelThreadPool.getParallelism() + "\n"
                + "\u00a77  SIMD:          " + (ParallelCoreConfig.simdEnabled() ? "\u00a7aON" : "\u00a7cOFF") + "\n"
                + "\u00a77Usage: /parallel core [pool <type>|parallelism <n>|reload]"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int showPool(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7a" + ParallelCoreConfig.poolImplementation().name()), false);
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
        ParallelCoreConfig.setPoolImplementation(impl);
        ParallelThreadPool.recreateAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aPool set to " + impl.name() + " — all pools recreated."), false);
        return 1;
    }

    private int showParallelism(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7a" + ParallelThreadPool.getParallelism() + " workers"), false);
        return 1;
    }

    private int setParallelism(CommandContext<CommandSourceStack> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        ParallelCoreConfig.setPoolParallelism(count);
        ParallelThreadPool.recreateAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aParallelism set to " + count + " — all pools recreated."), false);
        return 1;
    }

    private int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        ParallelCoreConfig.reloadConfig();
        ParallelThreadPool.recreateAll();
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aCore config reloaded — all pools recreated."), false);
        return 1;
    }
}
