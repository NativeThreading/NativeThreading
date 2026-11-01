package com.github.uright008.nt.core.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * Interface for mods that want to add subcommands under /nt.
 *
 * Implement this interface and call
 * {@link NtCommands#registerSubCommand(NtSubCommand)} during
 * your mod's initialization to add your subcommand.
 *
 * <pre>
 * // Example: adding /nt explosion
 * NtCommands.registerSubCommand(new NtSubCommand() {
 *     public String getName() { return "explosion"; }
 *     public void build(LiteralArgumentBuilder&lt;CommandSourceStack&gt; builder) {
 *         builder.executes(ctx -&gt; showStatus(ctx))
 *                .then(Commands.argument("enabled", BoolArgumentType.bool())
 *                         .executes(ctx -&gt; setEnabled(ctx)));
 *     }
 *     public String getStatusLine() { return "  Explosion: " + (enabled ? "ON" : "OFF"); }
 * });
 * </pre>
 */
public interface NtSubCommand {

    /**
     * The literal name of the subcommand, e.g. "explosion".
     * This becomes: /nt <getName()>
     */
    String getName();

    /**
     * Build the argument tree for this subcommand.
     * The builder is a literal node with the name already set.
     * Add your arguments and execution callbacks here.
     */
    void build(LiteralArgumentBuilder<CommandSourceStack> builder);

    /**
     * A one-line status string shown in /nt overview.
     * e.g. "§7  Explosion: §aON"
     */
    String getStatusLine();

    /**
     * Platform-shell execution path for hosts without a Brigadier dispatcher (the
     * Leaves/Paper plugin shell bridges /nt through the Bukkit command map).
     *
     * <p>Implementations must accept the same argument forms as {@link #build} and
     * emit human-readable lines through {@code output}. The default no-op lets the
     * shell fall back to {@link #getStatusLine()}.</p>
     *
     * @param output receives one line per message, legacy formatting codes included
     * @param args   arguments after the subcommand name (may be empty)
     */
    default void executePaper(Consumer<String> output, String[] args) {}

    /**
     * Tab completion counterpart of {@link #executePaper}; {@code args} are the
     * arguments after the subcommand name. Returning {@code null} disables completion.
     */
    default List<String> tabCompletePaper(String[] args) {
        return null;
    }
}
