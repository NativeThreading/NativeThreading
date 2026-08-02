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
     * Fills the flat array from a {@link ChunkGrid} in section-major order.
     * Each section is resolved once; sections that are entirely air (the common
     * case in an explosion volume) are skipped with a bulk AIR fill instead of
     * per-cell palette reads. Result is identical to {@link #fill} with
     * {@code chunkGrid::getBlockState}.
     */
    public static BlockState[] fillSectioned(BlockState[] dst,
                                             int minX, int minY, int minZ,
                                             int maxX, int maxY, int maxZ,
                                             int strideY, int strideZ,
                                             ChunkGrid chunkGrid) {
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        ChunkGrid.SectionRef ref = new ChunkGrid.SectionRef();
        for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
            int zMin = Math.max(minZ, cz << 4);
            int zMax = Math.min(maxZ, (cz << 4) + 15);
            for (int secY = minY >> 4; secY <= maxY >> 4; secY++) {
                int yMin = Math.max(minY, secY << 4);
                int yMax = Math.min(maxY, (secY << 4) + 15);
                for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                    int xMin = Math.max(minX, cx << 4);
                    int xMax = Math.min(maxX, (cx << 4) + 15);

                    chunkGrid.getSection(cx, cz, secY << 4, ref);
                    LevelChunkSection section = ref.section;
                    if (section == null || section.hasOnlyAir()) {
                        for (int z = zMin; z <= zMax; z++) {
                            int zOff = (z - minZ) * strideZ;
                            for (int y = yMin; y <= yMax; y++) {
                                java.util.Arrays.fill(dst,
                                        zOff + (y - minY) * strideY + (xMin - minX),
                                        zOff + (y - minY) * strideY + (xMax - minX) + 1, air);
                            }
                        }
                    } else {
                        for (int z = zMin; z <= zMax; z++) {
                            int zOff = (z - minZ) * strideZ;
                            int lz = z & 15;
                            for (int y = yMin; y <= yMax; y++) {
                                int i = zOff + (y - minY) * strideY + (xMin - minX);
                                int ly = y & 15;
                                for (int x = xMin; x <= xMax; x++, i++) {
                                    dst[i] = section.getBlockState(x & 15, ly, lz);
                                }
                            }
                        }
                    }
                }
            }
        }
        return dst;
    }
}
