package com.github.uright008.pc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

public final class ChunkGridBlockGetter implements BlockGetter {

    private final ChunkGrid grid;

    public ChunkGridBlockGetter(ChunkGrid grid) {
        this.grid = grid;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int cx = net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX());
        int cz = net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ());
        return grid.getBlockState(cx, cz, pos.getY(), pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.defaultFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return grid.getHeight();
    }

    @Override
    public int getMinY() {
        return grid.getMinY();
    }
}
