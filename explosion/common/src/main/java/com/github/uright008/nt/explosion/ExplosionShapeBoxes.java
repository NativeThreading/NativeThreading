package com.github.uright008.nt.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.atomic.AtomicReference;

/** Flattens each cell's collision shape into axis-aligned boxes (6 doubles per
 *  box, relative to the cell origin) for the worker DDA. Air cells yield null;
 *  full-block cells yield the unit box {0,0,0,1,1,1}; partial shapes yield
 *  their exact box decomposition.
 *
 *  <p>Shapes are resolved on the main thread against the <em>live</em>
 *  {@link BlockGetter} and the cell's real {@link BlockPos}:
 *  {@code state.getCollisionShape(level, pos)}. Vanilla only guarantees that
 *  call is a pure function of the state when {@code !block.hasDynamicShape()}:
 *  then it returns the baked {@code BlockStateBase.Cache} payload without
 *  running block code, so the flattened boxes are a pure function of the state
 *  and can be cached by state identity. For {@code dynamicShape} blocks
 *  (moving piston, shulker boxes, bamboo, pointed dripstone, ...) the shape
 *  depends on the block entity and/or the cell position, so those cells are
 *  resolved live, per cell, and are <em>never</em> cached. Passing a null
 *  level/pos instead would dereference null inside the block implementation
 *  (a real crash: {@code MovingPistonBlock.getBlockEntity} on a moving piston).
 *
 *  <p>Only the immutable {@code double[][]} box table crosses the worker
 *  boundary; the {@link BlockGetter} is used no longer than this main-thread
 *  call and is never stored. */
public final class ExplosionShapeBoxes {

    // ── Per-BlockState static cache (static shapes only) ─────────────────────
    // For !hasDynamicShape() states, getCollisionShape(level, pos) returns the
    // same baked shape for every level/pos (vanilla builds it once from
    // EmptyBlockGetter + BlockPos.ZERO at registration), so the flattened box
    // payload is a pure function of the BlockState and identity-keying is exact.
    // dynamicShape() states must never enter this map: their value also depends
    // on level/pos (block entity progress, position-hash offset, entity context
    // for the 3-arg form), which is why flatten resolves them per cell instead.
    // Who writes: main thread (flatten). Who reads: main thread only. When
    // joined: never crosses a worker boundary. Why no race: workers receive the
    // flattened box table, not this map.
    private static final java.util.IdentityHashMap<BlockState, double[]> BLOCK_BOX_CACHE = new java.util.IdentityHashMap<>(2048);
    /** Full-block cells all share this box table (one allocation per JVM). */
    private static final double[] FULL_CELL_BOX = new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};

    private ExplosionShapeBoxes() {}

    /** {@link #flattenShapeBoxesReused} without a reusable outer array —
     *  test/one-off use. */
    public static double[][] flattenShapeBoxes(
            BlockState[] states, BlockGetter level,
            int minX, int minY, int minZ,
            int strideY, int strideZ, int gridSize) {
        return flattenShapeBoxesReused(states, level,
                minX, minY, minZ, strideY, strideZ, gridSize, null);
    }

    /** Flatten every cell of the flat array into a box table. The outer
     *  {@code double[][]} (one slot per cell) is recycled across explosions via
     *  {@code cache}; per-box {@code double[]} payloads for static states are
     *  shared through {@link #BLOCK_BOX_CACHE} and for dynamic states are fresh
     *  every blast (their shape may depend on the cell's position or the block
     *  entity state). Cells that were non-null last time but are air now are
     *  explicitly cleared — a stale box table would be read by the
     *  entity-exposure DDA.
     *
     *  <p>Flat index layout matches {@link ExplosionFlatViewHelper}:
     *  {@code (x-minX) + (y-minY)*strideY + (z-minZ)*strideZ}, where
     *  {@code strideZ == strideY * (maxY-minY+1)}. */
    public static double[][] flattenShapeBoxesReused(
            BlockState[] states, BlockGetter level,
            int minX, int minY, int minZ,
            int strideY, int strideZ, int gridSize,
            AtomicReference<double[][]> cache) {
        if (level == null) throw new IllegalArgumentException("level is required to resolve block shapes");
        if (strideY <= 0 || strideZ <= 0 || strideZ % strideY != 0) {
            throw new IllegalArgumentException("invalid flat-view strides: " + strideZ + "/" + strideY);
        }
        final int yLen = strideZ / strideY;
        double[][] boxes = cache != null ? cache.getAndSet(null) : null;
        if (boxes == null || boxes.length < gridSize) boxes = new double[gridSize][];
        // Main-thread scratch: dynamicShape states need the real cell position.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < gridSize; i++) {
            BlockState state = states[i];
            if (state == null || state.isAir()) {
                boxes[i] = null;
                continue;
            }
            boolean dynamic = state.getBlock().hasDynamicShape();
            if (!dynamic) {
                double[] cached = BLOCK_BOX_CACHE.get(state);
                if (cached != null) {
                    boxes[i] = cached;
                    continue;
                }
            }
            pos.set(minX + i % strideY, minY + (i / strideY) % yLen, minZ + i / strideZ);
            double[] result = buildBoxes(state, level, pos);
            if (!dynamic && result != null) BLOCK_BOX_CACHE.put(state, result);
            boxes[i] = result;
        }
        return boxes;
    }

    /** Resolve one cell's collision shape against the real level/pos and
     *  flatten it. Never called for air. Caching, if any, is the caller's job —
     *  the per-state cache is only valid for {@code !hasDynamicShape()} states. */
    private static double[] buildBoxes(BlockState state, BlockGetter level, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
            return FULL_CELL_BOX;
        }
        if (shape.isEmpty()) {
            return null;
        }
        return buildBoxesFromShape(shape);
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
