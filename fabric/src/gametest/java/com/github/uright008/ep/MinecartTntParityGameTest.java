package com.github.uright008.ep;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TNT-minecart explosion parity gametest — the regression for the worker-thread
 * / live-Level deadlock.
 *
 * <p>A primed {@link MinecartTNT} is the one vanilla source whose
 * {@code getBlockExplosionResistance}/{@code shouldBlockExplode} read the live
 * {@code Level} ({@code level.getBlockState(pos.above())} rail check). Feeding
 * that through the old parallel block stage deadlocked the server for the 5s
 * worker timeout. This test reproduces the exact call {@code MinecartTNT.explode()}
 * makes — {@code level.explode(minecart, null, null, ...)} with the primed
 * minecart as source — and asserts (a) the parallel detonation returns fast (no
 * worker stall) and (b) the destroyed-block set is bit-identical to vanilla.</p>
 *
 * <p>Note: the rail exemption also protects the block directly below a rail
 * ({@code pos.above()} reads the rail), so the rig deliberately places dirt only
 * away from that path.</p>
 */
public final class MinecartTntParityGameTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);
    /** Parallel detonation must return well under the 5s worker timeout; the
     *  deadlock stall is ~5s+. Fixed runs return in well under a second. */
    private static final long MAX_DETONATION_MS = 4000;
    /** Ticks to let block drops settle before capturing findings. */
    private static final int SETTLE_TICKS = 20;

    /** Dirt blocks placed around the rail; all are genuinely destroyed by the
     *  blast (none sits on or below the rail, so the rail exemption never
     *  protects them). */
    private static final BlockPos[] RIG_DIRT = {
            new BlockPos(1, 0, 0),
            new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1),
            new BlockPos(0, 0, -1),
            new BlockPos(0, 1, 0),
    };

    /** Builds the rig: a rail for the minecart plus the dirt ring/column. */
    private static void buildRig(GameTestHelper helper, BlockPos origin) {
        helper.setBlock(origin, Blocks.RAIL.defaultBlockState());
        for (BlockPos p : RIG_DIRT) {
            helper.setBlock(origin.offset(p), Blocks.DIRT.defaultBlockState());
        }
    }

    /** Survivors among the rig dirt blocks, as a deterministic string. */
    private static String survivors(GameTestHelper helper, BlockPos origin) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : RIG_DIRT) {
            BlockState state = helper.getBlockState(origin.offset(p));
            if (!state.isAir()) {
                sb.append(p).append('=').append(state).append(';');
            }
        }
        return sb.toString();
    }

    /** Clears every entity (previous run's block-drop items). */
    private static void clearEntities(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getAllEntities().forEach(Entity::discard);
    }

    /** Spawns a primed TNT minecart on the rail. */
    private static MinecartTNT spawnPrimedMinecart(GameTestHelper helper, BlockPos origin) {
        BlockPos abs = helper.absolutePos(origin);
        MinecartTNT minecart = EntityTypes.TNT_MINECART.create(helper.getLevel(), EntitySpawnReason.LOAD);
        if (minecart == null) throw new IllegalStateException("cannot spawn TNT minecart");
        minecart.setPos(abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5);
        helper.getLevel().addFreshEntity(minecart);
        minecart.primeFuse(null);
        return minecart;
    }

    /**
     * Detonates the primed minecart via the exact call {@code MinecartTNT.explode()}
     * makes — {@code level.explode(minecart, null, null, ...)} — and returns the
     * wall-clock milliseconds the call took. The minecart is discarded afterwards
     * so its 80-tick fuse never re-detonates it.
     */
    private static long detonatePrimedMinecart(GameTestHelper helper, BlockPos origin) {
        BlockPos abs = helper.absolutePos(origin);
        MinecartTNT minecart = spawnPrimedMinecart(helper, origin);
        long start = System.nanoTime();
        helper.getLevel().explode(minecart, null, null,
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                4.0F, false, Level.ExplosionInteraction.TNT);
        long ms = (System.nanoTime() - start) / 1_000_000;
        minecart.discard();
        return ms;
    }

    @GameTest(maxTicks = 200, padding = 40)
    public void primedMinecartExplosionParityAndNoWorkerStall(GameTestHelper helper) {
        boolean original = ExplosionParallelConfig.isEnabled();
        String[] vanillaSurvivors = new String[1];
        String[] parallelSurvivors = new String[1];
        long[] detonationMs = new long[1];

        helper.startSequence()
                // Baseline: vanilla (parallel disabled).
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildRig(helper, ORIGIN);
                    ExplosionParallelConfig.setEnabled(false);
                    detonatePrimedMinecart(helper, ORIGIN);
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> vanillaSurvivors[0] = survivors(helper, ORIGIN))
                // Candidate: parallel enabled — must not stall on the worker pool.
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildRig(helper, ORIGIN);
                    ExplosionParallelConfig.setEnabled(true);
                    detonationMs[0] = detonatePrimedMinecart(helper, ORIGIN);
                })
                .thenIdle(SETTLE_TICKS)
                .thenExecute(() -> {
                    if (detonationMs[0] > MAX_DETONATION_MS) {
                        helper.fail("parallel TNT minecart detonation stalled "
                                + detonationMs[0] + "ms (worker/live-Level deadlock?)");
                        return;
                    }
                    parallelSurvivors[0] = survivors(helper, ORIGIN);
                    if (!vanillaSurvivors[0].isEmpty()) {
                        helper.fail("vanilla blast left rig blocks: " + vanillaSurvivors[0]);
                    }
                    if (!parallelSurvivors[0].isEmpty()) {
                        helper.fail("parallel blast left rig blocks: " + parallelSurvivors[0]);
                    }
                    if (!vanillaSurvivors[0].equals(parallelSurvivors[0])) {
                        helper.fail("TNT minecart destroyed blocks differ: vanilla='"
                                + vanillaSurvivors[0] + "' parallel='" + parallelSurvivors[0] + "'");
                    }
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(original);
                    helper.succeed();
                });
    }
}
