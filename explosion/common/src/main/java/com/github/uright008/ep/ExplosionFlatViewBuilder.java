package com.github.uright008.ep;

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
}
