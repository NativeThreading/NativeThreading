package com.github.uright008.benchmark;

import com.google.gson.GsonBuilder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread phase accounting. Distinguishes inline and delayed vanilla recomputations. */
final class BlockUpdateRunStage {
    private final BlockUpdateScene scene;
    private final String phase;
    private final int ticks;
    private final boolean initialSolid;
    private final double[] lastX;
    private final double[] lastZ;
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private final List<Double> queryMs = new ArrayList<>();
    private final List<Double> latency = new ArrayList<>();
    private final Set<Integer> dead = new HashSet<>();
    private final Set<Integer> escaped = new HashSet<>();
    final List<Double> eventActive = new ArrayList<>();
    final IdentityHashMap<Mob, Integer> pending = new IdentityHashMap<>();
    private long tickStart;
    private long initialEntityTicks;
    private long reached;
    private long partial;
    private long nullPaths;
    int tick;
    int activeBefore;
    int completedLegs;
    int blockChanges;
    int shouldChecks;
    int shouldPositive;
    int updateRequests;
    int delayedAttempts;
    int recomputeDepth;
    private int patrol;
    private int immediate;
    private int delayed;
    private double searchMs;
    double blockUpdateMs;

    BlockUpdateRunStage(BlockUpdateScene scene, String phase, int ticks) {
        if (ticks < Math.max(100, scene.interval + 21) || ticks > 12000) {
            throw new IllegalArgumentException("Phase needs at least max(100, interval+21) ticks");
        }
        this.scene = scene;
        this.phase = phase;
        this.ticks = ticks;
        initialSolid = scene.solid;
        lastX = new double[scene.mobs.length];
        lastZ = new double[scene.mobs.length];
        if (Files.exists(java.nio.file.Path.of("pathbench-" + phase + ".json"))) {
            throw new IllegalStateException("Refusing to overwrite phase output");
        }
    }

    boolean inTick() { return tickStart != 0; }
    boolean isEvent() { return BlockUpdateScenarioHelper.eventAt(tick + 1, ticks, scene.interval); }

    void startTick() {
        tickStart = System.nanoTime();
        if (tick == 0) {
            initialEntityTicks = scene.entityTicks();
            for (int i = 0; i < scene.mobs.length; i++) {
                lastX[i] = scene.mobs[i].getX();
                lastZ[i] = scene.mobs[i].getZ();
            }
        }
        completedLegs = 0;
        blockChanges = shouldChecks = shouldPositive = updateRequests = delayedAttempts = patrol = immediate = delayed = 0;
        searchMs = blockUpdateMs = 0;
    }

    void searchFinished(Mob mob, Path path, long nanos) {
        double ms = nanos / 1e6;
        searchMs += ms;
        queryMs.add(ms);
        if (path == null) nullPaths++;
        else if (path.canReach()) reached++;
        else partial++;
        if (recomputeDepth == 0) patrol++;
        else {
            if (scene.changingBlocks) immediate++;
            else delayed++;
            Integer requestTick = pending.remove(mob);
            if (requestTick == null) throw new IllegalStateException("Recompute without observed block invalidation");
            latency.add((double) (tick - requestTick));
        }
    }

