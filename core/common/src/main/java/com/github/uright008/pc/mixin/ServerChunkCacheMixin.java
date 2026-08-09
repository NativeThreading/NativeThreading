package com.github.uright008.pc.mixin;

import com.github.uright008.pc.ChunkSafeAccessor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Exposes visible chunks without a force-load, so {@link ChunkGrid} can grab a
 * chunk and return null when absent (the caller then force-loads explicitly).
 * Runs on the main thread — chunk access stays within the threading boundary;
 * workers only ever read the captured flat view.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin implements ChunkSafeAccessor {

    @Shadow
    @Nullable
    private native ChunkHolder getVisibleChunkIfPresent(long key);

    @Override
    @Unique
    @Nullable
    public ChunkAccess parallelCore$getChunkSafe(int x, int z) {
        long key = parallelCore$chunkKey(x, z);
        ChunkHolder holder = getVisibleChunkIfPresent(key);
        if (holder == null) return null;
        return holder.getChunkIfPresentUnchecked(ChunkStatus.FULL);
    }

    @Unique
    private static long parallelCore$chunkKey(int x, int z) {
        return ((long) x & 0xffffffffL) | (((long) z & 0xffffffffL) << 32);
    }

}
