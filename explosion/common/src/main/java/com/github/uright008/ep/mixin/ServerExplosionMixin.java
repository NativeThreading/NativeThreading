package com.github.uright008.ep.mixin;

import com.github.uright008.ep.ExplosionHelper;
import com.github.uright008.ep.ExplosionParallelEligibility;
import com.github.uright008.ep.ExplosionParallelConfig;
import com.github.uright008.pc.ChunkGrid;
import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
import com.github.uright008.pc.simd.SimdBatchOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
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

    @Unique private volatile float[] cachedFirstBlockDistances;

    @Unique private ChunkGrid cachedChunkGrid;

    @Unique private static final Logger LOGGER = LoggerFactory.getLogger("mc-parallel:explosion");


    // ──────────────────────────────────────────────
    // ──────────────────────────────────────────────
    //  Inject: intercept calculatedExplodedPositions
    @Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true)
    private void onCalculateExplodedPositions(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (!ExplosionParallelConfig.isEnabled() || !allowsWorkerExecution()) {
            return;
        }
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
        if (!ExplosionParallelConfig.isEnabled() || !allowsWorkerExecution()) return;

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
    private boolean allowsWorkerExecution() {
        return ExplosionParallelEligibility.allowsWorkerExecution(this.damageCalculator.getClass());
    }

    // ──────────────────────────────────────────────
    //  Parallel calculateExplodedPositions
    // ──────────────────────────────────────────────
    @Unique
    private @Nullable List<BlockPos> calculateExplodedPositionsParallel() {
        int gs = ExplosionParallelConfig.getAdaptiveRays();
        List<ExplosionHelper.RayParam> rays = gs > 0 ? ExplosionHelper.buildRayParams(gs) : ExplosionHelper.RAY_PARAMS;
        int rayCount = rays.size();
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int numThreads = Math.min(ParallelThreadPool.getParallelism(), Math.min(cpuCores, Math.max(2, rayCount / 64)));
        final ChunkGrid chunkGrid = this.cachedChunkGrid;
        final float[] firstBlockDistances = new float[rayCount];
        java.util.Arrays.fill(firstBlockDistances, Float.MAX_VALUE);

        final float[] rayPowers = new float[rayCount];
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        final float radiusF = this.radius;
        for (int i = 0; i < rayCount; i++) {
            rayPowers[i] = radiusF * (0.7F + rng.nextFloat() * 0.6F);
        }

        final int r = (int) Math.ceil(this.radius) + 1;
        final int minX = net.minecraft.util.Mth.floor(this.center.x - r);
        final int minY = net.minecraft.util.Mth.floor(this.center.y - r);
        final int minZ = net.minecraft.util.Mth.floor(this.center.z - r);
        final int maxX = net.minecraft.util.Mth.floor(this.center.x + r);
        final int maxY = net.minecraft.util.Mth.floor(this.center.y + r);
        final int maxZ = net.minecraft.util.Mth.floor(this.center.z + r);
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
        final BlockState[] flatBlocks = new BlockState[gridSize];
        for (int z = minZ; z <= maxZ; z++) {
            int zOff = (z - minZ) * strideZ;
            for (int y = minY; y <= maxY; y++) {
                int yzOff = zOff + (y - minY) * strideY;
                for (int x = minX; x <= maxX; x++) {
                    int cx = SectionPos.blockToSectionCoord(x);
                    int cz = SectionPos.blockToSectionCoord(z);
                    flatBlocks[yzOff + (x - minX)] = chunkGrid.getBlockState(cx, cz, y, x & 15, y & 15, z & 15);
                }
            }
        }

        try {
            workerGrids = ParallelWorker.mapEach(ParallelThreadPool.getPool("Explosion"),
                    ranges, range -> {
                        for (int i = range.start; i < range.end; i++)
                            traceRay(rays.get(i), i, range.grid, minX, minY, minZ, maxX, maxY, maxZ,
                                    chunkGrid, strideY, strideZ, firstBlockDistances, rayPowers[i], flatBlocks);
                        return range.grid;
                    }, 5);
        } catch (RuntimeException e) {
            LOGGER.error("Explosion ray workers failed; falling back to vanilla", e);
            return null;
        }

        BitSet grid = new BitSet(gridSize);
        for (BitSet wg : workerGrids) grid.or(wg);

        this.cachedFirstBlockDistances = firstBlockDistances;

        List<BlockPos> result = new ArrayList<>(strideZ * (maxZ - minZ + 1) / 4);
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            int zOff = (z - minZ) * strideZ;
            for (int y = minY; y <= maxY; y++) {
                int yzOff = zOff + (y - minY) * strideY;
                for (int x = minX; x <= maxX; x++)
                    if (grid.get(yzOff + (x - minX)))
                        result.add(mpos.set(x, y, z).immutable());
            }
        }
        return result;
    }

    // ──────────────────────────────────────────────
    //  Single ray trace
    // ──────────────────────────────────────────────
    @Unique
    private void traceRay(ExplosionHelper.RayParam ray, int rayIndex,
                          BitSet grid, int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ, ChunkGrid chunkGrid,
                          int strideY, int strideZ, float[] firstBlockDistances,
                          float initialPower, BlockState[] flatBlocks) {
        float remainingPower = initialPower;
        final ServerExplosion self = (ServerExplosion) (Object) this;
        final boolean isDefaultCalc = this.damageCalculator.getClass() == ExplosionDamageCalculator.class;
        final int gMinX = minX, gMinY = minY, gMinZ = minZ;
        final int gMaxX = maxX, gMaxY = maxY, gMaxZ = maxZ;
        final int MAX = ExplosionHelper.MAX_RAY_STEPS;
        final int strideY_ = strideY, strideZ_ = strideZ;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        if (ExplosionParallelConfig.isPreciseRays()) {
            double xp = this.center.x, yp = this.center.y, zp = this.center.z;
            final double sx = ray.xd() * 0.3, sy = ray.yd() * 0.3, sz = ray.zd() * 0.3;

            for (int s = 0; s < MAX && remainingPower > 0.0F; remainingPower -= 0.22500001F, s++) {
                int bx = net.minecraft.util.Mth.floor(xp);
                int by = net.minecraft.util.Mth.floor(yp);
                int bz = net.minecraft.util.Mth.floor(zp);
                pos.set(bx, by, bz);
                if (bx < gMinX || bx > gMaxX || by < gMinY || by > gMaxY || bz < gMinZ || bz > gMaxZ) break;

                BlockState block = flatBlocks[(bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_];
                if (isDefaultCalc) {
                    if (!block.isAir() || !block.getFluidState().isEmpty()) {
                        float res = (Math.max(block.getBlock().getExplosionResistance(),
                                block.getFluidState().getExplosionResistance()) + 0.3F) * 0.3F;
                        remainingPower -= res;
                        if (firstBlockDistances[rayIndex] == Float.MAX_VALUE) {
                            double ddx = bx + 0.5 - this.center.x;
                            double ddy = by + 0.5 - this.center.y;
                            double ddz = bz + 0.5 - this.center.z;
                            firstBlockDistances[rayIndex] = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                        }
                    }
                    if (remainingPower > 0.0F && bx >= gMinX && bx <= gMaxX && by >= gMinY && by <= gMaxY && bz >= gMinZ && bz <= gMaxZ)
                        grid.set((bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_);
                } else {
                    FluidState fluid = block.getFluidState();
                    recordFirstBlockDistance(pos, block, fluid, rayIndex, firstBlockDistances);
                    remainingPower = applyResistance(remainingPower, block, fluid, pos, self, false);
                    long packed = processBlock(block, fluid, pos, remainingPower, self, false);
                    if (remainingPower > 0.0F && packed != Long.MIN_VALUE) {
                        if (bx >= gMinX && bx <= gMaxX && by >= gMinY && by <= gMaxY && bz >= gMinZ && bz <= gMaxZ)
                            grid.set((bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_);
                    }
                }
                xp += sx; yp += sy; zp += sz;
            }
        } else {
            int bx = net.minecraft.util.Mth.floor(this.center.x);
            int by = net.minecraft.util.Mth.floor(this.center.y);
            int bz = net.minecraft.util.Mth.floor(this.center.z);
            final int[] deltas = ExplosionHelper.RAY_DELTAS[rayIndex];

            for (int s = 0; s < MAX && remainingPower > 0.0F; remainingPower -= 0.22500001F, s++) {
                pos.set(bx, by, bz);
                if (bx < gMinX || bx > gMaxX || by < gMinY || by > gMaxY || bz < gMinZ || bz > gMaxZ) break;

                BlockState block = flatBlocks[(bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_];
                if (isDefaultCalc) {
                    if (!block.isAir() || !block.getFluidState().isEmpty()) {
                        float res = (Math.max(block.getBlock().getExplosionResistance(),
                                block.getFluidState().getExplosionResistance()) + 0.3F) * 0.3F;
                        remainingPower -= res;
                        if (firstBlockDistances[rayIndex] == Float.MAX_VALUE) {
                            double ddx = bx + 0.5 - this.center.x;
                            double ddy = by + 0.5 - this.center.y;
                            double ddz = bz + 0.5 - this.center.z;
                            firstBlockDistances[rayIndex] = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                        }
                    }
                    if (remainingPower > 0.0F && bx >= gMinX && bx <= gMaxX && by >= gMinY && by <= gMaxY && bz >= gMinZ && bz <= gMaxZ)
                        grid.set((bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_);
                } else {
                    FluidState fluid = block.getFluidState();
                    recordFirstBlockDistance(pos, block, fluid, rayIndex, firstBlockDistances);
                    remainingPower = applyResistance(remainingPower, block, fluid, pos, self, false);
                    long packed = processBlock(block, fluid, pos, remainingPower, self, false);
                    if (remainingPower > 0.0F && packed != Long.MIN_VALUE) {
                        if (bx >= gMinX && bx <= gMaxX && by >= gMinY && by <= gMaxY && bz >= gMinZ && bz <= gMaxZ)
                            grid.set((bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_);
                    }
                }

                int dp = deltas[s];
                bx += (dp << 24) >> 24;
                by += (dp << 16) >> 24;
                bz += (dp << 8) >> 24;
            }
        }
    }

    @Unique
    private float applyResistance(float remainingPower, BlockState block, FluidState fluid,
                                  BlockPos pos, ServerExplosion self, boolean isDefaultCalc) {
        if (isDefaultCalc) {
            if (!block.isAir() || !fluid.isEmpty())
                remainingPower -= (Math.max(block.getBlock().getExplosionResistance(), fluid.getExplosionResistance()) + 0.3F) * 0.3F;
        } else if (this.damageCalculator instanceof net.minecraft.world.level.EntityBasedExplosionDamageCalculator && this.source != null) {
            if (!block.isAir() || !fluid.isEmpty()) {
                float res = Math.max(block.getBlock().getExplosionResistance(), fluid.getExplosionResistance());
                res = this.source.getBlockExplosionResistance(self, this.level, pos, block, fluid, res);
                remainingPower -= (res + 0.3F) * 0.3F;
            }
        } else {
            Optional<Float> resistance = this.damageCalculator.getBlockExplosionResistance(self, this.level, pos, block, fluid);
            if (resistance.isPresent()) remainingPower -= (resistance.get() + 0.3F) * 0.3F;
        }
        return remainingPower;
    }

    @Unique
    private long processBlock(BlockState block, FluidState fluid, BlockPos pos,
                              float remainingPower, ServerExplosion self, boolean isDefaultCalc) {
        if (isDefaultCalc) {
            return remainingPower > 0.0F ? pos.asLong() : Long.MIN_VALUE;
        } else if (this.damageCalculator instanceof net.minecraft.world.level.EntityBasedExplosionDamageCalculator && this.source != null) {
            return remainingPower > 0.0F && this.source.shouldBlockExplode(self, this.level, pos, block, remainingPower)
                    ? pos.asLong() : Long.MIN_VALUE;
        } else {
            return remainingPower > 0.0F && this.damageCalculator.shouldBlockExplode(self, this.level, pos, block, remainingPower)
                    ? pos.asLong() : Long.MIN_VALUE;
        }
    }

    @Unique
    private void recordFirstBlockDistance(BlockPos pos, BlockState block, FluidState fluid,
                                          int rayIndex, float[] firstBlockDistances) {
        if (firstBlockDistances[rayIndex] == Float.MAX_VALUE && (!block.isAir() || !fluid.isEmpty())) {
            double dcx = this.center.x, dcy = this.center.y, dcz = this.center.z;
            double ddx = pos.getX() + 0.5 - dcx;
            double ddy = pos.getY() + 0.5 - dcy;
            double ddz = pos.getZ() + 0.5 - dcz;
            firstBlockDistances[rayIndex] = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
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
        List<ExplosionHelper.EntityDamageResult> results;
        try {
            if (SimdBatchOps.VECTORIAL_AVAILABLE && ExplosionParallelConfig.isSimdEntityDamage()) {
                // ── SoA AABB query + direct SoA position extraction ──
                int[] hits = new int[SimdBatchOps.slotCount()];
                int nHits = SimdBatchOps.intersectAABB(hits, x0, y0, z0, x1, y1, z1);

                if (nHits == 0) return true;

                double[] distSq = new double[nHits];
                SimdBatchOps.distanceSqBySlotBatch(hits, nHits,
                        this.center.x, this.center.y, this.center.z,
                        distSq);

                double radiusSq = (double) dr * (double) dr;
                List<Entity> entities = new ArrayList<>(nHits);
                final Entity source = this.source;
                final ServerExplosion self = (ServerExplosion) (Object) this;
                for (int i = 0; i < nHits; i++) {
                    if (distSq[i] > radiusSq) continue;
                    int entityId = SimdBatchOps.slotToEntityId(hits[i]);
                    if (entityId < 0) continue;
                    Entity e = this.level.getEntity(entityId);
                    if (e == null || e.equals(source) || e.ignoreExplosion(self)) continue;
                    entities.add(e);
                }

                if (entities.isEmpty()) return true;
                if (entities.size() < 50) {
                    results = new ArrayList<>(entities.size());
                    for (Entity entity : entities) results.add(computeEntityDamage(entity, dr));
                } else {
                    results = ParallelWorker.mapBatched(ParallelThreadPool.getPool("Explosion"), entities,
                            entity -> computeEntityDamage(entity, dr), ParallelWorker.autoBatchSize(entities.size()), 5);
                }
            } else {
                AABB bb = new AABB(x0, y0, z0, x1, y1, z1);
                final List<Entity> allEntities = this.level.getEntities(this.source, bb);
                if (allEntities.isEmpty()) return true;
                final Entity source = this.source;
                final ServerExplosion self = (ServerExplosion) (Object) this;
                List<Entity> entities = new ArrayList<>(allEntities.size());
                for (Entity e : allEntities) {
                    if (e == null || e.isRemoved() || e.equals(source) || e.ignoreExplosion(self)) continue;
                    entities.add(e);
                }
                if (entities.isEmpty()) return true;
                if (entities.size() < 50) {
                    results = new ArrayList<>(entities.size());
                    for (Entity entity : entities) results.add(computeEntityDamage(entity, dr));
                } else {
                    results = ParallelWorker.mapBatched(ParallelThreadPool.getPool("Explosion"), entities,
                            entity -> computeEntityDamage(entity, dr), ParallelWorker.autoBatchSize(entities.size()), 5);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Explosion entity workers failed; falling back to vanilla", e);
            return false;
        }

        for (ExplosionHelper.EntityDamageResult r : results) {
            if (r == null) continue;
            Entity entity = r.entity();
            entity.push(r.makeKnockback());
            if (r.damage() > 0.0F) entity.hurtServer(this.level, this.damageSource, r.damage());
            if (entity.getType().builtInRegistryHolder().is(EntityTypeTags.REDIRECTABLE_PROJECTILE)
                    && entity instanceof Projectile projectile)
                projectile.setOwner(this.damageSource.getEntity());
            if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying))
                this.hitPlayers.put(player, r.makeKnockback());
            entity.onExplosionHit(this.source);
        }

        return true;
    }

    // ── SIMD batch entity damage ──────────────────

    @Unique
    private List<ExplosionHelper.EntityDamageResult> hurtEntitiesSimd(List<Entity> entities, float doubleRadius) {
        int n = entities.size();

        double[] posX = new double[n];
        double[] posY = new double[n];
        double[] posZ = new double[n];
        double[] distSq = new double[n];

        for (int i = 0; i < n; i++) {
            Entity e = entities.get(i);
            posX[i] = e.getX();
            posY[i] = e.getY();
            if (!(e instanceof PrimedTnt)) posY[i] += e.getEyeHeight();
            posZ[i] = e.getZ();
        }

        SimdBatchOps.distanceSqBatch(posX, posY, posZ,
                this.center.x, this.center.y, this.center.z,
                distSq, 0, n);

        double radiusSq = (double) doubleRadius * (double) doubleRadius;
        List<Entity> filtered = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            if (distSq[i] <= radiusSq) {
                Entity e = entities.get(i);
                if (!e.ignoreExplosion((ServerExplosion) (Object) this)) {
                    filtered.add(e);
                }
            }
        }

        if (filtered.isEmpty()) return List.of();

        if (filtered.size() < 50) {
            List<ExplosionHelper.EntityDamageResult> results = new ArrayList<>(filtered.size());
            for (Entity entity : filtered) results.add(computeEntityDamage(entity, doubleRadius));
            return results;
        }
        return ParallelWorker.mapBatched(ParallelThreadPool.getPool("Explosion"), filtered,
                entity -> computeEntityDamage(entity, doubleRadius), ParallelWorker.autoBatchSize(filtered.size()), 5);
    }

    @Unique
    private float getSeenPercentFast(Vec3 center, Entity entity) {
        float[] distances = this.cachedFirstBlockDistances;
        if (distances == null) return getSeenPercentSafe(center, entity);

        AABB bb = entity.getBoundingBox();
        float f = ExplosionParallelConfig.getSamplingFactor();
        double xs = 1.0 / ((bb.maxX - bb.minX) * f + 1.0);
        double ys = 1.0 / ((bb.maxY - bb.minY) * f + 1.0);
        double zs = 1.0 / ((bb.maxZ - bb.minZ) * f + 1.0);
        double xOff = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOff = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) return 0.0F;

        int hits = 0, count = 0;
        double cx = center.x, cy = center.y, cz = center.z;
        int[][][] idxGrid = ExplosionHelper.RAY_INDEX_BY_GRID;

        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double sx = net.minecraft.util.Mth.lerp(xx, bb.minX, bb.maxX) + xOff;
                    double sy = net.minecraft.util.Mth.lerp(yy, bb.minY, bb.maxY);
                    double sz = net.minecraft.util.Mth.lerp(zz, bb.minZ, bb.maxZ) + zOff;
                    double dx = sx - cx, dy = sy - cy, dz = sz - cz;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double inv = 1.0 / dist;
                    double ndx = dx * inv, ndy = dy * inv, ndz = dz * inv;
                    int gx = (int)((ndx + 1.0) * 7.5 + 0.5);
                    int gy = (int)((ndy + 1.0) * 7.5 + 0.5);
                    int gz = (int)((ndz + 1.0) * 7.5 + 0.5);
                    gx = Math.max(0, Math.min(15, gx));
                    gy = Math.max(0, Math.min(15, gy));
                    gz = Math.max(0, Math.min(15, gz));
                    int dgx = Math.min(gx, 15 - gx);
                    int dgy = Math.min(gy, 15 - gy);
                    int dgz = Math.min(gz, 15 - gz);
                    if (dgx <= dgy && dgx <= dgz) gx = (gx < 8 ? 0 : 15);
                    else if (dgy <= dgz) gy = (gy < 8 ? 0 : 15);
                    else gz = (gz < 8 ? 0 : 15);
                    int r = idxGrid[gx][gy][gz];
                    if (r >= 0 && r < distances.length && dist <= distances[r]) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    @Unique
    private float getSeenPercentSafe(Vec3 center, Entity entity) {
        AABB bb = entity.getBoundingBox();
        float f = ExplosionParallelConfig.getSamplingFactor();
        double xs = 1.0 / ((bb.maxX - bb.minX) * f + 1.0);
        double ys = 1.0 / ((bb.maxY - bb.minY) * f + 1.0);
        double zs = 1.0 / ((bb.maxZ - bb.minZ) * f + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) return 0.0F;

        ChunkGrid chunkGrid = this.cachedChunkGrid;
        Vec3 toVec = new Vec3(center.x, center.y, center.z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int hits = 0, count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = net.minecraft.util.Mth.lerp(xx, bb.minX, bb.maxX);
                    double y = net.minecraft.util.Mth.lerp(yy, bb.minY, bb.maxY);
                    double z = net.minecraft.util.Mth.lerp(zz, bb.minZ, bb.maxZ);
                    if (!rayCastHitsBlock(x + xOffset, y, z + zOffset, center.x, center.y, center.z,
                            chunkGrid, toVec, pos)) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    @Unique
    private static boolean rayCastHitsBlock(double fx, double fy, double fz,
                                            double tx, double ty, double tz,
                                            ChunkGrid chunkGrid, Vec3 toVec,
                                            BlockPos.MutableBlockPos pos) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        if (dx * dx + dy * dy + dz * dz < 1.0E-7) return false;

        double fromX = net.minecraft.util.Mth.lerp(-1.0E-7, fx, tx);
        double fromY = net.minecraft.util.Mth.lerp(-1.0E-7, fy, ty);
        double fromZ = net.minecraft.util.Mth.lerp(-1.0E-7, fz, tz);
        double toX   = net.minecraft.util.Mth.lerp(-1.0E-7, tx, fx);
        double toY   = net.minecraft.util.Mth.lerp(-1.0E-7, ty, fy);
        double toZ   = net.minecraft.util.Mth.lerp(-1.0E-7, tz, fz);

        int x = net.minecraft.util.Mth.floor(fromX);
        int y = net.minecraft.util.Mth.floor(fromY);
        int z = net.minecraft.util.Mth.floor(fromZ);
        int endX = net.minecraft.util.Mth.floor(toX);
        int endY = net.minecraft.util.Mth.floor(toY);
        int endZ = net.minecraft.util.Mth.floor(toZ);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = stepX != 0 ? stepX / dx : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? stepY / dy : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? stepZ / dz : Double.MAX_VALUE;

        double tMaxX = tDeltaX * (stepX > 0 ? 1.0 - net.minecraft.util.Mth.frac(fromX) : net.minecraft.util.Mth.frac(fromX));
        double tMaxY = tDeltaY * (stepY > 0 ? 1.0 - net.minecraft.util.Mth.frac(fromY) : net.minecraft.util.Mth.frac(fromY));
        double tMaxZ = tDeltaZ * (stepZ > 0 ? 1.0 - net.minecraft.util.Mth.frac(fromZ) : net.minecraft.util.Mth.frac(fromZ));

        int cachedSectionX = Integer.MIN_VALUE;
        int cachedSectionY = Integer.MIN_VALUE;
        int cachedSectionZ = Integer.MIN_VALUE;
        LevelChunkSection cachedSection = null;
        boolean cachedSectionAir = true;

        while (true) {
            if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
            if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
            if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

            int cx = x >> 4;
            int cy = y >> 4;
            int cz = z >> 4;
            if (cx != cachedSectionX || cy != cachedSectionY || cz != cachedSectionZ) {
                cachedSectionX = cx;
                cachedSectionY = cy;
                cachedSectionZ = cz;
                cachedSection = chunkGrid.getSection(cx, cz, y);
                cachedSectionAir = cachedSection == null || cachedSection.hasOnlyAir();
            }

            if (cachedSectionAir) {
                int nx = 0, ny = 0, nz = 0;
                if (stepX > 0) nx = ((cx + 1) << 4) - x; else if (stepX < 0) nx = x - ((cx << 4) - 1);
                if (stepY > 0) ny = ((cy + 1) << 4) - y; else if (stepY < 0) ny = y - ((cy << 4) - 1);
                if (stepZ > 0) nz = ((cz + 1) << 4) - z; else if (stepZ < 0) nz = z - ((cz << 4) - 1);

                int ex = 0, ey = 0, ez = 0;
                if (stepX > 0) ex = endX - x + 1; else if (stepX < 0) ex = x - endX + 1;
                if (stepY > 0) ey = endY - y + 1; else if (stepY < 0) ey = y - endY + 1;
                if (stepZ > 0) ez = endZ - z + 1; else if (stepZ < 0) ez = z - endZ + 1;

                double sectionT = Double.MAX_VALUE; char exitA = 0;
                if (nx > 0) { double t = tMaxX + (nx - 1) * tDeltaX; if (t < sectionT) { sectionT = t; exitA = 'x'; } }
                if (ny > 0) { double t = tMaxY + (ny - 1) * tDeltaY; if (t < sectionT) { sectionT = t; exitA = 'y'; } }
                if (nz > 0) { double t = tMaxZ + (nz - 1) * tDeltaZ; if (t < sectionT) { sectionT = t; exitA = 'z'; } }
                double endT = Double.MAX_VALUE;
                if (ex > 0) { double t = tMaxX + (ex - 1) * tDeltaX; if (t < endT) endT = t; }
                if (ey > 0) { double t = tMaxY + (ey - 1) * tDeltaY; if (t < endT) endT = t; }
                if (ez > 0) { double t = tMaxZ + (ez - 1) * tDeltaZ; if (t < endT) endT = t; }
                if (endT <= sectionT || exitA == 0) return false;

                int cX = 0, cY = 0, cZ = 0;
                if (exitA == 'x') { cX = nx; if (ny > 0) { double r = (sectionT - tMaxY - 1e-12) / tDeltaY; if (r > 0) cY = (int) r + 1; } if (nz > 0) { double r = (sectionT - tMaxZ - 1e-12) / tDeltaZ; if (r > 0) cZ = (int) r + 1; } }
                else if (exitA == 'y') { cY = ny; if (nx > 0) { double r = (sectionT - tMaxX - 1e-12) / tDeltaX; if (r > 0) cX = (int) r + 1; } if (nz > 0) { double r = (sectionT - tMaxZ - 1e-12) / tDeltaZ; if (r > 0) cZ = (int) r + 1; } }
                else { cZ = nz; if (nx > 0) { double r = (sectionT - tMaxX - 1e-12) / tDeltaX; if (r > 0) cX = (int) r + 1; } if (ny > 0) { double r = (sectionT - tMaxY - 1e-12) / tDeltaY; if (r > 0) cY = (int) r + 1; } }
                x += stepX * cX; y += stepY * cY; z += stepZ * cZ;
                tMaxX += cX * tDeltaX; tMaxY += cY * tDeltaY; tMaxZ += cZ * tDeltaZ;
                if (stepX > 0 ? x > endX : (stepX < 0 && x < endX)) return false;
                if (stepY > 0 ? y > endY : (stepY < 0 && y < endY)) return false;
                if (stepZ > 0 ? z > endZ : (stepZ < 0 && z < endZ)) return false;
                continue;
            }

            BlockState state = chunkGrid.getBlockState(cx, cz, y, x & 15, y & 15, z & 15);
            if (!state.isAir()) {
                pos.set(x, y, z);
                if (ExplosionHelper.isFullCube(state)) return true;
                ChunkAccess chunk = chunkGrid.getChunk(cx, cz);
                VoxelShape shape = state.getCollisionShape(chunk, pos);
                if (!shape.isEmpty()) {
                    Vec3 fromVec = new Vec3(fx, fy, fz);
                    BlockHitResult hit = shape.clip(fromVec, toVec, pos);
                    if (hit != null && hit.getType() != HitResult.Type.MISS) return true;
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { if (stepX == 0) break; x += stepX; tMaxX += tDeltaX; }
                else                 { if (stepZ == 0) break; z += stepZ; tMaxZ += tDeltaZ; }
            } else {
                if (tMaxY < tMaxZ) { if (stepY == 0) break; y += stepY; tMaxY += tDeltaY; }
                else                 { if (stepZ == 0) break; z += stepZ; tMaxZ += tDeltaZ; }
            }
        }
        return false;
    }

    // ──────────────────────────────────────────────
    //  Compute entity damage (worker-thread safe)
    // ──────────────────────────────────────────────
    @Unique
    @Nullable
    private ExplosionHelper.EntityDamageResult computeEntityDamage(Entity entity, float doubleRadius) {
        // feet position — used for distance (vanilla: entity.distanceToSqr)
        double fx = entity.getX();
        double fy = entity.getY();
        double fz = entity.getZ();

        // eye position — used for knockback direction (vanilla: entity.getEyePosition / entity.position)
        double ex = fx;
        double ey = fy;
        double ez = fz;
        if (!(entity instanceof PrimedTnt)) {
            ey += entity.getEyeHeight();
        }

        double dx = fx - this.center.x;
        double dy = fy - this.center.y;
        double dz = fz - this.center.z;
        double distR = Math.sqrt(dx * dx + dy * dy + dz * dz) / doubleRadius;
        if (distR > 1.0) return null;

        // knockback direction from eye position (vanilla: entityOrigin.subtract(this.center).normalize())
        double ndx = ex - this.center.x;
        double ndy = ey - this.center.y;
        double ndz = ez - this.center.z;
        double invKnockback = 1.0 / Math.sqrt(ndx * ndx + ndy * ndy + ndz * ndz);

        boolean shouldDamage = this.damageCalculator.shouldDamageEntity((ServerExplosion) (Object) this, entity);
        float knockbackMult = this.damageCalculator.getKnockbackMultiplier(entity);
        float exposure = !shouldDamage && knockbackMult == 0.0F ? 0.0F
                : ExplosionParallelConfig.isRayLookup() ? getSeenPercentFast(this.center, entity) : getSeenPercentSafe(this.center, entity);
        float damage = shouldDamage ? this.damageCalculator.getEntityDamageAmount((ServerExplosion) (Object) this, entity, exposure) : 0.0F;

        double knockbackResistance = entity instanceof LivingEntity le
                ? le.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE) : 0.0;
        double knockbackPower = (1.0 - distR) * exposure * knockbackMult * (1.0 - knockbackResistance);

        return new ExplosionHelper.EntityDamageResult(entity, damage,
                ndx * invKnockback * knockbackPower,
                ndy * invKnockback * knockbackPower,
                ndz * invKnockback * knockbackPower);
    }

    // ──────────────────────────────────────────────
    //  Compute entity damage (worker-thread safe)
    // ──────────────────────────────────────────────
    @Unique
    private void applyEntityDamage(ExplosionHelper.EntityDamageResult result) {
        Entity entity = result.entity();
        if (result.damage() > 0.0F) entity.hurtServer(this.level, this.damageSource, result.damage());
        entity.push(result.makeKnockback());
        if (entity.getType().builtInRegistryHolder().is(EntityTypeTags.REDIRECTABLE_PROJECTILE)
                && entity instanceof Projectile projectile)
            projectile.setOwner(this.damageSource.getEntity());
        if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying)) {
            synchronized (this.hitPlayers) {
                this.hitPlayers.put(player, result.makeKnockback());
            }
        }
        entity.onExplosionHit(this.source);
    }
}
