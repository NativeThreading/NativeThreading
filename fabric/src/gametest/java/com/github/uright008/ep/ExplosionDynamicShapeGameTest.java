package com.github.uright008.ep;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Integration guard for the dynamic-collision-shape capture fix.
 *
 * <p>Vanilla's {@code getCollisionShape(level, pos)} is only a pure function of
 * the block state when {@code !hasDynamicShape()}. The crash this test pins:
 * the capture-phase flatten called {@code getCollisionShape(null, null)} for
 * every non-air cell, and a moving piston (or shulker box / bamboo / pointed
 * dripstone / sulfur spike) inside the blast AABB then NPE'd on the null
 * level/pos on the server thread, aborting the explosion.</p>
 *
 * <p>The rig places one of every vanilla dynamic shape whose resolution needs
 * the real level and/or position, all within a TNT blast box but off the
 * line to any entity. A parallel explosion must complete exactly like the
 * parallel-disabled one; before the fix the parallel run throws.</p>
 */
public final class ExplosionDynamicShapeGameTest {

    private static final BlockPos ORIGIN = BlockPos.ZERO;

    private static final BlockPos TNT = ORIGIN.offset(0, 1, 0);
    private static final BlockPos MOVING_PISTON = ORIGIN.offset(3, 1, -2);
    private static final BlockPos SHULKER_BOX = ORIGIN.offset(3, 1, 2);
    private static final BlockPos BAMBOO = ORIGIN.offset(3, 2, -1);
    private static final BlockPos POINTED_DRIPSTONE = ORIGIN.offset(3, 3, 1);
    private static final BlockPos SULFUR_SPIKE = ORIGIN.offset(3, 0, 1);

    private static final BlockPos[] DYNAMIC_RIG = {
            MOVING_PISTON, SHULKER_BOX, BAMBOO, POINTED_DRIPSTONE, SULFUR_SPIKE};

    /** Builds the blast rig: TNT plus one of every vanilla dynamic-shape block
     *  family that resolves through the live level/position. */
    private static void buildRig(GameTestHelper helper) {
        helper.setBlock(TNT, Blocks.TNT.defaultBlockState());

        BlockState movingPiston = Blocks.MOVING_PISTON.defaultBlockState();
        helper.setBlock(MOVING_PISTON, movingPiston);
        BlockPos pistonAbs = helper.absolutePos(MOVING_PISTON);
        // setBlockEntity is void in 26.3; assertRigPresent verifies it attached.
        helper.getLevel().setBlockEntity(
                new PistonMovingBlockEntity(pistonAbs, movingPiston,
                        Blocks.STONE.defaultBlockState(), Direction.UP, true, false));

        helper.setBlock(SHULKER_BOX, Blocks.SHULKER_BOX.defaultBlockState());
        helper.setBlock(BAMBOO, Blocks.BAMBOO.defaultBlockState());
        helper.setBlock(POINTED_DRIPSTONE, Blocks.POINTED_DRIPSTONE.defaultBlockState());
        helper.setBlock(SULFUR_SPIKE, Blocks.SULFUR_SPIKE.defaultBlockState());
    }

    /** Fails if the rig is missing a block, so a silently failed placement can
     *  never make the crash reproduction vacuous. */
    private static void assertRigPresent(GameTestHelper helper) {
        if (helper.getBlockState(TNT).isAir()) helper.fail("TNT block missing from rig");
        for (BlockPos pos : DYNAMIC_RIG) {
            if (helper.getBlockState(pos).isAir()) {
                helper.fail("dynamic-shape rig block missing at " + pos);
            }
        }
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(MOVING_PISTON))
                instanceof PistonMovingBlockEntity)) {
            helper.fail("moving-piston block entity missing from rig");
        }
    }

    private static void clearEntities(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // getAllEntities() is a live view over the entity lookup map. Discarding
        // while iterating mutates that map (NPE/flaky once the level holds
        // entities from another concurrently running gametest). Snapshot first.
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        level.getAllEntities().forEach(entities::add);
        entities.forEach(Entity::discard);
    }

    private static void detonate(GameTestHelper helper) {
        BlockPos abs = helper.absolutePos(TNT);
        helper.getLevel().explode(null,
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                4.0F, false, Level.ExplosionInteraction.TNT);
    }

    @GameTest(maxTicks = 20, padding = 40)
    public void parallelExplosionWithDynamicShapesDoesNotCrash(GameTestHelper helper) {
        boolean original = ExplosionParallelConfig.isEnabled();
        helper.startSequence()
                .thenExecute(() -> {
                    // Parallel-disabled reference run: must complete.
                    clearEntities(helper);
                    buildRig(helper);
                    assertRigPresent(helper);
                    ExplosionParallelConfig.setEnabled(false);
                    detonate(helper);

                    // Same rig with the parallel pipeline enabled: the capture
                    // phase must resolve the dynamic shapes live instead of
                    // NPE'ing on a null level/pos.
                    clearEntities(helper);
                    buildRig(helper);
                    assertRigPresent(helper);
                    ExplosionParallelConfig.setEnabled(true);
                    detonate(helper);
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(original);
                    helper.succeed();
                });
    }
}
