package com.github.uright008.ep;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.command.ParallelSubCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Implements /parallel explosion subcommand.
 * Registered with parallel-core via {@link ParallelSubCommand}.
 */
public final class ExplosionParallelCommand implements ParallelSubCommand {

    // ── ParallelSubCommand interface ─────────────

    @Override
    public String getName() {
        return "explosion";
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder
                .executes(this::showStatus)
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(this::setEnabled))
                .then(Commands.literal("reload")
                        .executes(this::reloadConfig));
    }

    @Override
    public String getStatusLine() {
        boolean on = ExplosionParallelConfig.isEnabled();
        return "§7  Explosion: " + (on ? "§aON" : "§cOFF")
                + " §7pool=" + ParallelThreadPool.getParallelism();
    }

    // ── Command implementations ──────────────────

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        boolean on = ExplosionParallelConfig.isEnabled();
        int poolSize = ParallelThreadPool.getParallelism();
        Component msg = Component.literal(
                "§e/parallel explosion\n" +
                "§7  Status:       " + (on ? "§aON" : "§cOFF") + "\n" +
                "§7  ThreadPool:   §a" + poolSize + " workers\n" +
                "§7Usage: /parallel explosion [on|off|reload]"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        ExplosionParallelConfig.setEnabled(enabled);
        Component msg = Component.literal("§aParallel explosion is now " + (enabled ? "§eON" : "§cOFF"));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        ExplosionParallelConfig.reloadConfig();
        Component msg = Component.literal("§aConfig reloaded from file.");
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }
}
