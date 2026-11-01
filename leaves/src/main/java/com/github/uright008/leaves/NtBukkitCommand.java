package com.github.uright008.leaves;

import com.github.uright008.nt.core.command.NtCommandResponses;
import com.github.uright008.nt.core.command.NtCommands;
import com.github.uright008.nt.core.command.NtSubCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Bukkit command-map bridge for {@code /nt} and {@code /native-threading}.
 *
 * <p>Paper-family servers do not expose their Brigadier dispatcher to plugins, so
 * the hardware-independent {@link NtSubCommand#executePaper} hook is used instead
 * of {@code NtCommandRegistration}. Access mirrors the in-game command's
 * gamemaster requirement: console, ops, or the {@code native-threading.admin}
 * permission node.</p>
 */
final class NtBukkitCommand extends Command {

    private static final String PERMISSION = "native-threading.admin";

    NtBukkitCommand() {
        super("nt", "NativeThreading control", "/nt <subsystem>", List.of("native-threading"));
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!isAdmin(sender)) {
            sender.sendMessage("§cYou do not have permission to use NativeThreading commands.");
            return true;
        }
        if (args.length == 0) {
            send(sender, NtCommandResponses.overview(NtCommands.subCommands().values()));
            return true;
        }
        NtSubCommand sub = NtCommands.subCommands().get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            send(sender, "§cUnknown subsystem: " + args[0] + "§7 — try /nt");
            return true;
        }
        List<String> lines = new ArrayList<>();
        sub.executePaper(lines::add, Arrays.copyOfRange(args, 1, args.length));
        if (lines.isEmpty()) {
            lines.add(sub.getStatusLine());
        }
        for (String line : lines) {
            send(sender, line);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!isAdmin(sender) || args.length == 0) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(new ArrayList<>(NtCommands.subCommands().keySet()), args[0]);
        }
        NtSubCommand sub = NtCommands.subCommands().get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            return List.of();
        }
        List<String> completions = sub.tabCompletePaper(Arrays.copyOfRange(args, 1, args.length));
        if (completions == null) {
            return List.of();
        }
        return filter(completions, args[args.length - 1]);
    }

    private static boolean isAdmin(CommandSender sender) {
        return sender.isOp() || sender.hasPermission(PERMISSION);
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(message);
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
