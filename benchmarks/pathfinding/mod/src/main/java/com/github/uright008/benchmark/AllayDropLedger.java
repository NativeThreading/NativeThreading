package com.github.uright008.benchmark;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-thread accounting only; follows vanilla merges without modifying ItemStacks. */
final class AllayDropLedger {
    static final class Drop {
        final String phase;
        final int layer;
        final long tick;
        final long nanos;
        String status = "remaining";

        Drop(String phase, int layer, long tick, long nanos) {
            this.phase = phase;
            this.layer = layer;
            this.tick = tick;
            this.nanos = nanos;
        }
    }

    private final Map<UUID, ArrayDeque<Drop>> items = new HashMap<>();

    void add(UUID entity, Drop drop) {
        if (items.containsKey(entity)) throw new IllegalStateException("Duplicate item identity");
        items.put(entity, new ArrayDeque<>(List.of(drop)));
    }

    boolean merge(UUID target, UUID source, int targetCount) {
        var to = items.get(target);
        var from = items.get(source);
        if (to == null && from == null) return false;
        if (to == null || from == null || to == from) throw new IllegalStateException("Untracked item merge");
        int moved = targetCount - to.size();
        if (moved < 0 || moved > from.size()) throw new IllegalStateException("Invalid item merge count");
        for (int i = 0; i < moved; i++) to.addLast(from.removeFirst());
        if (from.isEmpty()) items.remove(source);
        return moved > 0;
    }

    List<Drop> pickup(UUID item, int remaining) {
        var queue = items.get(item);
        if (queue == null) return List.of();
        if (remaining < 0 || remaining > queue.size()) throw new IllegalStateException("Invalid pickup count");
        List<Drop> picked = new ArrayList<>();
        // Indistinguishable units in a merged stack use a deterministic FIFO attribution.
        while (queue.size() > remaining) {
            Drop drop = queue.removeFirst();
            drop.status = "picked_up";
            picked.add(drop);
        }
        if (queue.isEmpty()) items.remove(item);
        return picked;
    }

    void lost(UUID item) {
        var queue = items.remove(item);
        if (queue != null) queue.forEach(drop -> drop.status = "lost");
    }
}
