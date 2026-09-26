package com.github.uright008.benchmark;

import com.google.gson.GsonBuilder;
import net.minecraft.world.level.pathfinder.Path;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread measurement of naturally ticked AI; never submits or replaces a path. */
final class AllayRunStage {
    final String phase;
    final List<AllayDropLedger.Drop> drops = new ArrayList<>();
    final AllayScene scene;
    final int ticks;
    private final AllayScenarioHelper.Position[] schedule;
    private long initialEntityTicks;
    private final List<Double> queryMs = new ArrayList<>();
    private final List<Double> pickupTicks = new ArrayList<>();
    private final List<Double> pickupMs = new ArrayList<>();
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final Set<Integer> dead = new HashSet<>();
    private final Set<Integer> escaped = new HashSet<>();
    private final double[] tickMs;
    private final double[] maintenanceMs;
    private long tickStart;
    private int tick;
    private int nextDrop;
    private long pathReached;
    private long pathPartial;
    private long pathNull;
    private long navigatingTicks;
    private long carriedPickups;
    long inventoryResets;
    long inventoryItemsRemoved;
    long merges;
    long maintenanceNanos;

    AllayRunStage(AllayScene scene, String phase, int ticks) {
        if (ticks < 100 || ticks > 12000) throw new IllegalArgumentException("Allay phases require 100..12000 ticks");
        this.scene = scene;
        this.phase = phase;
        this.ticks = ticks;
        schedule = AllayScenarioHelper.schedule(scene.seed, phase, ticks);
        tickMs = new double[ticks];
        maintenanceMs = new double[ticks];
        if (Files.exists(java.nio.file.Path.of("pathbench-" + phase + ".json"))) {
            throw new IllegalStateException("Refusing to overwrite previous phase output");
        }
    }

    boolean inTick() { return tickStart != 0; }

    void startTick(long now) {
        if (tick == 0) initialEntityTicks = entityTicks();
        tickStart = now;
        maintenanceNanos = 0;
    }

    void dropItems() {
        if (tick % 20 == 0) {
            for (int layer = 0; layer < 8; layer++) scene.spawn(schedule[nextDrop++], this);
        }
    }

    void searchFinished(Path path, long nanos) {
        queryMs.add(nanos / 1e6);
        if (path == null) pathNull++;
        else if (path.canReach()) pathReached++;
        else pathPartial++;
    }

    void picked(List<AllayDropLedger.Drop> picked) {
        long now = System.nanoTime();
        for (var drop : picked) {
            if (drop.phase.equals(phase)) {
                pickupTicks.add((double) (scene.clock - drop.tick));
                pickupMs.add((now - drop.nanos) / 1e6);
            } else carriedPickups++;
        }
    }

    boolean endTick() {
        if (!inTick()) return false;
        long now = System.nanoTime();
        int navigating = 0;
        for (int i = 0; i < scene.mobs.length; i++) {
            var mob = scene.mobs[i];
            if (!mob.isAlive()) dead.add(i);
            if (mob.getNavigation().isInProgress()) navigating++;
            var box = mob.getBoundingBox();
            int floor = 64 + (i % 8) * 8;
            if (box.minX < 1 - 1e-5 || box.maxX > 15 + 1e-5 || box.minZ < 1 - 1e-5 || box.maxZ > 15 + 1e-5
                || box.minY < floor + 1 - 1e-5 || box.maxY > floor + 8 + 1e-5) escaped.add(i);
        }
        navigatingTicks += navigating;
        if ((tick + 1) % 20 == 0 || tick + 1 == ticks) {
            samples.add(Map.of("tick", tick + 1, "path_searches", queryMs.size(), "picked_up", pickupTicks.size(),
                "navigating", navigating, "remaining", drops.stream().filter(d -> d.status.equals("remaining")).count()));
        }
        maintenanceNanos += System.nanoTime() - now;
        maintenanceMs[tick] = maintenanceNanos / 1e6;
        tickMs[tick++] = (System.nanoTime() - tickStart) / 1e6;
        tickStart = 0;
        if (tick != ticks) return false;
        writeResult();
        return true;
    }

