package com.github.uright008.ep.mixin;

import com.github.uright008.ep.GeometryVersioned;
import com.github.uright008.ep.VisibilityCollisionChunkCache;
import com.github.uright008.ep.VisibilityCollisionSectionGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkVisibilityCollisionCacheMixin implements VisibilityCollisionChunkCache {
    @Unique private CacheEntry[] explosion$sectionCache = new CacheEntry[0];

    @Override
    public @Nullable VisibilityCollisionSectionGeometry explosion$getVisibilityCollisionSection(int sectionIndex) {
        explosion$ensureCapacity(sectionIndex);
        LevelChunk chunk = (LevelChunk) (Object) this;
        LevelChunkSection section = chunk.getSection(sectionIndex);
        long version = ((GeometryVersioned) section).explosion$getGeometryVersion();
        CacheEntry entry = explosion$sectionCache[sectionIndex];
        if (entry == null || entry.version != version) {
            entry = new CacheEntry(version,
                    VisibilityCollisionSectionGeometry.capture(chunk, sectionIndex));
            explosion$sectionCache[sectionIndex] = entry;
        }
        return entry.geometry;
    }

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void explosion$invalidateVisibilitySection(BlockPos pos, BlockState state, int flags,
                                                       CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) {
            return;
        }
        LevelChunk chunk = (LevelChunk) (Object) this;
        int sectionIndex = chunk.getSectionIndex(pos.getY());
        explosion$ensureCapacity(sectionIndex);
        explosion$sectionCache[sectionIndex] = null;
    }

    @Unique
    private void explosion$ensureCapacity(int sectionIndex) {
        if (sectionIndex < explosion$sectionCache.length) {
            return;
        }
        int length = sectionIndex + 1;
        CacheEntry[] cache = new CacheEntry[length];
        System.arraycopy(explosion$sectionCache, 0, cache, 0, explosion$sectionCache.length);
        explosion$sectionCache = cache;
    }

    @Unique
    private static final class CacheEntry {
        private final long version;
        private final VisibilityCollisionSectionGeometry geometry;

        private CacheEntry(long version, VisibilityCollisionSectionGeometry geometry) {
            this.version = version;
            this.geometry = geometry;
        }
    }
}
