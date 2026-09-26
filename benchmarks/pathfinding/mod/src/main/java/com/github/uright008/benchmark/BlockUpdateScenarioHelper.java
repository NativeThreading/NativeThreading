package com.github.uright008.benchmark;

import java.util.Random;
import java.util.Set;

/** Pure geometry and stimulus schedule for the navigation-invalidation fixture. */
final class BlockUpdateScenarioHelper {
    static final int BLOCKS_PER_EVENT = 9;
    static final int DRAIN_TICKS = 21;

    record Position(double x, double y, double z) {}

    static void validate(int entities, int interval, String change) {
        if (entities < 64 || interval < 1 || interval > 1200
                || !Set.of("collision", "same-shape", "none").contains(change)) {
            throw new IllegalArgumentException("Expected >=64 cows, interval 1..1200, collision|same-shape|none");
        }
    }

    static boolean eventAt(int tick, int ticks, int interval) {
        return tick > 0 && tick % interval == 0 && tick <= ticks - DRAIN_TICKS;
    }

    static Position[] spawnPositions(int entities, long seed) {
        if (entities < 64) throw new IllegalArgumentException("At least 64 cows required");
        int columns = (int) Math.ceil(Math.sqrt(entities * 2.0));
        int rows = Math.ceilDiv(entities, columns);
        Random random = new Random(seed);
        Position[] positions = new Position[entities];
        for (int i = 0; i < entities; i++) {
            positions[i] = new Position(2.5 + (i % columns) * 59.0 / (columns - 1) + (random.nextDouble() - .5) * .1,
                80, 2.5 + (i / columns) * 27.0 / (rows - 1) + (random.nextDouble() - .5) * .1);
        }
        return positions;
    }

    static Position[] blocks() {
        Position[] positions = new Position[BLOCKS_PER_EVENT];
        int i = 0;
        for (int x : new int[]{16, 32, 48}) {
            for (int z : new int[]{8, 16, 24}) positions[i++] = new Position(x, 83, z);
        }
        return positions;
    }
}