    private long entityTicks() {
        long count = 0;
        for (var mob : scene.mobs) count += mob.tickCount;
        return count;
    }

    private void writeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 2);
        result.put("scene", "allay");
        result.put("entities", scene.mobs.length);
        result.put("seed", scene.seed);
        result.put("ticks", ticks);
        result.put("layers", 8);
        result.put("drop_interval_ticks", 20);
        result.put("spawned", drops.size());
        result.put("picked_up", pickupTicks.size());
        result.put("remaining", drops.stream().filter(d -> d.status.equals("remaining")).count());
        result.put("lost", drops.stream().filter(d -> d.status.equals("lost")).count());
        result.put("carried_pickups", carriedPickups);
        result.put("inventory_resets", inventoryResets);
        result.put("inventory_items_removed", inventoryItemsRemoved);
        result.put("merges", merges);
        result.put("dead", dead.size());
        result.put("escaped", escaped.size());
        result.put("entity_ticks", entityTicks() - initialEntityTicks);
        result.put("gap_entity_ticks", scene.lastPhaseEntityTicks < 0 ? 0 : initialEntityTicks - scene.lastPhaseEntityTicks);
        scene.lastPhaseEntityTicks = entityTicks();
        result.put("alive_end", java.util.Arrays.stream(scene.mobs).filter(m -> m.isAlive()).count());
        result.put("path_searches", queryMs.size());
        result.put("path_reached", pathReached);
        result.put("path_partial", pathPartial);
        result.put("path_null", pathNull);
        result.put("search_ms", queryMs.stream().mapToDouble(Double::doubleValue).sum());
        result.put("query_ms", AllayScenarioHelper.statistics(queryMs.stream().mapToDouble(Double::doubleValue).toArray()));
        result.put("tick_ms", AllayScenarioHelper.statistics(tickMs));
        result.put("maintenance_ms", AllayScenarioHelper.statistics(maintenanceMs));
        result.put("pickup_latency_ticks", AllayScenarioHelper.statistics(pickupTicks.stream().mapToDouble(Double::doubleValue).toArray()));
        result.put("pickup_latency_ms", AllayScenarioHelper.statistics(pickupMs.stream().mapToDouble(Double::doubleValue).toArray()));
        result.put("navigation_active_fraction", navigatingTicks / ((double) ticks * scene.mobs.length));
        result.put("schedule_hash", scheduleHash());
        List<Map<String, Object>> layers = new ArrayList<>();
        for (int layer = 0; layer < 8; layer++) {
            int l = layer;
            int alive = 0;
            for (int i = layer; i < scene.mobs.length; i += 8) if (scene.mobs[i].isAlive()) alive++;
            layers.add(Map.of("spawned", drops.stream().filter(d -> d.layer == l).count(),
                "picked_up", drops.stream().filter(d -> d.layer == l && d.status.equals("picked_up")).count(),
                "remaining", drops.stream().filter(d -> d.layer == l && d.status.equals("remaining")).count(),
                "lost", drops.stream().filter(d -> d.layer == l && d.status.equals("lost")).count(), "alive_end", alive));
        }
        result.put("per_layer", layers);
        long now = System.nanoTime();
        result.put("uncollected", drops.stream().filter(d -> !d.status.equals("picked_up")).map(d -> Map.of(
            "layer", d.layer, "age_ticks", scene.clock - d.tick, "age_ms", (now - d.nanos) / 1e6, "status", d.status)).toList());
        result.put("samples", samples);
        var output = java.nio.file.Path.of("pathbench-" + phase + ".json");
        var temporary = java.nio.file.Path.of(output + ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private String scheduleHash() {
        long hash = 0xcbf29ce484222325L;
        for (var p : schedule) {
            hash = (hash ^ p.layer()) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(p.x())) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(p.y())) * 0x100000001b3L;
            hash = (hash ^ Double.doubleToLongBits(p.z())) * 0x100000001b3L;
        }
        return Long.toUnsignedString(hash, 16);
    }
}
