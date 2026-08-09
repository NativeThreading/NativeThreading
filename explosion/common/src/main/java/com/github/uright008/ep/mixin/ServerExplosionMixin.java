package com.github.uright008.ep.mixin;

import com.github.uright008.ep.ExplosionBlockStage;
import com.github.uright008.ep.ExplosionChunkGridCache;
import com.github.uright008.ep.ExplosionContext;
import com.github.uright008.ep.ExplosionEntityStage;
import com.github.uright008.ep.ExplosionHelper;
import com.github.uright008.ep.ExplosionParallelConfig;
import com.github.uright008.ep.ExplosionParallelEligibility;
import com.github.uright008.ep.WorldReadViewImpl;
import com.github.uright008.pc.ChunkGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/** Injection-only shell. All pipeline logic lives in
 *  {@link com.github.uright008.ep.ExplosionBlockStage} /
 *  {@link com.github.uright008.ep.ExplosionEntityStage} /
 *  {@link com.github.uright008.ep.ExplosionChunkGridCache}; this class only
 *  translates shadow fields into an {@link ExplosionContext}, short-circuits
 *  damage-only blasts, and hands the return values back. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @Shadow private ServerLevel level;
    @Shadow private Vec3 center;
    @Shadow private float radius;
    @Shadow @Nullable private Entity source;
    @Shadow private DamageSource damageSource;
    @Shadow private ExplosionDamageCalculator damageCalculator;
    @Shadow private Map<Player, Vec3> hitPlayers;
    @Shadow private native boolean interactsWithBlocks();
    @Shadow private boolean fire;

    /** Built lazily and shared by the two injection points within one blast;
     *  the grid itself is reused across blasts via ExplosionChunkGridCache. */
    @Unique private ChunkGrid cachedChunkGrid;

    /** The flat view built by the block stage, consumed by the entity stage
     *  within the same blast. Explicitly threaded through rather than hidden
     *  in the stages so the run-before ordering is visible at the call site. */
    @Unique private WorldReadViewImpl blastWorldView;

    // ──────────────────────────────────────────────
    //  Inject: intercept calculatedExplodedPositions
    @Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true)
    private void onCalculateExplodedPositions(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (!ExplosionParallelConfig.isEnabled()) return;
        if (!resolveTier().allowsParallel()) return;
        ExplosionContext ctx = context();
        if (ctx.blockStageIsSkipped()) {
            // KEEP block interaction with fire off: vanilla's explode() still
            // runs the 1352-ray trace + flat view, but neither the block
            // drops nor the fire pass consume the result. The shortest
            // vanilla-identical path is to align the world RNG (one LCG step
            // per consumeCount — exactly the state advance of 1352
            // nextFloat) and let hurtEntities run vanilla-exact afterwards.
            // Entity damage is unaffected; the empty list only zeroes the
            // client blockCount, which weights explosion particles.
            this.level.getRandom().consumeCount(ExplosionHelper.RAY_PARAMS.size());
            cir.setReturnValue(java.util.Collections.emptyList());
            return;
        }
        ExplosionBlockStage.Result result = ExplosionBlockStage.compute(ctx, ensureChunksLoaded());
        this.blastWorldView = result.worldView();
        cir.setReturnValue(result.blocks());
    }

    // ──────────────────────────────────────────────
    @Inject(method = "hurtEntities", at = @At("HEAD"), cancellable = true)
    private void onHurtEntities(CallbackInfo ci) {
        if (!ExplosionParallelConfig.isEnabled()) return;
        if (!resolveTier().allowsParallel()) return;
        ExplosionContext ctx = context();
        if (ctx.blockStageIsSkipped()) {
            // Short-circuited explosion: no flat view was built, so the
            // parallel entity path (flat-view exposure DDA) has nothing to
            // read — run vanilla hurtEntities, whose real-time getSeenPercent
            // is exactly the original behaviour.
            return;
        }
        ProfilerFiller profiler = Profiler.get();
        profiler.push("explosion_entities_parallel");
        if (ExplosionEntityStage.apply(ctx, this.blastWorldView)) {
            ci.cancel();
        }
        profiler.pop();
    }

    // ──────────────────────────────────────────────
    //  Thin helpers
    // ──────────────────────────────────────────────
    @Unique
    private ExplosionParallelEligibility.Tier resolveTier() {
        return ExplosionParallelEligibility.resolveTier(this.damageCalculator.getClass());
    }

    @Unique
    private ExplosionContext context() {
        return new ExplosionContext(this.level, this.center, this.radius, this.source,
                this.damageSource, this.damageCalculator, this.hitPlayers,
                this.fire, this.interactsWithBlocks(), (ServerExplosion) (Object) this);
    }

    @Unique
    private ChunkGrid ensureChunksLoaded() {
        if (this.cachedChunkGrid == null) {
            this.cachedChunkGrid = ExplosionChunkGridCache.forExplosion(context());
        }
        return this.cachedChunkGrid;
    }
}
