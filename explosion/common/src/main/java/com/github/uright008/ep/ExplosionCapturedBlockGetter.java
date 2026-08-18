package com.github.uright008.ep;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * {@link BlockGetter} adapter over the captured flat view, used ONLY on the main
 * thread to evaluate an explosion source entity's damage callbacks
 * ({@code getBlockExplosionResistance} / {@code shouldBlockExplode}) against the
 * captured block snapshot.
 *
 * <p>{@code MinecartTNT} overrides those callbacks to read
 * {@code level.getBlockState(pos.above())} (its rail exemption). Calling that on
 * a live {@code ServerLevel} from a worker deadlocks — an off-main-thread chunk
 * fetch blocks on the server thread's mailbox while the main thread waits on the
 * worker latch. Giving the callback this captured view routes every read into
 * the immutable flat array instead, so {@code ExplosionBlockStage} can capture
 * the entity's decisions on the main thread and hand workers pure tables.
 *
 * <p>During {@code calculateExplodedPositions} the world is not mutated, so the
 * captured snapshot is bit-identical to the live level for every read these
 * callbacks make (out-of-bounds reads yield AIR — rails never float at a blast
 * edge, so the MinecartTNT rail check is unaffected).
 *
 * <p>Block entities are not captured (explosion damage callbacks never read
 * them); build-height bounds mirror the real level so
 * {@link #isInsideBuildHeight} behaves like vanilla.
 */
public final class ExplosionCapturedBlockGetter implements BlockGetter {

    private final WorldReadViewImpl view;
    private final int minY;
    private final int height;

    public ExplosionCapturedBlockGetter(WorldReadViewImpl view, int minY, int height) {
        this.view = view;
        this.minY = minY;
        this.height = height;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return view.getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }
}
