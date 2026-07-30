package com.github.uright008.ep;

import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Production implementation of {@link WorldReadView} backed by a flat
 * {@link BlockState} array and a {@link VisibilityCollisionSnapshot}.
 *
 * <p>Created on the main thread from version-validated section data.
 * The block-state array is a defensive copy; the snapshot is immutable.
 * Safe to pass to worker threads.</p>
 */
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
    private final @Nullable VisibilityCollisionSnapshot collision;

    public WorldReadViewImpl(
            BlockState[] states,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int strideY, int strideZ,
            @Nullable VisibilityCollisionSnapshot collision) {
        this.states = states;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.strideY = strideY;
        this.strideZ = strideZ;
        this.collision = collision;
    }

    @Override
    public BlockState getBlockState(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return Blocks.AIR.defaultBlockState();
        }
        int index = (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ;
        if (index < 0 || index >= states.length) {
            return Blocks.AIR.defaultBlockState();
        }
        return states[index];
    }

    @Override
    public List<double[]> getCollisionBoxes(int x, int y, int z) {
        if (collision == null) return List.of();
        return collision.getBoxesForCell(x, y, z);
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return getBlockState(x, y, z).isAir();
    }
}
