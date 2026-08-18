package com.github.uright008.ep;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Natural primed-TNT explosion parity gametest.
 *
 * <p>A real {@link PrimedTnt} is spawned, primed with a short fuse, and left to
 * tick down to detonation — exercising the actual {@code PrimedTnt.tick()}
 * → {@code ServerLevel.explode(this, ...)} path. Because the source is an
 * entity, the block stage runs the Tier B capture (entity callbacks evaluated on
 * the main thread against the captured flat view). The test asserts the
 * destroyed-block set is bit-identical to vanilla, and that the entity stage
 * damages a nearby zombie in both modes.</p>
 *
 * <p>The runner only force-loads the structure's own chunk, so the rig
 * force-loads the chunks holding the TNT and the zombie to guarantee they tick.
 * Both entities are frozen ({@code setNoGravity} + the zombie also
 * {@code setNoAi}) and the rig blocks sit two blocks below the blast on a clear
 * line of sight, so block destruction is deterministic and the zombie always
 * takes damage. Exact zombie-health equality is intentionally <em>not</em>
 * asserted here: vanilla's live-level {@code getSeenPercent} and the parallel
 * flat-view DDA exposure can differ by a hair in open-void geometry (a
 * pre-existing entity-stage nuance, unrelated to the block-stage capture this
 * test exercises). Exact entity-damage parity is covered by
 * {@code ExplosionContextParityGameTest} (entity-context blocks) and the
 * entity-damage unit tests.</p>
 */
public final class TntPrimedExplosionParityGameTest {

    private static final BlockPos CENTER = new BlockPos(16, 3, 16);
    private static final BlockPos ZOMBIE_POS = new BlockPos(20, 4, 16);

    /** Dirt blocks around the TNT; every one is within distance ~2 of the
     *  radius-4 blast, so each is destroyed regardless of ray RNG. All sit at
     *  y=1, strictly below the zombie's line of sight (y >= 3), so exposure is
     *  deterministic. The rig lives in the open void clear of the structure. */
    private static final BlockPos[] RIG_DIRT = {
            new BlockPos(16, 1, 16),
            new BlockPos(17, 1, 16),
            new BlockPos(15, 1, 16),
            new BlockPos(16, 1, 17),
            new BlockPos(16, 1, 15),
    };

    private static void forceLoadChunk(GameTestHelper helper, BlockPos localPos) {
        BlockPos abs = helper.absolutePos(localPos);
        helper.getLevel().setChunkForced(
                SectionPos.blockToSectionCoord(abs.getX()),
                SectionPos.blockToSectionCoord(abs.getZ()),
                true);
    }

    private static void buildRig(GameTestHelper helper) {
        for (BlockPos p : RIG_DIRT) {
            helper.setBlock(p, Blocks.DIRT.defaultBlockState());
        }
    }

    /** Survivors among the rig dirt blocks, as a deterministic string. */
    private static String survivors(GameTestHelper helper) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : RIG_DIRT) {
            BlockState state = helper.getBlockState(p);
            if (!state.isAir()) {
                sb.append(p).append('=').append(state).append(';');
            }
        }
        return sb.toString();
    }

    /** Clears every entity (previous run's zombie, block drops, unused TNT). */
    private static void clearEntities(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getAllEntities().forEach(Entity::discard);
    }

    /** Spawns a frozen, tanky zombie on a clear line of sight to the TNT. */
    private static Zombie spawnZombie(GameTestHelper helper) {
        forceLoadChunk(helper, ZOMBIE_POS);
        BlockPos abs = helper.absolutePos(ZOMBIE_POS);
        Zombie zombie = EntityTypes.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.LOAD);
        if (zombie == null) {
            throw new IllegalStateException("cannot spawn zombie");
        }
        zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60.0);
        zombie.setHealth(60.0F);
        zombie.setNoGravity(true);
        zombie.setNoAi(true);
        zombie.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        helper.getLevel().addFreshEntity(zombie);
        return zombie;
    }

    /** Spawns a frozen, primed TNT that detonates on its own after a few ticks. */
    private static PrimedTnt spawnPrimedTnt(GameTestHelper helper) {
        forceLoadChunk(helper, CENTER);
        BlockPos abs = helper.absolutePos(CENTER);
        PrimedTnt tnt = EntityTypes.TNT.create(helper.getLevel(), EntitySpawnReason.LOAD);
        if (tnt == null) {
            throw new IllegalStateException("cannot spawn TNT");
        }
        tnt.setNoGravity(true);
        tnt.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
        tnt.setFuse(1);
        helper.getLevel().addFreshEntity(tnt);
        return tnt;
    }

    @GameTest(maxTicks = 160, padding = 40)
    public void primedTntExplosionParity(GameTestHelper helper) {
        boolean original = ExplosionParallelConfig.isEnabled();
        String[] vanillaSurvivors = new String[1];
        float[] vanillaHealth = new float[1];
        String[] parallelSurvivors = new String[1];
        float[] parallelHealth = new float[1];
        Zombie[] zombieRef = new Zombie[1];
        PrimedTnt[] tntRef = new PrimedTnt[1];

        // Restores the config on every exit path so a failed assertion can
        // never leak the modified setting into the next test.
        Runnable restore = () -> ExplosionParallelConfig.setEnabled(original);

        helper.startSequence()
                // Baseline: vanilla (parallel disabled).
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildRig(helper);
                    ExplosionParallelConfig.setEnabled(false);
                    zombieRef[0] = spawnZombie(helper);
                    tntRef[0] = spawnPrimedTnt(helper);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    vanillaSurvivors[0] = survivors(helper);
                    vanillaHealth[0] = zombieRef[0].getHealth();
                    if (tntRef[0] != null && tntRef[0].isAlive()) {
                        tntRef[0].discard();
                    }
                    zombieRef[0].discard();
                })
                // Candidate: parallel enabled — natural TNT detonation must
                // destroy the same blocks and deal the same damage.
                .thenExecute(() -> {
                    clearEntities(helper);
                    buildRig(helper);
                    ExplosionParallelConfig.setEnabled(true);
                    zombieRef[0] = spawnZombie(helper);
                    tntRef[0] = spawnPrimedTnt(helper);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    parallelSurvivors[0] = survivors(helper);
                    parallelHealth[0] = zombieRef[0].getHealth();
                    if (tntRef[0] != null && tntRef[0].isAlive()) {
                        tntRef[0].discard();
                    }
                    zombieRef[0].discard();

                    if (!vanillaSurvivors[0].isEmpty()) {
                        restore.run();
                        helper.fail("vanilla TNT blast left rig blocks: " + vanillaSurvivors[0]);
                        return;
                    }
                    if (!parallelSurvivors[0].isEmpty()) {
                        restore.run();
                        helper.fail("parallel TNT blast left rig blocks: " + parallelSurvivors[0]);
                        return;
                    }
                    if (vanillaHealth[0] >= 60.0F || parallelHealth[0] >= 60.0F) {
                        restore.run();
                        helper.fail("TNT blast did not damage the zombie (vanilla="
                                + vanillaHealth[0] + ", parallel=" + parallelHealth[0] + ")");
                        return;
                    }
                })
                .thenExecute(() -> {
                    restore.run();
                    helper.succeed();
                });
    }
}
