package com.github.uright008.benchmark;

import com.google.gson.GsonBuilder;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** One main-thread measurement phase; owns all counters, no shared caches or workers. */
final class BenchmarkRunStage {
    private final BenchmarkScene scene;
    private final String phase;
    private final int ticks;
    private final long[] batchNanos;
    private final long[] tickNanos;
    private final long[] queryNanos;
    private final String[] signatures;
    private int tick;
    private long tickStart;
    private long searchNanos;
    private long reached;
    private long partial;
    private long nullPaths;
    private long nodes;
    private long checksum = 0xcbf29ce484222325L;

    BenchmarkRunStage(BenchmarkScene scene, String phase, int ticks) {
        this.scene = scene;
        this.phase = phase;
        this.ticks = ticks;
        batchNanos = new long[ticks];
        tickNanos = new long[ticks];
        queryNanos = new long[ticks * scene.mobs().length];
        signatures = new String[scene.mobs().length];
        if (Files.exists(java.nio.file.Path.of("pathbench-" + phase + ".json"))) {
            throw new IllegalStateException("Refusing to overwrite previous phase output");
        }
    }

    void startTick() {
        tickStart = System.nanoTime();
        for (int i = 0; i < scene.mobs().length; i++) {
            var navigation = scene.mobs()[i].getNavigation();
            navigation.stop();
            long start = System.nanoTime();
            Path path = navigation.createPath(scene.targets()[i], 0);
            long elapsed = System.nanoTime() - start;
            if (tick == 0 && (path == null || path.canReach() == scene.name().equals("blocked"))) {
                org.slf4j.LoggerFactory.getLogger("pathbench").warn(
                    "Unexpected path query={} from={} target={} path={} end={}", i,
                    scene.mobs()[i].blockPosition(), scene.targets()[i], path,
                    path == null ? null : path.getEndNode());
            }
            searchNanos += elapsed;
            queryNanos[tick * scene.mobs().length + i] = elapsed;
            long fingerprint = fingerprint(path);
            String signature = Long.toUnsignedString(fingerprint, 16);
            if (tick == 0) signatures[i] = signature;
            else if (!signature.equals(signatures[i])) {
                throw new IllegalStateException("Unstable path at query " + i + " tick " + tick);
            }
            checksum = mix(checksum, fingerprint);
            if (path == null) nullPaths++;
            else {
                if (path.canReach()) reached++;
                else partial++;
                nodes += path.getNodeCount();
            }
        }
        batchNanos[tick] = System.nanoTime() - tickStart;
    }

    boolean endTick() {
        // RCON can start a phase after START_SERVER_TICK has already fired.
        if (tickStart == 0) return false;
        tickNanos[tick++] = System.nanoTime() - tickStart;
        tickStart = 0;
        if (tick != ticks) return false;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", 1);
        result.put("scene", scene.name());
        result.put("requests_per_tick", scene.mobs().length);
        result.put("ticks", ticks);
        result.put("requests", (long) ticks * scene.mobs().length);
        result.put("reached", reached);
        result.put("partial", partial);
        result.put("null_paths", nullPaths);
        result.put("nodes", nodes);
        result.put("checksum", Long.toUnsignedString(checksum, 16));
        result.put("query_signatures", signatures);
        result.put("search_ms", searchNanos / 1e6);
        result.put("query_ms", statistics(queryNanos));
        result.put("batch_ms", statistics(batchNanos));
        result.put("tick_ms", statistics(tickNanos));
        var output = java.nio.file.Path.of("pathbench-" + phase + ".json");
        var temporary = java.nio.file.Path.of(output + ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return true;
    }

    private static Map<String, Double> statistics(long[] samples) {
        Arrays.sort(samples);
        return Map.of("mean", Arrays.stream(samples).average().orElseThrow() / 1e6,
            "p50", percentile(samples, 0.50), "p95", percentile(samples, 0.95),
            "p99", percentile(samples, 0.99), "max", samples[samples.length - 1] / 1e6);
    }

    private static double percentile(long[] sorted, double quantile) {
        return sorted[(int) Math.ceil(quantile * sorted.length) - 1] / 1e6;
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static long fingerprint(Path path) {
        if (path == null) return 0;
        long hash = mix(0xcbf29ce484222325L, path.canReach() ? 1 : 2);
        hash = mix(hash, path.getTarget().asLong());
        hash = mix(hash, path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            hash = mix(hash, node.x);
            hash = mix(hash, node.y);
            hash = mix(hash, node.z);
            hash = mix(hash, node.type.ordinal());
            hash = mix(hash, Float.floatToRawIntBits(node.costMalus));
            hash = mix(hash, Float.floatToRawIntBits(node.g));
            hash = mix(hash, Float.floatToRawIntBits(node.h));
            hash = mix(hash, Float.floatToRawIntBits(node.f));
            hash = mix(hash, Float.floatToRawIntBits(node.walkedDistance));
        }
        return hash;
    }
}
