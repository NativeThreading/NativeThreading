package com.github.uright008.pc.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

public final class ParallelCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallel-core");
    private static final Map<String, ParallelSubCommand> SUB_COMMANDS = new ConcurrentSkipListMap<>();

    private ParallelCommand() {}

    public static void registerSubCommand(ParallelSubCommand sub) {
        SUB_COMMANDS.put(sub.getName(), sub);
        LOGGER.info("Registered /parallel {} subcommand", sub.getName());
    }

    public static Map<String, ParallelSubCommand> subCommands() {
        return SUB_COMMANDS;
    }
}
