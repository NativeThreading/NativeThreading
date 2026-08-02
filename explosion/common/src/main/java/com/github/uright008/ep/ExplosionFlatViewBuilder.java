package com.github.uright008.ep;

import com.github.uright008.pc.ChunkGrid;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ExplosionFlatViewBuilder {
    private ExplosionFlatViewBuilder() {}

    @FunctionalInterface
    public interface BlockLookup<B> {
        B get(int sectionX, int sectionZ, int blockY, int localX, int localY, int localZ);
    }

    public static <B> B[] fill(B[] dst, int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ,
                               int strideY, int strideZ, BlockLookup<B> lookup) {
        for (int z = minZ; z <= maxZ; z++) {
            int cz = z >> 4;
            int lz = z & 15;
            int zOff = (z - minZ) * strideZ;
            for (int y = minY; y <= maxY; y++) {
                int ly = y & 15;
                int yzOff = zOff + (y - minY) * strideY;
                int cx = minX >> 4;
                int lx = minX & 15;
                int i = yzOff;
                for (int x = minX; x <= maxX; x++, i++) {
                    dst[i] = lookup.get(cx, cz, y, lx, ly, lz);
                    if (++lx == 16) { lx = 0; cx++; }
                }
            }
        }
        return dst;
    }

    /**
     * Fills the flat array from a {@link ChunkGrid}, caching the current section
     * so reads inside one section go straight to
     * {@link LevelChunkSection#getBlockState} (O(1) array access) instead of the
     * per-cell ChunkGrid palette chain. Result is identical to {@link #fill}
     * with {@code chunkGrid::getBlockState}.
     */
    public static BlockState[] fillSectioned(BlockState[] dst,
                                             int minX, int minY, int minZ,
                                             int maxX, int maxY, int maxZ,
                                             int strideY, int strideZ,
                                             ChunkGrid chunkGrid) {
        ChunkGrid.SectionRef ref = new ChunkGrid.SectionRef();
        int cachedSx = Integer.MIN_VALUE, cachedSz = Integer.MIN_VALUE, cachedSecY = Integer.MIN_VALUE;
        for (int z = minZ; z <= maxZ; z++) {
            int cz = z >> 4;
            int lz = z & 15;
            int zOff = (z - minZ) * strideZ;
            for (int y = minY; y <= maxY; y++) {
                int secY = y >> 4;
                int ly = y & 15;
                int yzOff = zOff + (y - minY) * strideY;
                int cx = minX >> 4;
                int lx = minX & 15;
                int i = yzOff;
                for (int x = minX; x <= maxX; x++, i++) {
                    if (cx != cachedSx || cz != cachedSz || secY != cachedSecY) {
                        chunkGrid.getSection(cx, cz, y, ref);
                        cachedSx = cx;
                        cachedSz = cz;
                        cachedSecY = secY;
                    }
                    LevelChunkSection section = ref.section;
                    dst[i] = section != null ? section.getBlockState(lx, ly, lz)
                                             : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    if (++lx == 16) { lx = 0; cx++; }
                }
            }
        }
        return dst;
    }
}
