package com.github.uright008.nt.core.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NtCommandRegistrationTest {

    @Test
    void formatsOverviewInRegisteredNameOrder() {
        String response = NtCommandResponses.overview(List.of(testCommand("alpha"), testCommand("beta")));

        assertThat(response).isEqualTo("\u00a76--- Native Threading ---\n"
                + "\u00a77  alpha status\n"
                + "\u00a77  beta status\n"
                + "\u00a77Usage: /nt <subsystem>  |  /native-threading");
    }

    private static NtSubCommand testCommand(String name) {
        return new NtSubCommand() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void build(LiteralArgumentBuilder<CommandSourceStack> builder) {
                builder.executes(context -> 1);
            }

            @Override
            public String getStatusLine() {
                return "\u00a77  " + name + " status";
            }
        };
    }
}
