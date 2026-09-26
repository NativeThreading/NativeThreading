package com.github.uright008.benchmark;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;

import java.util.IdentityHashMap;

/** Main-thread world fixture. Only the destination driver is scripted; navigation is vanilla. */
public final class BlockUpdateScene {
    // Instrumentation routing, not a shared world cache: main-thread only, world
    // and mob identity checked, no worker access/join, cleared on server stop.
    private static BlockUpdateScene current;
    final ServerLevel level;
    final Cow[] mobs;
    final long seed;
    final int interval;
    final String change;
    private final IdentityHashMap<Mob, Boolean> members = new IdentityHashMap<>();
    private final boolean[] east;
    private final boolean[] hasDestination;
    private final double[] destinationX;
    private final double[] lanes;
    boolean solid;
    boolean changingBlocks;
    long lastPhaseEntityTicks = -1;
    BlockUpdateRunStage run;

    BlockUpdateScene(ServerLevel level, int entities, long seed, int interval, String change) {
        BlockUpdateScenarioHelper.validate(entities, interval, change);
        this.level = level;
        this.seed = seed;
        this.interval = interval;
        this.change = change;
        // Cow required path length 64 + normal region offset 8. Include every
        // possible region of this arena; generation stays outside measurement.
        for (int x = -5; x <= 8; x++) {
            for (int z = -5; z <= 6; z++) {
                level.getChunk(x, z);
                level.setChunkForced(x, z, true);
            }
        }
        for (int x = 0; x <= 64; x++) {
            for (int z = 0; z <= 32; z++) {
                for (int y = 79; y <= 85; y++) {
                    boolean wall = x == 0 || x == 64 || z == 0 || z == 32 || y == 79 || y == 85;
                    level.setBlock(new BlockPos(x, y, z), (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        if (change.equals("same-shape")) {
            for (var pos : BlockUpdateScenarioHelper.blocks()) {
                level.setBlock(BlockPos.containing(pos.x(), pos.y(), pos.z()), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        mobs = new Cow[entities];
        east = new boolean[entities];
        hasDestination = new boolean[entities];
        destinationX = new double[entities];
        lanes = new double[entities];
        var positions = BlockUpdateScenarioHelper.spawnPositions(entities, seed);
        for (int i = 0; i < entities; i++) {
            var pos = positions[i];
            Cow cow = new Cow(EntityTypes.COW, level);
            cow.removeFreeWill();
            cow.setPos(pos.x(), pos.y(), pos.z());
            cow.setOnGround(true);
            cow.setPersistenceRequired();
            cow.getNavigation().setRequiredPathLength(64);
            if (level.getBlockCollisions(cow, cow.getBoundingBox()).iterator().hasNext()
                    || !level.addFreshEntity(cow)) throw new IllegalStateException("Cow spawn failed");
            east[i] = pos.x() < 32;
            lanes[i] = pos.z();
            mobs[i] = cow;
            members.put(cow, true);
        }
        current = this;
        level.getServer().tickRateManager().setFrozen(true);
    }

    void startTick() {
        if (run == null) return;
        var rate = level.getServer().tickRateManager();
        if (rate.isFrozen()) rate.setFrozen(false);
        if (!rate.runsNormally()) return;
        run.startTick();
        for (int i = 0; i < mobs.length; i++) {
            var navigation = mobs[i].getNavigation();
            if (navigation.isDone()) {
                if (hasDestination[i] && mobs[i].distanceToSqr(destinationX[i], 80, lanes[i]) < 4) run.completedLegs++;
                destinationX[i] = east[i] ? 61.5 : 2.5;
                hasDestination[i] = true;
                navigation.moveTo(destinationX[i], 80, lanes[i], 0, 1.0);
                east[i] = !east[i];
            }
        }
        run.activeBefore = navigating();
        if (run.isEvent()) {
            run.eventActive.add((double) run.activeBefore);
            if (!change.equals("none")) {
                long start = System.nanoTime();
                changingBlocks = true;
                try {
                    solid = !solid;
                    var block = change.equals("same-shape") ? (solid ? Blocks.ANDESITE : Blocks.STONE)
                        : (solid ? Blocks.STONE : Blocks.AIR);
                    for (var pos : BlockUpdateScenarioHelper.blocks()) {
                        // These blocks are above cow bodies, not placed into entities.
                        // UPDATE_ALL goes through the real collision-shape notification path.
                        if (!level.setBlock(BlockPos.containing(pos.x(), pos.y(), pos.z()), block.defaultBlockState(), Block.UPDATE_ALL)) {
                            throw new IllegalStateException("Scheduled block change was a no-op");
                        }
                        run.blockChanges++;
                    }
                } finally {
                    changingBlocks = false;
                    run.blockUpdateMs = (System.nanoTime() - start) / 1e6;
                }
            }
        }
    }

    void endTick() {
        if (run != null && run.inTick() && run.endTick()) {
            run = null;
            level.getServer().tickRateManager().setFrozen(true);
        }
    }

    int navigating() {
        int count = 0;
        for (Cow mob : mobs) if (mob.getNavigation().isInProgress()) count++;
        return count;
    }

    long entityTicks() {
        long count = 0;
        for (Cow mob : mobs) count += mob.tickCount;
        return count;
    }

    private static BlockUpdateScene forMob(Mob mob) {
        BlockUpdateScene scene = current;
        if (scene == null || mob.level() != scene.level || !scene.members.containsKey(mob)) return null;
        if (!scene.level.getServer().isSameThread()) throw new IllegalStateException("Benchmark hooks require the server thread");
        return scene.run != null && scene.run.inTick() ? scene : null;
    }

    public static long searchStart(Mob mob) {
        return forMob(mob) == null ? 0 : System.nanoTime();
    }

    public static void searchEnd(Mob mob, Path path, long started) {
        if (started == 0) return;
        long elapsed = System.nanoTime() - started;
        BlockUpdateScene scene = forMob(mob);
        if (scene == null) throw new IllegalStateException("Search crossed phase boundary");
        scene.run.searchFinished(mob, path, elapsed);
    }

    public static void checked(Mob mob, boolean positive) {
        BlockUpdateScene scene = forMob(mob);
        if (scene == null) return;
        scene.run.shouldChecks++;
        if (positive) scene.run.shouldPositive++;
    }

    public static void recomputeStart(Mob mob) {
        BlockUpdateScene scene = forMob(mob);
        if (scene == null) return;
        scene.run.recomputeDepth++;
        if (scene.changingBlocks) {
            scene.run.updateRequests++;
            scene.run.pending.putIfAbsent(mob, scene.run.tick);
        } else scene.run.delayedAttempts++;
    }

    public static void recomputeEnd(Mob mob) {
        BlockUpdateScene scene = forMob(mob);
        if (scene != null) scene.run.recomputeDepth--;
    }

    public static void clear() { current = null; }
}
