package com.github.uright008.benchmark.tnt;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Passive main-thread observer. Never changes entities, blocks, RNG or tick rate. */
public final class TntObservationStage {
    // One server-owned observation, no world cache or worker access. Match server
    // identity on every callback and release the reference/files on completion/stop.
    private static TntObservationStage current;
    private final MinecraftServer server;
    private final int warmupWindows;
    private final int totalWindows;
    private final int windowSize;
    private final boolean tickMode;
    private final BufferedWriter windows;
    private final BufferedWriter raw;
    private final List<Double> timings = new ArrayList<>();
    private final StringBuilder rawBatch = new StringBuilder();
    private final Gson gson = new Gson();
    private long epoch;
    private long tickStart;
    private long totalTicks;
    private long tickExplosions;
    private long explosions;
    private long previousGcCount;
    private long previousGcMillis;
    private int windowIndex;
    private double windowStart;

    private TntObservationStage(MinecraftServer server, int warmup, int duration, int window, boolean tickMode) throws IOException {
        if (warmup % window != 0 || duration % window != 0) throw new IllegalArgumentException("Phases must divide into whole windows");
        for (String name : List.of("tnt-windows.jsonl", "tnt-ticks.csv", "tnt-complete.json", "tnt-environment.json")) {
            if (Files.exists(Path.of(name))) throw new IllegalStateException("Refusing stale observer output: " + name);
        }
        this.server = server;
        windowSize = window;
        this.tickMode = tickMode;
        warmupWindows = warmup / window;
        totalWindows = (warmup + duration) / window;
        List<Map<String, Object>> commands = new ArrayList<>();
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                var chunk = server.overworld().getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var block : chunk.getBlockEntities().values()) {
                    if (block instanceof CommandBlockEntity command) {
                        commands.add(Map.of("position", command.getBlockPos().toShortString(),
                            "command", command.getCommandBlock().getCommand()));
                    }
                }
            }
        }
        Files.writeString(Path.of("tnt-environment.json"), gson.toJson(Map.of("loaded_command_blocks", commands,
            "tnt_explodes", server.overworld().getGameRules().get(GameRules.TNT_EXPLODES),
            "mode", tickMode ? "ticks" : "time", "warmup", warmup, "duration", duration, "window", window)) + "\n");
        windows = Files.newBufferedWriter(Path.of("tnt-windows.jsonl"));
        raw = Files.newBufferedWriter(Path.of("tnt-ticks.csv"));
        raw.write("tick_index,elapsed_seconds,mspt,explosions\n");
        raw.flush();
        previousGcCount = gcCount();
        previousGcMillis = gcMillis();
    }

    public static void begin(MinecraftServer server, int warmup, int duration, int window, boolean tickMode) {
        if (current != null) throw new IllegalStateException("Observation already active");
        try {
            current = new TntObservationStage(server, warmup, duration, window, tickMode);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static void tickStart(MinecraftServer server) {
        TntObservationStage stage = current;
        if (stage == null || stage.server != server) return;
        stage.tickStart = System.nanoTime();
        if (stage.epoch == 0) stage.epoch = stage.tickStart;
        stage.tickExplosions = 0;
    }

    public static void exploded(PrimedTnt tnt) {
        TntObservationStage stage = current;
        if (stage != null && stage.tickStart != 0 && tnt.level() == stage.server.overworld()
                && stage.server.overworld().getGameRules().get(GameRules.TNT_EXPLODES)) stage.tickExplosions++;
    }

    public static void tickEnd(MinecraftServer server) {
        long end = System.nanoTime();
        TntObservationStage stage = current;
        if (stage == null || stage.server != server || stage.tickStart == 0) return;
        double elapsed = (end - stage.epoch) / 1e9;
        double ms = (end - stage.tickStart) / 1e6;
        stage.tickStart = 0;
        stage.timings.add(ms);
        stage.explosions += stage.tickExplosions;
        stage.rawBatch.append(++stage.totalTicks).append(',').append(elapsed).append(',').append(ms)
            .append(',').append(stage.tickExplosions).append('\n');
        if (stage.tickMode ? stage.timings.size() == stage.windowSize : elapsed >= (stage.windowIndex + 1L) * stage.windowSize) {
            try {
                stage.publish(elapsed);
                if (stage.windowIndex == stage.totalWindows) {
                    close(server);
                    Files.writeString(Path.of("tnt-complete.json.tmp"), stage.gson.toJson(Map.of(
                        "mode", stage.tickMode ? "ticks" : "time", "windows", stage.windowIndex,
                        "ticks", stage.totalTicks, "elapsed_seconds", elapsed)) + "\n");
                    Files.move(Path.of("tnt-complete.json.tmp"), Path.of("tnt-complete.json"), StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private void publish(double elapsed) throws IOException {
        Map<String, Object> row = TntWindowHelper.statistics(timings.stream().mapToDouble(Double::doubleValue).toArray());
        double seconds = elapsed - windowStart;
        row.put("index", windowIndex);
        row.put("phase", windowIndex < warmupWindows ? "warmup" : "measure");
        row.put("start_seconds", windowStart);
        row.put("end_seconds", elapsed);
        row.put("seconds", seconds);
        row.put("ticks", timings.size());
        row.put("tps_over_window", timings.size() / seconds);
        row.put("explosions", explosions);
        row.put("explosions_per_tick", (double) explosions / timings.size());
        row.put("explosions_per_second", explosions / seconds);
        int entities = 0, tnt = 0, items = 0;
        for (var entity : server.overworld().getAllEntities()) {
            entities++;
            if (entity instanceof PrimedTnt) tnt++;
            if (entity instanceof ItemEntity) items++;
        }
        row.put("entities", entities);
        row.put("tnt_entities", tnt);
        row.put("item_entities", items);
        row.put("loaded_chunks", server.overworld().getChunkSource().getLoadedChunksCount());
        row.put("heap_used_mb", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576.0);
        long count = gcCount(), millis = gcMillis();
        row.put("gc_count", count - previousGcCount);
        row.put("gc_ms", millis - previousGcMillis);
        previousGcCount = count;
        previousGcMillis = millis;
        raw.write(rawBatch.toString());
        raw.flush();
        windows.write(gson.toJson(row) + "\n");
        windows.flush();
        windowStart = elapsed;
        windowIndex++;
        timings.clear();
        rawBatch.setLength(0);
        explosions = 0;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum();
    }

    public static void close(MinecraftServer server) {
        TntObservationStage stage = current;
        if (stage == null || stage.server != server) return;
        current = null;
        try {
            stage.raw.close();
            stage.windows.close();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
