package com.github.uright008.ep;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ExplosionShapeBoxes}: the capture-phase shape resolver.
 *
 * <p>The crash this guards against: resolving a {@code dynamicShape} block's
 * collision shape with {@code null} level/pos NPEs inside the block
 * implementation (moving piston → {@code level.getBlockEntity}; bamboo,
 * pointed dripstone, sulfur spike → {@code pos} in the position hash). The
 * resolver must always use the real level and the real cell position, and must
 * never serve a dynamic state's boxes from the per-state cache.</p>
 */
class ExplosionShapeBoxesLiveTest {

    private static final BlockGetter EMPTY = EmptyBlockGetter.INSTANCE;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ── Fixtures ──

    /** Minimal {@link BlockGetter}: air everywhere, plus optional block
     *  entities at exact positions (enough for moving-piston shape resolution). */
    private static final class TestLevel implements BlockGetter {
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

        void put(BlockEntity blockEntity) {
            blockEntities.put(blockEntity.getBlockPos(), blockEntity);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState().getFluidState();
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return blockEntities.get(pos);
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    /** Oracle: what the flattened box payload must be for one cell. */
    private static double[] expectedBoxes(BlockGetter level, BlockState state, BlockPos pos) {
        if (state.isAir()) return null;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape == Shapes.block()) return new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        if (shape.isEmpty()) return null;
        List<AABB> aabbs = shape.toAabbs();
        double[] packed = new double[aabbs.size() * 6];
        for (int b = 0; b < aabbs.size(); b++) {
            AABB bb = aabbs.get(b);
            int o = b * 6;
            packed[o] = bb.minX;
            packed[o + 1] = bb.minY;
            packed[o + 2] = bb.minZ;
            packed[o + 3] = bb.maxX;
            packed[o + 4] = bb.maxY;
            packed[o + 5] = bb.maxZ;
        }
        return packed;
    }

    private static void assertBoxesEqual(double[] actual, double[] expected, String description) {
        if (expected == null) {
            assertThat(actual).as(description).isNull();
            return;
        }
        assertThat(actual).as(description).isNotNull();
        assertThat(actual).as(description).containsExactly(expected);
    }

    // ── Invariant: every registered default state resolves without crashing ──

