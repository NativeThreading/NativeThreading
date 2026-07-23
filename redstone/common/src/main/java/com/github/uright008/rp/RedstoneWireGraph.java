package com.github.uright008.rp;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class RedstoneWireGraph {
    final List<BlockPos> positions;
    final int[][] edges;
    final int[] blockSignals;

    private RedstoneWireGraph(List<BlockPos> positions, int[][] edges, int[] blockSignals) {
        this.positions = positions;
        this.edges = edges;
        this.blockSignals = blockSignals;
    }

    @org.jspecify.annotations.Nullable
    static RedstoneWireGraph build(Level level, BlockPos initialPos) {
        if (!level.getBlockState(initialPos).is(Blocks.REDSTONE_WIRE)) return null;

        GraphDiscovery discovery = new GraphDiscovery(initialPos);

        while (!discovery.queue.isEmpty()) {
            BlockPos pos = discovery.queue.poll();
            int srcIdx = discovery.posToIdx.get(pos.asLong());
            while (discovery.edges.size() <= srcIdx) discovery.edges.add(null);
            IntArrayList destinations = new IntArrayList(4);

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighborPos = pos.relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);

                if (neighborState.is(Blocks.REDSTONE_WIRE)) {
                    addDestination(discovery, destinations, neighborPos);
                } else if (neighborState.isRedstoneConductor(level, neighborPos)) {
                    BlockPos aboveNeighbor = neighborPos.above();
                    if (!level.getBlockState(pos.above()).isRedstoneConductor(level, pos.above())
                            && level.getBlockState(aboveNeighbor).is(Blocks.REDSTONE_WIRE)) {
                        addDestination(discovery, destinations, aboveNeighbor);
                    }
                } else {
                    BlockPos belowNeighbor = neighborPos.below();
                    if (level.getBlockState(belowNeighbor).is(Blocks.REDSTONE_WIRE)) {
                        addDestination(discovery, destinations, belowNeighbor);
                    }
                }
            }
            discovery.edges.set(srcIdx, destinations.toIntArray());
        }

        if (!discovery.complete || discovery.positions.size() < 2) return null;

        int size = discovery.positions.size();
        int[] blockSignals = new int[size];
        for (int index = 0; index < size; index++) {
            blockSignals[index] = getBlockSignalDirect(level, discovery.positions.get(index));
        }
        return new RedstoneWireGraph(discovery.positions, discovery.edges.toArray(new int[size][]), blockSignals);
    }

    private static void addDestination(GraphDiscovery discovery, IntArrayList destinations, BlockPos position) {
        int index = discovery.addOrGet(position);
        if (index >= 0) destinations.add(index);
    }

    private static int getBlockSignalDirect(Level level, BlockPos position) {
        return ((RedStoneWireBlock) Blocks.REDSTONE_WIRE).getBlockSignal(level, position);
    }

    private static final class GraphDiscovery {
        final Long2IntOpenHashMap posToIdx = new Long2IntOpenHashMap();
        final List<BlockPos> positions = new ArrayList<>();
        final List<int[]> edges = new ArrayList<>();
        final Deque<BlockPos> queue = new ArrayDeque<>();
        boolean complete = true;

        GraphDiscovery(BlockPos initialPos) {
            posToIdx.defaultReturnValue(-1);
            posToIdx.put(initialPos.asLong(), 0);
            positions.add(initialPos);
            edges.add(null);
            queue.add(initialPos);
        }

        int addOrGet(BlockPos position) {
            int existing = posToIdx.get(position.asLong());
            if (existing >= 0) return existing;
            if (positions.size() >= 4096) {
                complete = false;
                return -1;
            }
            int index = positions.size();
            posToIdx.put(position.asLong(), index);
            positions.add(position);
            queue.add(position);
            return index;
        }
    }
}
