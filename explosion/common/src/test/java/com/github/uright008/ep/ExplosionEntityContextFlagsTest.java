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
 * Tests for the per-entity entity-context routing driven by
 * {@link ExplosionFlatViewHelper.EntityContextFlags}.
 *
 * <p>Three things are locked here:</p>
 * <ol>
 *   <li><b>Classification:</b> only vanilla {@link LiquidBlock} is a plain liquid;
 *       scaffolding (and a modded {@code LiquidBlock} subclass) is state-dependent
 *       and forces the live per-entity exposure for every entity; powder snow gets
 *       its own flag because its context dependence can be decided per entity.</li>
 *   <li><b>Routing policy:</b> liquid needs live exposure for living entities,
 *       powder snow for entities that can observe a powder-snow shape (fall
 *       distance / falling block / walk-on-snow), state-dependent blocks for
 *       everyone.</li>
 *   <li><b>Exactness premise:</b> a {@link LiquidBlock} resolves to an empty
 *       collision shape for the collision context vanilla's {@code getSeenPercent}
 *       builds for a non-living entity, and powder snow resolves to an empty shape
 *       for both the context-free flat view and any entity context that fails the
 *       powder-snow predicate. The flat-view box table must therefore be a
 *       pass-through ({@code null}) for those cells, while scaffolding's flat-view
 *       shape is non-empty ({@code SHAPE_STABLE}) - which is why scaffolding keeps
 *       the blast-wide live fallback.</li>
 * </ol>
 *
 * <p>A real non-living entity needs a level to construct, so the non-living branch
 * is exercised with an {@link EntityCollisionContext} carrying a {@code null}
 * entity: both liquid and powder-snow shape overrides only inspect the entity when
 * it is non-null (and liquid only for {@code LivingEntity}).</p>
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
        ExplosionFlatViewHelper.EntityContextFlags none = ExplosionFlatViewHelper.EntityContextFlags.NONE;
        assertThat(none.requiresLiveExposure(false, false)).isFalse();
        assertThat(none.requiresLiveExposure(true, false)).isFalse();
        assertThat(none.requiresLiveExposure(false, true)).isFalse();
        assertThat(none.requiresLiveExposure(true, true)).isFalse();
    }

    @Test
    void liquidOnly_needsLiveExposureForLivingEntitiesOnly() {
        ExplosionFlatViewHelper.EntityContextFlags flags =
                new ExplosionFlatViewHelper.EntityContextFlags(false, true, false);
        assertThat(flags.requiresLiveExposure(false, false)).isFalse();
        assertThat(flags.requiresLiveExposure(true, false)).isTrue();
        assertThat(flags.requiresLiveExposure(false, true)).isFalse();
        assertThat(flags.requiresLiveExposure(true, true)).isTrue();
    }

    @Test
    void powderSnowOnly_needsLiveExposureOnlyForPowderSensitiveEntities() {
        ExplosionFlatViewHelper.EntityContextFlags flags =
                new ExplosionFlatViewHelper.EntityContextFlags(false, false, true);
        assertThat(flags.requiresLiveExposure(false, false)).isFalse();
        assertThat(flags.requiresLiveExposure(true, false)).isFalse();
        assertThat(flags.requiresLiveExposure(false, true)).isTrue();
        assertThat(flags.requiresLiveExposure(true, true)).isTrue();
    }

    @Test
    void stateDependent_needsLiveExposureForEveryEntity() {
        ExplosionFlatViewHelper.EntityContextFlags flags =
                new ExplosionFlatViewHelper.EntityContextFlags(true, false, false);
        assertThat(flags.requiresLiveExposure(false, false)).isTrue();
        assertThat(flags.requiresLiveExposure(true, false)).isTrue();
        assertThat(flags.requiresLiveExposure(false, true)).isTrue();
        assertThat(flags.requiresLiveExposure(true, true)).isTrue();
        ExplosionFlatViewHelper.EntityContextFlags all =
                new ExplosionFlatViewHelper.EntityContextFlags(true, true, true);
        assertThat(all.requiresLiveExposure(false, false)).isTrue();
        assertThat(all.requiresLiveExposure(true, true)).isTrue();
    }

    @Test
    void liquidAndPowderSnow_areIndependentlyRouted() {
        ExplosionFlatViewHelper.EntityContextFlags flags =
                new ExplosionFlatViewHelper.EntityContextFlags(false, true, true);
        // Non-living, powder-insensitive entity: the only exact flat-view case.
        assertThat(flags.requiresLiveExposure(false, false)).isFalse();
        // Living entity: liquid shape is context-dependent (Striders).
        assertThat(flags.requiresLiveExposure(true, false)).isTrue();
        // Powder-sensitive non-living entity (falling block / falling item).
        assertThat(flags.requiresLiveExposure(false, true)).isTrue();
        assertThat(flags.requiresLiveExposure(true, true)).isTrue();
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
            assertThat(ExplosionFlatViewHelper.isPowderSnowBlock(block))
                    .as("not powder snow: %s", block)
                    .isFalse();
        }
    }

    @Test
    void scaffolding_isStateDependent() {
        assertThat(ExplosionFlatViewHelper.isStateDependentContextBlock(Blocks.SCAFFOLDING))
                .isTrue();
        assertThat(ExplosionFlatViewHelper.isPlainLiquidBlock(Blocks.SCAFFOLDING))
                .isFalse();
        assertThat(ExplosionFlatViewHelper.isPowderSnowBlock(Blocks.SCAFFOLDING))
                .isFalse();
    }

    @Test
    void powderSnow_hasItsOwnFlagAndIsNotStateDependent() {
        assertThat(ExplosionFlatViewHelper.isPowderSnowBlock(Blocks.POWDER_SNOW))
                .isTrue();
        assertThat(ExplosionFlatViewHelper.isStateDependentContextBlock(Blocks.POWDER_SNOW))
                .isFalse();
        assertThat(ExplosionFlatViewHelper.isPlainLiquidBlock(Blocks.POWDER_SNOW))
                .isFalse();
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
            assertThat(ExplosionFlatViewHelper.isPowderSnowBlock(block))
                    .as("not powder snow: %s", block)
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
    void powderSnowCollisionShape_isEmptyForContextFreeAndEntitylessContexts() {
        BlockState state = Blocks.POWDER_SNOW.defaultBlockState();
        VoxelShape empty = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS, CollisionContext.empty());
        assertThat(empty.isEmpty()).as("context-free powder snow shape").isTrue();
        VoxelShape entityless = state.getCollisionShape(EmptyBlockGetter.INSTANCE, POS,
                new NonLivingCollisionContext(null));
        assertThat(entityless.isEmpty()).as("entityless powder snow shape").isTrue();
    }

    @Test
    void flatViewShapeBoxes_arePassThroughForLiquidAndPowderSnowCells() {
        // 2x2 grid in one section; layout: index = (x)+y*2 (min=(0,0,0), strideY=2, strideZ=2).
        BlockState[] states = new BlockState[]{
                Blocks.LAVA.defaultBlockState(),                                        // (0,0,0) liquid
                Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1),        // (1,0,0) flowing liquid
                Blocks.POWDER_SNOW.defaultBlockState(),                                 // (0,1,0) powder snow
                Blocks.AIR.defaultBlockState(),                                         // (1,1,0) air
        };
        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                states, EmptyBlockGetter.INSTANCE, 0, 0, 0, 2, 2, states.length);
        assertThat(boxes[0]).as("source lava cell").isNull();
        assertThat(boxes[1]).as("flowing water cell").isNull();
        assertThat(boxes[2]).as("powder snow cell").isNull();
        assertThat(boxes[3]).as("air cell").isNull();
    }

    @Test
    void flatViewShapeBoxes_areNotEmptyForScaffoldingCells() {
        // Empty context resolves scaffolding with isAbove(..., true) -> SHAPE_STABLE,
        // which is why scaffolding keeps the blast-wide live fallback.
        BlockState[] states = new BlockState[]{
                Blocks.SCAFFOLDING.defaultBlockState(),
        };
        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                states, EmptyBlockGetter.INSTANCE, 0, 0, 0, 1, 1, states.length);
        assertThat(boxes[0]).as("scaffolding cell").isNotNull();
    }
}
