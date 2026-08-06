package com.github.uright008.ep.mixin;

import com.github.uright008.ep.ExplosionFlatViewBuilder;
import com.github.uright008.ep.ExplosionHelper;
import com.github.uright008.ep.ExplosionEntityApplication;
import com.github.uright008.ep.ExplosionParallelEligibility;
import com.github.uright008.ep.ExplosionParallelConfig;
import com.github.uright008.ep.ExplosionRayBounds;
import com.github.uright008.ep.WorldReadView;
import com.github.uright008.ep.WorldReadViewImpl;
import com.github.uright008.pc.ChunkGrid;
import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @FunctionalInterface
    @Unique
    interface ResistanceCalculator {
        float apply(BlockPos pos, BlockState block, FluidState fluid, float baseResistance);
    }

    @FunctionalInterface
    @Unique
    interface BlockExplodeDecider {
        boolean shouldExplode(BlockPos pos, BlockState block, float remainingPower);
    }

    @Shadow private ServerLevel level;
    @Shadow private Vec3 center;
    @Shadow private float radius;
    @Shadow @Nullable private Entity source;
    @Shadow private DamageSource damageSource;
    @Shadow private ExplosionDamageCalculator damageCalculator;
    @Shadow private Map<Player, Vec3> hitPlayers;
    @Shadow private native boolean interactsWithBlocks();

    @Unique private ChunkGrid cachedChunkGrid;
    @Unique private WorldReadViewImpl cachedWorldView;

    // Reusable flat-view buffers. Explosions run serially on the main thread
    // and the worker phase is joined before any reuse, so static caches are
    // safe and avoid allocating three arrays (block states, shapes, box
    // table) per explosion — ~30k arrays/tick under sustained TNT chains.
    @Unique private static final java.util.concurrent.atomic.AtomicReference<BlockState[]> FLAT_BLOCKS_CACHE = new java.util.concurrent.atomic.AtomicReference<>();
    @Unique private static final java.util.concurrent.atomic.AtomicReference<VoxelShape[]> FLAT_SHAPES_CACHE = new java.util.concurrent.atomic.AtomicReference<>();
    @Unique private static final java.util.concurrent.atomic.AtomicReference<double[][]> SHAPE_BOXES_CACHE = new java.util.concurrent.atomic.AtomicReference<>();

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("native-threading:explosion");
    @Unique private static final AtomicLong PARALLEL_ENTITY_PATHS = new AtomicLong();
    @Unique private static final AtomicLong ENTITY_WORKER_BATCHES = new AtomicLong();
    @Unique private static final AtomicLong ENTITY_FALLBACKS = new AtomicLong();

    @Unique private static final ResistanceCalculator DEFAULT_RESISTANCE_CALC = (pos, block, fluid, baseRes) -> {
        if (!block.isAir() || !fluid.isEmpty()) {
            return (baseRes + 0.3F) * 0.3F;
        }
        return 0.0F;
    };

    @Unique private static final BlockExplodeDecider DEFAULT_EXPLODE_DECIDER = (pos, block, remainingPower) -> remainingPower > 0.0F;


    // ──────────────────────────────────────────────
    // ──────────────────────────────────────────────
    //  Inject: intercept calculatedExplodedPositions
    @Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true)
    private void onCalculateExplodedPositions(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (!ExplosionParallelConfig.isEnabled()) return;
        ExplosionParallelEligibility.Tier tier = resolveTier();
        if (!tier.allowsParallel()) return;
        ensureChunksLoaded();
        List<BlockPos> result = calculateExplodedPositionsParallel();
        if (result != null) {
            cir.setReturnValue(result);
        } else {
            LOGGER.warn("Explosion parallel block-position calculation failed; falling back to vanilla");
        }
    }

    // ──────────────────────────────────────────────
    @Inject(method = "hurtEntities", at = @At("HEAD"), cancellable = true)
    private void onHurtEntities(CallbackInfo ci) {
        if (!ExplosionParallelConfig.isEnabled()) return;
        ExplosionParallelEligibility.Tier tier = resolveTier();
        if (!tier.allowsParallel()) return;

        ensureChunksLoaded();
        ProfilerFiller profiler = Profiler.get();
        profiler.push("explosion_entities_parallel");
        if (hurtEntitiesParallel()) {
            ci.cancel();
        }
        profiler.pop();
    }


    // ──────────────────────────────────────────────
    //  Pre-load chunks
    // ──────────────────────────────────────────────
    @Unique
    private void ensureChunksLoaded() {
        if (this.cachedChunkGrid != null) return;
        this.cachedChunkGrid = new ChunkGrid(this.level, this.center.x, this.center.z, this.radius);
    }

    @Unique
    private ExplosionParallelEligibility.Tier resolveTier() {
        return ExplosionParallelEligibility.resolveTier(this.damageCalculator.getClass());
    }

    // ──────────────────────────────────────────────
    //  Parallel calculateExplodedPositions
    // ──────────────────────────────────────────────
    @Unique
    private @Nullable List<BlockPos> calculateExplodedPositionsParallel() {
        List<ExplosionHelper.RayParam> rays = ExplosionHelper.RAY_PARAMS;
        int rayCount = rays.size();
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int numThreads = Math.min(ParallelThreadPool.getParallelism(), Math.min(cpuCores, Math.max(2, rayCount / 64)));
        final ChunkGrid chunkGrid = this.cachedChunkGrid;

        final float[] rayPowers = new float[rayCount];
        final float radiusF = this.radius;
        // Random powers are drawn on the main thread, one nextFloat per ray,
        // in exactly the vanilla iteration order (xx→yy→zz over the 16³ grid
        // boundary). Reusing level.getRandom() keeps the drawn sequence
        // identical to vanilla; the worker rays consume these precomputed
        // values and never touch an RNG themselves, so no cross-thread RNG
        // access exists.
        for (int i = 0; i < rayCount; i++) {
            rayPowers[i] = radiusF * (0.7F + this.level.getRandom().nextFloat() * 0.6F);
        }

        ExplosionRayBounds bounds = ExplosionRayBounds.forExplosion(this.center, this.radius);
        final int minX = bounds.minX();
        final int minY = bounds.minY();
        final int minZ = bounds.minZ();
        final int maxX = bounds.maxX();
        final int maxY = bounds.maxY();
        final int maxZ = bounds.maxZ();
        final int strideY = maxX - minX + 1;
        final int strideZ = strideY * (maxY - minY + 1);
        final int gridSize = strideZ * (maxZ - minZ + 1);

        int perWorker = rayCount / numThreads;
        int extra = rayCount % numThreads;
        record RayRange(int start, int end, BitSet grid) {}
        List<RayRange> ranges = new ArrayList<>(numThreads);
        for (int t = 0; t < numThreads; t++) {
            int start = t * perWorker + Math.min(t, extra);
            int end = start + perWorker + (t < extra ? 1 : 0);
            if (start < end) ranges.add(new RayRange(start, end, new BitSet(gridSize)));
        }

        List<BitSet> workerGrids;
        BlockState[] flatBlocks = FLAT_BLOCKS_CACHE.getAndSet(null);
        if (flatBlocks == null || flatBlocks.length < gridSize) flatBlocks = new BlockState[gridSize];
        ExplosionFlatViewBuilder.fillSectioned(flatBlocks, minX, minY, minZ, maxX, maxY, maxZ,
                strideY, strideZ, chunkGrid);

        // Precompute collision shapes so workers never call getCollisionShape
        // in the DDA inner loop. Same call as the live path
        // (getCollisionShape(null, null)), so results are identical.
        VoxelShape[] flatShapes = FLAT_SHAPES_CACHE.getAndSet(null);
        if (flatShapes == null || flatShapes.length < gridSize) flatShapes = new VoxelShape[gridSize];
        for (int i = 0; i < gridSize; i++) {
            BlockState s = flatBlocks[i];
            flatShapes[i] = s.isAir() ? null : s.getCollisionShape(null, null);
        }

        final WorldReadViewImpl worldView = new WorldReadViewImpl(
                flatBlocks, flatShapes,
                ExplosionHelper.flattenShapeBoxesReused(flatBlocks, flatShapes, gridSize, SHAPE_BOXES_CACHE),
                minX, minY, minZ, maxX, maxY, maxZ, strideY, strideZ);
        this.cachedWorldView = worldView;

        final ServerExplosion self = (ServerExplosion) (Object) this;
        final boolean isDefaultCalc = this.damageCalculator.getClass() == ExplosionDamageCalculator.class;
        final ResistanceCalculator resistanceCalc;
        final BlockExplodeDecider explodeDecider;

        if (isDefaultCalc) {
            resistanceCalc = DEFAULT_RESISTANCE_CALC;
            explodeDecider = DEFAULT_EXPLODE_DECIDER;
        } else if (this.damageCalculator instanceof net.minecraft.world.level.EntityBasedExplosionDamageCalculator
                && this.source != null) {
            final Entity entity = this.source;
            final ServerLevel level = this.level;
            resistanceCalc = (pos, block, fluid, baseRes) -> {
                if (!block.isAir() || !fluid.isEmpty()) {
                    float res = Math.max(block.getBlock().getExplosionResistance(),
                            fluid.getExplosionResistance());
                    res = entity.getBlockExplosionResistance(self, level, pos, block, fluid, res);
                    return (res + 0.3F) * 0.3F;
                }
                return 0.0F;
            };
            explodeDecider = (pos, block, remainingPower) ->
                    entity.shouldBlockExplode(self, level, pos, block, remainingPower);
        } else {
            final ExplosionDamageCalculator calc = this.damageCalculator;
            final ServerLevel level = this.level;
            resistanceCalc = (pos, block, fluid, baseRes) -> {
                Optional<Float> resistance = calc.getBlockExplosionResistance(self, level, pos, block, fluid);
                return resistance.map(r -> (r + 0.3F) * 0.3F).orElse(0.0F);
            };
            explodeDecider = (pos, block, remainingPower) ->
                    calc.shouldBlockExplode(self, level, pos, block, remainingPower);
        }

        try {
            workerGrids = ParallelWorker.mapEach(ParallelThreadPool.getPool("Explosion"),
                    ranges, range -> {
                        for (int i = range.start; i < range.end; i++)
                            traceRay(rays.get(i), i, range.grid, minX, minY, minZ, maxX, maxY, maxZ,
                                    worldView, strideY, strideZ, rayPowers[i],
                                    resistanceCalc, explodeDecider);
                        return range.grid;
                    }, 5);
        } catch (RuntimeException e) {
            LOGGER.error("Explosion ray workers failed; falling back to vanilla", e);
            return null;
        }

        BitSet grid = new BitSet(gridSize);
        for (BitSet wg : workerGrids) grid.or(wg);

        List<BlockPos> result = new ArrayList<>(gridSize);
        for (int z = minZ; z <= maxZ; z++) {
            int zOff = (z - minZ) * strideZ;
            for (int y = minY; y <= maxY; y++) {
                int yzOff = zOff + (y - minY) * strideY;
                for (int x = minX; x <= maxX; x++)
                    if (grid.get(yzOff + (x - minX)))
                        result.add(new BlockPos(x, y, z));
            }
        }

        // Workers have joined; hand the buffers back for reuse.
        FLAT_BLOCKS_CACHE.set(flatBlocks);
        FLAT_SHAPES_CACHE.set(flatShapes);
        SHAPE_BOXES_CACHE.set(((WorldReadViewImpl) worldView).shapeBoxes());

        return result;
    }

    // ──────────────────────────────────────────────
    //  Single ray trace
    // ──────────────────────────────────────────────
    @Unique
    private void traceRay(ExplosionHelper.RayParam ray, int rayIndex,
                          BitSet grid, int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ, WorldReadViewImpl worldView,
                          int strideY, int strideZ,
                          float initialPower,
                          ResistanceCalculator resistanceCalc,
                          BlockExplodeDecider explodeDecider) {
        float remainingPower = initialPower;
        final int gMinX = minX, gMinY = minY, gMinZ = minZ;
        final int gMaxX = maxX, gMaxY = maxY, gMaxZ = maxZ;
        final int MAX = ExplosionHelper.rayMaxSteps(this.radius);
        final int strideY_ = strideY, strideZ_ = strideZ;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Vanilla-exact march: float accumulation from the exact centre,
        // flooring each step — identical to ServerExplosion.calculateExplodedPositions.
        // Step uses the precomputed direction*0.3F (vanilla's 0.3F, not the
        // double literal 0.3) so accumulation matches bit for bit.
        double xp = this.center.x, yp = this.center.y, zp = this.center.z;
        final double sx = ray.stepX(), sy = ray.stepY(), sz = ray.stepZ();

        final int worldMinY = this.level.getMinY(), worldMaxY = this.level.getMaxY();

        for (int s = 0; s < MAX && remainingPower > 0.0F; remainingPower -= 0.22500001F, s++) {
            int bx = net.minecraft.util.Mth.floor(xp);
            int by = net.minecraft.util.Mth.floor(yp);
            int bz = net.minecraft.util.Mth.floor(zp);
            pos.set(bx, by, bz);
            // Vanilla breaks on !level.isInWorldBounds(pos) — the build-height
            // check matters: cells above the world are read as AIR by the flat
            // view, so without this the ray would keep marching past the world
            // ceiling and set grid bits there (feeding createFire). x/z are
            // unbounded on servers, only y needs the check.
            if (by < worldMinY || by > worldMaxY) break;
            if (bx < gMinX || bx > gMaxX || by < gMinY || by > gMaxY || bz < gMinZ || bz > gMaxZ) break;

            BlockState block = worldView.getBlockStateUnchecked(bx, by, bz);
            FluidState fluid = block.getFluidState();
            if (!block.isAir() || !fluid.isEmpty()) {
                float baseRes = Math.max(block.getBlock().getExplosionResistance(),
                        fluid.getExplosionResistance());
                remainingPower -= resistanceCalc.apply(pos, block, fluid, baseRes);
            }
            if (remainingPower > 0.0F && explodeDecider.shouldExplode(pos, block, remainingPower)) {
                if (bx >= gMinX && bx <= gMaxX && by >= gMinY && by <= gMaxY && bz >= gMinZ && bz <= gMaxZ)
                    grid.set((bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_);
            }
            xp += sx; yp += sy; zp += sz;
        }
    }

    // ──────────────────────────────────────────────
    //  Parallel entity damage
    // ──────────────────────────────────────────────
    @Unique
    private boolean hurtEntitiesParallel() {
        if (this.radius < 1.0E-5F) return true;

        float doubleRadius = this.radius * 2.0F;
        int x0 = net.minecraft.util.Mth.floor(this.center.x - doubleRadius - 1.0);
        int x1 = net.minecraft.util.Mth.floor(this.center.x + doubleRadius + 1.0);
        int y0 = net.minecraft.util.Mth.floor(this.center.y - doubleRadius - 1.0);
        int y1 = net.minecraft.util.Mth.floor(this.center.y + doubleRadius + 1.0);
        int z0 = net.minecraft.util.Mth.floor(this.center.z - doubleRadius - 1.0);
        int z1 = net.minecraft.util.Mth.floor(this.center.z + doubleRadius + 1.0);

        final float dr = doubleRadius;
        final double centerX = this.center.x;
        final double centerY = this.center.y;
        final double centerZ = this.center.z;
        List<ExplosionHelper.EntityDamageSnapshot> snapshots;
        List<Entity> refs;
        List<ExplosionHelper.EntityDamageResult> results;
        try {
            CapturedDamage captured = captureEntityDamageSnapshots(x0, y0, z0, x1, y1, z1, dr);
            snapshots = captured.snapshots();
            refs = captured.refs();
            if (snapshots.isEmpty()) return true;
            ENTITY_WORKER_BATCHES.incrementAndGet();
            results = ParallelWorker.mapBatched(ParallelThreadPool.getPool("Explosion"), snapshots,
                    snapshot -> ExplosionHelper.computeEntityDamage(
                            snapshot, centerX, centerY, centerZ, dr, this.cachedWorldView),
                    ParallelWorker.autoBatchSize(snapshots.size()), 5);
        } catch (RuntimeException e) {
            ENTITY_FALLBACKS.incrementAndGet();
            LOGGER.error("Explosion entity workers failed; falling back to vanilla", e);
            return false;
        }

        for (int i = 0; i < results.size(); i++) {
            ExplosionHelper.EntityDamageResult r = results.get(i);
            if (r != null) applyEntityDamage(r, refs.get(i));
        }

        logEntityPathCounters();
        return true;
    }

    @Unique
    private CapturedDamage captureEntityDamageSnapshots(
            int x0, int y0, int z0, int x1, int y1, int z1, float doubleRadius) {
        // Spatial query on the main thread — the same box vanilla
        // hurtEntities scans, returning only entities inside the blast AABB.
        List<Entity> candidates = this.level.getEntities(
                this.source, new AABB(x0, y0, z0, x1, y1, z1));
        if (candidates.isEmpty()) return new CapturedDamage(List.of(), List.of());

        double radiusSquare = (double) doubleRadius * doubleRadius;
        ServerExplosion self = (ServerExplosion) (Object) this;
        final boolean isDefaultCalc = this.damageCalculator.getClass() == ExplosionDamageCalculator.class;

        // Context-free flat shapes are vanilla-exact for 99.9% of blocks. Only
        // scaffolding and powder snow vary their collision shape by the querying
        // entity; when either is present in the blast box, exposure must be
        // computed with the real entity context (vanilla-exact) per hit entity.
        final boolean needsEntityContext = ExplosionHelper.hasEntityContextBlocks(this.cachedWorldView);

        List<ExplosionHelper.EntityDamageSnapshot> snapshots = new ArrayList<>(candidates.size());
        List<Entity> refs = new ArrayList<>(candidates.size());
        for (Entity entity : candidates) {
            if (entity.ignoreExplosion(self)) continue;
            int entityId = entity.getId();
            double feetX = entity.getX(), feetY = entity.getY(), feetZ = entity.getZ();
            if (entity.distanceToSqr(this.center) > radiusSquare) continue;

            refs.add(entity);
            AABB bb = entity.getBoundingBox();
            boolean shouldDamage;
            float knockbackMultiplier;
            boolean isPrimedTnt;
            float exposure = 0.0F;
            boolean exposurePreset = false;
            if (needsEntityContext) {
                shouldDamage = isDefaultCalc
                        ? true : this.damageCalculator.shouldDamageEntity(self, entity);
                knockbackMultiplier = isDefaultCalc
                        ? 1.0F : this.damageCalculator.getKnockbackMultiplier(entity);
                isPrimedTnt = entity instanceof PrimedTnt;
                exposure = ExplosionHelper.computeContextAwareExposure(
                        entity, this.center.x, this.center.y, this.center.z);
                exposurePreset = true;
            } else if (isDefaultCalc) {
                shouldDamage = true;
                knockbackMultiplier = 1.0F;
                isPrimedTnt = entity instanceof PrimedTnt;
            } else {
                shouldDamage = this.damageCalculator.shouldDamageEntity(self, entity);
                knockbackMultiplier = this.damageCalculator.getKnockbackMultiplier(entity);
                isPrimedTnt = entity instanceof PrimedTnt;
            }
            double eyeY = isPrimedTnt ? feetY : feetY + entity.getEyeHeight();
            // Captured on the main thread so the worker applies it in the exact
            // vanilla product order (1-dist)*exposure*kbMult*(1-res).
            double kbRes = entity instanceof LivingEntity living
                    ? living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                    : 0.0;

            snapshots.add(new ExplosionHelper.EntityDamageSnapshot(entityId,
                    feetX, feetY, feetZ, eyeY,
                    bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
                    shouldDamage, knockbackMultiplier, exposure, exposurePreset,
                    kbRes));
        }
        return new CapturedDamage(snapshots, refs);
    }

    /** Snapshots plus the capture-time entity references, parallel lists.
     *  Applying by reference (not by ID re-lookup) matches vanilla
     *  hurtEntities, which iterates its collected list and still damages an
     *  entity that an earlier hit in the same blast removed. */
    private record CapturedDamage(
            List<ExplosionHelper.EntityDamageSnapshot> snapshots,
            List<Entity> refs) {}

    @Unique
    private void logEntityPathCounters() {
        long paths = PARALLEL_ENTITY_PATHS.incrementAndGet();
        if ((paths & (paths - 1)) == 0) {
            LOGGER.info("Explosion entity paths: active={}, workerBatches={}, fallbacks={}",
                    paths, ENTITY_WORKER_BATCHES.get(), ENTITY_FALLBACKS.get());
        }
    }

    // ──────────────────────────────────────────────
    //  Compute entity damage (worker-thread safe)
    // ──────────────────────────────────────────────
    @Unique
    private void applyEntityDamage(ExplosionHelper.EntityDamageResult result, Entity entity) {
        // Uses the capture-time reference, not a by-ID re-lookup — vanilla
        // iterates its collected entity list and applies to every member even
        // if an earlier hit removed it.
        ExplosionEntityApplication.apply(result, new ExplosionEntityApplication.Target() {
            @Override
            public void hurt(float damage) {
                entity.hurtServer(level, damageSource, damage);
            }

            @Override
            public void push(Vec3 knockback) {
                entity.push(knockback);
            }

            @Override
            public void bookkeep(Vec3 knockback) {
                if (entity.getType().builtInRegistryHolder().is(EntityTypeTags.REDIRECTABLE_PROJECTILE)
                        && entity instanceof Projectile projectile) {
                    projectile.setOwner(damageSource.getEntity());
                } else if (entity instanceof Player player && !player.isSpectator()
                        && (!player.isCreative() || !player.getAbilities().flying)) {
                    hitPlayers.put(player, knockback);
                }
            }

            @Override
            public void onExplosionHit() {
                entity.onExplosionHit(source);
            }
        });
    }
}
