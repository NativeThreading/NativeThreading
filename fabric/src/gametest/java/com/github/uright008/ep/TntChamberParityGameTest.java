package com.github.uright008.ep;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * TNT-chamber explosion gametest built on {@link TntChamberTestBase}.
 *
 * <p>No command blocks are used: the chamber is built programmatically, detonated,
 * and after 10 seconds the obsidian interior must be empty — no leftover blocks,
 * no item entities. The parallel pipeline must be a no-op with respect to this
 * observable outcome, so the same assertion is checked with the pipeline both
 * disabled and enabled.</p>
 */
public final class TntChamberParityGameTest extends TntChamberTestBase {

    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);
    private static final BlockPos CENTER = ORIGIN.offset(
            CHAMBER_SIZE / 2, CHAMBER_SIZE / 2, CHAMBER_SIZE / 2);

    @GameTest(maxTicks = 400, padding = 40)
    public void interiorIsEmptyAfterDetonation(GameTestHelper helper) {
        String[] findings = new String[1];
        helper.startSequence()
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildChamber(helper, ORIGIN);
                    detonateChamber(helper, CENTER);
                })
                .thenIdle(DETONATION_SETTLE_TICKS)
                .thenExecute(() -> {
                    findings[0] = interiorFindings(helper, ORIGIN);
                    if (!findings[0].isEmpty()) {
                        helper.fail("interior not empty after detonation: " + findings[0]);
                    }
                })
                .thenExecute(helper::succeed);
    }

    @GameTest(maxTicks = 400, padding = 40)
    public void interiorEmptyMatchesWithParallelEnabled(GameTestHelper helper) {
        boolean originalEnabled = ExplosionParallelConfig.isEnabled();
        String[] vanillaFindings = new String[1];
        String[] parallelFindings = new String[1];

        helper.startSequence()
                // Baseline: vanilla (parallel disabled).
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildChamber(helper, ORIGIN);
                    ExplosionParallelConfig.setEnabled(false);
                    detonateChamber(helper, CENTER);
                })
                .thenIdle(DETONATION_SETTLE_TICKS)
                .thenExecute(() -> vanillaFindings[0] = interiorFindings(helper, ORIGIN))
                // Candidate: parallel enabled.
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildChamber(helper, ORIGIN);
                    ExplosionParallelConfig.setEnabled(true);
                    detonateChamber(helper, CENTER);
                })
                .thenIdle(DETONATION_SETTLE_TICKS)
                .thenExecute(() -> {
                    parallelFindings[0] = interiorFindings(helper, ORIGIN);
                    if (!parallelFindings[0].isEmpty()) {
                        helper.fail("interior not empty (parallel): " + parallelFindings[0]);
                    }
                    if (!vanillaFindings[0].equals(parallelFindings[0])) {
                        helper.fail("findings differ: vanilla='" + vanillaFindings[0]
                                + "' parallel='" + parallelFindings[0] + "'");
                    }
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(originalEnabled);
                    helper.succeed();
                });
    }
}
