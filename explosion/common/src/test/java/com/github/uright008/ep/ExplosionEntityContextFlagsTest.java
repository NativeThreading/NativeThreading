package com.github.uright008.ep;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the per-entity entity-context routing that splits the old
 * blast-wide {@code hasEntityContextBlocks} flag into
 * {@link ExplosionFlatViewHelper.EntityContextFlags}.
 *
 * <p>Two things are locked here:</p>
 * <ol>
 *   <li><b>Classification:</b> only vanilla {@link LiquidBlock} is a plain liquid;
 *       scaffolding / powder snow (and a modded {@code LiquidBlock} subclass) are
 *       state-dependent and therefore force the live per-entity exposure for every
 *       entity.</li>
 *   <li><b>Exactness premise:</b> a {@link LiquidBlock} resolves to an empty
 *       collision shape for the collision context vanilla's
 *       {@code getSeenPercent} builds for a non-living entity
 *       ({@code CollisionContext.of(entity)} — {@code alwaysCollideWithFluid=false},
 *       {@code entity instanceof LivingEntity == false}), so the context-free flat
 *       view is bit-identical for those entities. The second test also locks the
 *       flat-view box table to a pass-through ({@code null}) for liquid cells.</li>
 * </ol>
 *
 * <p>A real non-living entity needs a level to construct, so the non-living branch
 * is exercised with an {@link EntityCollisionContext} carrying a {@code null}
 * entity: {@code LiquidBlock#ifMobIsColliding} only checks
 * {@code entity instanceof LivingEntity}, which is false in both cases.</p>
 */
class ExplosionEntityContextFlagsTest {

    private static final BlockPos POS = BlockPos.ZERO;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Vanilla {@code CollisionContext.of(entity)} for a non-living entity:
     *  {@code alwaysCollideWithFluid=false}, no descending/placement, no item. */
    private static final class NonLivingCollisionContext extends EntityCollisionContext {
        NonLivingCollisionContext(@Nullable Entity entity) {
            super(false, false, 0.0, ItemStack.EMPTY, false, entity);
        }
    }

    // ── Routing policy ──────────────────────────────────────────────────────

    @Test
    void noContextBlocks_neverNeedsLiveExposure() {
        assertThat(ExplosionFlatViewHelper.EntityContextFlags.NONE.requiresLiveExposure(false)).isFalse();
        assertThat(ExplosionFlatViewHelper.EntityContextFlags.NONE.requiresLiveExposure(true)).isFalse();
    }

    @Test
    void liquidOnly_needsLiveExposureForLivingEntitiesOnly() {
        ExplosionFlatViewHelper.EntityContextFlags flags =
                new ExplosionFlatViewHelper.EntityContextFlags(false, true);
        assertThat(flags.requiresLiveExposure(false)).isFalse();
        assertThat(flags.requiresLiveExposure(true)).isTrue();
    }

    @Test
    void stateDependent_needsLiveExposureForEveryEntity() {
        ExplosionFlatViewHelper.EntityContextFlags flags =
                new ExplosionFlatViewHelper.EntityContextFlags(true, false);
        assertThat(flags.requiresLiveExposure(false)).isTrue();
        assertThat(flags.requiresLiveExposure(true)).isTrue();
        ExplosionFlatViewHelper.EntityContextFlags both =
                new ExplosionFlatViewHelper.EntityContextFlags(true, true);
        assertThat(both.requiresLiveExposure(false)).isTrue();
        assertThat(both.requiresLiveExposure(true)).isTrue();
    }

    // ── Block classification ────────────────────────────────────────────────

    @Test
    void vanillaLiquids_arePlainLiquidNotStateDependent() {
        for (Block block : new Block[]{Blocks.WATER, Blocks.LAVA}) {
            assertThat(ExplosionFlatViewHelper.isPlainLiquidBlock(block))
                    .as("plain liquid: %s", block)
                    .isTrue();
            assertThat(ExplosionFlatViewHelper.isStateDependentContextBlock(block))
                    .as("not state-dependent: %s", block)
                    .isFalse();
        }
    }

    @Test
    void scaffoldingAndPowderSnow_areStateDependent() {
        for (Block block : new Block[]{Blocks.SCAFFOLDING, Blocks.POWDER_SNOW}) {
            assertThat(ExplosionFlatViewHelper.isStateDependentContextBlock(block))
                    .as("state-dependent: %s", block)
                    .isTrue();
            assertThat(ExplosionFlatViewHelper.isPlainLiquidBlock(block))
                    .as("not a liquid: %s", block)
                    .isFalse();
        }
    }

    @Test
    void ordinaryBlocks_areNeitherCategory() {
        for (Block block : new Block[]{Blocks.STONE, Blocks.OBSIDIAN, Blocks.AIR, Blocks.STONE_SLAB}) {
            assertThat(ExplosionFlatViewHelper.isStateDependentContextBlock(block))
                    .as("not state-dependent: %s", block)
                    .isFalse();
            assertThat(ExplosionFlatViewHelper.isPlainLiquidBlock(block))
                    .as("not a liquid: %s", block)
                    .isFalse();
        }
    }

    // ── Exactness premise (vanilla 26.3 source behavior) ────────────────────

    @Test
    void liquidCollisionShape_isEmptyForContextFreeAndNonLivingContexts() {
        BlockState[] states = new BlockState[]{
                Blocks.LAVA.defaultBlockState(),
                Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),
                Blocks.WATER.defaultBlockState(),
                Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),
        };
        for (BlockState state : states) {
            VoxelShape empty = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS, CollisionContext.empty());
            assertThat(empty.isEmpty())
                    .as("context-free shape is empty: %s", state)
                    .isTrue();
            VoxelShape nonLiving = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS,
                    new NonLivingCollisionContext(null));
            assertThat(nonLiving.isEmpty())
                    .as("non-living entity shape is empty: %s", state)
                    .isTrue();
        }
    }

    @Test
    void flatViewShapeBoxes_arePassThroughForLiquidCells() {
        // 2x2 grid in one section; layout: index = (x)+y*2 (min=(0,0,0), strideY=2, strideZ=2).
        BlockState[] states = new BlockState[]{
                Blocks.LAVA.defaultBlockState(),                                        // (0,0,0) liquid
                Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),        // (1,0,0) flowing liquid
                Blocks.STONE.defaultBlockState(),                                       // (0,1,0) solid
                Blocks.AIR.defaultBlockState(),                                         // (1,1,0) air
        };
        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                states, EmptyBlockGetter.INSTANCE, 0, 0, 0, 2, 2, states.length);
        assertThat(boxes[0]).as("source lava cell").isNull();
        assertThat(boxes[1]).as("flowing water cell").isNull();
        assertThat(boxes[2]).as("stone cell").isNotNull();
        assertThat(boxes[3]).as("air cell").isNull();
    }
}
