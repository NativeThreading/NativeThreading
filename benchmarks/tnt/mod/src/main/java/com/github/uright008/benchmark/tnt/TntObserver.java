package com.github.uright008.benchmark.tnt;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class TntObserver implements ModInitializer {
    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("tnt.observer.enabled")) {
            throw new IllegalStateException("Observer requires -Dtnt.observer.enabled=true");
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            for (String mode : java.util.List.of("start", "ticks")) {
            dispatcher.register(Commands.literal("tntobserve").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.literal(mode)
                    .then(Commands.argument("warmup", IntegerArgumentType.integer(0, 3600))
                        .then(Commands.argument("duration", IntegerArgumentType.integer(1, 86400))
                            .then(Commands.argument("window", IntegerArgumentType.integer(1, 600))
                                .executes(context -> {
                                    TntObservationStage.begin(context.getSource().getServer(),
                                        IntegerArgumentType.getInteger(context, "warmup"),
                                        IntegerArgumentType.getInteger(context, "duration"),
                                        IntegerArgumentType.getInteger(context, "window"), mode.equals("ticks"));
                                    context.getSource().sendSuccess(() -> Component.literal("TNT_OBSERVER_STARTED"), false);
                                    return 1;
                                }))))));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(TntObservationStage::close);
    }
}
