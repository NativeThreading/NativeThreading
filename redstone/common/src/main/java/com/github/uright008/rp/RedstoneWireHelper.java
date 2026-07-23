package com.github.uright008.rp;

import com.github.uright008.pc.ParallelThreadPool;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Parallel redstone wire power propagation via iterative relaxation.
 *
 * <p>Vanilla {@code DefaultRedstoneWireEvaluator} cascades through connected
 * wires sequentially.  This helper collects all wires in a connected component,
 * computes their target powers in parallel using iterative relaxation, then
 * applies changes wire-by-wire matching the vanilla notification order.</p>
 */
public final class RedstoneWireHelper {

    private static final Set<Level> GUARDS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Long2IntOpenHashMap PROCESSED = new Long2IntOpenHashMap();
    private static int processEpoch = 1;
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    private RedstoneWireHelper() {}

    public static void clearProcessed() {
        processEpoch++;
        if (processEpoch < 0) {
            PROCESSED.clear();
            processEpoch = 1;
        }
    }

    public static boolean tryParallelUpdate(Level level, BlockPos initialPos) {
        if (!RedstoneParallelConfig.isWireEnabled()) return false;
        if (!GUARDS.add(level)) return false;
        try {
            return tryParallelUpdateInner(level, initialPos);
        } finally {
            GUARDS.remove(level);
        }
    }

    private static boolean tryParallelUpdateInner(Level level, BlockPos initialPos) {
        RedstoneWireGraph graph = RedstoneWireGraph.build(level, initialPos);
        if (graph == null) return false;

        int count = graph.positions.size();
        if (count < RedstoneParallelConfig.wireThreshold()) return false;

        if (!markUnprocessed(graph.positions)) return false;

        return propagateAndApply(graph.blockSignals, graph.edges, ParallelThreadPool.getPool("Redstone"),
                Math.min(RedstoneParallelConfig.maxWorkers(), CPU_CORES * 2),
                latch -> latch.await(5, TimeUnit.SECONDS), powers -> applyChanges(level, graph, powers));
    }

    private static boolean markUnprocessed(List<BlockPos> positions) {
        boolean allProcessed = true;
        for (BlockPos position : positions) {
            long key = position.asLong();
            if (PROCESSED.get(key) != processEpoch) {
                PROCESSED.put(key, processEpoch);
                allProcessed = false;
            }
        }
        return !allProcessed;
    }

    static boolean markComponentForTesting(List<BlockPos> positions) {
        return markUnprocessed(positions);
    }

    static Set<BlockPos> notificationCentersForTesting(BlockPos pos) {
        return notificationCenters(pos);
    }

    static boolean propagateAndApplyForTesting(int[] blockSignals, int[][] edges, Executor executor, int maxWorkers,
                                               CompletionAwaiter completionAwaiter, Consumer<int[]> apply) {
        return propagateAndApply(blockSignals, edges, executor, maxWorkers, completionAwaiter, apply);
    }

    private static boolean propagateAndApply(int[] blockSignals, int[][] edges, Executor executor, int maxWorkers,
                                             CompletionAwaiter completionAwaiter, Consumer<int[]> apply) {
        int[] powers = propagatePowers(blockSignals, edges, executor, maxWorkers, completionAwaiter);
        if (powers == null) return false;
        apply.accept(powers);
        return true;
    }

    @org.jspecify.annotations.Nullable
    private static int[] propagatePowers(int[] blockSignals, int[][] edges, Executor executor, int maxWorkers,
                                         CompletionAwaiter completionAwaiter) {
        int n = blockSignals.length;
        int[] bufA = new int[n];
        int[] bufB = new int[n];
        for (int i = 0; i < n; i++) bufA[i] = blockSignals[i];

        boolean changed;
        int iterations = 0;
        int[] current = bufA;
        int[] prev = bufB;
        do {
            changed = false;
            iterations++;
            int[] tmp = prev; prev = current; current = tmp;

            if (n < 16 || maxWorkers <= 1) {
                for (int i = 0; i < n; i++) {
                    int np = relaxWire(blockSignals, prev, edges[i], i);
                    current[i] = np;
                    if (np != prev[i]) changed = true;
                }
            } else {
                int workers = Math.min(maxWorkers, Math.max(2, n / 16));
                int perWorker = n / workers;
                int extra = n % workers;
                CountDownLatch latch = new CountDownLatch(workers);
                boolean[] localChanged = new boolean[workers * 64]; // 64-byte gap per slot avoids false sharing
                AtomicReference<Throwable> failure = new AtomicReference<>();
                int offset = 0;
                for (int w = 0; w < workers; w++) {
                    final int start = offset;
                    final int end = offset + perWorker + (w < extra ? 1 : 0);
                    offset = end;
                    final int slot = w * 64;
                    final int[] cur = current;
                    final int[] prv = prev;
                    try {
                        executor.execute(() -> {
                        boolean any = false;
                        try {
                            for (int i = start; i < end; i++) {
                                int np = relaxWire(blockSignals, prv, edges[i], i);
                                cur[i] = np;
                                if (np != prv[i]) any = true;
                            }
                        } catch (Throwable throwable) {
                            failure.compareAndSet(null, throwable);
                        } finally {
                            localChanged[slot] = any;
                            latch.countDown();
                        }
                        });
                    } catch (RuntimeException exception) {
                        return null;
                    }
                }
                try {
                    if (!completionAwaiter.await(latch)) return null;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (failure.get() != null) return null;
                for (int w = 0; w < workers; w++) {
                    if (localChanged[w * 64]) { changed = true; break; }
                }
            }
        } while (changed && iterations < n);

        return current;
    }

    @FunctionalInterface
    interface CompletionAwaiter {
        boolean await(CountDownLatch latch) throws InterruptedException;
    }

    static int relaxWire(int[] blockSignals, int[] prevPowers, int[] neighbors, int selfIdx) {
        int p = blockSignals[selfIdx];
        if (p == 15) return 15;
        int maxIncoming = 0;
        for (int nb : neighbors) {
            int np = prevPowers[nb];
            if (np == 15) { maxIncoming = 15; break; } // max possible, stop early
            if (np > maxIncoming) maxIncoming = np;
        }
        int incoming = maxIncoming > 0 ? maxIncoming - 1 : 0;
        return Math.max(p, incoming);
    }

    private static void applyChanges(Level level, RedstoneWireGraph graph, int[] powers) {
        List<BlockPos> changed = new ArrayList<>();
        for (int i = 0; i < graph.positions.size(); i++) {
            BlockPos pos = graph.positions.get(i);
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.REDSTONE_WIRE)) continue;
            int currentPower = state.getValue(RedStoneWireBlock.POWER);
            int targetPower = powers[i];
            if (currentPower == targetPower) continue;

            level.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, targetPower), 2);
            changed.add(pos);
        }
        for (BlockPos pos : changed) {
            for (BlockPos center : notificationCenters(pos)) {
                level.updateNeighborsAt(center, Blocks.REDSTONE_WIRE);
            }
        }
    }

    private static Set<BlockPos> notificationCenters(BlockPos pos) {
        Set<BlockPos> centers = Sets.newHashSet();
        centers.add(pos);
        for (Direction direction : Direction.values()) {
            centers.add(pos.relative(direction));
        }
        return centers;
    }
}
