package com.github.uright008.nt.explosion;

import com.github.uright008.nt.core.NtThreadPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight runtime counters and phase timings for the explosion pipeline,
 * exposed through {@code /nt explosion stats}.
 *
 * <p>Counters use {@link LongAdder} because worker computations can increment
 * them; phase timings are measured on the main thread once per blast, so the
 * overhead is a few nanoseconds per blast. This is diagnostic data only: the
 * pipeline's behaviour never depends on it.</p>
 */
public final class ExplosionStats {

    // Counters (worker-safe).
    private static final LongAdder BLOCK_BLASTS = new LongAdder();
    private static final LongAdder ENTITY_BLASTS = new LongAdder();
    private static final LongAdder ENTITY_SERIAL_FALLBACKS = new LongAdder();
    private static final LongAdder BLOCK_SERIAL_FALLBACKS = new LongAdder();
    private static final LongAdder VICTIMS = new LongAdder();
    private static final LongAdder LIVE_VICTIMS = new LongAdder();
    private static final LongAdder DEDUP_HITS = new LongAdder();
    private static final LongAdder COMPUTE_UNITS = new LongAdder();
    private static final LongAdder GROUPED_VICTIMS = new LongAdder();
    private static final LongAdder CLEAR_REGION_HITS = new LongAdder();

    // Main-thread phase timings, in nanoseconds.
    private static final LongAdder BLOCK_CAPTURE_NANOS = new LongAdder();
    private static final LongAdder BLOCK_WORKER_NANOS = new LongAdder();
    private static final LongAdder ENTITY_CAPTURE_NANOS = new LongAdder();
    private static final LongAdder ENTITY_WORKER_NANOS = new LongAdder();
    private static final LongAdder ENTITY_APPLY_NANOS = new LongAdder();

    private ExplosionStats() {}

    static void recordBlockStage(long captureNanos, long workerNanos) {
        BLOCK_BLASTS.increment();
        BLOCK_CAPTURE_NANOS.add(captureNanos);
        BLOCK_WORKER_NANOS.add(workerNanos);
    }

    static void recordBlockSerialFallback() {
        BLOCK_SERIAL_FALLBACKS.increment();
    }

    static void recordEntityStage(long captureNanos, long workerNanos, long applyNanos,
                                  int victims, int liveVictims, int dedupHits,
                                  int units, long groupedVictims) {
        ENTITY_BLASTS.increment();
        ENTITY_CAPTURE_NANOS.add(captureNanos);
        ENTITY_WORKER_NANOS.add(workerNanos);
        ENTITY_APPLY_NANOS.add(applyNanos);
        VICTIMS.add(victims);
        LIVE_VICTIMS.add(liveVictims);
        DEDUP_HITS.add(dedupHits);
        COMPUTE_UNITS.add(units);
        GROUPED_VICTIMS.add(groupedVictims);
    }

    static void recordEntitySerialFallback() {
        ENTITY_SERIAL_FALLBACKS.increment();
    }

    /** Called from the worker-side clear-region precheck when it short-circuits to 1.0F. */
    static void recordClearRegionHit() {
        CLEAR_REGION_HITS.increment();
    }

    public static void reset() {
        BLOCK_BLASTS.reset();
        ENTITY_BLASTS.reset();
        ENTITY_SERIAL_FALLBACKS.reset();
        BLOCK_SERIAL_FALLBACKS.reset();
        VICTIMS.reset();
        LIVE_VICTIMS.reset();
        DEDUP_HITS.reset();
        COMPUTE_UNITS.reset();
        GROUPED_VICTIMS.reset();
        CLEAR_REGION_HITS.reset();
        BLOCK_CAPTURE_NANOS.reset();
        BLOCK_WORKER_NANOS.reset();
        ENTITY_CAPTURE_NANOS.reset();
        ENTITY_WORKER_NANOS.reset();
        ENTITY_APPLY_NANOS.reset();
    }

    /** Human-readable stat lines for {@code /nt explosion stats}. */
    public static List<String> lines() {
        long blockBlasts = BLOCK_BLASTS.sum();
        long entityBlasts = ENTITY_BLASTS.sum();
        long victims = VICTIMS.sum();
        long live = LIVE_VICTIMS.sum();
        long flat = victims - live;
        long dedup = DEDUP_HITS.sum();
        long units = COMPUTE_UNITS.sum();
        long grouped = GROUPED_VICTIMS.sum();

        List<String> lines = new ArrayList<>(7);
        lines.add(String.format(
                "blasts: block=%d entity=%d, serial fallbacks: block=%d entity=%d",
                blockBlasts, entityBlasts, BLOCK_SERIAL_FALLBACKS.sum(), ENTITY_SERIAL_FALLBACKS.sum()));
        lines.add(String.format(
                "victims: total=%d (live=%d, flat=%d), dedup hits=%d (%s of live), grouped=%d (%s of victims)",
                victims, live, flat, dedup, pct(dedup, live), grouped, pct(grouped, victims)));
        lines.add(String.format(
                "units: %d (%s per entity blast), rays=%d per block blast",
                units, ratio(units, entityBlasts), ExplosionRayParams.RAY_PARAMS.size()));
        lines.add(String.format(
                "exposure: clear-region fast path=%d hits (dedup slots=%d, group slots=%d)",
                CLEAR_REGION_HITS.sum(),
                ExplosionEntityStage.exposureDedupCapacity(),
                ExplosionEntityStage.groupCapacity()));
        lines.add(String.format(
                "time: block capture=%.1fus worker=%.1fus | entity capture=%.1fus worker=%.1fus apply=%.1fus",
                avgMicros(BLOCK_CAPTURE_NANOS.sum(), blockBlasts),
                avgMicros(BLOCK_WORKER_NANOS.sum(), blockBlasts),
                avgMicros(ENTITY_CAPTURE_NANOS.sum(), entityBlasts),
                avgMicros(ENTITY_WORKER_NANOS.sum(), entityBlasts),
                avgMicros(ENTITY_APPLY_NANOS.sum(), entityBlasts)));
        lines.add(ExplosionChunkGridCache.statsLine());
        lines.add(String.format(
                "last blast: victims=%d units=%d grouped=%d",
                ExplosionEntityStage.lastVictims(),
                ExplosionEntityStage.lastUnits(),
                ExplosionEntityStage.lastGroupedVictims()));
        return lines;
    }

    /** Effective tuning parameters shown with the stats. */
    public static String paramsLine() {
        return String.format(
                "params: pool=%d workers, rays=%d, dedupSlots=%d, groupSlots=%d, clearRegionCells=%d",
                NtThreadPool.getParallelism(),
                ExplosionRayParams.RAY_PARAMS.size(),
                ExplosionEntityStage.exposureDedupCapacity(),
                ExplosionEntityStage.groupCapacity(),
                ExplosionExposureComputer.clearRegionMaxCells());
    }

    private static String pct(long part, long total) {
        return total == 0 ? "n/a" : String.format("%.1f%%", (double) part / total * 100.0);
    }

    private static String ratio(long part, long total) {
        return total == 0 ? "n/a" : String.format("%.2f", (double) part / total);
    }

    private static double avgMicros(long totalNanos, long count) {
        return count == 0 ? 0.0 : (double) totalNanos / count / 1000.0;
    }
}
