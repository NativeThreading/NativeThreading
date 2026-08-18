package com.github.uright008.ep;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ExplosionCapturedBlockGetter} — the captured
 * {@link net.minecraft.world.level.BlockGetter} adapter that lets entity damage
 * callbacks (e.g. {@code MinecartTNT}'s rail check) read the immutable flat view
 * instead of a live {@code Level}.
 *
 * <p>The adapter is the main-thread capture surface for Tier B blasts: it must
 * return exactly the captured state for in-bounds reads, air for out-of-bounds,
 * and the real build-height bounds so height checks behave like vanilla.</p>
 */
class ExplosionCapturedBlockGetterTest {

    private static final int MIN_X = 0, MIN_Y = 0, MIN_Z = 0;
    private static final int MAX_X = 2, MAX_Y = 0, MAX_Z = 0; // 3×1×1 grid along X
    private static final int STRIDE_Y = 3, STRIDE_Z = 3;

    private static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static WorldReadViewImpl viewWith(BlockState[] states) {
        return new WorldReadViewImpl(states, MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    @Test
    void getBlockState_inBounds_returnsCapturedState() {
        bootstrap();
        WorldReadViewImpl view = viewWith(new BlockState[]{
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
        });
        ExplosionCapturedBlockGetter captured = new ExplosionCapturedBlockGetter(view, -64, 384);

        assertThat(captured.getBlockState(new BlockPos(1, 0, 0)))
                .isSameAs(Blocks.STONE.defaultBlockState());
        assertThat(captured.getBlockState(new BlockPos(2, 0, 0)))
                .isSameAs(Blocks.DIRT.defaultBlockState());
    }

    @Test
    void getBlockState_outOfBounds_returnsAir() {
        bootstrap();
        WorldReadViewImpl view = viewWith(new BlockState[]{
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
        });
        ExplosionCapturedBlockGetter captured = new ExplosionCapturedBlockGetter(view, -64, 384);

        // Above the view's top row — MinecartTNT's pos.above() rail read lands
        // here at the blast edge and must yield air, never a live-level fetch.
        assertThat(captured.getBlockState(new BlockPos(1, 1, 0)))
                .isSameAs(Blocks.AIR.defaultBlockState());
        assertThat(captured.getBlockState(new BlockPos(1, -1, 0)))
                .isSameAs(Blocks.AIR.defaultBlockState());
        assertThat(captured.getBlockState(new BlockPos(5, 0, 0)))
                .isSameAs(Blocks.AIR.defaultBlockState());
        assertThat(captured.getBlockState(new BlockPos(-1, 0, 0)))
                .isSameAs(Blocks.AIR.defaultBlockState());
    }

    @Test
    void getFluidState_matchesBlockState() {
        bootstrap();
        WorldReadViewImpl view = viewWith(new BlockState[]{
                Blocks.WATER.defaultBlockState(),
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
        });
        ExplosionCapturedBlockGetter captured = new ExplosionCapturedBlockGetter(view, -64, 384);

        assertThat(captured.getFluidState(new BlockPos(0, 0, 0)).isEmpty()).isFalse();
        assertThat(captured.getFluidState(new BlockPos(1, 0, 0)).isEmpty()).isTrue();
        // Out of bounds reads air → empty fluid.
        assertThat(captured.getFluidState(new BlockPos(0, 1, 0)).isEmpty()).isTrue();
    }

    @Test
    void getBlockEntity_returnsNull() {
        bootstrap();
        WorldReadViewImpl view = viewWith(new BlockState[]{
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
        });
        ExplosionCapturedBlockGetter captured = new ExplosionCapturedBlockGetter(view, -64, 384);

        assertThat(captured.getBlockEntity(new BlockPos(1, 0, 0))).isNull();
    }

    @Test
    void heightBounds_reflectConstructorArgs() {
        bootstrap();
        WorldReadViewImpl view = viewWith(new BlockState[]{
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
        });
        ExplosionCapturedBlockGetter captured = new ExplosionCapturedBlockGetter(view, -64, 384);

        assertThat(captured.getMinY()).isEqualTo(-64);
        assertThat(captured.getHeight()).isEqualTo(384);
        assertThat(captured.getMaxY()).isEqualTo(-64 + 384 - 1);
        assertThat(captured.isInsideBuildHeight(new BlockPos(1, 0, 0))).isTrue();
        assertThat(captured.isInsideBuildHeight(new BlockPos(1, 1000, 0))).isFalse();
    }
}