    @Test
    void everyRegisteredDefaultState_resolvesLiveWithoutCrash() {
        BlockPos origin = new BlockPos(0, 0, 0);
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            String label = BuiltInRegistries.BLOCK.getKey(block).toString();
            assertThatCode(() -> {
                double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                        new BlockState[] {state}, EMPTY, 0, 0, 0, 1, 1, 1);
                assertBoxesEqual(boxes[0], expectedBoxes(EMPTY, state, origin), label);
            }).as("live shape resolution for %s", label).doesNotThrowAnyException();
        }
    }

    // ── dynamicShape: live level + position, never per-state cached ──

    @Test
    void movingPiston_resolvesPerCellBlockEntityShape() {
        BlockState movingPiston = Blocks.MOVING_PISTON.defaultBlockState();
        BlockPos posStone = new BlockPos(0, 0, 0);
        BlockPos posSlab = new BlockPos(1, 0, 0);
        TestLevel level = new TestLevel();
        // extending=true, progress=0 → moved state shifted down one block.
        level.put(new PistonMovingBlockEntity(posStone, movingPiston,
                Blocks.STONE.defaultBlockState(), Direction.UP, true, false));
        level.put(new PistonMovingBlockEntity(posSlab, movingPiston,
                Blocks.OAK_SLAB.defaultBlockState(), Direction.UP, true, false));

        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                new BlockState[] {movingPiston, movingPiston, movingPiston},
                level, 0, 0, 0, 3, 3, 3);

        double[] expectedStone = expectedBoxes(level, movingPiston, posStone);
        double[] expectedSlab = expectedBoxes(level, movingPiston, posSlab);
        assertThat(Arrays.equals(expectedStone, expectedSlab))
                .as("fixture must produce distinct piston shapes per block entity").isFalse();
        assertBoxesEqual(boxes[0], expectedStone, "moving piston with STONE block entity");
        assertBoxesEqual(boxes[1], expectedSlab, "moving piston with OAK_SLAB block entity");
        // No block entity at the third cell → Shapes.empty(), and it must not
        // have been served from cell 0/1 (no dynamic-state caching).
        assertThat(boxes[2]).as("moving piston without block entity").isNull();
    }

    @Test
    void shulkerBox_withoutBlockEntity_resolvesWithoutCrash() {
        BlockState shulker = Blocks.SHULKER_BOX.defaultBlockState();
        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                new BlockState[] {shulker}, EMPTY, 0, 0, 0, 1, 1, 1);
        // Vanilla falls back to Shapes.block() when the block entity is absent
        // (a null level would have NPE'd before reaching that fallback).
        assertBoxesEqual(boxes[0], new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0},
                "shulker box without block entity");
    }

    /** Bamboo, pointed dripstone and sulfur spike offset their shape by a hash
     *  of the cell position, so resolving them at the wrong (or null) position
     *  is silently wrong or an NPE. */
    @Test
    void positionDependentDynamicStates_resolvePerCellPosition() {
        for (BlockState state : List.of(
                Blocks.BAMBOO.defaultBlockState(),
                Blocks.POINTED_DRIPSTONE.defaultBlockState(),
                Blocks.SULFUR_SPIKE.defaultBlockState())) {
            BlockPos origin = new BlockPos(0, 0, 0);
            double[] originExpected = expectedBoxes(EMPTY, state, origin);
            int otherX = -1;
            double[] otherExpected = null;
            for (int x = 1; x <= 64; x++) {
                double[] candidate = expectedBoxes(EMPTY, state, new BlockPos(x, 0, 0));
                if (!Arrays.equals(candidate, originExpected)) {
                    otherX = x;
                    otherExpected = candidate;
                    break;
                }
            }
            assertThat(otherX).as("position hash must vary within 64 blocks for %s", state)
                    .isPositive();

            // Recycle the outer box table between the two blasts to prove a
            // recycled array cannot leak the first cell's dynamic payload.
            AtomicReference<double[][]> cache = new AtomicReference<>();
            double[][] first = ExplosionShapeBoxes.flattenShapeBoxesReused(
                    new BlockState[] {state}, EMPTY, 0, 0, 0, 1, 1, 1, cache);
            assertBoxesEqual(first[0], originExpected, "cell (0,0,0) for " + state);
            cache.set(first);
            double[][] second = ExplosionShapeBoxes.flattenShapeBoxesReused(
                    new BlockState[] {state}, EMPTY, otherX, 0, 0, 1, 1, 1, cache);
            assertThat(second).as("outer box table must be recycled").isSameAs(first);
            assertBoxesEqual(second[0], otherExpected,
                    "cell (" + otherX + ",0,0) for " + state);
        }
    }

    // ── static shapes: per-state identity cache ──

    @Test
    void staticStates_sharePerStateCachePayload() {
        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                new BlockState[] {
                        Blocks.STONE.defaultBlockState(),
                        Blocks.STONE.defaultBlockState(),
                        Blocks.OAK_SLAB.defaultBlockState()},
                EMPTY, 0, 0, 0, 3, 3, 3);

        assertThat(boxes[0]).as("static states must share the cached payload").isSameAs(boxes[1]);
        assertBoxesEqual(boxes[0], new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0}, "stone");
        assertBoxesEqual(boxes[2], expectedBoxes(EMPTY, Blocks.OAK_SLAB.defaultBlockState(),
                new BlockPos(2, 0, 0)), "oak slab");
    }

    // ── Reuse discipline (C3) and contract guards ──

    @Test
    void recycledBoxTable_clearsCellsThatBecameAir() {
        AtomicReference<double[][]> cache = new AtomicReference<>();
        double[][] filled = ExplosionShapeBoxes.flattenShapeBoxesReused(
                new BlockState[] {Blocks.STONE.defaultBlockState()}, EMPTY, 0, 0, 0, 1, 1, 1, cache);
        assertThat(filled[0]).isNotNull();
        cache.set(filled);

        double[][] cleared = ExplosionShapeBoxes.flattenShapeBoxesReused(
                new BlockState[] {Blocks.AIR.defaultBlockState()}, EMPTY, 0, 0, 0, 1, 1, 1, cache);
        assertThat(cleared).isSameAs(filled);
        assertThat(cleared[0]).as("stale box payload for a now-air cell").isNull();
    }

    @Test
    void nullLevel_isRejected() {
        assertThatThrownBy(() -> ExplosionShapeBoxes.flattenShapeBoxes(
                new BlockState[] {Blocks.STONE.defaultBlockState()}, null, 0, 0, 0, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level");
    }

    @Test
    void inconsistentStrides_areRejected() {
        assertThatThrownBy(() -> ExplosionShapeBoxes.flattenShapeBoxes(
                new BlockState[] {Blocks.STONE.defaultBlockState()}, EMPTY, 0, 0, 0, 2, 3, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strides");
    }
}
