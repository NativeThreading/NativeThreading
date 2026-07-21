package com.github.uright008.ep;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
                .then(Commands.literal("sampling")
                        .executes(this::showSampling)
                        .then(Commands.argument("quality", StringArgumentType.word())
                                .suggests((ctx, sb) ->
                                        sb.suggest("accurate").suggest("fast").suggest("aggressive").buildFuture())
                                .executes(this::setSampling)))
                .then(Commands.literal("raylookup")
                        .executes(this::showRayLookup)
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(this::setRayLookup)))
                .then(Commands.literal("adaptiveRays")
                        .executes(this::showAdaptiveRays)
                        .then(Commands.argument("gridSize", IntegerArgumentType.integer(0, 16))
                                .executes(this::setAdaptiveRays)))
                .then(Commands.literal("preciseRays")
                        .executes(this::showPreciseRays)
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                .executes(this::setPreciseRays)))
                .then(Commands.literal("reload")
                        .executes(this::reloadConfig));
    }

    @Override
    public String getStatusLine() {
        boolean on = ExplosionParallelConfig.isEnabled();
        return "§7  Explosion: " + (on ? "§aON" : "§cOFF")
                + " §7[" + ExplosionParallelConfig.getSamplingQuality() + "]"
                + " §7pool=" + ParallelThreadPool.getParallelism();
    }

    // ── Command implementations ──────────────────

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        boolean on = ExplosionParallelConfig.isEnabled();
        String quality = ExplosionParallelConfig.getSamplingQuality();
        int samples = estimateSamples(quality);
        int gs = ExplosionParallelConfig.getAdaptiveRays();
        int poolSize = ParallelThreadPool.getParallelism();
        Component msg = Component.literal(
                "§e/parallel explosion\n" +
                "§7  Status:       " + (on ? "§aON" : "§cOFF") + "\n" +
                "§7  Sampling:     " + quality + " (§7~" + samples + " samples/entity§7)\n" +
                "§7  RayLookup:    " + (ExplosionParallelConfig.isRayLookup() ? "§aON" : "§cOFF") + "\n" +
                "§7  AdaptiveRays: " + (gs > 0 ? "§a" + gs : "§cOFF (vanilla 16)") + "\n" +
                "§7  PreciseRays:  " + (ExplosionParallelConfig.isPreciseRays() ? "§aON" : "§6fast-delta") + "\n" +
                "§7  ThreadPool:   §a" + poolSize + " workers\n" +
                "§7Usage: /parallel explosion [on|off|sampling|raylookup|adaptiveRays|reload]"
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

    private int showSampling(CommandContext<CommandSourceStack> ctx) {
        String quality = ExplosionParallelConfig.getSamplingQuality();
        Component msg = Component.literal(
                "§eSampling quality: §f" + quality + " (§7~" + estimateSamples(quality) + " samples/entity§7)\n" +
                "§7Usage: /parallel explosion sampling [accurate|fast|aggressive]"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setSampling(CommandContext<CommandSourceStack> ctx) {
        String quality = StringArgumentType.getString(ctx, "quality").toLowerCase();
        ExplosionParallelConfig.setSamplingQuality(quality);
        Component msg = Component.literal("§aSampling quality set to §e" + ExplosionParallelConfig.getSamplingQuality() +
                " (§7~" + estimateSamples(quality) + " samples/entity§7)");
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int showRayLookup(CommandContext<CommandSourceStack> ctx) {
        boolean on = ExplosionParallelConfig.isRayLookup();
        Component msg = Component.literal(
                "§eRay lookup: " + (on ? "§aON" : "§cOFF") + "\n" +
                "§7Fast getSeenPercent using pre-computed ray distances (~40x).\n" +
                "§7Usage: /parallel explosion raylookup <true|false>"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setRayLookup(CommandContext<CommandSourceStack> ctx) {
        boolean on = BoolArgumentType.getBool(ctx, "enabled");
        ExplosionParallelConfig.setRayLookup(on);
        Component msg = Component.literal("§aRay lookup is now " + (on ? "§eON" : "§cOFF"));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int showAdaptiveRays(CommandContext<CommandSourceStack> ctx) {
        int gs = ExplosionParallelConfig.getAdaptiveRays();
        Component msg = Component.literal(
                "§eAdaptive rays: " + (gs > 0 ? "§a" + gs : "§cOFF (vanilla 16)") + "\n" +
                "§7Reduces ray count for smaller explosions.\n" +
                "§7  8=fast(~296 rays)  12=balanced(~728)  0=off(vanilla 1352)\n" +
                "§7Usage: /parallel explosion adaptiveRays <0..16>"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setAdaptiveRays(CommandContext<CommandSourceStack> ctx) {
        int gs = IntegerArgumentType.getInteger(ctx, "gridSize");
        ExplosionParallelConfig.setAdaptiveRays(gs);
        Component msg = Component.literal("§aAdaptive rays: " + (gs > 0 ? "§e" + gs : "§cOFF (vanilla 16)"));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int showPreciseRays(CommandContext<CommandSourceStack> ctx) {
        boolean on = ExplosionParallelConfig.isPreciseRays();
        Component msg = Component.literal(
                "§ePrecise rays: " + (on ? "§aON" : "§6fast-delta") + "\n" +
                "§7Uses float accumulation from exact centre (vanilla behaviour).\n" +
                "§7Turn off for faster integer-delta stepping (slight block traversal difference).\n" +
                "§7Usage: /parallel explosion preciseRays <true|false>"
        );
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int setPreciseRays(CommandContext<CommandSourceStack> ctx) {
        boolean on = BoolArgumentType.getBool(ctx, "enabled");
        ExplosionParallelConfig.setPreciseRays(on);
        Component msg = Component.literal("§aPrecise rays: " + (on ? "§eON" : "§6fast-delta"));
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        ExplosionParallelConfig.reloadConfig();
        Component msg = Component.literal("§aConfig reloaded from file.");
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private static int estimateSamples(String quality) {
        return switch (quality) {
            case "fast" -> 24;
            case "aggressive" -> 12;
            default -> 45;
        };
    }
}
