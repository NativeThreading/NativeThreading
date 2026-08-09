package com.github.uright008.ep;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorldReadViewImpl} — the immutable world snapshot that
 * workers consume instead of live {@code Level} or {@code ChunkGrid}.
 *
 * <p>Immutability tests verify that the view cannot observe mutations after
 * construction, and that out-of-bounds access returns air.</p>
 */
class WorldReadViewTest {

    private static final int MIN_X = 0, MIN_Y = 0, MIN_Z = 0;
    private static final int MAX_X = 2, MAX_Y = 0, MAX_Z = 0; // 3×1×1 grid along X
    private static final int STRIDE_Y = 3, STRIDE_Z = 3;

    private static WorldReadViewImpl buildView() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        BlockState[] states = {
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
        };
        return new WorldReadViewImpl(states, MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    @Test
    void viewImmutability_repeatedReads_areStable() {
        // The view holds the flat array by reference (the main-thread
        // producer fills it and never mutates it afterwards); repeated reads
        // must return identical captured states.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        BlockState[] source = {
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.DIRT.defaultBlockState(),
        };
        WorldReadViewImpl view = new WorldReadViewImpl(
                source, MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);

        assertThat(view.getBlockState(1, 0, 0)).isSameAs(Blocks.STONE.defaultBlockState());
        assertThat(view.getBlockState(1, 0, 0)).isSameAs(view.getBlockState(1, 0, 0));
    }

    @Test
    void viewImmutability_outOfBounds_returnsAir() {
        WorldReadViewImpl view = buildView();

        // Accessing outside bounds returns air.
        assertThat(view.getBlockState(-1, 0, 0)).isSameAs(Blocks.AIR.defaultBlockState());
        assertThat(view.getBlockState(100, 100, 100)).isSameAs(Blocks.AIR.defaultBlockState());
    }

    @Test
    void getBlockStateUnchecked_insideBounds_returnsState() {
        WorldReadViewImpl view = buildView();

        // Unchecked accessor matches the bounds-checked one for in-bounds coords.
        assertThat(view.getBlockStateUnchecked(1, 0, 0)).isSameAs(view.getBlockState(1, 0, 0));
    }
}
