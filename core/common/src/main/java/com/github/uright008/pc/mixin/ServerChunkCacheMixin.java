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
 * Thread-safe chunk access for worker threads.
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
