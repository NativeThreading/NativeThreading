package com.github.uright008.rp.mixin;

import com.github.uright008.rp.RedstoneWireHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DefaultRedstoneWireEvaluator.class)
public abstract class DefaultRedstoneWireEvaluatorMixin {

    @Inject(method = "updatePowerStrength(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/redstone/Orientation;Z)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void onUpdatePowerStrength(Level level, BlockPos pos, BlockState state,
                                       @Nullable Orientation orientation, boolean skipShapeUpdates,
                                       CallbackInfo ci) {
        if (!state.is(Blocks.REDSTONE_WIRE)) return;

        if (RedstoneWireHelper.tryParallelUpdate(level, pos)) {
            ci.cancel();
        }
    }
}
