package com.github.uright008.ep;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.atomic.AtomicReference;

/** Flattens each cell's collision shape into axis-aligned boxes (6 doubles per
 *  box, relative to the cell origin) for the worker DDA. Air cells yield null;
 *  full-block cells yield the unit box {0,0,0,1,1,1}; partial shapes yield
 *  their exact box decomposition. Precomputed on the main thread.
 *  <p>Holds the per-BlockState box cache — state ownership lives here with its
 *  only consumer (F2), and the four-question cache contract holds: written on
 *  the main thread, read by the main thread only, no worker ever mutates it. */
public final class ExplosionShapeBoxes {

    // ── Per-BlockState static caches ─────────────────────────────────────────
    // getCollisionShape(null, null) and toAabbs() are pure functions of the
    // BlockState, and BlockState instances are global singletons
    // (IdentityHashMap is semantically correct and avoids the ~800k
    // equals()/hashCode() probes the state data uses). Keyed lookups fold the
    // per-cell 30k-element loops of every explosion into a handful of map hits.
    // Who writes: main thread (flatten). Who reads: main thread only. When
    // joined: never crosses a worker boundary. Why no race: workers receive
    // the flattened box table, not this map.
    private static final java.util.IdentityHashMap<BlockState, double[]> BLOCK_BOX_CACHE = new java.util.IdentityHashMap<>(2048);
    /** Full-block cells all share this box table (one allocation per JVM). */
    private static final double[] FULL_CELL_BOX = new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

    private ExplosionShapeBoxes() {}

    /** {@link #flattenShapeBoxesReused} without a reusable outer array —
     *  test/one-off use. */
    public static double[][] flattenShapeBoxes(
            BlockState[] states, VoxelShape[] shapes, int gridSize) {
        return flattenShapeBoxesReused(states, shapes, gridSize, null);
    }

    /** Flatten with a reusable outer array. The outer {@code double[][]} (one
     *  slot per cell) is recycled across explosions via {@code cache}; per-box
     *  {@code double[]} payloads are fresh because shape sets vary. Cells that
     *  were non-null last time but are air now must be explicitly cleared — a
     *  stale box table would be read by the entity-exposure DDA.
     *  <p>When {@code shapes} is null the per-cell boxes are resolved from
     *  {@link #BLOCK_BOX_CACHE} (keyed by BlockState identity, the exact
     *  {@code getCollisionShape(null, null)} result), so the caller can skip
     *  materialising a {@code VoxelShape[]} entirely. */
    public static double[][] flattenShapeBoxesReused(
            BlockState[] states, VoxelShape[] shapes, int gridSize,
            AtomicReference<double[][]> cache) {
        double[][] boxes = cache != null ? cache.getAndSet(null) : null;
        if (boxes == null || boxes.length < gridSize) boxes = new double[gridSize][];
        for (int i = 0; i < gridSize; i++) {
            if (shapes == null) {
                BlockState state = states[i];
                if (state == null || state.isAir()) {
                    boxes[i] = null;
                    continue;
                }
                double[] cached = BLOCK_BOX_CACHE.get(state);
                if (cached != null) {
                    boxes[i] = cached;
                    continue;
                }
                boxes[i] = buildBoxes(state, true);
            } else {
                VoxelShape shape = shapes[i];
                if (shape == null) {
                    boxes[i] = null;
                    continue;
                }
                if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                    boxes[i] = FULL_CELL_BOX;
                    continue;
                }
                boxes[i] = buildBoxesFromShape(shape);
            }
        }
        return boxes;
    }

    private static double[] buildBoxes(BlockState state, boolean cache) {
        VoxelShape shape = state.getCollisionShape(null, null);
        double[] result;
        if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
            result = FULL_CELL_BOX;
        } else if (shape.isEmpty()) {
            result = null;
        } else {
            result = buildBoxesFromShape(shape);
        }
        if (cache && result != null) BLOCK_BOX_CACHE.put(state, result);
        return result;
    }

    private static double[] buildBoxesFromShape(VoxelShape shape) {
        java.util.List<net.minecraft.world.phys.AABB> aabbs = shape.toAabbs();
        if (aabbs.size() == 1) {
            net.minecraft.world.phys.AABB bb = aabbs.get(0);
            return new double[] {
                    bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ};
        }
        double[] packed = new double[aabbs.size() * 6];
        for (int b = 0; b < aabbs.size(); b++) {
            net.minecraft.world.phys.AABB bb = aabbs.get(b);
            int o = b * 6;
            packed[o] = bb.minX;
            packed[o + 1] = bb.minY;
            packed[o + 2] = bb.minZ;
            packed[o + 3] = bb.maxX;
            packed[o + 4] = bb.maxY;
            packed[o + 5] = bb.maxZ;
        }
        return packed;
    }
}
