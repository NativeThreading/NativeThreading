package com.github.uright008.ep;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Entity-context exposure parity gametests.
 *
 * <p>Scaffolding and powder snow change their collision shape based on the
 * querying entity (isAbove/isDescending, fallDistance, boots). The parallel
 * pipeline must produce the same entity damage as vanilla when these blocks
 * are in the blast box. Each test detonates a TNT behind a scaffold/powder-snow
 * wall, then asserts the entity's health matches between vanilla (parallel
 * disabled) and parallel runs.</p>
 *
 * <p>Powder snow is routed per entity: entities that cannot observe a
 * powder-snow shape (fall distance <= 2.5, not a falling block, not walking on
 * powder snow) use the flat-view exposure, while powder-sensitive entities
 * (e.g. a zombie falling with fallDistance > 2.5) must keep the live
 * per-entity sampling. {@link #powderSnowSensitiveEntityParity} covers both
 * sides in one blast: a falling zombie only matches vanilla if the live shape is
 * sampled, and the item entity's knockback only matches if the flat path is
 * bit-identical.</p>
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
        // getAllEntities() is a live view over the entity lookup map. Discarding
        // while iterating mutates that map (NPE/flaky once the level holds
        // entities from another concurrently running gametest). Snapshot first.
        java.util.List<Entity> entities = new java.util.ArrayList<>();
        level.getAllEntities().forEach(entities::add);
        entities.forEach(Entity::discard);
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

    // ── Powder-snow per-entity routing (tested separately from the fallback) ──

    /** Observable outcome of one powder-snow blast run. */
    private record PowderOutcome(float zombieHealth, Vec3 itemDelta) {}

    /**
     * Builds a powder-snow blast: TNT at the west end, a powder-snow wall in the
     * middle, one powder-sensitive entity (zombie with fallDistance > 2.5: live
     * shape) and one powder-insensitive entity (item: flat shape) on the east side.
     * The wall lies between both victims and the blast center, so a flat exposure
     * for the falling zombie would visibly change its damage.
     */
    private static PowderOutcome runPowderSensitiveOnce(GameTestHelper helper, boolean parallel) {
        clearEntities(helper);
        buildPowderSnowRig(helper, ORIGIN);
        Zombie zombie = spawnZombie(helper, ORIGIN.offset(6, 1, 0));
        // > 2.5 makes PowderSnowBlock return FALLING_COLLISION_SHAPE for this
        // entity; NT must therefore use the live per-entity exposure for it.
        zombie.fallDistance = 4.0;
        ItemEntity item = spawnItem(helper, ORIGIN.offset(6, 1, 2));
        ExplosionParallelConfig.setEnabled(parallel);
        detonate(helper, ORIGIN.offset(0, 1, 0));
        PowderOutcome outcome = new PowderOutcome(zombie.getHealth(), item.getDeltaMovement());
        zombie.discard();
        item.discard();
        return outcome;
    }

    /**
     * Mixed powder-snow parity: the falling zombie exercises the powder-sensitive
     * live path, the item entity the powder-insensitive flat path. Both must match
     * vanilla bit-for-bit (identical health / knockback vector).
     */
    @GameTest(maxTicks = 200, padding = 40)
    public void powderSnowSensitiveEntityParity(GameTestHelper helper) {
        boolean original = ExplosionParallelConfig.isEnabled();
        helper.startSequence()
                .thenExecute(() -> {
                    PowderOutcome vanilla = runPowderSensitiveOnce(helper, false);
                    PowderOutcome parallel = runPowderSensitiveOnce(helper, true);
                    if (Float.compare(vanilla.zombieHealth(), parallel.zombieHealth()) != 0) {
                        helper.fail("powder-snow sensitive zombie health differs: vanilla="
                                + vanilla.zombieHealth() + " parallel=" + parallel.zombieHealth());
                    }
                    if (!vanilla.itemDelta().equals(parallel.itemDelta())) {
                        helper.fail("powder-snow item knockback differs: vanilla="
                                + vanilla.itemDelta() + " parallel=" + parallel.itemDelta());
                    }
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(original);
                    helper.succeed();
                });
    }

    // ── Liquid (Nether lava) parity ─────────────────────────────────────────

    /** Observable outcome of one liquid-blast run. */
    private record LiquidOutcome(float zombieHealth, Vec3 itemDelta) {}

    /**
     * Builds a lava-wall rig at origin: TNT at the west end, a source-lava wall in
     * the middle. Source lava survives the blast (fluid resistance), so both runs
     * see the same wall even though the TNT block itself is destroyed.
     */
    private static void buildLiquidRig(GameTestHelper helper, BlockPos origin) {
        helper.setBlock(origin.offset(0, 1, 0), Blocks.TNT.defaultBlockState());
        for (int y = 0; y <= 3; y++) {
            helper.setBlock(origin.offset(3, y, 0), Blocks.LAVA.defaultBlockState());
        }
    }

    /** Spawns a non-living victim (item entity) with a deterministic zero velocity. */
    private static ItemEntity spawnItem(GameTestHelper helper, BlockPos pos) {
        BlockPos abs = helper.absolutePos(pos);
        ItemEntity item = new ItemEntity(helper.getLevel(),
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                new ItemStack(Items.STONE));
        item.setDeltaMovement(Vec3.ZERO);
        helper.getLevel().addFreshEntity(item);
        return item;
    }

    private static LiquidOutcome runLiquidOnce(GameTestHelper helper, boolean parallel) {
        clearEntities(helper);
        buildLiquidRig(helper, ORIGIN);
        // Living victim: must keep the live per-entity exposure even in a lava blast.
        Zombie zombie = spawnZombie(helper, ORIGIN.offset(6, 1, 0));
        // Non-living victim: liquid collision shapes are context-free for it, so the
        // parallel pipeline may use the flat-view exposure. Its knockback
        // (1-dist)*exposure*kbMult is the exact-value observable.
        ItemEntity item = spawnItem(helper, ORIGIN.offset(6, 1, 2));
        ExplosionParallelConfig.setEnabled(parallel);
        detonate(helper, ORIGIN.offset(0, 1, 0));
        LiquidOutcome outcome = new LiquidOutcome(zombie.getHealth(), item.getDeltaMovement());
        zombie.discard();
        item.discard();
        return outcome;
    }

    /**
     * Mixed-victim liquid parity. The zombie covers the living entity-context path;
     * the item entity covers the non-living flat-view path that the per-entity
     * routing introduces for liquid blasts. Both must match vanilla exactly
     * (identical health / identical knockback vector).
     */
    @GameTest(maxTicks = 200, padding = 40)
    public void liquidContextMixedVictimParity(GameTestHelper helper) {
        boolean original = ExplosionParallelConfig.isEnabled();
        helper.startSequence()
                .thenExecute(() -> {
                    LiquidOutcome vanilla = runLiquidOnce(helper, false);
                    LiquidOutcome parallel = runLiquidOnce(helper, true);
                    if (Float.compare(vanilla.zombieHealth(), parallel.zombieHealth()) != 0) {
                        helper.fail("liquid blast zombie health differs: vanilla="
                                + vanilla.zombieHealth() + " parallel=" + parallel.zombieHealth());
                    }
                    if (!vanilla.itemDelta().equals(parallel.itemDelta())) {
                        helper.fail("liquid blast item knockback differs: vanilla="
                                + vanilla.itemDelta() + " parallel=" + parallel.itemDelta());
                    }
                })
                .thenExecute(() -> {
                    ExplosionParallelConfig.setEnabled(original);
                    helper.succeed();
                });
    }
}
