package com.github.uright008.nt.explosion;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Pre-load visibility snapshot for the entity broad phase.
 *
 * <p>The problem this solves: vanilla only sees entities whose chunk the entity manager has
 * already promoted to {@code Visibility.TRACKED} when {@code hurtEntities} runs. Vanilla's own
 * chunk loading (lazy block reads inside the ray trace) queues that promotion and does not drain
 * the main-thread queue again, so a chunk the explosion itself just loaded is still HIDDEN at
 * capture time — measured on 26.3: a frozen TNT parked in an unloaded chunk is never knocked by
 * the explosion that loads its chunk. NativeThreading builds its snapshot from a batched
 * {@code getChunk(..., FULL, true)} grid whose sequential managed blocks drain the queue, so the
 * promotion lands before {@code hurtEntities} and the blast knocks/damages entities vanilla does
 * not touch (measured 0.700 knockback vs vanilla's 0).</p>
 *
 * <p>Fix: record — before any loading happens — which chunks around the blast are currently not
 * loaded ({@code hasChunk == false}, which is exactly {@code Visibility.HIDDEN}), and let the
 * capture predicate skip entities located in those chunks. The entities stay TRACKED afterwards,
 * matching vanilla's end state, so the next blast sees them exactly like vanilla does.</p>
 *
 * <p>Main-thread only, reused across blasts (explosions are serial on the main thread), same
 * contract as {@code ExplosionEntityStage}'s capture buffers.</p>
 */
public final class ExplosionVisibilitySnapshot {

    private static final LongOpenHashSet NOT_LOADED = new LongOpenHashSet();
    private static boolean active;

    private ExplosionVisibilitySnapshot() {}

    /** Records chunks not loaded at this moment. Call before the grid build, i.e. before the
     *  explosion performs any world access of its own. */
    public static void begin(ServerLevel level, Vec3 center, float radius) {
        NOT_LOADED.clear();
        ExplosionRayBounds.SectionRect rect = ExplosionRayBounds.sectionRect(center, radius);
        // One chunk of slack: the entity broad phase scans the ±(2r+1) box with vanilla's
        // +2/-4/+2 block padding, which can reach past the flat view's rectangle.
        int minX = rect.minX() - 1;
        int maxX = rect.maxX() + 1;
        int minZ = rect.minZ() - 1;
        int maxZ = rect.maxZ() + 1;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    NOT_LOADED.add(ChunkPos.pack(cx, cz));
                }
            }
        }
        active = true;
    }

    /** True when this entity's chunk was not loaded before the blast started, i.e. vanilla's
     *  entity query could not have returned it. */
    public static boolean hiddenAtCapture(Entity entity) {
        // The snapshot only contains chunks that were HIDDEN when the blast
        // started; the common case is that every chunk around the blast is already
        // loaded and the set is empty. Short-circuit before the per-entity
        // section-coordinate work (this predicate runs for every entity of every
        // blast; on a 93k-TNT pile that is billions of calls).
        if (!active || NOT_LOADED.isEmpty()) return false;
        int x = SectionPos.blockToSectionCoord(entity.blockPosition().getX());
        int z = SectionPos.blockToSectionCoord(entity.blockPosition().getZ());
        return NOT_LOADED.contains(ChunkPos.pack(x, z));
    }

    public static boolean active() {
        return active;
    }

    /** Drops the snapshot. Must run after the entity capture, whatever happened. */
    public static void end() {
        active = false;
        NOT_LOADED.clear();
    }
}
