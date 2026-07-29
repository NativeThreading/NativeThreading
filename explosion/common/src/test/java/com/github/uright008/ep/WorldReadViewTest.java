package com.github.uright008.ep;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorldReadView} — the immutable world snapshot interface
 * that workers consume instead of live {@code Level} or {@code ChunkGrid}.
 *
 * <p>Baseline tests characterize the existing flat-array block-state capture
 * pattern used by the ray phase. Immutability tests verify that the view
 * cannot observe mutations after construction.</p>
 */
class WorldReadViewTest {

    // ── Baseline characterization: flat-array block capture ──

    @Test
    void flatArray_blockStateAccess_byIndex() {
        // Given: a flat block-state array simulating the ray-phase capture
        String[] flatBlocks = {"air", "stone", "dirt", "air", "cobble", "air"};
        int minX = 10, minY = 60, minZ = 20;
        int strideY = 3, strideZ = 6; // 3x2x1 grid

        // When: accessing block at world (11, 61, 20) → index (1,1,0) → 1 + 1*3 + 0*6 = 4
        int worldX = 11, worldY = 61, worldZ = 20;
        int index = (worldX - minX) + (worldY - minY) * strideY + (worldZ - minZ) * strideZ;
        String block = flatBlocks[index];

        // Then: returns the captured state
        assertThat(block).isEqualTo("cobble");
    }

    @Test
    void flatArray_outOfBounds_returnsDefault() {
        // Given: a flat block-state array with bounds
        String[] flatBlocks = {"air", "stone", "dirt"};
        int minX = 0, minY = 0, minZ = 0;
        int maxX = 0, maxY = 0, maxZ = 0;
        int strideY = 1, strideZ = 1;

        // When: accessing block outside bounds — current pattern returns air default
        int worldX = 5, worldY = 5, worldZ = 5;
        boolean inBounds = worldX >= minX && worldX <= maxX
                && worldY >= minY && worldY <= maxY
                && worldZ >= minZ && worldZ <= maxZ;
        String block = inBounds
                ? flatBlocks[(worldX - minX) + (worldY - minY) * strideY + (worldZ - minZ) * strideZ]
                : "air";

        // Then: returns air for out-of-bounds
        assertThat(block).isEqualTo("air");
    }

    @Test
    void flatArray_multipleAccesses_sameData() {
        // Given: a flat block-state array
        String[] flatBlocks = {"air", "stone", "dirt", "air", "cobble", "air"};
        int minX = 10, minY = 60, minZ = 20;
        int strideY = 3, strideZ = 6;

        // When: accessing the same position multiple times
        int worldX = 11, worldY = 61, worldZ = 20;
        int index = (worldX - minX) + (worldY - minY) * strideY + (worldZ - minZ) * strideZ;
        String first = flatBlocks[index];
        String second = flatBlocks[index];

        // Then: returns consistent data
        assertThat(first).isEqualTo(second).isEqualTo("cobble");
    }

    // ── Immutability tests (failing until WorldReadView implemented) ──

    @Test
    void viewImmutability_sourceArrayMutation_doesNotAffectView() {
        // Given: a mutable source array and a view capturing it (3×1×1 grid along X)
        String[] source = {"air", "stone", "dirt"};
        WorldReadView<BlockStateStub> view = WorldReadView.captureFlat(
                source, BlockStateStub::fromName, BlockStateStub::isAir, "air",
                0, 0, 0, 2, 0, 0, 1, 1);

        // When: the source array is mutated after capture
        source[1] = "obsidian";

        // Then: the view still returns the original captured data
        assertThat(view.getBlockState(0, 0, 0)).isSameAs(BlockStateStub.AIR);
        assertThat(view.getBlockState(1, 0, 0)).isSameAs(BlockStateStub.STONE);
        assertThat(view.getBlockState(2, 0, 0)).isSameAs(BlockStateStub.DIRT);
    }

    @Test
    void viewImmutability_isAir_consistentWithBlockState() {
        // Given: a view with mixed air and non-air blocks (3×1×1 grid along X)
        String[] source = {"air", "stone", "air"};
        WorldReadView<BlockStateStub> view = WorldReadView.captureFlat(
                source, BlockStateStub::fromName, BlockStateStub::isAir, "air",
                0, 0, 0, 2, 0, 0, 1, 1);

        // Then: isAir matches block state
        assertThat(view.isAir(0, 0, 0)).isTrue();
        assertThat(view.isAir(1, 0, 0)).isFalse();
        assertThat(view.isAir(2, 0, 0)).isTrue();
    }

    @Test
    void viewImmutability_outOfBounds_returnsAir() {
        // Given: a view with a small bounded region
        String[] source = {"stone"};
        WorldReadView<BlockStateStub> view = WorldReadView.captureFlat(
                source, BlockStateStub::fromName, BlockStateStub::isAir, "air",
                5, 60, 5, 5, 60, 5, 1, 1);

        // When/Then: accessing outside bounds returns air
        assertThat(view.getBlockState(0, 0, 0)).isSameAs(BlockStateStub.AIR);
        assertThat(view.getBlockState(100, 100, 100)).isSameAs(BlockStateStub.AIR);
        assertThat(view.isAir(0, 0, 0)).isTrue();
    }

    // ── Stub for testing without Minecraft runtime ──

    /**
     * Minimal stub for testing WorldReadView without Minecraft runtime.
     * Maps string names to singleton BlockState instances.
     */
    static final class BlockStateStub {
        static final BlockStateStub AIR = new BlockStateStub("air");
        static final BlockStateStub STONE = new BlockStateStub("stone");
        static final BlockStateStub DIRT = new BlockStateStub("dirt");
        static final BlockStateStub COBBLE = new BlockStateStub("cobble");

        private final String name;

        private BlockStateStub(String name) {
            this.name = name;
        }

        boolean isAir() {
            return this == AIR;
        }

        static BlockStateStub fromName(String name) {
            return switch (name) {
                case "air" -> AIR;
                case "stone" -> STONE;
                case "dirt" -> DIRT;
                case "cobble" -> COBBLE;
                default -> AIR;
            };
        }

        @Override
        public String toString() {
            return "BlockStateStub[" + name + "]";
        }
    }
}
