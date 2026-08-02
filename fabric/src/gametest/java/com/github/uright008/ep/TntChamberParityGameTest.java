package com.github.uright008.ep;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * TNT-chamber explosion gametest built on {@link TntChamberTestBase}.
 *
 * <p>The backup world (world-tnt-backup) is a post-explosion snapshot: it
 * contains 2400+ TNT entities and no intact chamber. These tests therefore
 * build the chamber programmatically, clear all entities, place a single
 * {@code Fuse:0} PrimedTnt detonator above the shell, and verify the explosion
 * behaves identically with the parallel pipeline enabled vs disabled.</p>
 */
public final class TntChamberParityGameTest extends TntChamberTestBase {

    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);
    private static final BlockPos DETONATOR = ORIGIN.offset(
            CHAMBER_SIZE / 2, CHAMBER_SIZE + 1, CHAMBER_SIZE / 2);

    @GameTest(maxTicks = 400, padding = 40)
    public void parallelAndVanillaLeaveSameTntResidue(GameTestHelper helper) {
        boolean originalEnabled = ExplosionParallelConfig.isEnabled();
        long[] vanillaResidue = new long[1];
        long[] parallelResidue = new long[1];

        helper.startSequence()
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildChamber(helper, ORIGIN);
                    ExplosionParallelConfig.setEnabled(false);
                    spawnDetonator(helper, DETONATOR);
                    detonateChamber(helper, ORIGIN.offset(
                            CHAMBER_SIZE / 2, CHAMBER_SIZE / 2, CHAMBER_SIZE / 2));
                })
                .thenIdle(160)
                .thenExecute(() -> vanillaResidue[0] = countTntBlocks(helper, ORIGIN))
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildChamber(helper, ORIGIN);
                    ExplosionParallelConfig.setEnabled(true);
                    spawnDetonator(helper, DETONATOR);
                    detonateChamber(helper, ORIGIN.offset(
                            CHAMBER_SIZE / 2, CHAMBER_SIZE / 2, CHAMBER_SIZE / 2));
                })
                .thenIdle(160)
                .thenExecute(() -> {
                    parallelResidue[0] = countTntBlocks(helper, ORIGIN);
                    helper.assertTrue(
                            vanillaResidue[0] == parallelResidue[0],
                            "TNT residue differs: vanilla=" + vanillaResidue[0]
                                    + " parallel=" + parallelResidue[0]);
                    helper.assertTrue(countTntEntities(helper) == 0,
                            "TNT entities remained after explosion");
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(originalEnabled);
                    helper.succeed();
                });
    }

    @GameTest(maxTicks = 200, padding = 40)
    public void chamberIsCleanAndExplodes(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildChamber(helper, ORIGIN);
                })
                .thenExecute(() -> {
                    helper.assertTrue(helper.getBlockState(
                            ORIGIN.offset(CHAMBER_SIZE / 2, CHAMBER_SIZE / 2, CHAMBER_SIZE / 2))
                            .is(Blocks.TNT),
                            "chamber center must be TNT");
                    helper.assertTrue(countTntEntities(helper) == 0,
                            "entities must be cleared before detonation");
                })
                .thenExecute(() -> {
                    spawnDetonator(helper, DETONATOR);
                    detonateChamber(helper, ORIGIN.offset(
                            CHAMBER_SIZE / 2, CHAMBER_SIZE / 2, CHAMBER_SIZE / 2));
                })
                .thenIdle(160)
                .thenExecute(() -> {
                    helper.assertTrue(countTntEntities(helper) == 0,
                            "TNT entities remained after explosion");
                })
                .thenExecute(helper::succeed);
    }
}
