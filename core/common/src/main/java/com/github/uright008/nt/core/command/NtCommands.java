package com.github.uright008.nt.core.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public final class NtCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("native-threading-core");
    private static final Map<String, NtSubCommand> SUB_COMMANDS = new ConcurrentSkipListMap<>();

    private NtCommands() {}

    public static void registerSubCommand(NtSubCommand sub) {
        SUB_COMMANDS.put(sub.getName(), sub);
        LOGGER.info("Registered /nt {} subcommand", sub.getName());
    }

    public static Map<String, NtSubCommand> subCommands() {
        return SUB_COMMANDS;
    }
}