    boolean endTick() {
        if (recomputeDepth != 0) throw new IllegalStateException("Unbalanced recompute hook");
        int moving = 0;
        double distance = 0;
        for (int i = 0; i < scene.mobs.length; i++) {
            var mob = scene.mobs[i];
            double dx = mob.getX() - lastX[i], dz = mob.getZ() - lastZ[i];
            double squared = dx * dx + dz * dz;
            if (squared > 1e-6) moving++;
            distance += Math.sqrt(squared);
            lastX[i] = mob.getX();
            lastZ[i] = mob.getZ();
            if (!mob.isAlive()) dead.add(i);
            var box = mob.getBoundingBox();
            if (box.minX < 1 - 1e-5 || box.maxX > 64 + 1e-5 || box.minZ < 1 - 1e-5 || box.maxZ > 32 + 1e-5
                || box.minY < 80 - 1e-5 || box.maxY > 85 + 1e-5) escaped.add(i);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tick", tick + 1);
        row.put("update_event", isEvent());
        row.put("block_changes", blockChanges);
        row.put("active_before", activeBefore);
        row.put("should_checks", shouldChecks);
        row.put("should_positive", shouldPositive);
        row.put("update_recompute_calls", updateRequests);
        row.put("delayed_attempts", delayedAttempts);
        row.put("recompute_calls", updateRequests + delayedAttempts);
        row.put("path_searches", patrol + immediate + delayed);
        row.put("patrol_searches", patrol);
        row.put("recompute_searches", immediate + delayed);
        row.put("immediate_searches", immediate);
        row.put("delayed_searches", delayed);
        row.put("search_ms", searchMs);
        row.put("block_update_ms", blockUpdateMs);
        row.put("navigating", scene.navigating());
        row.put("pending", pending.size());
        row.put("moving", moving);
        row.put("distance_moved", distance);
        row.put("completed_legs", completedLegs);
        row.put("mspt", (System.nanoTime() - tickStart) / 1e6);
        samples.add(row);
        tickStart = 0;
        if (++tick != ticks) return false;
        writeResult();
        return true;
    }

    private long sum(String key) {
        return samples.stream().mapToLong(row -> ((Number) row.get(key)).longValue()).sum();
    }

    private Map<String, Double> distribution(String value, java.util.function.Predicate<Map<String, Object>> filter) {
        return AllayScenarioHelper.statistics(samples.stream().filter(filter)
            .mapToDouble(row -> ((Number) row.get(value)).doubleValue()).toArray());
    }

    private void writeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 3);
        result.put("scene", "block-updates");
        result.put("entities", scene.mobs.length);
        result.put("seed", scene.seed);
        result.put("ticks", ticks);
        result.put("update_interval", scene.interval);
        result.put("block_change", scene.change);
        result.put("blocks_per_event", 9);
        result.put("update_events", eventActive.size());
        for (String key : List.of("block_changes", "path_searches", "patrol_searches", "recompute_searches",
                "immediate_searches", "delayed_searches", "should_checks", "should_positive", "recompute_calls",
                "update_recompute_calls", "delayed_attempts")) result.put(key, sum(key));
        result.put("path_reached", reached);
        result.put("path_partial", partial);
        result.put("path_null", nullPaths);
        result.put("pending_end", pending.size());
        result.put("entity_ticks", scene.entityTicks() - initialEntityTicks);
        result.put("gap_entity_ticks", scene.lastPhaseEntityTicks < 0 ? 0 : initialEntityTicks - scene.lastPhaseEntityTicks);
        scene.lastPhaseEntityTicks = scene.entityTicks();
        result.put("alive_end", java.util.Arrays.stream(scene.mobs).filter(m -> m.isAlive()).count());
        result.put("dead", dead.size());
        result.put("escaped", escaped.size());
        result.put("search_ms", queryMs.stream().mapToDouble(Double::doubleValue).sum());
        result.put("query_ms", AllayScenarioHelper.statistics(queryMs.stream().mapToDouble(Double::doubleValue).toArray()));
        result.put("tick_ms", distribution("mspt", row -> true));
        result.put("block_update_ms", distribution("block_update_ms", row -> (boolean) row.get("update_event")));
        result.put("update_tick_ms", distribution("mspt", row -> (boolean) row.get("update_event")));
        result.put("non_update_tick_ms", distribution("mspt", row -> !(boolean) row.get("update_event")));
        result.put("recompute_tick_ms", distribution("mspt", row -> ((Number) row.get("recompute_searches")).intValue() > 0));
        result.put("quiet_tick_ms", distribution("mspt", row -> ((Number) row.get("recompute_searches")).intValue() == 0));
        result.put("recompute_latency_ticks", AllayScenarioHelper.statistics(latency.stream().mapToDouble(Double::doubleValue).toArray()));
        result.put("navigation_active_fraction", sum("navigating") / ((double) ticks * scene.mobs.length));
        result.put("moving_fraction", sum("moving") / ((double) ticks * scene.mobs.length));
        result.put("distance_moved", samples.stream().mapToDouble(row -> ((Number) row.get("distance_moved")).doubleValue()).sum());
        result.put("completed_legs", sum("completed_legs"));
        result.put("event_active_min", eventActive.stream().mapToInt(Double::intValue).min().orElseThrow());
        result.put("event_active_mean", eventActive.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        result.put("schedule_hash", "cow-grid-v1:" + scene.seed + ":" + scene.change + ":" + scene.interval + ":" + ticks + ":" + initialSolid);
        result.put("tick_samples", samples);
        var output = java.nio.file.Path.of("pathbench-" + phase + ".json");
        var temporary = java.nio.file.Path.of(output + ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
