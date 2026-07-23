package com.github.uright008.pc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

/**
 * Thread-safe world writes via deferred execution.
 *
 * <p>Workers call write primitives only for operations that can be deferred;
 * those primitives record the
 * operation into a {@link WriteQueue} rather than executing it immediately.
 * After workers complete, the main thread calls {@link #drainWrites()} to
 * apply all mutations sequentially — eliminating lock contention on shared
 * world objects.</p>
 */
public final class SafeOps {

    private static final WriteQueue queue = ConcurrentWriteQueue.INSTANCE;

    private SafeOps() {}

    // ── Deferred writes ────────────────────────

    public static void setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        queue.addDeferred(() -> level.setBlock(pos, state, flags));
    }

    public static void scheduleTick(ServerLevel level, BlockPos pos, Block block, int delay, TickPriority priority) {
        queue.addDeferred(() -> level.scheduleTick(pos, block, delay, priority));
    }

    // ── Drain ──────────────────────────────────

    /** Drain all pending deferred writes. Must be called on the main thread. */
    public static void drainWrites() {
        queue.drainWrites();
    }

    /** Clear all pending writes. Intended for test teardown only. */
    public static void resetForTesting() {
        ConcurrentWriteQueue.resetForTesting();
    }
}
