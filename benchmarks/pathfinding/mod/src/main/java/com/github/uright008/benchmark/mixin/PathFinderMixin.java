package com.github.uright008.benchmark.mixin;

import com.github.uright008.benchmark.AllayScene;
import com.github.uright008.benchmark.BlockUpdateScene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Set;

@Mixin(PathFinder.class)
public abstract class PathFinderMixin {
    @Unique private long pathbench$start;
    @Unique private long pathbench$updateStart;

    @Inject(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("HEAD"))
    private void pathbench$start(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
            float length, int reach, float multiplier, CallbackInfoReturnable<Path> cir) {
        pathbench$start = AllayScene.searchStart(mob);
        pathbench$updateStart = BlockUpdateScene.searchStart(mob);
    }

    @Inject(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At("RETURN"))
    private void pathbench$end(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
            float length, int reach, float multiplier, CallbackInfoReturnable<Path> cir) {
        AllayScene.searchEnd(mob, cir.getReturnValue(), pathbench$start);
        BlockUpdateScene.searchEnd(mob, cir.getReturnValue(), pathbench$updateStart);
    }
}
