package com.github.uright008.nt.core.command;

import java.util.Collection;

public final class NtCommandResponses {

    private NtCommandResponses() {}

    public static String overview(Collection<NtSubCommand> subCommands) {
        StringBuilder response = new StringBuilder("\u00a76--- Native Threading ---\n");
        if (subCommands.isEmpty()) {
            response.append("\u00a77  (no subsystems registered)\n");
        } else {
            for (NtSubCommand subCommand : subCommands) {
                response.append(subCommand.getStatusLine()).append("\n");
            }
        }
        return response.append("\u00a77Usage: /nt <subsystem>  |  /native-threading").toString();
    }
}
