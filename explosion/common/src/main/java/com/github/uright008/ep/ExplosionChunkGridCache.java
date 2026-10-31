package com.github.uright008.ep;

import com.github.uright008.pc.ChunkGrid;
import com.github.uright008.pc.ChunkSafeAccessor;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicReference;

/** Static {@link ChunkGrid} reuse across blasts. A built grid plus the level
 *  it reads from and the section rectangle it covers; chained blasts in the
 *  same region (the TNT benchmark's forceloaded room) reuse one grid instead of
 *  rebuilding it per explosion — every blast constructs a fresh
 *  ServerExplosion, so an instance field would rebuild every time. The level
 *  is part of the key: a grid's chunks are bound to one ServerLevel, so a
 *  blast in a different dimension must never reuse it. Chunks are referenced,
 *  not copied, so block edits between blasts are read live; a blast whose
 *  coverage differs from the cached rectangle rebuilds. Chunk object identity is
 *  part of the implicit key: an unload/reload replaces the {@code LevelChunk}
 *  objects, so a hit also verifies every captured chunk is still current
 *  ({@link ChunkGrid#stillCurrent}) and otherwise rebuilds. */
public final class ExplosionChunkGridCache {

    private record CachedGrid(ServerLevel level, ChunkGrid grid,
                              int minSectionX, int minSectionZ, int sizeX, int sizeZ) {}

    private static final AtomicReference<CachedGrid> CACHE = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicLong HITS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong MISSES = new java.util.concurrent.atomic.AtomicLong();

    private ExplosionChunkGridCache() {}

    /** Hit = cached grid covers the blast, miss = rebuilt. */
    public static long hits() { return HITS.get(); }
    public static long misses() { return MISSES.get(); }
    public static double missRate() {
        long h = HITS.get(), m = MISSES.get();
        long total = h + m;
        return total == 0 ? 0.0 : (double) m / total;
    }
    public static String statsLine() {
        long h = HITS.get(), m = MISSES.get();
        long total = h + m;
        double missPct = total == 0 ? 0.0 : (double) m / total * 100.0;
        return String.format("ChunkGrid cache: hits=%d misses=%d total=%d miss=%.1f%%", h, m, total, missPct);
    }

    /**
     * Drops the cached grid and the {@link ServerLevel} it references. Called when the
     * owning server stops: the dedicated-server assumption "the level lives as long as the
     * process" does not hold for a client, whose integrated server creates and discards
     * levels per single-player session. Without this, the last session's level (and its
     * chunks) would stay reachable from this static until another explosion replaced it.
     */
    public static void clear() {
        CACHE.set(null);
        HITS.set(0);
        MISSES.set(0);
    }

    /** Returns a grid covering exactly the sections the flat view reads, reusing
     *  the cached one when the same level and rectangle are hit again. Only an
     *  identical rectangle may be reused: a larger grid would force-load extra
     *  chunks the explosion never reads. Reuse also requires every captured chunk
     *  to still be the level's current chunk object: a reload replaces them and
     *  the cached grid would then read pre-unload block states. */
    public static ChunkGrid forExplosion(ExplosionContext ctx) {
        ExplosionRayBounds.SectionRect rect = ExplosionRayBounds.sectionRect(ctx.center(), ctx.radius());
        int needMinX = rect.minX();
        int needMaxX = rect.maxX();
        int needMinZ = rect.minZ();
        int needMaxZ = rect.maxZ();
        int needSizeX = needMaxX - needMinX + 1;
        int needSizeZ = needMaxZ - needMinZ + 1;

        CachedGrid cached = CACHE.get();
        ChunkSafeAccessor accessor = (ChunkSafeAccessor) ctx.level().getChunkSource();
        if (cached != null && cached.level == ctx.level()
                && cached.minSectionX == needMinX && cached.minSectionZ == needMinZ
                && cached.sizeX == needSizeX && cached.sizeZ == needSizeZ
                && cached.grid.stillCurrent(accessor)) {
            HITS.incrementAndGet();
            // Log every 8192 hits (power of two) to avoid spam, like entity paths.
            long h = HITS.get();
            if ((h & (h - 1)) == 0) {
                org.slf4j.LoggerFactory.getLogger("native-threading:explosion:cache")
                        .info(statsLine());
            }
            return cached.grid;
        }
        MISSES.incrementAndGet();
        ChunkGrid grid = new ChunkGrid(ctx.level(), needMinX, needMaxX, needMinZ, needMaxZ);
        CACHE.set(new CachedGrid(ctx.level(), grid, needMinX, needMinZ, needSizeX, needSizeZ));
        return grid;
    }
}
