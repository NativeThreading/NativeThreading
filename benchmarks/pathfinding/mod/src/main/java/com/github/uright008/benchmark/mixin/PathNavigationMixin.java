package com.github.uright008.benchmark.mixin;

import com.github.uright008.benchmark.BlockUpdateScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
public abstract class PathNavigationMixin {
    @Shadow @Final protected Mob mob;

    @Inject(method = "shouldRecomputePath", at = @At("RETURN"))
    private void pathbench$checked(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockUpdateScene.checked(mob, cir.getReturnValue());
    }

    @Inject(method = "recomputePath", at = @At("HEAD"))
    private void pathbench$recomputeStart(CallbackInfo ci) {
        BlockUpdateScene.recomputeStart(mob);
    }

    @Inject(method = "recomputePath", at = @At("RETURN"))
    private void pathbench$recomputeEnd(CallbackInfo ci) {
        BlockUpdateScene.recomputeEnd(mob);
    }
}
