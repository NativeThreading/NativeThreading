package com.github.uright008.benchmark;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Test-only entrypoint. All state and world access stay on the server thread. */
public final class PathfindingBenchmark implements ModInitializer {
    private BenchmarkScene scene;
    private BenchmarkRunStage run;
    private AllayScene allay;

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("pathbench.enabled")) {
            throw new IllegalStateException("Test mod requires -Dpathbench.enabled=true and a disposable world");
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("pathbench")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.literal("setup")
                    .then(Commands.argument("scene", StringArgumentType.word())
                        .then(Commands.argument("requests", IntegerArgumentType.integer(1, 256))
                            .executes(context -> {
                                if (run != null || allay != null) throw error("Benchmark busy; use a fresh server to change mode");
                                String name = StringArgumentType.getString(context, "scene");
                                if (!name.equals("open") && !name.equals("maze") && !name.equals("blocked")) {
                                    throw error("Expected open, maze, or blocked");
                                }
                                try {
                                    scene = BenchmarkScene.create(context.getSource().getServer().overworld(),
                                        name, IntegerArgumentType.getInteger(context, "requests"));
                                } catch (RuntimeException exception) {
                                    org.slf4j.LoggerFactory.getLogger("pathbench").error("Fixture setup failed", exception);
                                    throw exception;
                                }
                                context.getSource().sendSuccess(() -> Component.literal("PATHBENCH READY"), false);
                                return 1;
                            }))))
                .then(Commands.literal("allay")
                    .then(Commands.argument("entities", IntegerArgumentType.integer(64))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                            .executes(context -> {
                                if (scene != null || allay != null) throw error("Use a fresh server for each fixture");
                                try {
                                    allay = new AllayScene(context.getSource().getServer().overworld(),
                                        IntegerArgumentType.getInteger(context, "entities"), LongArgumentType.getLong(context, "seed"));
                                } catch (RuntimeException exception) {
                                    org.slf4j.LoggerFactory.getLogger("pathbench").error("Allay setup failed", exception);
                                    throw error(exception.getMessage());
                                }
                                context.getSource().sendSuccess(() -> Component.literal("PATHBENCH READY"), false);
                                return 1;
                            }))))
                .then(Commands.literal("run")
                    .then(Commands.argument("phase", StringArgumentType.word())
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 12000))
                            .executes(context -> {
                                if ((scene == null && allay == null) || run != null || (allay != null && allay.run != null)) {
                                    throw error("Setup required, or benchmark busy");
                                }
                                String phase = StringArgumentType.getString(context, "phase");
                                if (!phase.equals("warmup") && !phase.equals("measure")) {
                                    throw error("Expected warmup or measure");
                                }
                                if (allay != null) allay.run = new AllayRunStage(allay, phase, IntegerArgumentType.getInteger(context, "ticks"));
                                else run = new BenchmarkRunStage(scene, phase, IntegerArgumentType.getInteger(context, "ticks"));
                                context.getSource().sendSuccess(() -> Component.literal("PATHBENCH STARTED"), false);
                                return 1;
                            }))))));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (allay != null) allay.startTick();
            if (run != null) run.startTick();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (allay != null) allay.endTick();
            if (run != null && run.endTick()) run = null;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            run = null;
            scene = null;
            allay = null;
            AllayScene.clear();
        });
    }

    private static com.mojang.brigadier.exceptions.CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
