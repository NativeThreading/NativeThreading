package com.github.uright008.ep;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Entity-context exposure parity gametests.
 *
 * <p>Scaffolding and powder snow change their collision shape based on the
 * querying entity (isAbove/isDescending, fallDistance, boots). The parallel
 * pipeline must produce the same entity damage as vanilla when these blocks
 * are in the blast box. Each test detonates a TNT behind a scaffold/powder-snow
 * wall, then asserts the entity's health matches between vanilla (parallel
 * disabled) and parallel runs.</p>
 */
public final class ExplosionContextParityGameTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    /**
     * Builds a blast rig at origin: TNT at the west end, a scaffold wall in the
     * middle, a zombie east of the wall. The zombie's exposure is reduced by the
     * wall, and the scaffold's entity-dependent shape decides how much.
     */
    private static void buildScaffoldRig(GameTestHelper helper, BlockPos origin) {
        helper.setBlock(origin.offset(0, 1, 0), Blocks.TNT.defaultBlockState());
        for (int y = 0; y <= 3; y++) {
            helper.setBlock(origin.offset(3, y, 0),
                    Blocks.SCAFFOLDING.defaultBlockState()
                            .setValue(ScaffoldingBlock.BOTTOM, y == 0));
        }
    }

    /** Spawns a zombie at the given position and returns it. */
    private static Zombie spawnZombie(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        Zombie zombie = EntityTypes.ZOMBIE.create(helper.getLevel(),
                net.minecraft.world.entity.EntitySpawnReason.LOAD);
        if (zombie == null) throw new IllegalStateException("cannot spawn zombie");
        zombie.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        helper.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    /**
     * Detonates TNT at the given position. The TNT block is primed by the
     * explosion itself (TNT interaction), mirroring the chamber test's direct
     * explode call for determinism.
     */
    private static void detonate(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        helper.getLevel().explode(null,
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                4.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
    }

    /** Removes every entity in the level. */
    private static void clearEntities(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getAllEntities().forEach(Entity::discard);
    }

    private static float runOnce(GameTestHelper helper, boolean parallel) {
        clearEntities(helper);
        buildScaffoldRig(helper, ORIGIN);
        Zombie zombie = spawnZombie(helper, ORIGIN.offset(6, 1, 0));
        ExplosionParallelConfig.setEnabled(parallel);
        detonate(helper, ORIGIN.offset(0, 1, 0));
        float health = zombie.getHealth();
        zombie.discard();
        return health;
    }

    @GameTest(maxTicks = 200, padding = 40)
    public void scaffoldExposureParity(GameTestHelper helper) {
        boolean original = ExplosionParallelConfig.isEnabled();
        helper.startSequence()
                .thenExecute(() -> {
                    float vanillaHealth = runOnce(helper, false);
                    float parallelHealth = runOnce(helper, true);
                    if (Float.compare(vanillaHealth, parallelHealth) != 0) {
                        helper.fail("scaffold exposure differs: vanilla health="
                                + vanillaHealth + " parallel health=" + parallelHealth);
                    }
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(original);
                    helper.succeed();
                });
    }

    private static void buildPowderSnowRig(GameTestHelper helper, BlockPos origin) {
        helper.setBlock(origin.offset(0, 1, 0), Blocks.TNT.defaultBlockState());
        for (int y = 0; y <= 3; y++) {
            helper.setBlock(origin.offset(3, y, 0), Blocks.POWDER_SNOW.defaultBlockState());
        }
    }

    private static float runPowderOnce(GameTestHelper helper, boolean parallel) {
        clearEntities(helper);
        buildPowderSnowRig(helper, ORIGIN);
        Zombie zombie = spawnZombie(helper, ORIGIN.offset(6, 1, 0));
        ExplosionParallelConfig.setEnabled(parallel);
        detonate(helper, ORIGIN.offset(0, 1, 0));
        float health = zombie.getHealth();
        zombie.discard();
        return health;
    }

    @GameTest(maxTicks = 200, padding = 40)
    public void powderSnowExposureParity(GameTestHelper helper) {
        boolean original = ExplosionParallelConfig.isEnabled();
        helper.startSequence()
                .thenExecute(() -> {
                    float vanillaHealth = runPowderOnce(helper, false);
                    float parallelHealth = runPowderOnce(helper, true);
                    if (Float.compare(vanillaHealth, parallelHealth) != 0) {
                        helper.fail("powder-snow exposure differs: vanilla health="
                                + vanillaHealth + " parallel health=" + parallelHealth);
                    }
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(original);
                    helper.succeed();
                });
    }
}
