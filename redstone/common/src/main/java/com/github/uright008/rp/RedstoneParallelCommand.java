package com.github.uright008.rp;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.github.uright008.pc.command.ParallelSubCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class RedstoneParallelCommand implements ParallelSubCommand {

    @Override
    public String getName() {
        return "redstone";
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder
                .executes(this::showStatus)
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(this::setEnabled))
                .then(Commands.literal("reload")
                        .executes(this::reloadConfig))
                .then(Commands.literal("wire")
                        .executes(this::showWire)
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(this::setWireEnabled))
                        .then(Commands.literal("threshold")
                                .then(Commands.argument("count", IntegerArgumentType.integer(2, 4096))
                                        .executes(this::setWireThreshold)))
                        .then(Commands.literal("workers")
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                        .executes(this::setMaxWorkers))));
    }

    @Override
    public String getStatusLine() {
        boolean on = RedstoneParallelConfig.isEnabled();
        boolean wireOn = RedstoneParallelConfig.isWireEnabled();
        return "\u00a77  Redstone:     " + (on ? "\u00a7aON" : "\u00a7cOFF")
                + " \u00a77wire=" + (wireOn ? "\u00a7aON" : "\u00a7cOFF");
    }

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        boolean wire = RedstoneParallelConfig.isWireEnabled();
        int threshold = RedstoneParallelConfig.wireThreshold();
        int workers = RedstoneParallelConfig.maxWorkers();
        Component msg = Component.literal(
                "\u00a7e/parallel redstone\n" +
                "\u00a77  Status:       " + (RedstoneParallelConfig.isEnabled() ? "\u00a7aON" : "\u00a7cOFF") + "\n" +
                "\u00a77  Wire:         " + (wire ? "\u00a7aON" : "\u00a7cOFF") + "\n" +
                "\u00a77  Threshold:    \u00a7a" + threshold + "\n" +
                "\u00a77  MaxWorkers:   \u00a7a" + workers + "\n" +
                "\u00a77Usage: /parallel redstone [on|off|reload|wire [on|off|threshold <n>|workers <n>]]"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        RedstoneParallelConfig.setEnabled(enabled);
        Component msg = Component.literal("\u00a7aRedstone is now " + (enabled ? "\u00a7eON" : "\u00a7cOFF"));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        RedstoneParallelConfig.reloadConfig();
        Component msg = Component.literal("\u00a7aRedstone config reloaded.");
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int showWire(CommandContext<CommandSourceStack> ctx) {
        boolean wire = RedstoneParallelConfig.isWireEnabled();
        int threshold = RedstoneParallelConfig.wireThreshold();
        int workers = RedstoneParallelConfig.maxWorkers();
        Component msg = Component.literal(
                "\u00a7e/parallel redstone wire\n" +
                "\u00a77  Status:       " + (wire ? "\u00a7aON" : "\u00a7cOFF") + "\n" +
                "\u00a77  Threshold:    \u00a7a" + threshold + "\n" +
                "\u00a77  MaxWorkers:   \u00a7a" + workers + "\n" +
                "\u00a77Usage: /parallel redstone wire [on|off|threshold <n>|workers <n>]"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setWireEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        RedstoneParallelConfig.setWireEnabled(enabled);
        Component msg = Component.literal("\u00a7aRedstone wire is now " + (enabled ? "\u00a7eON" : "\u00a7cOFF"));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setWireThreshold(CommandContext<CommandSourceStack> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        RedstoneParallelConfig.setWireThreshold(count);
        Component msg = Component.literal("\u00a7aWire threshold set to \u00a7e" + count);
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setMaxWorkers(CommandContext<CommandSourceStack> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        RedstoneParallelConfig.setMaxWorkers(count);
        Component msg = Component.literal("\u00a7aWire max workers set to \u00a7e" + count);
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }
}
