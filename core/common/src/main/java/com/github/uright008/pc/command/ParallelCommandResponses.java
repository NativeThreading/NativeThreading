package com.github.uright008.pc.command;

import java.util.Collection;

public final class ParallelCommandResponses {

    private ParallelCommandResponses() {}

    public static String overview(Collection<ParallelSubCommand> subCommands) {
        StringBuilder response = new StringBuilder("\u00a76--- Parallel Systems ---\n");
        if (subCommands.isEmpty()) {
            response.append("\u00a77  (no subsystems registered)\n");
        } else {
            for (ParallelSubCommand subCommand : subCommands) {
                response.append(subCommand.getStatusLine()).append("\n");
            }
        }
        return response.append("\u00a77Usage: /parallel <subsystem>  |  /pc").toString();
    }
}
