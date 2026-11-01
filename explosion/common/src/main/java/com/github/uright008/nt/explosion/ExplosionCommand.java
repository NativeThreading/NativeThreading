package com.github.uright008.nt.explosion;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.github.uright008.nt.core.NtThreadPool;
import com.github.uright008.nt.core.command.NtSubCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Implements /nt explosion subcommand.
 * Registered with native-threading-core via {@link NtSubCommand}.
 */
public final class ExplosionCommand implements NtSubCommand {

    // ── NtSubCommand interface ─────────────

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
                        .executes(this::reloadConfig))
                .then(Commands.literal("stats")
                        .executes(this::showStats)
                        .then(Commands.literal("reset")
                                .executes(this::resetStats)));
    }

    @Override
    public String getStatusLine() {
        boolean on = ExplosionConfig.isEnabled();
        return "§7  Explosion: " + (on ? "§aON" : "§cOFF")
                + " §7pool=" + NtThreadPool.getParallelism();
    }

    @Override
    public void executePaper(Consumer<String> output, String[] args) {
        if (args.length == 0) {
            output.accept(statusText());
            return;
        }
        switch (args[0]) {
            case "reload" -> {
                ExplosionConfig.reloadConfig();
                output.accept("§aConfig reloaded from file.");
            }
            case "stats" -> {
                if (args.length > 1 && "reset".equals(args[1])) {
                    resetStatsState();
                    output.accept("§aExplosion stats reset.");
                } else {
                    output.accept(statsText());
                }
            }
            case "on", "true" -> setEnabledFromPaper(true, output);
            case "off", "false" -> setEnabledFromPaper(false, output);
            default -> output.accept("§cUnknown argument: " + args[0]
                    + " §7(usage: /nt explosion [on|off|reload|stats [reset]])");
        }
    }

    @Override
    public List<String> tabCompletePaper(String[] args) {
        if (args.length <= 1) {
            return List.of("on", "off", "reload", "stats");
        }
        if (args.length == 2 && "stats".equals(args[0])) {
            return List.of("reset");
        }
        return List.of();
    }

    // ── Command implementations ──────────────────

    private static String statusText() {
        boolean on = ExplosionConfig.isEnabled();
        int poolSize = NtThreadPool.getParallelism();
        String cacheStats = ExplosionChunkGridCache.statsLine();
        return "§e/nt explosion\n" +
                "§7  Status:       " + (on ? "§aON" : "§cOFF") + "\n" +
                "§7  ThreadPool:   §a" + poolSize + " workers\n" +
                "§7  " + cacheStats + "\n" +
                "§7Usage: /nt explosion [on|off|reload|stats [reset]]";
    }

    private static String statsText() {
        StringBuilder msg = new StringBuilder("§e[Explosion Stats]\n");
        for (String line : ExplosionStats.lines()) {
            msg.append("§7  ").append(line).append('\n');
        }
        msg.append("§7  ").append(ExplosionStats.paramsLine()).append('\n');
        msg.append("§7  reset with §a/nt explosion stats reset");
        return msg.toString();
    }

    private static void resetStatsState() {
        ExplosionStats.reset();
        ExplosionChunkGridCache.resetStats();
    }

    private static void setEnabledFromPaper(boolean enabled, Consumer<String> output) {
        ExplosionConfig.setEnabled(enabled);
        output.accept("§aExplosion pipeline is now " + (enabled ? "§eON" : "§cOFF"));
    }

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        Component msg = Component.literal(statusText());
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int showStats(CommandContext<CommandSourceStack> ctx) {
        Component component = Component.literal(statsText());
        ctx.getSource().sendSuccess(() -> component, false);
        return 1;
    }

    private int resetStats(CommandContext<CommandSourceStack> ctx) {
        resetStatsState();
        ctx.getSource().sendSuccess(
                () -> Component.literal("§aExplosion stats reset."), false);
        return 1;
    }

    private int setEnabled(CommandContext<CommandSourceStack> ctx) {
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        ExplosionConfig.setEnabled(enabled);
        Component msg = Component.literal("§aExplosion pipeline is now " + (enabled ? "§eON" : "§cOFF"));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        ExplosionConfig.reloadConfig();
        Component msg = Component.literal("§aConfig reloaded from file.");
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }
}
