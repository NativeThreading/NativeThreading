package com.github.uright008.pc.command;

import com.github.uright008.pc.ParallelCoreConfig;
import com.github.uright008.pc.simd.SimdBatchOps;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public final class SimdCommand implements ParallelSubCommand {

    private static final boolean VECTOR_API_AVAILABLE = ModuleLayer.boot()
            .findModule("jdk.incubator.vector").isPresent();

    @Override
    public String getName() { return "simd"; }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> builder) {
        builder
                .executes(this::showStatus)
                .then(Commands.literal("on")
                        .executes(ctx -> setEnabled(ctx, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> setEnabled(ctx, false)));
    }

    @Override
    public String getStatusLine() {
        boolean on = ParallelCoreConfig.simdEnabled();
        boolean vec = SimdBatchOps.VECTORIAL_AVAILABLE;
        String hint = !VECTOR_API_AVAILABLE ? " \u00a7c(no jdk.incubator.vector)"
                      : !vec ? " \u00a77(vectorial not loaded)" : "";
        return "\u00a77  SIMD:      " + (on ? "\u00a7aON" : "\u00a7cOFF") + hint;
    }

    private int showStatus(CommandContext<CommandSourceStack> ctx) {
        boolean on = ParallelCoreConfig.simdEnabled();
        boolean vec = SimdBatchOps.VECTORIAL_AVAILABLE;
        String jvmHint = VECTOR_API_AVAILABLE ? "\u00a7a available" : "\u00a7c missing (add --add-modules=jdk.incubator.vector)";
        String msg = "\u00a7e/parallel simd\n"
                + "\u00a77  Enabled:    " + (on ? "\u00a7aON" : "\u00a7cOFF") + "\n"
                + "\u00a77  Vectorial:  " + (vec ? "\u00a7a loaded" : "\u00a77 not loaded") + "\n"
                + "\u00a77  Vector API: " + jvmHint + "\n"
                + "\u00a77Usage: /parallel simd [on|off]";
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private int setEnabled(CommandContext<CommandSourceStack> ctx, boolean value) {
        if (value && !VECTOR_API_AVAILABLE) {
            ctx.getSource().sendFailure(
                    Component.literal("\u00a7cVector API not available. "
                            + "Add --add-modules=jdk.incubator.vector to JVM args."));
            return 0;
        }
        ParallelCoreConfig.setSimdEnabled(value);
        String note = value && !SimdBatchOps.VECTORIAL_AVAILABLE
                ? " (needs vectorial to take effect)" : "";
        ctx.getSource().sendSuccess(
                () -> Component.literal("\u00a7aSIMD " + (value ? "enabled" : "disabled") + note + "."),
                false);
        return 1;
    }

    @Override
    public void executePaper(Consumer<String> out, String[] args) {
        if (args.length == 0) {
            boolean on = ParallelCoreConfig.simdEnabled();
            boolean vec = SimdBatchOps.VECTORIAL_AVAILABLE;
            String jvmHint = VECTOR_API_AVAILABLE ? "\u00a7a available" : "\u00a7c missing (add --add-modules=jdk.incubator.vector)";
            out.accept("\u00a7e/parallel simd");
            out.accept("\u00a77  Enabled:    " + (on ? "\u00a7aON" : "\u00a7cOFF"));
            out.accept("\u00a77  Vectorial:  " + (vec ? "\u00a7a loaded" : "\u00a77 not loaded"));
            out.accept("\u00a77  Vector API: " + jvmHint);
            out.accept("\u00a77Usage: /parallel simd [on|off]");
            return;
        }
        String arg = args[0].toLowerCase();
        if (arg.equals("on")) {
            setEnabledPaper(out, true);
        } else if (arg.equals("off")) {
            setEnabledPaper(out, false);
        } else {
            out.accept("\u00a7cUnknown argument: " + arg + ". Use on or off.");
        }
    }

    @Override
    public List<String> tabCompletePaper(String[] args) {
        if (args.length == 1) {
            return List.of("on", "off");
        }
        return null;
    }

    private void setEnabledPaper(Consumer<String> out, boolean value) {
        if (value && !VECTOR_API_AVAILABLE) {
            out.accept("\u00a7cVector API not available. Add --add-modules=jdk.incubator.vector to JVM args.");
            return;
        }
        ParallelCoreConfig.setSimdEnabled(value);
        String note = value && !SimdBatchOps.VECTORIAL_AVAILABLE
                ? " (needs vectorial to take effect)" : "";
        out.accept("\u00a7aSIMD " + (value ? "enabled" : "disabled") + note + ".");
    }
}
