package com.github.uright008.ep;

import com.github.uright008.pc.ChunkGrid;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ExplosionFlatViewParityGameTest {

    @GameTest(maxTicks = 12, padding = 40)
    public void fillSectionedMatchesBlockLookup(GameTestHelper helper) {
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.STONE.defaultBlockState());
        helper.setBlock(new BlockPos(9, 1, 8), Blocks.STONE_SLAB.defaultBlockState());
        helper.setBlock(new BlockPos(8, 2, 8), Blocks.GLASS.defaultBlockState());
        helper.setBlock(new BlockPos(8, 1, 9), Blocks.DIRT.defaultBlockState());
        helper.setBlock(new BlockPos(12, 3, 12), Blocks.OBSIDIAN.defaultBlockState());

        var level = helper.getLevel();
        ChunkGrid grid = new ChunkGrid(level, 8.0, 8.0, 8.0F);

        int minX = 5, minY = 0, minZ = 5;
        int maxX = 12, maxY = 5, maxZ = 12;
        int strideY = maxX - minX + 1;
        int strideZ = strideY * (maxY - minY + 1);
        int size = strideZ * (maxZ - minZ + 1);

        BlockState[] slow = new BlockState[size];
        ExplosionFlatViewBuilder.fill(slow, minX, minY, minZ, maxX, maxY, maxZ,
                strideY, strideZ, grid::getBlockState);

        BlockState[] fast = new BlockState[size];
        ExplosionFlatViewBuilder.fillSectioned(fast, minX, minY, minZ, maxX, maxY, maxZ,
                strideY, strideZ, grid);

        for (int i = 0; i < size; i++) {
            if (slow[i] != fast[i]) {
                helper.fail("fillSectioned differs at index " + i
                        + ": slow=" + slow[i] + " fast=" + fast[i]);
            }
        }
        helper.succeed();
    }
}
