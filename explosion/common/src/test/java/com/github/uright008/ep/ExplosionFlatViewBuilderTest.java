package com.github.uright008.ep;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential test for {@link ExplosionFlatViewBuilder#fill} against a naive
 * triple loop. Uses a generic {@code String} payload and a deterministic
 * {@link ExplosionFlatViewBuilder.BlockLookup} formula so the two fillers can be
 * compared cell-by-cell without a Minecraft runtime.
 *
 * <p>The naive loop mirrors the original {@code ServerExplosionMixin} capture:
 * {@code (z-minZ)*strideZ + (y-minY)*strideY + (x-minX)} with floor-division
 * section coords ({@code x >> 4}) and masked locals ({@code x & 15}).</p>
 */
class ExplosionFlatViewBuilderTest {

    // ── Deterministic lookup: world coordinate -> unique key string ──

    /**
     * Deterministic key for one block: {@code x} and {@code z} are reconstructed
     * from (section, local) pairs as {@code sec << 4 | local} (valid for negative
     * coords because {@code >> 4} is floor division and {@code & 15} is the
     * non-negative remainder). {@code blockY} is the world Y as captured.
     */
    private static String blockKey(int sectionX, int sectionZ, int blockY,
                                   int localX, int localY, int localZ) {
        return "b:" + (sectionX << 4 | localX) + "," + blockY + "," + (sectionZ << 4 | localZ);
    }

    private static ExplosionFlatViewBuilder.BlockLookup<String> lookup() {
        return ExplosionFlatViewBuilderTest::blockKey;
    }

    // ── Reference implementation: the original naive triple loop ──

    private static String[] fillNaive(int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ,
                                      int strideY, int strideZ) {
        String[] slow = new String[strideZ * (maxZ - minZ + 1)];
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int index = (z - minZ) * strideZ + (y - minY) * strideY + (x - minX);
                    slow[index] = blockKey(x >> 4, z >> 4, y, x & 15, y & 15, z & 15);
                }
            }
        }
        return slow;
    }

    private static String[] fillFast(int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ,
                                     int strideY, int strideZ) {
        String[] fast = new String[strideZ * (maxZ - minZ + 1)];
        return ExplosionFlatViewBuilder.fill(fast, minX, minY, minZ, maxX, maxY, maxZ,
                strideY, strideZ, lookup());
    }

    // ── Differential cases ──

    @Test
    void fill_singleBlock_matchesNaive() {
        assertThat(fillFast(0, 0, 0, 0, 0, 0, 1, 1))
                .containsExactly(fillNaive(0, 0, 0, 0, 0, 0, 1, 1));
    }

    @Test
    void fill_crosses16Boundary_matchesNaive() {
        // x spans sections -1..1 (-3..20), z spans -10..30, y dips below 0 (-5..12)
        int strideY = 20 - (-3) + 1;
        int strideZ = strideY * (12 - (-5) + 1);
        assertThat(fillFast(-3, -5, -10, 20, 12, 30, strideY, strideZ))
                .containsExactly(fillNaive(-3, -5, -10, 20, 12, 30, strideY, strideZ));
    }

    @Test
    void fill_allNegativeCoordinates_matchesNaive() {
        // Entirely negative region: x -40..-1, y -33..-17, z -20..-1
        int strideY = (-1) - (-40) + 1;
        int strideZ = strideY * ((-17) - (-33) + 1);
        assertThat(fillFast(-40, -33, -20, -1, -17, -1, strideY, strideZ))
                .containsExactly(fillNaive(-40, -33, -20, -1, -17, -1, strideY, strideZ));
    }

    @Test
    void fill_minYBelowZero_matchesNaive() {
        // minY < 0 while x/z stay positive: y -20..-1, x 0..15, z 0..15
        int strideY = 15 - 0 + 1;
        int strideZ = strideY * ((-1) - (-20) + 1);
        assertThat(fillFast(0, -20, 0, 15, -1, 15, strideY, strideZ))
                .containsExactly(fillNaive(0, -20, 0, 15, -1, 15, strideY, strideZ));
    }

    @Test
    void fill_typicalExplosionBounds_matchesNaive() {
        // Explosion-style bounds fully inside one section with natural strides
        int strideY = 116 - 100 + 1;
        int strideZ = strideY * (71 - 60 + 1);
        assertThat(fillFast(100, 60, 200, 116, 71, 216, strideY, strideZ))
                .containsExactly(fillNaive(100, 60, 200, 116, 71, 216, strideY, strideZ));
    }

    @Test
    void fill_sparseStrides_matchesNaive() {
        // Strides deliberately larger than the natural ranges — index math must
        // not assume contiguity between rows/planes.
        assertThat(fillFast(0, 0, 0, 3, 0, 3, 16, 256))
                .containsExactly(fillNaive(0, 0, 0, 3, 0, 3, 16, 256));
    }

    @Test
    void fill_returnsSameArrayInstance() {
        // Given: a pre-allocated destination array
        String[] dst = new String[1];
        // When: filling it
        String[] result = ExplosionFlatViewBuilder.fill(dst, 5, 60, 5, 5, 60, 5, 1, 1, lookup());
        // Then: the caller's array is returned and populated
        assertThat(result).isSameAs(dst);
        assertThat(dst[0]).isEqualTo(blockKey(0, 0, 60, 5, 12, 5));
    }
}
