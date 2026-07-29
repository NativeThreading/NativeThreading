package com.github.uright008.hp.mixin;

import com.github.uright008.hp.HopperParallelConfig;
import com.github.uright008.hp.HopperParallelHelper;
import com.github.uright008.hp.HopperParallelization;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Replaces {@link Level#tickBlockEntities()} with a two-phase parallel
 * implementation for hoppers.
 *
 * <p>Non-hopper block entities continue to tick on the main thread exactly
 * as in vanilla.  Hoppers are collected and handed to
 * {@link HopperParallelHelper#processHoppers(Level, List)} which runs the
 * read-phase in parallel and the write-phase sequentially.</p>
 *
 * <p>When {@link HopperParallelConfig#isEnabled()} returns {@code false},
 * the mixin does nothing and vanilla behaviour is preserved.</p>
 *
 * <p>On error, the parallel path logs the failure and lets vanilla execute
 * as a safe fallback.</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Shadow
    @Final
    protected List<TickingBlockEntity> blockEntityTickers;

    @Shadow
    @Final
    private List<TickingBlockEntity> pendingBlockEntityTickers;

    @Shadow
    private boolean tickingBlockEntities;

    @Shadow
    public abstract boolean shouldTickBlocksAt(BlockPos pos);

    /**
     * Replace the entire {@code tickBlockEntities()} body when hopper
     * is enabled. On failure, vanilla will execute as fallback (no cancel).
     */
    @Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
    private void onTickBlockEntities(CallbackInfo ci) {
        if (!HopperParallelConfig.isEnabled()) return;

        Level self = (Level) (Object) this;

        this.tickingBlockEntities = true;
        try {
            if (!this.pendingBlockEntityTickers.isEmpty()) {
                this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
                this.pendingBlockEntityTickers.clear();
            }

            boolean tickBlockEntities = self.tickRateManager().runsNormally();

            List<HopperBlockEntity> hoppers = new ArrayList<>();
            Iterator<TickingBlockEntity> iterator = this.blockEntityTickers.iterator();

            while (iterator.hasNext()) {
                TickingBlockEntity ticker = iterator.next();

                if (ticker.isRemoved()) {
                    iterator.remove();
                    continue;
                }

                if (!tickBlockEntities || !this.shouldTickBlocksAt(ticker.getPos())) {
                    continue;
                }

                if (self.getBlockEntity(ticker.getPos()) instanceof HopperBlockEntity hopper) {
                    hoppers.add(hopper);
                } else {
                    if (!hoppers.isEmpty()) {
                        HopperParallelHelper.processHoppers(self, hoppers);
                        hoppers.clear();
                    }
                    ticker.tick();
                }
            }

            if (!hoppers.isEmpty()) {
                HopperParallelHelper.processHoppers(self, hoppers);
            }

            ci.cancel();
        } catch (Throwable t) {
            HopperParallelization.LOGGER.error("Hopper parallel tick failed; falling back to vanilla tick", t);
            // No ci.cancel() — vanilla will execute as fallback
        } finally {
            this.tickingBlockEntities = false;
        }
    }
}
