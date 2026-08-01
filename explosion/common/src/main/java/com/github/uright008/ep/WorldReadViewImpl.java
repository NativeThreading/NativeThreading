package com.github.uright008.ep;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class WorldReadViewImpl implements WorldReadView<BlockState> {

    private final BlockState[] states;
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
        this.states = states;
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

    // ── Package-private accessors for the fast ray path ──

    BlockState[] states() {
        return states;
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
