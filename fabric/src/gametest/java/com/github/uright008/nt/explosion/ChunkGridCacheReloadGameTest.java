package com.github.uright008.nt.explosion;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Regression guard for cross-blast {@code ChunkGrid} reuse across a chunk unload/reload.
 *
 * <p>{@link ExplosionChunkGridCache} reuses one grid across same-rectangle explosions. The grid
 * references the {@code LevelChunk}/{@code LevelChunkSection} objects loaded when it was built;
 * an unload/reload replaces those objects, so a hit that does not verify chunk identity is a stale
 * read. The failure is observable even though the explode apply stage re-reads the live block at
 * every position it destroys: vanilla's position list includes air cells, while the stale view
 * changes the power bookkeeping. A stale "air" cell where the live world now has obsidian lets the
 * ray travel past the shield, so blocks behind it are listed and destroyed although vanilla never
 * reaches them.</p>
 *
 * <p>Rig (radius 8, bound = 17 blocks; cleared void region):</p>
 * <pre>
 *   west chunk | east chunk (unloaded and reloaded by the test)
 *   ... center | S B ...
 * </pre>
 * <p>{@code center} is the last block column of the west chunk and the blast center is its block
 * center. {@code S} is an obsidian shield on the first column of the east chunk and {@code B} dirt
 * directly east of it, in the same y/z block row. From the center block row every ray that can
 * reach {@code B} crosses {@code S}'s cell first, so vanilla stops at the obsidian resistance and
 * leaves {@code B} intact. A stale grid sees the pre-unload air at {@code S}, keeps the ray alive,
 * lists {@code B}, and the live apply destroys it (and the obsidian too, which a radius-8 vanilla
 * explosion can never destroy).</p>
 *
 * <p>Phases: C = vanilla control (parallel disabled) proves the rig geometry; P = cache miss then
 * hit with the chunks still loaded proves a hit sees live edits; A = release this test's forced
 * tickets, let the rig chunks unload, cache a grid (A1) on the reloaded chunks, let them unload
 * again, reload them, rebuild the rig, then blast (A2). A correct implementation keeps vanilla
 * parity: it either reuses a grid only when every chunk object is unchanged, or rebuilds after
 * noticing the replacement.</p>
 */
public final class ChunkGridCacheReloadGameTest {

    private static final float RADIUS = 8.0F;

    /** Rig center in structure-relative coordinates, high above the cleared void floor. */
    private static final BlockPos RIG_LOCAL = new BlockPos(24, 30, 24);

    /** Ticks for the transient UNKNOWN force-load tickets to expire and the chunks to unload. */
    private static final int UNLOAD_WAIT_TICKS = 100;

    // Own batch: GameTestBatchFactory groups batches by (test environment, dimension) and batches
    // run sequentially, so no other test's explosion can replace or clear the static cache between
    // this test's cache-miss and cache-hit blasts.
    @GameTest(maxTicks = 900, padding = 40, dimension = "minecraft:the_end")
    public void cacheHitAfterChunkReloadKeepsVanillaParity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        // Put the blast center in the last column of a chunk so the shield/dirt rig fits in the
        // next chunk, which is the chunk that unloads and reloads.
        BlockPos rigAbs = helper.absolutePos(RIG_LOCAL);
        int eastChunkX = SectionPos.blockToSectionCoord(rigAbs.getX()) + 1;
        int eastChunkStartX = eastChunkX << 4;
        int centerBlockX = eastChunkStartX - 1;
        int centerBlockY = rigAbs.getY();
        int centerBlockZ = rigAbs.getZ();
        BlockPos shieldAbs = new BlockPos(eastChunkStartX, centerBlockY, centerBlockZ);
        BlockPos behindAbs = new BlockPos(eastChunkStartX + 1, centerBlockY, centerBlockZ);
        BlockPos behindLocal = helper.relativePos(behindAbs);

