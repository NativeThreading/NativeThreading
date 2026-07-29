package com.github.uright008.ep.mixin;

import com.github.uright008.ep.GeometryVersioned;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds a geometry version counter to {@link LevelChunkSection}.
 *
 * <p>Each {@code setBlockState} call that actually changes the block state
 * (i.e.&nbsp;the returned previous state is not the same reference as the new
 * state) increments the counter. The visibility collision cache reads this
 * version to detect stale captured geometry without maintaining its own
 * per-section version array.</p>
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionGeometryVersionMixin implements GeometryVersioned {

    @Unique private long explosion$geometryVersion;

    @Override
    public long explosion$getGeometryVersion() {
        return explosion$geometryVersion;
    }

    /**
     * Increments the geometry version when {@code setBlockState} actually
     * changes the block state. Uses reference equality (matching vanilla's
     * {@code oldState == state} check in {@code LevelChunk.setBlockState}).
     */
    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void explosion$incrementGeometryVersion(int sectionX, int sectionY, int sectionZ,
                                                     BlockState state,
                                                     CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        if (previous != null) {
            explosion$geometryVersion++;
        }
    }
}
