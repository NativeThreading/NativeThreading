package com.github.uright008.ep;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class WorldReadViewImpl implements WorldReadView<BlockState> {

    private final BlockState[] states;
    private final VoxelShape[] shapes;
    /** Per-cell axis-aligned box list (6 doubles per box, [minX,minY,minZ,maxX,maxY,maxZ]
     *  relative to the cell origin), or null for air/full cells. Precomputed on the
     *  main thread so the worker DDA can do exact box tests without toAabbs() allocation. */
    private final double[][] shapeBoxes;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int strideY;
    private final int strideZ;

    public WorldReadViewImpl(
            BlockState[] states,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int strideY, int strideZ) {
        this(states, null, null, minX, minY, minZ, maxX, maxY, maxZ, strideY, strideZ);
    }

    public WorldReadViewImpl(
            BlockState[] states, VoxelShape[] shapes,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int strideY, int strideZ) {
        this(states, shapes, null, minX, minY, minZ, maxX, maxY, maxZ, strideY, strideZ);
    }

    public WorldReadViewImpl(
            BlockState[] states, VoxelShape[] shapes, double[][] shapeBoxes,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int strideY, int strideZ) {
        this.states = states;
        this.shapes = shapes;
        this.shapeBoxes = shapeBoxes;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.strideY = strideY;
        this.strideZ = strideZ;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return Blocks.AIR.defaultBlockState();
        }
        int index = (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ;
        return states[index];
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return getBlockState(x, y, z).isAir();
    }

    /**
     * Reads without the bounds check. Caller must have already verified the
     * coordinates are inside [min..max] on every axis (traceRay's loop break
     * guarantees this before each read).
     */
    public BlockState getBlockStateUnchecked(int x, int y, int z) {
        return states[(x - minX) + (y - minY) * strideY + (z - minZ) * strideZ];
    }

    // ── Package-private accessors for the fast ray path ──

    BlockState[] states() {
        return states;
    }

    VoxelShape[] shapes() {
        return shapes;
    }

    public double[][] shapeBoxes() {
        return shapeBoxes;
    }

    int minX() {
        return minX;
    }

    int minY() {
        return minY;
    }

    int minZ() {
        return minZ;
    }

    int maxX() {
        return maxX;
    }

    int maxY() {
        return maxY;
    }

    int maxZ() {
        return maxZ;
    }

    int strideY() {
        return strideY;
    }

    int strideZ() {
        return strideZ;
    }
}