        int westChunkX = SectionPos.blockToSectionCoord(centerBlockX);
        int westChunkZ = SectionPos.blockToSectionCoord(centerBlockZ);
        int eastChunkZ = SectionPos.blockToSectionCoord(shieldAbs.getZ());
        BlockPos structureOrigin = helper.absolutePos(BlockPos.ZERO);
        int structureChunkX = SectionPos.blockToSectionCoord(structureOrigin.getX());
        int structureChunkZ = SectionPos.blockToSectionCoord(structureOrigin.getZ());

        double explosionX = centerBlockX + 0.5;
        double explosionY = centerBlockY + 0.5;
        double explosionZ = centerBlockZ + 0.5;

        boolean originalEnabled = ExplosionConfig.isEnabled();
        String[] failure = {null};
        LevelChunk[] oldEastChunk = {null};

        Runnable detonate = () -> level.explode(null, explosionX, explosionY, explosionZ, RADIUS, false,
                Level.ExplosionInteraction.TNT);
        Runnable placeRig = () -> {
            level.setBlock(shieldAbs, Blocks.OBSIDIAN.defaultBlockState(), 3);
            level.setBlock(behindAbs, Blocks.DIRT.defaultBlockState(), 3);
        };
        Runnable clearRig = () -> {
            level.setBlock(shieldAbs, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(behindAbs, Blocks.AIR.defaultBlockState(), 3);
        };
        Runnable discardNearbyItems = () -> helper.despawnItem(behindLocal, 16);

        helper.startSequence()
                // ---------------------------------------------------------- C: vanilla control
                .thenExecute(() -> {
                    ExplosionConfig.setEnabled(false);
                    discardNearbyItems.run();
                    placeRig.run();
                    requireRigIntact(level, shieldAbs, behindAbs, failure, "C setup");
                })
                .thenExecute(detonate)
                .thenExecute(() -> requireRigIntact(level, shieldAbs, behindAbs, failure, "C: vanilla blast"))
                // ------------------------------------- P: cache hit with the chunk still loaded
                .thenExecute(() -> {
                    ExplosionChunkGridCache.clear();
                    ExplosionConfig.setEnabled(true);
                    discardNearbyItems.run();
                    clearRig.run();
                })
                .thenExecute(detonate) // P1: cache miss, nothing to destroy
                .thenExecute(() -> {
                    if (ExplosionChunkGridCache.misses() != 1 || ExplosionChunkGridCache.hits() != 0) {
                        fail(failure, "P1: expected one cache miss, got " + ExplosionChunkGridCache.statsLine());
                    }
                    placeRig.run();
                })
                .thenExecute(detonate) // P2: cache hit; the flat view must see the live shield
                .thenExecute(() -> {
                    if (ExplosionChunkGridCache.hits() != 1) {
                        fail(failure, "P2: expected a cache hit, got " + ExplosionChunkGridCache.statsLine());
                    }
                    requireRigIntact(level, shieldAbs, behindAbs, failure, "P2: cache-hit blast");
                })
                // ----------------------------------- A: cache reuse across chunk unload/reload
                .thenExecute(() -> {
                    ExplosionChunkGridCache.clear();
                    discardNearbyItems.run();
                    clearRig.run();
                    releaseForcedChunks(level, structureChunkX, structureChunkZ);
                })
                .thenIdle(UNLOAD_WAIT_TICKS)
                .thenExecute(() -> {
                    boolean westLoaded = level.getChunkSource().hasChunk(westChunkX, westChunkZ);
                    boolean eastLoaded = level.getChunkSource().hasChunk(eastChunkX, eastChunkZ);
                    if (westLoaded || eastLoaded) {
                        fail(failure, "A: rig chunks still loaded after releasing forced tickets (west="
                                + westLoaded + ", east=" + eastLoaded + ")");
                    }
                })
                .thenExecute(() -> {
                    if (failure[0] != null) {
                        return;
                    }
                    detonate.run(); // A1: miss; the grid is built on chunks that will unload again
                    oldEastChunk[0] = level.getChunk(eastChunkX, eastChunkZ);
                    if (ExplosionChunkGridCache.misses() != 1 || ExplosionChunkGridCache.hits() != 0) {
                        fail(failure, "A1: expected one cache miss, got " + ExplosionChunkGridCache.statsLine());
                    }
                })
                .thenIdle(UNLOAD_WAIT_TICKS)
                .thenExecute(() -> {
                    if (failure[0] != null) {
                        return;
                    }
                    if (level.getChunkSource().hasChunk(eastChunkX, eastChunkZ)) {
                        fail(failure, "A: east chunk did not unload after the caching blast");
                        return;
                    }
                    // Simulated player return: fresh LevelChunk objects, then rebuild the rig.
                    placeRig.run();
                    level.getChunk(westChunkX, westChunkZ);
                    if (level.getBlockState(shieldAbs).is(Blocks.AIR)
                            || level.getBlockState(behindAbs).is(Blocks.AIR)) {
                        fail(failure, "A: rig missing after reloading the chunks");
                        return;
                    }
                    LevelChunk newEastChunk = level.getChunk(eastChunkX, eastChunkZ);
                    if (newEastChunk == oldEastChunk[0]) {
                        fail(failure, "A: chunk object was not replaced by unload/reload; "
                                + "the stale-object scenario is not exercised");
                    }
                })
                .thenExecute(() -> {
                    if (failure[0] != null) {
                        return;
                    }
                    detonate.run(); // A2: must not reuse a grid built before the reload
                    // Any correct strategy reaches exactly one cache decision here: a validated
                    // hit, or a rebuild after detecting the replaced chunk object.
                    if (ExplosionChunkGridCache.hits() + ExplosionChunkGridCache.misses() != 2) {
                        fail(failure, "A2: expected exactly one cache decision, got "
                                + ExplosionChunkGridCache.statsLine());
                    }
                    if (!level.getBlockState(shieldAbs).is(Blocks.OBSIDIAN)) {
                        fail(failure, "A2: obsidian shield was destroyed at " + shieldAbs
                                + " (state=" + level.getBlockState(shieldAbs) + ")");
                    }
                    if (!level.getBlockState(behindAbs).is(Blocks.DIRT)) {
                        fail(failure, "A2: stale grid destroyed the dirt behind the obsidian shield at "
                                + behindAbs + " (state=" + level.getBlockState(behindAbs)
                                + "); vanilla parity keeps it (phase C) because every ray reaching it"
                                + " crosses the shield first");
                    }
                })
                // --------------------------------------------------------------------- teardown
                .thenExecute(() -> {
                    ExplosionConfig.setEnabled(originalEnabled);
                    level.setBlock(shieldAbs, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(behindAbs, Blocks.AIR.defaultBlockState(), 3);
                    discardNearbyItems.run();
                    if (failure[0] != null) {
                        throw helper.assertionException(Component.literal(failure[0]));
                    }
                    helper.succeed();
                });
    }

    /** Asserts the exact vanilla-parity expectations for the two-block rig. */
    private static void requireRigIntact(ServerLevel level, BlockPos shieldAbs, BlockPos behindAbs,
                                         String[] failure, String phase) {
        if (!level.getBlockState(shieldAbs).is(Blocks.OBSIDIAN)) {
            fail(failure, phase + ": obsidian shield is " + level.getBlockState(shieldAbs)
                    + " at " + shieldAbs);
        }
        if (!level.getBlockState(behindAbs).is(Blocks.DIRT)) {
            fail(failure, phase + ": dirt behind the shield is " + level.getBlockState(behindAbs)
                    + " at " + behindAbs + " (vanilla must not reach it through the obsidian)");
        }
    }

    private static void fail(String[] failure, String message) {
        if (failure[0] == null) {
            failure[0] = message;
        }
    }

    /**
     * Releases the forced tickets the game-test instance placed on the structure's own chunks, so
     * the surrounding chunks can unload like they would in a normal world. Only chunks within two
     * chunks of this test's structure origin are touched; other tests in the same level keep theirs.
     */
    private static void releaseForcedChunks(ServerLevel level, int structureChunkX, int structureChunkZ) {
        LongSet forced = level.getForceLoadedChunks();
        for (long key : forced.toLongArray()) {
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            if (Math.abs(x - structureChunkX) <= 2 && Math.abs(z - structureChunkZ) <= 2) {
                level.setChunkForced(x, z, false);
            }
        }
    }
}
