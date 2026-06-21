package com.github.uright008.rp;

import com.github.uright008.pc.ParallelThreadPool;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

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
        Graph graph = buildGraph(level, initialPos);
        if (graph == null) return false;

        int count = graph.positions.size();
        if (count < RedstoneParallelConfig.wireThreshold()) return false;

        boolean allProcessed = true;
        for (int i = 0; i < count; i++) {
            long key = graph.positions.get(i).asLong();
            if (PROCESSED.get(key) != processEpoch) {
                PROCESSED.put(key, processEpoch);
                allProcessed = false;
            }
        }
        if (allProcessed) return false;

        int[] powers = propagatePowers(graph);
        applyChanges(level, graph, powers);
        return true;
    }

    private static final class Graph {
        final List<BlockPos> positions;
        final int[][] edges;
        final int[] blockSignals;
        Graph(List<BlockPos> p, int[][] e, int[] b) { positions = p; edges = e; blockSignals = b; }
    }

    @org.jspecify.annotations.Nullable
    private static Graph buildGraph(Level level, BlockPos initialPos) {
        Long2IntOpenHashMap posToIdx = new Long2IntOpenHashMap();
        posToIdx.defaultReturnValue(-1);
        List<BlockPos> positions = new ArrayList<>();
        List<int[]> edgeList = new ArrayList<>();

        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(initialPos);
        posToIdx.put(initialPos.asLong(), 0);
        positions.add(initialPos);
        edgeList.add(null);

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            int srcIdx = posToIdx.get(pos.asLong());
            while (edgeList.size() <= srcIdx) edgeList.add(null);
            IntArrayList dsts = new IntArrayList(4);

            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighborPos = pos.relative(dir);
                BlockState neighborState = level.getBlockState(neighborPos);

                if (neighborState.is(Blocks.REDSTONE_WIRE)) {
                    int dstIdx = addOrGet(posToIdx, positions, queue, neighborPos);
                    if (dstIdx >= 0) dsts.add(dstIdx);
                } else if (neighborState.isRedstoneConductor(level, neighborPos)) {
                    BlockPos aboveNeighbor = neighborPos.above();
                    if (!level.getBlockState(pos.above()).isRedstoneConductor(level, pos.above())
                            && level.getBlockState(aboveNeighbor).is(Blocks.REDSTONE_WIRE)) {
                        int dstIdx = addOrGet(posToIdx, positions, queue, aboveNeighbor);
                        if (dstIdx >= 0) dsts.add(dstIdx);
                    }
                } else {
                    BlockPos belowNeighbor = neighborPos.below();
                    if (level.getBlockState(belowNeighbor).is(Blocks.REDSTONE_WIRE)) {
                        int dstIdx = addOrGet(posToIdx, positions, queue, belowNeighbor);
                        if (dstIdx >= 0) dsts.add(dstIdx);
                    }
                }
            }
            int[] dstArr = new int[dsts.size()];
            for (int j = 0; j < dstArr.length; j++) dstArr[j] = dsts.getInt(j);
            edgeList.set(srcIdx, dstArr);
        }

        int n = positions.size();
        if (n < 2) return null;
        int[][] edges = edgeList.toArray(new int[n][]);

        int[] blockSignals = new int[n];
        for (int i = 0; i < n; i++) {
            blockSignals[i] = getBlockSignalDirect(level, positions.get(i));
        }
        return new Graph(positions, edges, blockSignals);
    }

    private static int getBlockSignalDirect(Level level, BlockPos pos) {
        return ((RedStoneWireBlock) Blocks.REDSTONE_WIRE).getBlockSignal(level, pos);
    }

    private static int addOrGet(Long2IntOpenHashMap posToIdx, List<BlockPos> positions,
                                 Deque<BlockPos> queue, BlockPos pos) {
        int existing = posToIdx.get(pos.asLong());
        if (existing >= 0) return existing;
        if (positions.size() >= 4096) return -1;
        int idx = positions.size();
        posToIdx.put(pos.asLong(), idx);
        positions.add(pos);
        queue.add(pos);
        return idx;
    }

    private static int[] propagatePowers(Graph graph) {
        int n = graph.positions.size();
        int[] bufA = new int[n];
        int[] bufB = new int[n];
        for (int i = 0; i < n; i++) bufA[i] = graph.blockSignals[i];

        int maxWorkers = Math.min(RedstoneParallelConfig.maxWorkers(), CPU_CORES * 2);
        ExecutorService pool = ParallelThreadPool.getPool("Redstone");

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
                    int np = relaxWire(graph.blockSignals, prev, graph.edges[i], i);
                    if (np != prev[i]) { current[i] = np; changed = true; }
                }
            } else {
                int workers = Math.min(maxWorkers, Math.max(2, n / 16));
                int perWorker = n / workers;
                int extra = n % workers;
                CountDownLatch latch = new CountDownLatch(workers);
                boolean[] localChanged = new boolean[workers * 64]; // 64-byte gap per slot avoids false sharing
                int offset = 0;
                for (int w = 0; w < workers; w++) {
                    final int start = offset;
                    final int end = offset + perWorker + (w < extra ? 1 : 0);
                    offset = end;
                    final int slot = w * 64;
                    final int[] cur = current;
                    final int[] prv = prev;
                    pool.execute(() -> {
                        boolean any = false;
                        for (int i = start; i < end; i++) {
                            int np = relaxWire(graph.blockSignals, prv, graph.edges[i], i);
                            if (np != prv[i]) { cur[i] = np; any = true; }
                        }
                        localChanged[slot] = any;
                        latch.countDown();
                    });
                }
                try { latch.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int w = 0; w < workers; w++) {
                    if (localChanged[w * 64]) { changed = true; break; }
                }
            }
        } while (changed && iterations < n);

        return current;
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

    private static void applyChanges(Level level, Graph graph, int[] powers) {
        for (int i = 0; i < graph.positions.size(); i++) {
            BlockPos pos = graph.positions.get(i);
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.REDSTONE_WIRE)) continue;
            int currentPower = state.getValue(RedStoneWireBlock.POWER);
            int targetPower = powers[i];
            if (currentPower == targetPower) continue;

            level.setBlock(pos, state.setValue(RedStoneWireBlock.POWER, targetPower), 2);
            level.updateNeighborsAt(pos, Blocks.REDSTONE_WIRE);
        }
    }
}
