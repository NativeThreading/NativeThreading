package com.github.uright008.ep;

import com.github.uright008.pc.ChunkGrid;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicReference;

/** Static {@link ChunkGrid} reuse across blasts. A built grid plus the level
 *  it reads from and the section range it covers; chained blasts in the same
 *  region (the TNT benchmark's forceloaded room) reuse one grid instead of
 *  rebuilding it per explosion — every blast constructs a fresh
 *  ServerExplosion, so an instance field would rebuild every time. The level
 *  is part of the key: a grid's chunks are bound to one ServerLevel, so a
 *  blast in a different dimension must never reuse it. Chunks are referenced,
 *  not copied, so block edits between blasts are read live; a blast whose
 *  coverage leaves the cached range or whose dimension differs rebuilds. */
public final class ExplosionChunkGridCache {

    private record CachedGrid(ServerLevel level, ChunkGrid grid,
                              int minSectionX, int minSectionZ, int sizeX, int sizeZ) {}

    private static final AtomicReference<CachedGrid> CACHE = new AtomicReference<>();

    private ExplosionChunkGridCache() {}

    /** Returns a grid covering the blast's section range, reusing the cached
     *  one when the blast is in the same level and inside the covered range. */
    public static ChunkGrid forExplosion(ExplosionContext ctx) {
        int range = ExplosionRayBounds.sectionRange(ctx.radius());
        int scx = SectionPos.blockToSectionCoord((int) Math.floor(ctx.center().x));
        int scz = SectionPos.blockToSectionCoord((int) Math.floor(ctx.center().z));
        int needMinX = scx - range, needMaxX = scx + range;
        int needMinZ = scz - range, needMaxZ = scz + range;

        CachedGrid cached = CACHE.get();
        if (cached != null
                && cached.level == ctx.level()
                && needMinX >= cached.minSectionX && needMaxX <= cached.minSectionX + cached.sizeX - 1
                && needMinZ >= cached.minSectionZ && needMaxZ <= cached.minSectionZ + cached.sizeZ - 1) {
            return cached.grid;
        }
        ChunkGrid grid = new ChunkGrid(ctx.level(), scx, scz, range);
        CACHE.set(new CachedGrid(ctx.level(), grid, scx - range, scz - range, range * 2 + 1, range * 2 + 1));
        return grid;
    }
}
