package com.github.uright008.benchmark;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

/** Pure fixture geometry and statistics, independent of Minecraft bootstrap. */
final class AllayScenarioHelper {
    static final int BASE_Y = 64;
    static final int LAYERS = 8;
    static final int DROP_INTERVAL = 20;

    static void validateEntities(int entities) {
        if (entities < 64 || entities % LAYERS != 0) {
            throw new IllegalArgumentException("Allay count must be >=64 and divisible by 8");
        }
    }

    record Position(int layer, double x, double y, double z) {}

    static Position[] schedule(long seed, String phase, int ticks) {
        if (ticks < 1 || ticks > 12000) throw new IllegalArgumentException("Invalid tick count");
        Random random = new Random(seed ^ phase.hashCode());
        Position[] positions = new Position[((ticks + 19) / 20) * LAYERS];
        for (int i = 0; i < positions.length; i++) {
            int layer = i % LAYERS;
            positions[i] = new Position(layer, 2 + random.nextDouble() * 12,
                BASE_Y + layer * 8 + 2 + random.nextDouble() * 4, 2 + random.nextDouble() * 12);
        }
        return positions;
    }

    static Map<String, Double> statistics(double[] values) {
        if (values.length == 0) return Map.of("mean", 0.0, "p50", 0.0, "p95", 0.0, "p99", 0.0, "max", 0.0);
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return Map.of("mean", Arrays.stream(sorted).average().orElseThrow(),
            "p50", sorted[(int) Math.ceil(sorted.length * .50) - 1],
            "p95", sorted[(int) Math.ceil(sorted.length * .95) - 1],
            "p99", sorted[(int) Math.ceil(sorted.length * .99) - 1], "max", sorted[sorted.length - 1]);
    }
}
